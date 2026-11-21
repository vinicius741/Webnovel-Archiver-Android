#!/usr/bin/env python3
"""Cuts sherpa-onnx's Kokoro v1.0 export into the three models the server's fast path runs.

    split_kokoro.py MODEL.onnx OUT_DIR

Kokoro's generator (the last stage, which turns features into sound) is about 90% of the work. The
fast path runs that part, the "trunk", in OpenVINO at reduced precision, and everything else at
full precision as before (see src/fast.rs and docs/tts/remote-narration-servers.md):

- front.onnx: everything up to the trunk (text encoder, durations, pitch, the harmonic source with
  its random numbers). sherpa-onnx runs it as if it were the whole model, so pronunciation,
  sentence splitting and voices stay sherpa-onnx's. Its one output, named "audio" as sherpa-onnx
  expects, carries the trunk's inputs instead of sound:
  [MAGIC, L, T, har (22 x L), x (512 x T), style (128)].
- trunk.onnx: the generator's residual blocks and upsamplers. Each Snake activation's sin(y)^2 is
  rewritten as a polynomial (OpenVINO computes Sin one value at a time; the polynomial vectorizes).
- tail.onnx: the last convolution and the inverse STFT, which need full precision.

Needs `onnx` and `numpy`. setup.sh runs it; FORMAT must match FORMAT in src/fast.rs.
"""

import os
import sys

import numpy as np
import onnx
from onnx import AttributeProto, TensorProto, helper, numpy_helper

FORMAT = "1"
MAGIC = 7654321.0

HAR = "/decoder/generator/Concat_1_output_0"  # harmonic source, [1, 22, L]
X = "/decoder/generator/LeakyRelu_output_0"  # decoder features, [1, 512, T]
STYLE = "/Slice_2_output_0"  # the voice's style, [1, 128]
Y = "/decoder/generator/Add_8_output_0"  # the trunk's output, [1, 128, N]
AUDIO = "audio"
SNAKES = 48

# sin(y)^2 = z * Q(z), with r = y/pi - round(y/pi) and z = r^2. Q is the degree-6 least-squares fit
# on Chebyshev nodes over z in [0, 0.25]; evaluated in float32 its error is below 6e-6.
SIN2 = [
    9.869604400158673,
    -32.46969664558604,
    42.72838511692918,
    -30.121754360347754,
    13.206610352884349,
    -3.913077715699321,
    0.7419801256421584,
]


def fail(message):
    sys.exit(f"split_kokoro: {message}")


def node_inputs(node):
    """A node's inputs, including what its subgraphs (If, Loop) read from the enclosing graph."""
    names = list(node.input)
    for attribute in node.attribute:
        if attribute.type == AttributeProto.GRAPH:
            graphs = [attribute.g]
        elif attribute.type == AttributeProto.GRAPHS:
            graphs = list(attribute.graphs)
        else:
            graphs = []
        for graph in graphs:
            inner = {o for n in graph.node for o in n.output}
            inner |= {i.name for i in graph.input} | {i.name for i in graph.initializer}
            for n in graph.node:
                names += [name for name in node_inputs(n) if name not in inner]
    return names


def cut(graph, producers, inputs, outputs):
    """The nodes and initializers that compute [outputs] from [inputs], in the graph's order."""
    needed, keep, stack = set(), set(), list(outputs)
    while stack:
        name = stack.pop()
        if name in needed:
            continue
        needed.add(name)
        if name in inputs or name not in producers:
            continue
        node = producers[name]
        keep.add(id(node))
        stack += node_inputs(node)
    nodes = [node for node in graph.node if id(node) in keep]
    initializers = [init for init in graph.initializer if init.name in needed]
    return nodes, initializers


def constant(name, value):
    return helper.make_node("Constant", [], [name], value=numpy_helper.from_array(np.array(value), name))


def fast_sin(nodes, graph_inputs, initializers):
    """Replaces every Sin -> Pow(2) pair with the polynomial; returns the nodes in a valid order."""
    consumers = {}
    for node in nodes:
        for name in node.input:
            consumers.setdefault(name, []).append(node)
    constants = {n.output[0]: numpy_helper.to_array(n.attribute[0].t) for n in nodes if n.op_type == "Constant" and n.attribute}
    constants.update({init.name: numpy_helper.to_array(init) for init in initializers})
    dropped, added, count = set(), [], 0
    for node in nodes:
        users = consumers.get(node.output[0], [])
        if node.op_type != "Sin" or len(users) != 1 or users[0].op_type != "Pow":
            continue
        power = users[0]
        exponent = constants.get(power.input[1])
        if power.input[0] != node.output[0] or exponent is None or exponent.size != 1 or float(exponent.reshape(-1)[0]) != 2.0:
            continue
        count += 1
        p, y, degree = node.name + "/fs", node.input[0], len(SIN2) - 1
        added.append(constant(p + "/invpi", np.float32(1 / np.pi)))
        steps = [("Mul", [y, p + "/invpi"], p + "/u"), ("Round", [p + "/u"], p + "/k"), ("Sub", [p + "/u", p + "/k"], p + "/r"), ("Mul", [p + "/r", p + "/r"], p + "/z")]
        added.append(constant(f"{p}/c{degree}", np.float32(SIN2[degree])))
        acc = f"{p}/c{degree}"
        for k in range(degree - 1, -1, -1):
            added.append(constant(f"{p}/c{k}", np.float32(SIN2[k])))
            steps.append(("Mul", [acc, p + "/z"], f"{p}/m{k}"))
            steps.append(("Add", [f"{p}/m{k}", f"{p}/c{k}"], f"{p}/q{k}"))
            acc = f"{p}/q{k}"
        steps.append(("Mul", [acc, p + "/z"], power.output[0]))
        added += [helper.make_node(op, ins, [out], name=out) for op, ins, out in steps]
        dropped |= {id(node), id(power)}
    if count != SNAKES:
        fail(f"expected {SNAKES} Snake activations in the trunk, found {count}; is this sherpa-onnx's kokoro-multi-lang-v1_0?")
    # The exponents the Pow nodes read are left without a reader.
    kept = [node for node in nodes if id(node) not in dropped]
    read = {name for node in kept + added for name in node.input}
    kept = [node for node in kept if node.op_type != "Constant" or node.output[0] in read]
    # The new nodes must come before what reads them.
    pending = kept + added
    produced = set(graph_inputs) | {init.name for init in initializers}
    ordered = []
    while pending:
        later = []
        for node in pending:
            if all(name == "" or name in produced for name in node.input):
                ordered.append(node)
                produced.update(node.output)
            else:
                later.append(node)
        if len(later) == len(pending):
            fail("the trunk could not be ordered")
        pending = later
    return ordered


def value(name, shape):
    return helper.make_tensor_value_info(name, TensorProto.FLOAT, shape)


def save(source, name, nodes, initializers, inputs, outputs, path, metadata=False):
    graph = helper.make_graph(nodes, name, inputs, outputs, initializer=initializers)
    model = helper.make_model(graph, opset_imports=list(source.opset_import), ir_version=source.ir_version)
    if metadata:
        # sherpa-onnx reads the sample rate, the voice count and more from here.
        model.metadata_props.extend(source.metadata_props)
    onnx.save(model, path)


def main():
    if len(sys.argv) != 3:
        fail("usage: split_kokoro.py MODEL.onnx OUT_DIR")
    source_path, out = sys.argv[1], sys.argv[2]
    stamp = f"{FORMAT} {os.path.getsize(source_path)}\n"
    stamp_path = os.path.join(out, "format")
    if os.path.isfile(stamp_path) and open(stamp_path).read() == stamp:
        return
    os.makedirs(out, exist_ok=True)
    if os.path.exists(stamp_path):
        os.remove(stamp_path)

    source = onnx.load(source_path)
    graph = source.graph
    producers = {name: node for node in graph.node for name in node.output}
    for name in (HAR, X, STYLE, Y, AUDIO):
        if name not in producers:
            fail(f"{name} is not in {source_path}; is this sherpa-onnx's kokoro-multi-lang-v1_0?")

    # Front: the model's own inputs to the trunk's inputs, packed into one float vector.
    nodes, initializers = cut(graph, producers, set(), [HAR, X, STYLE])
    pack = [constant("pk/flat", np.array([-1], dtype=np.int64)), constant("pk/magic", np.array([MAGIC], dtype=np.float32)), constant("pk/axis2", np.array(2, dtype=np.int64))]
    for short, tensor in (("har", HAR), ("x", X)):
        pack.append(helper.make_node("Shape", [tensor], [f"pk/{short}_shape"]))
        pack.append(helper.make_node("Gather", [f"pk/{short}_shape", "pk/axis2"], [f"pk/{short}_len_i"], axis=0))
        pack.append(helper.make_node("Cast", [f"pk/{short}_len_i"], [f"pk/{short}_len_f"], to=TensorProto.FLOAT))
        pack.append(helper.make_node("Reshape", [f"pk/{short}_len_f", "pk/flat"], [f"pk/{short}_len"]))
        pack.append(helper.make_node("Reshape", [tensor, "pk/flat"], [f"pk/{short}_flat"]))
    pack.append(helper.make_node("Reshape", [STYLE, "pk/flat"], ["pk/style_flat"]))
    pack.append(helper.make_node("Concat", ["pk/magic", "pk/har_len", "pk/x_len", "pk/har_flat", "pk/x_flat", "pk/style_flat"], [AUDIO], axis=0))
    save(source, "front", nodes + pack, initializers, list(graph.input), [value(AUDIO, ["n"])], os.path.join(out, "front.onnx"), metadata=True)

    # Trunk: the generator's residual blocks and upsamplers.
    nodes, initializers = cut(graph, producers, {HAR, X, STYLE}, [Y])
    nodes = fast_sin(nodes, [HAR, X, STYLE], initializers)
    inputs = [value(HAR, [1, 22, "L"]), value(X, [1, 512, "T"]), value(STYLE, [1, 128])]
    save(source, "trunk", nodes, initializers, inputs, [value(Y, [1, 128, "N"])], os.path.join(out, "trunk.onnx"))

    # Tail: the last convolution and the inverse STFT. OpenVINO needs the final Squeeze's result
    # shape spelled out, so it becomes a Reshape to one dimension.
    nodes, initializers = cut(graph, producers, {Y}, [AUDIO])
    last = producers[AUDIO]
    if last.op_type != "Squeeze" or len(last.input) != 1 or last.attribute:
        fail("the model does not end in the Squeeze this script expects")
    nodes = [node for node in nodes if node is not last]
    nodes += [constant("tail/flat", np.array([-1], dtype=np.int64)), helper.make_node("Reshape", [last.input[0], "tail/flat"], [AUDIO])]
    save(source, "tail", nodes, initializers, [value(Y, [1, 128, "N"])], [value(AUDIO, ["A"])], os.path.join(out, "tail.onnx"))

    with open(stamp_path, "w") as file:
        file.write(stamp)
    print(f"Kokoro v1.0 fast-path models written to {out}")


if __name__ == "__main__":
    main()

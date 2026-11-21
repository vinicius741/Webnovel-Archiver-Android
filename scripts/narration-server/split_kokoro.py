#!/usr/bin/env python3
"""Cuts sherpa-onnx's Kokoro v1.0 export into the three models the server's fast path runs.

    split_kokoro.py MODEL.onnx OUT_DIR bf16|f16

Kokoro's generator (the last stage, which turns features into sound) is about 90% of the work. The
fast path runs that part, the "trunk", in OpenVINO at reduced precision, and everything else at
full precision as before (see src/fast.rs and docs/tts/remote-narration-servers.md):

- front.onnx: everything up to the trunk (text encoder, durations, pitch, the harmonic source with
  its random numbers). sherpa-onnx runs it as if it were the whole model, so pronunciation,
  sentence splitting and voices stay sherpa-onnx's. Its one output, named "audio" as sherpa-onnx
  expects, carries the trunk's inputs instead of sound:
  [MAGIC, L, T, har (22 x L), x (512 x T), style (128)].
- trunk.onnx: the generator's residual blocks and upsamplers, rewritten for the precision it will
  run in (below). Every rewrite is exact in real arithmetic: the trunk computes the same function.
- tail.onnx: the last convolution and the inverse STFT, which need full precision.

bf16 is for x86-64 with AVX-512 BF16. There each Snake activation's sin(y)^2 becomes a polynomial,
because OpenVINO computes Sin one value at a time and the polynomial vectorizes.

f16 is for Apple silicon, where OpenVINO 2026.4.1's ARM plugin needs three things (found on an
M1 Pro, 2026-11-21):
- It computes ConvTranspose1d with the kernel reversed along time, at any precision. Each becomes
  an ordinary Conv over the phases plus a depth-to-space reshape (`polyphase`), in the tail too,
  where the inverse STFT's subtraction becomes an addition of a negated kernel (`add_negated`)
  because the plugin does not compile the subtraction of two such Convs.
- Its float16 convolution also adds its products up in float16, which costs about 28 dB per
  convolution. A Conv that sums more than MAX_TERMS products is split into partial Convs over
  groups of input channels (`split_convolutions`); each halving of the sum wins about 3 dB.
- Its float16 InstanceNormalization computes the variance as mean(x^2) - mean(x)^2, which cancels
  and gives NaN. Written out as d = x - mean(x); d / sqrt(mean(d*d) + eps) (`explicit_norms`),
  OpenVINO keeps it in float32, by its rule for dividing by an epsilon below float16's range.

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
UPSAMPLERS = 2
NORMS = 48
# f16: the most products (input channels x kernel) one convolution may add up. 704 gives 41-42 dB
# against float32 on the M1 Pro for 8% of the speed; 352 gives 2 dB more for another 17%.
MAX_TERMS = 704
FLOAT16_SMALLEST_NORMAL = 6.103515625e-05

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


def polyphase(nodes, initializers):
    """Rewrites every ConvTranspose1d whose kernel is a multiple of its stride as a Conv.

    With stride s, kernel m*s and padding p, output sample q*s + ph - p (phase ph in 0..s-1) is
        sum over c_in and j in 0..m-1 of x[c_in, q - j] * W[c_in, c_out, ph + j*s]   (+ bias)
    so one Conv with kernel m, padding m-1 and C_out*s output channels (channel c_out*s + ph holds
    phase ph) computes every phase. Reshape/Transpose/Reshape interleaves the phases along time and
    Slice removes the padding. The same multiplications as the ConvTranspose.
    """
    weights = {init.name: init for init in initializers}
    out, count = [], 0
    for node in nodes:
        if node.op_type != "ConvTranspose":
            out.append(node)
            continue
        attrs = {a.name: helper.get_attribute_value(a) for a in node.attribute}
        if node.input[1] not in weights:
            fail(f"{node.name}: the kernel is not a constant")
        w = numpy_helper.to_array(weights[node.input[1]])  # [C_in, C_out, kernel]
        stride = list(attrs.get("strides", [1]))[0]
        pads = list(attrs.get("pads", [0, 0]))
        plain = (
            w.ndim == 3
            and w.shape[2] % stride == 0
            and attrs.get("group", 1) == 1
            and list(attrs.get("dilations", [1])) == [1]
            and list(attrs.get("output_padding", [0])) == [0]
            and "output_shape" not in attrs
            and attrs.get("auto_pad", b"NOTSET") == b"NOTSET"
        )
        if not plain:
            fail(f"{node.name}: not a plain ConvTranspose1d with a kernel that is a multiple of its stride")
        c_in, c_out, taps = w.shape[0], w.shape[1], w.shape[2] // stride
        if len(node.input) > 2 and node.input[2]:
            bias = numpy_helper.to_array(weights[node.input[2]])
        else:
            bias = np.zeros(c_out, dtype=w.dtype)
        kernel = np.empty((c_out, stride, c_in, taps), dtype=w.dtype)
        for tap in range(taps):
            j = taps - 1 - tap  # this tap reads x[q - j]
            kernel[..., tap] = w[:, :, j * stride : (j + 1) * stride].transpose(1, 2, 0)
        p = node.name + "/pp"
        initializers.append(numpy_helper.from_array(kernel.reshape(c_out * stride, c_in, taps), p + "/w"))
        initializers.append(numpy_helper.from_array(np.repeat(bias, stride), p + "/b"))
        end = -pads[1] if pads[1] else np.iinfo(np.int64).max
        conv = helper.make_node(
            "Conv", [node.input[0], p + "/w", p + "/b"], [p + "/conv"], name=p + "/conv", kernel_shape=[taps], strides=[1], pads=[taps - 1, taps - 1], dilations=[1], group=1
        )
        out += [
            conv,
            constant(p + "/split", np.array([0, c_out, stride, -1], dtype=np.int64)),
            helper.make_node("Reshape", [p + "/conv", p + "/split"], [p + "/phases"], name=p + "/phases"),
            helper.make_node("Transpose", [p + "/phases"], [p + "/interleaved"], name=p + "/interleaved", perm=[0, 1, 3, 2]),
            constant(p + "/join", np.array([0, c_out, -1], dtype=np.int64)),
            helper.make_node("Reshape", [p + "/interleaved", p + "/join"], [p + "/full"], name=p + "/full"),
            constant(p + "/start", np.array([pads[0]], dtype=np.int64)),
            constant(p + "/end", np.array([end], dtype=np.int64)),
            constant(p + "/axis", np.array([2], dtype=np.int64)),
            helper.make_node("Slice", [p + "/full", p + "/start", p + "/end", p + "/axis"], [node.output[0]], name=p + "/crop"),
        ]
        count += 1
    return out, count


def add_negated(nodes, initializers):
    """Rewrites every a - ConvTranspose(b) as a + ConvTranspose(b) with the kernel and bias negated.

    Negation is exact, so the result is the same to the bit. Returns the nodes and how many
    subtractions it rewrote.
    """
    weights = {init.name: init for init in initializers}
    producers = {name: node for node in nodes for name in node.output}
    reads = [name for node in nodes for name in node.input]
    replaced = {}
    for node in nodes:
        source = producers.get(node.input[1]) if node.op_type == "Sub" else None
        if source is None or source.op_type != "ConvTranspose" or reads.count(node.input[1]) != 1:
            continue
        negated = helper.make_node("ConvTranspose", list(source.input), list(source.output), name=source.name)
        negated.attribute.extend(source.attribute)
        for index in range(1, len(negated.input)):
            name = negated.input[index]
            if not name:
                continue
            if name not in weights:
                fail(f"{source.name}: the kernel or the bias is not a constant")
            initializers.append(numpy_helper.from_array(-numpy_helper.to_array(weights[name]), name + "/neg"))
            negated.input[index] = name + "/neg"
        replaced[id(source)] = negated
        replaced[id(node)] = helper.make_node("Add", list(node.input), list(node.output), name=node.name)
    return [replaced.get(id(node), node) for node in nodes], len(replaced) // 2


def split_convolutions(nodes, initializers):
    """Splits each Conv that sums more than MAX_TERMS products per output into partial Convs.

    Conv(x, W) = sum over j of Conv(x[:, group j], W[:, group j]); the bias goes with the first part.
    The number of parts doubles until C_in * kernel / parts <= MAX_TERMS.
    """
    weights = {init.name: init for init in initializers}
    out = []
    for node in nodes:
        if node.op_type != "Conv" or node.input[1] not in weights:
            out.append(node)
            continue
        w = numpy_helper.to_array(weights[node.input[1]])  # [C_out, C_in, kernel]
        c_in, terms = w.shape[1], w.shape[1] * int(np.prod(w.shape[2:]))
        group = next((a.i for a in node.attribute if a.name == "group"), 1)
        parts = 1
        while terms / parts > MAX_TERMS and c_in % (parts * 2) == 0:
            parts *= 2
        if parts == 1 or group != 1:
            out.append(node)
            continue
        p, step = node.name + "/sp", c_in // parts
        out.append(helper.make_node("Split", [node.input[0]], [f"{p}/x{j}" for j in range(parts)], name=p + "/split", axis=1))
        for j in range(parts):
            initializers.append(numpy_helper.from_array(np.ascontiguousarray(w[:, j * step : (j + 1) * step]), f"{p}/w{j}"))
            inputs = [f"{p}/x{j}", f"{p}/w{j}"] + (list(node.input[2:]) if j == 0 else [])
            conv = helper.make_node("Conv", inputs, [f"{p}/y{j}"], name=f"{p}/conv{j}")
            conv.attribute.extend(node.attribute)
            out.append(conv)
        acc = p + "/y0"
        for j in range(1, parts):
            name = node.output[0] if j == parts - 1 else f"{p}/s{j}"
            out.append(helper.make_node("Add", [acc, f"{p}/y{j}"], [name], name=f"{p}/add{j}"))
            acc = name
    return out


def explicit_norms(nodes, initializers):
    """Replaces every InstanceNormalization by d = x - mean(x); y = d / sqrt(mean(d*d) + eps)."""
    values = {init.name: numpy_helper.to_array(init) for init in initializers}
    values.update({n.output[0]: numpy_helper.to_array(n.attribute[0].t) for n in nodes if n.op_type == "Constant" and n.attribute})
    alias = {node.output[0]: node.input[0] for node in nodes if node.op_type == "Identity"}

    def known(name):
        while name in alias:
            name = alias[name]
        return values.get(name)

    out, count = [], 0
    for node in nodes:
        if node.op_type != "InstanceNormalization":
            out.append(node)
            continue
        eps = next((a.f for a in node.attribute if a.name == "epsilon"), 1e-5)
        if not eps < FLOAT16_SMALLEST_NORMAL:
            fail(f"{node.name}: with epsilon {eps} OpenVINO would run this normalization in float16")
        scale, shift = known(node.input[1]), known(node.input[2])
        if scale is None or shift is None or not np.all(scale == 1) or not np.all(shift == 0):
            fail(f"{node.name}: the normalization has its own scale or shift, which Kokoro's do not")
        x, p = node.input[0], node.name + "/en"
        out += [
            helper.make_node("ReduceMean", [x], [p + "/m"], name=p + "/m", axes=[2], keepdims=1),
            helper.make_node("Sub", [x, p + "/m"], [p + "/d"], name=p + "/d"),
            helper.make_node("Mul", [p + "/d", p + "/d"], [p + "/dd"], name=p + "/dd"),
            helper.make_node("ReduceMean", [p + "/dd"], [p + "/v"], name=p + "/v", axes=[2], keepdims=1),
            constant(p + "/eps", np.float32(eps)),
            helper.make_node("Add", [p + "/v", p + "/eps"], [p + "/ve"], name=p + "/ve"),
            helper.make_node("Sqrt", [p + "/ve"], [p + "/sd"], name=p + "/sd"),
            helper.make_node("Div", [p + "/d", p + "/sd"], [node.output[0]], name=p + "/div"),
        ]
        count += 1
    return out, count


def prune(nodes, initializers, outputs):
    """Drops the nodes and initializers nothing reads any more (old kernels, the norms' weights)."""
    while True:
        read = {name for node in nodes for name in node.input} | set(outputs)
        kept = [node for node in nodes if any(name in read for name in node.output)]
        if len(kept) == len(nodes):
            break
        nodes = kept
    return nodes, [init for init in initializers if init.name in read]


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
    if len(sys.argv) != 4 or sys.argv[3] not in ("bf16", "f16"):
        fail("usage: split_kokoro.py MODEL.onnx OUT_DIR bf16|f16")
    source_path, out, precision = sys.argv[1], sys.argv[2], sys.argv[3]
    stamp = f"{FORMAT} {precision} {os.path.getsize(source_path)}\n"
    stamp_path = os.path.join(out, "format")
    if os.path.isfile(stamp_path) and open(stamp_path).read() == stamp:
        return
    os.makedirs(out, exist_ok=True)
    if os.path.exists(stamp_path):
        os.remove(stamp_path)

    source = onnx.load(source_path)
    graph = source.graph
    if next(o.version for o in source.opset_import if o.domain in ("", "ai.onnx")) >= 18:
        fail("the model's opset is 18 or later; the rewrites here write Split and ReduceMean as opset 14 has them")
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
    if precision == "bf16":
        nodes = fast_sin(nodes, [HAR, X, STYLE], initializers)
    else:
        nodes, upsamplers = polyphase(nodes, initializers)
        nodes = split_convolutions(nodes, initializers)
        nodes, norms = explicit_norms(nodes, initializers)
        if upsamplers != UPSAMPLERS or norms != NORMS:
            fail(f"expected {UPSAMPLERS} upsamplers and {NORMS} normalizations in the trunk, found {upsamplers} and {norms}")
        nodes, initializers = prune(nodes, initializers, [Y])
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
    if precision == "f16":
        # The inverse STFT is two ConvTranspose, which the ARM plugin gets wrong at any precision.
        # It ends in their difference, which the plugin does not compile once they are polyphase
        # Convs ("Check 'any_of(scale.size(), OC, 1U)' failed"), so the second is negated and added.
        nodes, differences = add_negated(nodes, initializers)
        nodes, upsamplers = polyphase(nodes, initializers)
        if upsamplers != 2 or differences != 1:
            fail(f"expected the inverse STFT's 2 ConvTranspose and their difference in the tail, found {upsamplers} and {differences}")
        nodes, initializers = prune(nodes, initializers, [AUDIO])
    save(source, "tail", nodes, initializers, [value(Y, [1, 128, "N"])], [value(AUDIO, ["A"])], os.path.join(out, "tail.onnx"))

    with open(stamp_path, "w") as file:
        file.write(stamp)
    print(f"Kokoro v1.0 fast-path models ({precision}) written to {out}")


if __name__ == "__main__":
    main()

//! Kokoro v1.0's fast path: the same model and weights, with the generator's trunk (about 90% of
//! the work) run by OpenVINO in a reduced precision the processor computes quickly.
//!
//! `split_kokoro.py` cuts the export into three models (`fast/` in the model's folder):
//! - `front.onnx` runs in sherpa-onnx as if it were the whole model, at full precision, so
//!   pronunciation, sentence splitting and voices are exactly the normal path's. What it returns as
//!   "audio" is the trunk's inputs, one [Chunk] per model run.
//! - `trunk.onnx` runs in OpenVINO at [Options::precision]: bf16 on x86-64 with AVX-512 BF16, f16 on
//!   Apple silicon.
//! - `tail.onnx` (the last convolution and the inverse STFT) runs in OpenVINO at full precision.
//!
//! Then pauses are shortened as sherpa-onnx does it ([scale_silence]), which the front model skips.
//! Only the trunk is reduced: the pitch source and the STFT lose audible quality at these
//! precisions, the trunk does not (measured and judged by ear, see the docs).

use crate::models::{Kind, Synth, VoiceRef};
use crate::openvino::{self, Compiled, Core, Request};
use crate::sherpa::{to_pcm16, Tts};
use std::ffi::CStr;
use std::path::{Path, PathBuf};
use std::sync::Mutex;

/// The layout `split_kokoro.py` writes; its FORMAT must match.
const FORMAT: &str = "1";
const MAGIC: f32 = 7_654_321.0;
const HAR_CHANNELS: usize = 22;
const X_CHANNELS: usize = 512;
const STYLE_SIZE: usize = 128;
const HAR: &CStr = c"/decoder/generator/Concat_1_output_0";
const X: &CStr = c"/decoder/generator/LeakyRelu_output_0";
const STYLE: &CStr = c"/Slice_2_output_0";
const TRUNK_OUT: &CStr = c"/decoder/generator/Add_8_output_0";
/// What the normal path passes to sherpa-onnx (`sherpa.rs`), applied here instead.
const SILENCE_SCALE: f32 = 0.2;
/// Threads for the front model in ONNX Runtime; it is a tenth of the work.
const FRONT_THREADS: usize = 2;
/// Most OpenVINO streams (groups of threads, each running one sentence's trunk). Measured on a
/// Ryzen Z1 Extreme with 8 threads: two streams give 10.9x with four sentences in flight against
/// 10.1x for one, but 6.4x against 8.4x with a single sentence, and hold 0.4 GB more. Four are slower.
const MAX_STREAMS: usize = 2;
/// Memory a fresh load needs before its first sentence, with one stream (see `engine.rs`): about
/// 0.7 GB more than the normal path, for the second runtime and its copy of the trunk.
pub const LOAD_COST: u64 = 1600 * 1024 * 1024;

/// How the fast path runs on this machine.
#[derive(Clone, Debug)]
pub struct Options {
    /// The folder holding OpenVINO's libraries.
    pub library: PathBuf,
    /// The trunk's precision: "bf16" or "f16".
    pub precision: &'static str,
    /// OpenVINO streams for the next load; see [MAX_STREAMS].
    pub streams: usize,
}

/// The reduced precision this processor computes quickly, if it has one.
fn precision() -> Option<&'static str> {
    #[cfg(target_arch = "x86_64")]
    if std::is_x86_feature_detected!("avx512bf16") {
        return Some("bf16");
    }
    None
}

impl Options {
    /// The fast path's settings for the Kokoro v1.0 in [model_dir], or why it does not apply here.
    pub fn detect(library: &Path, model_dir: &Path, slots: usize) -> Result<Options, String> {
        let precision = precision().ok_or("this processor has no fast reduced precision")?;
        if !openvino::installed(library) {
            return Err(format!("OpenVINO is not installed in {}", library.display()));
        }
        let stamp = std::fs::read_to_string(model_dir.join("fast").join("format")).unwrap_or_default();
        if stamp.split_whitespace().next() != Some(FORMAT) {
            return Err(format!("the split model is not in {}", model_dir.join("fast").display()));
        }
        Ok(Options { library: library.to_path_buf(), precision, streams: MAX_STREAMS }.for_slots(slots))
    }

    /// These settings for a model about to serve [slots] sentences at once: a model loading while
    /// memory allows one slot takes one stream, which is both faster and smaller there.
    pub fn for_slots(&self, slots: usize) -> Options {
        Options { streams: self.streams.min(slots.max(1)), ..self.clone() }
    }
}

/// A loaded Kokoro v1.0 on the fast path.
pub struct Fast {
    front: Tts,
    /// Requests not in use, a trunk and a tail each; one pair per sentence in flight.
    idle: Mutex<Vec<(Request, Request)>>,
    trunk: Compiled,
    tail: Compiled,
    // Last, so the models above are dropped before the runtime that made them.
    _core: Core,
}

impl Fast {
    pub fn load(model_dir: &Path, threads: usize, options: &Options) -> Result<Fast, String> {
        let fast = model_dir.join("fast");
        let front = Tts::load_as(Kind::KokoroV1, model_dir, "fast/front.onnx", FRONT_THREADS, 1.0)?;
        let core = Core::open(&options.library)?;
        let (streams, threads) = (options.streams.max(1).to_string(), threads.max(1).to_string());
        // Pinning threads to cores would fight ONNX Runtime's threads, which run the front model.
        let shared = [("NUM_STREAMS", streams.as_str()), ("INFERENCE_NUM_THREADS", threads.as_str()), ("ENABLE_CPU_PINNING", "NO")];
        let with = |precision: &'static str| [&shared[..], &[("INFERENCE_PRECISION_HINT", precision)]].concat();
        let trunk = core.compile(&fast.join("trunk.onnx"), &with(options.precision))?;
        let tail = core.compile(&fast.join("tail.onnx"), &with("f32"))?;
        Ok(Fast { front, idle: Mutex::new(Vec::new()), trunk, tail, _core: core })
    }

    fn take(&self) -> Result<(Request, Request), String> {
        let idle = self.idle.lock().unwrap_or_else(|poisoned| poisoned.into_inner()).pop();
        match idle {
            Some(pair) => Ok(pair),
            None => Ok((self.trunk.request()?, self.tail.request()?)),
        }
    }

    /// Turns the front model's output for one request into 16-bit PCM.
    fn finish(&self, mut packed: &[f32], sample_rate: u32) -> Result<Vec<u8>, String> {
        let (mut trunk, mut tail) = self.take()?;
        let mut samples = Vec::new();
        while !packed.is_empty() {
            let chunk = Chunk::read(&mut packed)?;
            trunk.set_input(HAR, &[1, HAR_CHANNELS as i64, chunk.har_frames as i64], chunk.har)?;
            trunk.set_input(X, &[1, X_CHANNELS as i64, chunk.x_frames as i64], chunk.x)?;
            trunk.set_input(STYLE, &[1, STYLE_SIZE as i64], chunk.style)?;
            trunk.run()?;
            tail.set_input_tensor(TRUNK_OUT, &trunk.output()?)?;
            tail.run()?;
            scale_silence(tail.output()?.floats()?, SILENCE_SCALE, sample_rate, &mut samples);
        }
        // A pair that failed is dropped above by `?`: its state is unknown.
        self.idle.lock().unwrap_or_else(|poisoned| poisoned.into_inner()).push((trunk, tail));
        Ok(to_pcm16(&samples))
    }
}

impl Synth for Fast {
    fn generate(&self, text: &str, voice: &VoiceRef) -> Result<(u32, Vec<u8>), String> {
        let VoiceRef::Speaker(speaker) = voice else { return Err("Kokoro voices are chosen by speaker number".to_string()) };
        let rate = self.front.sample_rate;
        let (_, pcm) = self.front.with_samples(text, *speaker, |packed| self.finish(packed, rate))?;
        Ok((rate, pcm?))
    }
}

/// The trunk's inputs for one model run, as `front.onnx` packs them:
/// `[MAGIC, L, T, har (22 x L), x (512 x T), style (128)]`.
struct Chunk<'a> {
    har: &'a [f32],
    har_frames: usize,
    x: &'a [f32],
    x_frames: usize,
    style: &'a [f32],
}

impl<'a> Chunk<'a> {
    /// Takes one chunk off the front of [packed].
    fn read(packed: &mut &'a [f32]) -> Result<Chunk<'a>, String> {
        let bad = || "The front model's output is not in the expected layout".to_string();
        let [magic, har_frames, x_frames, rest @ ..] = *packed else { return Err(bad()) };
        let count =
            |value: f32| (value.is_finite() && value >= 1.0 && value.fract() == 0.0 && value < 16_777_216.0).then_some(value as usize);
        let (Some(har_frames), Some(x_frames)) = (count(*har_frames), count(*x_frames)) else { return Err(bad()) };
        let (har_size, x_size) = (HAR_CHANNELS * har_frames, X_CHANNELS * x_frames);
        if *magic != MAGIC || rest.len() < har_size + x_size + STYLE_SIZE {
            return Err(bad());
        }
        let (har, rest) = rest.split_at(har_size);
        let (x, rest) = rest.split_at(x_size);
        let (style, rest) = rest.split_at(STYLE_SIZE);
        *packed = rest;
        Ok(Chunk { har, har_frames, x, x_frames, style })
    }
}

/// sherpa-onnx's `GeneratedAudio::ScaleSilence` (v1.12.21), appending to [out]: a pause is a run of
/// samples within ±0.01 lasting 0.2 s or more, and each is cut to [scale] of its length.
fn scale_silence(samples: &[f32], scale: f32, sample_rate: u32, out: &mut Vec<f32>) {
    if scale == 1.0 {
        out.extend_from_slice(samples);
        return;
    }
    let threshold = (f64::from(sample_rate) * 0.2) as usize;
    let mut pauses: Vec<(usize, usize)> = Vec::new();
    let mut start: Option<usize> = None;
    for (index, sample) in samples.iter().enumerate() {
        if f64::from(sample.abs()) <= 0.01 {
            start.get_or_insert(index);
            continue;
        }
        if let Some(from) = start.take() {
            if index - from >= threshold {
                pauses.push((from, index));
            }
        }
    }
    // A pause that runs to the end counts only when longer than the threshold, as in sherpa-onnx.
    if let Some(from) = start {
        if samples.len() - from > threshold {
            pauses.push((from, samples.len()));
        }
    }
    out.reserve(samples.len());
    let mut copied = 0;
    for (from, to) in pauses {
        out.extend_from_slice(&samples[copied..from]);
        let kept = ((to - from) as f32 * scale) as usize;
        out.extend_from_slice(&samples[from..from + kept.min(to - from)]);
        copied = to;
    }
    out.extend_from_slice(&samples[copied..]);
}

#[cfg(test)]
mod tests {
    use super::*;

    fn scaled(samples: &[f32]) -> Vec<f32> {
        let mut out = Vec::new();
        scale_silence(samples, 0.2, 24_000, &mut out);
        out
    }

    fn signal(parts: &[(f32, usize)]) -> Vec<f32> {
        parts.iter().flat_map(|&(value, count)| std::iter::repeat_n(value, count)).collect()
    }

    #[test]
    fn long_pauses_are_cut_to_a_fifth() {
        // 0.5 s of quiet between two loud parts: 12000 samples become 2400.
        let out = scaled(&signal(&[(0.5, 1000), (0.005, 12_000), (0.5, 1000)]));
        assert_eq!(out.len(), 1000 + 2400 + 1000);
        assert_eq!(out[999], 0.5);
        assert_eq!(out[1000], 0.005);
        assert_eq!(out[3400], 0.5);
    }

    #[test]
    fn short_pauses_stay() {
        // 4799 samples is under 0.2 s; 4800 is a pause.
        assert_eq!(scaled(&signal(&[(0.5, 10), (0.0, 4799), (0.5, 10)])).len(), 10 + 4799 + 10);
        assert_eq!(scaled(&signal(&[(0.5, 10), (0.0, 4800), (0.5, 10)])).len(), 10 + 960 + 10);
    }

    #[test]
    fn pauses_at_the_edges() {
        // Leading quiet is a pause like any other; trailing quiet must be longer than 0.2 s.
        assert_eq!(scaled(&signal(&[(0.0, 6000), (0.5, 10)])).len(), 1200 + 10);
        assert_eq!(scaled(&signal(&[(0.5, 10), (0.0, 4800)])).len(), 10 + 4800);
        assert_eq!(scaled(&signal(&[(0.5, 10), (0.0, 4801)])).len(), 10 + 960);
        assert_eq!(scaled(&[]).len(), 0);
    }

    #[test]
    fn the_threshold_is_inclusive() {
        // ±0.01 itself is quiet (as a float32 it is just under 0.01); the next float up is not.
        assert_eq!(scaled(&signal(&[(0.5, 10), (-0.01, 6000), (0.5, 10)])).len(), 10 + 1200 + 10);
        assert_eq!(scaled(&signal(&[(0.5, 10), (0.010_000_001, 6000), (0.5, 10)])).len(), 10 + 6000 + 10);
    }

    #[test]
    fn a_scale_of_one_changes_nothing() {
        let samples = signal(&[(0.5, 10), (0.0, 6000), (0.5, 10)]);
        let mut out = Vec::new();
        scale_silence(&samples, 1.0, 24_000, &mut out);
        assert_eq!(out, samples);
    }

    #[test]
    fn chunks_are_read_in_turn() {
        let mut packed = Vec::new();
        for (har_frames, x_frames) in [(3usize, 1usize), (5, 2)] {
            packed.extend([MAGIC, har_frames as f32, x_frames as f32]);
            packed.extend(std::iter::repeat_n(1.0, HAR_CHANNELS * har_frames));
            packed.extend(std::iter::repeat_n(2.0, X_CHANNELS * x_frames));
            packed.extend(std::iter::repeat_n(3.0, STYLE_SIZE));
        }
        let mut rest = &packed[..];
        let first = Chunk::read(&mut rest).unwrap();
        assert_eq!((first.har.len(), first.x.len(), first.style.len()), (66, 512, 128));
        assert!(first.har.iter().all(|&v| v == 1.0) && first.x.iter().all(|&v| v == 2.0) && first.style.iter().all(|&v| v == 3.0));
        let second = Chunk::read(&mut rest).unwrap();
        assert_eq!((second.har_frames, second.x_frames), (5, 2));
        assert!(rest.is_empty());
    }

    #[test]
    fn a_wrong_layout_is_refused() {
        // Real audio where a chunk should be, a chunk cut short, and sizes that are not counts.
        assert!(Chunk::read(&mut &[0.1f32, 0.2, 0.3, 0.4][..]).is_err());
        assert!(Chunk::read(&mut &[MAGIC, 1.0, 1.0, 0.0][..]).is_err());
        assert!(Chunk::read(&mut &[MAGIC, 0.0, 1.0][..]).is_err());
        assert!(Chunk::read(&mut &[MAGIC, 1.5, 1.0][..]).is_err());
        assert!(Chunk::read(&mut &[MAGIC][..]).is_err());
    }
}

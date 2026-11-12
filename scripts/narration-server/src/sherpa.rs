//! The sherpa-onnx C API (v1.12.21, the version the app ships), for Kokoro only.
//!
//! One [Tts] holds one ONNX Runtime session. `Generate` is safe to call from several threads at
//! once: sherpa-onnx serializes espeak-ng phonemization behind a mutex, and `Ort::Session::Run` is
//! thread-safe. So every request shares one copy of the model's weights.

use std::ffi::{c_char, c_void, CStr, CString};
use std::path::Path;

// Struct layouts copied from sherpa-onnx/c-api/c-api.h at v1.12.21. Unused models stay zeroed.
#[repr(C)]
struct VitsConfig {
    model: *const c_char,
    lexicon: *const c_char,
    tokens: *const c_char,
    data_dir: *const c_char,
    noise_scale: f32,
    noise_scale_w: f32,
    length_scale: f32,
    dict_dir: *const c_char,
}

#[repr(C)]
struct MatchaConfig {
    acoustic_model: *const c_char,
    vocoder: *const c_char,
    lexicon: *const c_char,
    tokens: *const c_char,
    data_dir: *const c_char,
    noise_scale: f32,
    length_scale: f32,
    dict_dir: *const c_char,
}

#[repr(C)]
struct KokoroConfig {
    model: *const c_char,
    voices: *const c_char,
    tokens: *const c_char,
    data_dir: *const c_char,
    length_scale: f32,
    dict_dir: *const c_char,
    lexicon: *const c_char,
    lang: *const c_char,
}

#[repr(C)]
struct KittenConfig {
    model: *const c_char,
    voices: *const c_char,
    tokens: *const c_char,
    data_dir: *const c_char,
    length_scale: f32,
}

#[repr(C)]
struct ZipvoiceConfig {
    tokens: *const c_char,
    encoder: *const c_char,
    decoder: *const c_char,
    vocoder: *const c_char,
    data_dir: *const c_char,
    lexicon: *const c_char,
    feat_scale: f32,
    t_shift: f32,
    target_rms: f32,
    guidance_scale: f32,
}

#[repr(C)]
struct ModelConfig {
    vits: VitsConfig,
    num_threads: i32,
    debug: i32,
    provider: *const c_char,
    matcha: MatchaConfig,
    kokoro: KokoroConfig,
    kitten: KittenConfig,
    zipvoice: ZipvoiceConfig,
}

#[repr(C)]
struct TtsConfig {
    model: ModelConfig,
    rule_fsts: *const c_char,
    max_num_sentences: i32,
    rule_fars: *const c_char,
    silence_scale: f32,
}

#[repr(C)]
struct GeneratedAudio {
    samples: *const f32,
    n: i32,
    sample_rate: i32,
}

extern "C" {
    fn SherpaOnnxCreateOfflineTts(config: *const TtsConfig) -> *const c_void;
    fn SherpaOnnxDestroyOfflineTts(tts: *const c_void);
    fn SherpaOnnxOfflineTtsSampleRate(tts: *const c_void) -> i32;
    fn SherpaOnnxOfflineTtsNumSpeakers(tts: *const c_void) -> i32;
    fn SherpaOnnxOfflineTtsGenerate(tts: *const c_void, text: *const c_char, sid: i32, speed: f32) -> *const GeneratedAudio;
    fn SherpaOnnxDestroyOfflineTtsGeneratedAudio(audio: *const GeneratedAudio);
    fn SherpaOnnxGetVersionStr() -> *const c_char;
}

pub fn version() -> String {
    // SAFETY: returns a static NUL-terminated string.
    unsafe { CStr::from_ptr(SherpaOnnxGetVersionStr()) }.to_string_lossy().into_owned()
}

/// A loaded Kokoro model.
pub struct Tts {
    raw: *const c_void,
    pub sample_rate: u32,
}

// SAFETY: the sherpa-onnx object is immutable after creation and its Generate is thread-safe
// (see the module docs); it is destroyed only when the last reference drops.
unsafe impl Send for Tts {}
unsafe impl Sync for Tts {}

impl Tts {
    /// Loads the model; takes about a second and ~400 MB. sherpa-onnx exits the process on a missing
    /// file, so the files are checked first.
    pub fn load(model_dir: &Path, threads: usize) -> Result<Tts, String> {
        let files = ["model.onnx", "voices.bin", "tokens.txt", "espeak-ng-data"];
        for file in files {
            if !model_dir.join(file).exists() {
                return Err(format!("{} is missing from {}", file, model_dir.display()));
            }
        }
        let path = |name: &str| CString::new(model_dir.join(name).to_string_lossy().as_bytes()).map_err(|e| e.to_string());
        let (model, voices, tokens, data) = (path(files[0])?, path(files[1])?, path(files[2])?, path(files[3])?);
        let provider = CString::new("cpu").unwrap();
        // SAFETY: an all-zero TtsConfig is valid (null strings and zero numbers mean "default").
        let mut config: TtsConfig = unsafe { std::mem::zeroed() };
        config.model.kokoro.model = model.as_ptr();
        config.model.kokoro.voices = voices.as_ptr();
        config.model.kokoro.tokens = tokens.as_ptr();
        config.model.kokoro.data_dir = data.as_ptr();
        config.model.kokoro.length_scale = 1.0;
        config.model.num_threads = threads.max(1) as i32;
        config.model.provider = provider.as_ptr();
        // The phone's defaults (sherpa-onnx Kotlin API): one sentence per batch, silence scale 0.2.
        config.max_num_sentences = 1;
        config.silence_scale = 0.2;
        // SAFETY: every pointer in config outlives this call.
        let raw = unsafe { SherpaOnnxCreateOfflineTts(&config) };
        if raw.is_null() {
            return Err(format!("sherpa-onnx could not load the model in {}", model_dir.display()));
        }
        // SAFETY: raw is a live TTS object.
        let (sample_rate, speakers) = unsafe { (SherpaOnnxOfflineTtsSampleRate(raw), SherpaOnnxOfflineTtsNumSpeakers(raw)) };
        if speakers != crate::http::SPEAKERS as i32 {
            // SAFETY: raw is live and not shared yet.
            unsafe { SherpaOnnxDestroyOfflineTts(raw) };
            return Err(format!("{} is not kokoro-en-v0_19: it has {speakers} voices, not {}", model_dir.display(), crate::http::SPEAKERS));
        }
        Ok(Tts { raw, sample_rate: sample_rate.max(0) as u32 })
    }

    /// Speaks [text] at 1x and returns 16-bit PCM, converted as the app's TtsWavWriter does (clamp,
    /// x32767, round half up), plus its sample rate.
    pub fn generate(&self, text: &str, speaker: u32) -> Result<(u32, Vec<u8>), String> {
        let text = CString::new(text).map_err(|_| "The text contains a NUL character".to_string())?;
        // SAFETY: self.raw is live for &self; text outlives the call.
        let audio = unsafe { SherpaOnnxOfflineTtsGenerate(self.raw, text.as_ptr(), speaker as i32, 1.0) };
        if audio.is_null() {
            return Err("sherpa-onnx returned no audio".to_string());
        }
        // SAFETY: audio is a live GeneratedAudio until destroyed below; samples holds n floats.
        let result = unsafe {
            let generated = &*audio;
            let samples: &[f32] = if generated.samples.is_null() || generated.n <= 0 {
                &[]
            } else {
                std::slice::from_raw_parts(generated.samples, generated.n as usize)
            };
            (generated.sample_rate.max(0) as u32, to_pcm16(samples))
        };
        // SAFETY: destroyed exactly once.
        unsafe { SherpaOnnxDestroyOfflineTtsGeneratedAudio(audio) };
        let rate = if result.0 == 0 { self.sample_rate } else { result.0 };
        Ok((rate, result.1))
    }
}

impl Drop for Tts {
    fn drop(&mut self) {
        // SAFETY: the last reference is going away; no generation is running.
        unsafe { SherpaOnnxDestroyOfflineTts(self.raw) };
    }
}

/// Java's `Math.round((x.coerceIn(-1f, 1f) * 32767f))`: round half up. The product is computed in
/// f32 like the app; adding 0.5 in f64 is exact, so the rounding matches Java's for every input.
pub fn to_pcm16(samples: &[f32]) -> Vec<u8> {
    let mut pcm = Vec::with_capacity(samples.len() * 2);
    for &sample in samples {
        let scaled = sample.clamp(-1.0, 1.0) * 32767.0f32;
        let value = (scaled as f64 + 0.5).floor() as i16;
        pcm.extend_from_slice(&value.to_le_bytes());
    }
    pcm
}

#[cfg(test)]
mod tests {
    use super::to_pcm16;

    fn values(samples: &[f32]) -> Vec<i16> {
        to_pcm16(samples).chunks(2).map(|b| i16::from_le_bytes([b[0], b[1]])).collect()
    }

    #[test]
    fn converts_like_the_app() {
        assert_eq!(values(&[0.0, 1.0, -1.0, 2.0, -2.0]), vec![0, 32767, -32767, 32767, -32767]);
        assert_eq!(values(&[f32::NAN]), vec![0]);
    }
}

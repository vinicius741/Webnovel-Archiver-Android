//! Phonon 31M through its own runtime, `ptts` (the same Rust library the app's Android build of
//! `libptts_ffi` wraps), on the CPU with the full-precision weights.
//!
//! The folder is a ptts checkpoint: `config.json`, `tokenizer.json`, `model.safetensors`, and the
//! voices as `voices/<Name>.safetensors`. Text is normalized as English before it is spoken, as
//! `ptts_new(dir, CPU, "en")` does on the phone.

use crate::models::{Synth, VoiceRef};
use crate::sherpa::to_pcm16;
use ptts::checkpoint::read_config;
use ptts::preprocess::Normalize;
use ptts::synth::{DeviceKind, Quant, SpeechOptions, Synth as PttsSynth, SynthBuilder};
use std::path::Path;

pub struct Phonon {
    synth: PttsSynth,
    normalize: Normalize,
    sample_rate: u32,
}

impl Phonon {
    pub fn load(dir: &Path) -> Result<Phonon, String> {
        let weights = dir.join("model.safetensors");
        if !weights.is_file() {
            return Err(format!("model.safetensors is missing from {}", dir.display()));
        }
        let config = read_config(dir.join("config.json")).map_err(|e| e.to_string())?;
        let mut builder = SynthBuilder::new(config, weights, Normalize::OFF)
            .tokenizer_file(dir.join("tokenizer.json"))
            .device(DeviceKind::Cpu)
            .quant(Quant::F32);
        let voices = ptts::loader::checkpoint_voices(dir);
        if voices.is_empty() {
            return Err(format!("{} has no voices", dir.display()));
        }
        for (name, path) in voices {
            builder = builder.add_voice(name, path);
        }
        let synth = builder.build().map_err(|e| e.to_string())?;
        let sample_rate = synth.sample_rate() as u32;
        let normalize = "en".parse::<Normalize>().map_err(|e| e.to_string())?;
        Ok(Phonon { synth, normalize, sample_rate })
    }
}

impl Synth for Phonon {
    fn generate(&self, text: &str, voice: &VoiceRef) -> Result<(u32, Vec<u8>), String> {
        let VoiceRef::Named(name) = voice else { return Err("Phonon voices are chosen by name".to_string()) };
        let options = SpeechOptions { voice: Some(name.clone()), ..Default::default() };
        let text = self.normalize.apply(text);
        let mut samples: Vec<f32> = Vec::new();
        for chunk in self.synth.stream_with(&text, &options).map_err(|e| e.to_string())? {
            samples.extend(chunk.map_err(|e| e.to_string())?);
        }
        Ok((self.sample_rate, to_pcm16(&samples)))
    }
}

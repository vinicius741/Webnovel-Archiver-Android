//! The models the server can speak with, each in its own folder under the models directory, and
//! the one interface the engine needs from them.
//!
//! - `kokoro-en-v0_19`: sherpa-onnx's first Kokoro export, the app's original voice (11 speakers).
//! - `kokoro-multi-lang-v1_0`: sherpa-onnx's Kokoro v1.0 export (54 speakers; the app uses the
//!   American English ones), with its US English lexicon in front of espeak-ng.
//! - `phonon-31m-en`: Gradium's Phonon 31M, run by its own Rust runtime (`ptts`), with voices
//!   addressed by name.
//!
//! Each loads on first use and unloads when idle, on its own (see `engine.rs`), so a server that has
//! all three installed holds none of them until a chapter asks. Where the processor allows it,
//! Kokoro v1.0 runs its generator through OpenVINO at reduced precision (see `fast.rs`).

use std::path::Path;
use std::sync::Arc;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Kind {
    KokoroV019,
    KokoroV1,
    Phonon,
}

/// Who speaks: a Kokoro speaker index, or a Phonon voice name.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum VoiceRef {
    Speaker(u32),
    Named(String),
}

/// A loaded model. `generate` may run on several threads at once.
pub trait Synth: Send + Sync {
    /// Speaks [text] at 1x and returns its sample rate and 16-bit PCM (see `sherpa::to_pcm16`).
    fn generate(&self, text: &str, voice: &VoiceRef) -> Result<(u32, Vec<u8>), String>;
}

const MIB: u64 = 1024 * 1024;

impl Kind {
    pub const ALL: [Kind; 3] = [Kind::KokoroV019, Kind::KokoroV1, Kind::Phonon];

    /// The name in the protocol and the folder under the models directory.
    pub fn name(self) -> &'static str {
        match self {
            Kind::KokoroV019 => "kokoro-en-v0_19",
            Kind::KokoroV1 => "kokoro-multi-lang-v1_0",
            Kind::Phonon => "phonon-31m-en",
        }
    }

    pub fn from_name(name: &str) -> Option<Kind> {
        Kind::ALL.into_iter().find(|kind| kind.name() == name)
    }

    /// Whether [dir] holds this model's files. A missing model is simply not offered.
    pub fn installed(self, dir: &Path) -> bool {
        match self {
            Kind::KokoroV019 | Kind::KokoroV1 => dir.join("model.onnx").is_file() && dir.join("voices.bin").is_file(),
            Kind::Phonon => dir.join("model.safetensors").is_file() && dir.join("config.json").is_file(),
        }
    }

    /// Memory a freshly loaded model needs before its first sentence, measured on x86-64: Kokoro
    /// ~400 MB weights and session plus ~450 MB for the first sentence; Phonon ~180 MB plus ~100 MB.
    pub fn default_load_cost(self) -> u64 {
        match self {
            Kind::KokoroV019 | Kind::KokoroV1 => 900 * MIB,
            Kind::Phonon => 400 * MIB,
        }
    }

    /// Speakers a Kokoro export has; the app's voice keys rely on this order.
    pub fn speakers(self) -> Option<u32> {
        match self {
            Kind::KokoroV019 => Some(11),
            Kind::KokoroV1 => Some(54),
            Kind::Phonon => None,
        }
    }

    /// The voice a request names, checked against what this model has.
    pub fn voice(self, speaker_id: Option<i64>, voice: Option<&str>, named: &[String]) -> Result<VoiceRef, String> {
        match self.speakers() {
            Some(count) => match speaker_id {
                Some(id) if (0..i64::from(count)).contains(&id) => Ok(VoiceRef::Speaker(id as u32)),
                Some(id) => Err(format!("No speaker {id} in {}", self.name())),
                None => Err(format!("{} needs a speakerId", self.name())),
            },
            None => match voice {
                Some(name) if named.iter().any(|known| known == name) => Ok(VoiceRef::Named(name.to_string())),
                Some(name) => Err(format!("No voice {name} in {}", self.name())),
                None => Err(format!("{} needs a voice", self.name())),
            },
        }
    }

    /// Loads the model. With [fast], Kokoro v1.0 takes its fast path (`fast.rs`); if that fails to
    /// load, it speaks through sherpa-onnx alone as before.
    pub fn load(self, dir: &Path, threads: usize, fast: Option<&crate::fast::Options>) -> Result<Arc<dyn Synth>, String> {
        if let (Kind::KokoroV1, Some(options)) = (self, fast) {
            match crate::fast::Fast::load(dir, threads, options) {
                Ok(model) => return Ok(Arc::new(model)),
                Err(error) => crate::engine::log(&format!("{}: the fast path did not load ({error}); using the normal path", self.name())),
            }
        }
        Ok(match self {
            Kind::KokoroV019 | Kind::KokoroV1 => Arc::new(crate::sherpa::Tts::load(self, dir, threads)?),
            Kind::Phonon => Arc::new(crate::phonon::Phonon::load(dir)?),
        })
    }

    /// Voice names a Phonon folder offers (its `voices/*.safetensors`); empty for Kokoro.
    pub fn named_voices(self, dir: &Path) -> Vec<String> {
        if self != Kind::Phonon {
            return Vec::new();
        }
        let mut names: Vec<String> = std::fs::read_dir(dir.join("voices"))
            .map(|entries| {
                entries
                    .filter_map(|entry| entry.ok()?.path().file_name()?.to_str().map(str::to_string))
                    .filter_map(|file| file.strip_suffix(".safetensors").map(str::to_string))
                    .collect()
            })
            .unwrap_or_default();
        names.sort();
        names
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn names_round_trip() {
        for kind in Kind::ALL {
            assert_eq!(Kind::from_name(kind.name()), Some(kind));
        }
        assert_eq!(Kind::from_name("kokoro"), None);
    }

    #[test]
    fn voices_are_checked_per_model() {
        assert_eq!(Kind::KokoroV019.voice(Some(10), None, &[]), Ok(VoiceRef::Speaker(10)));
        assert!(Kind::KokoroV019.voice(Some(11), None, &[]).is_err());
        assert_eq!(Kind::KokoroV1.voice(Some(53), None, &[]), Ok(VoiceRef::Speaker(53)));
        assert!(Kind::KokoroV1.voice(None, Some("Harper"), &[]).is_err());
        let named = vec!["Harper".to_string()];
        assert_eq!(Kind::Phonon.voice(None, Some("Harper"), &named), Ok(VoiceRef::Named("Harper".into())));
        assert!(Kind::Phonon.voice(None, Some("Zoey"), &named).is_err());
        assert!(Kind::Phonon.voice(Some(1), None, &named).is_err());
    }
}

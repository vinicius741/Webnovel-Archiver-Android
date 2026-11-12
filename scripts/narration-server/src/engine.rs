//! One shared Kokoro model, its generation slots, and the memory governor that sizes them.
//!
//! Requests take a slot ([Engine::acquire]) and generate on the shared model. The governor
//! ([Engine::govern], once a second) reads the machine's memory and sets how many slots exist:
//! - plenty of memory: up to `max_slots`;
//! - below the reserve: one slot, and the model is reloaded once to give back the memory that extra
//!   slots made ONNX Runtime keep (its arena never shrinks on its own);
//! - near exhaustion: no slots, the model is unloaded, and requests get 503 + Retry-After until
//!   memory has stayed above the critical line for a while.
//!
//! An idle model is unloaded too, so a server nobody uses holds a few MB.

use crate::memory::{self, Budget, Level, Policy, Snapshot, MIB};
use crate::sherpa::Tts;
use std::path::PathBuf;
use std::sync::{Arc, Condvar, Mutex, MutexGuard};
use std::time::{Duration, Instant};

/// Memory a freshly loaded model needs before its first sentence, measured: ~400 MB weights and
/// session, ~450 MB more for the first sentence's working memory.
const DEFAULT_LOAD_COST: u64 = 900 * MIB;
/// After a critical stop, memory must stay out of the critical zone this long before loading again.
/// It doubles (up to [MAX_COOLDOWN]) each time a reload is followed by another critical stop within
/// [RELOAD_REGRET], so a machine hovering at the line is not hit by a reload every few seconds.
const CRITICAL_COOLDOWN: Duration = Duration::from_secs(15);
const MAX_COOLDOWN: Duration = Duration::from_secs(300);
const RELOAD_REGRET: Duration = Duration::from_secs(60);
/// A model that fails to load this many times in a row is reported as broken (500), not busy.
const LOAD_FAILURES_BEFORE_ERROR: u32 = 2;
/// Memory must stay low this long before the model is reloaded to shrink it, so a brief dip (a
/// compile, a browser tab) does not cost a reload.
const LOW_BEFORE_SHRINK: Duration = Duration::from_secs(10);

#[derive(Clone, Debug)]
pub struct Settings {
    pub model_dir: PathBuf,
    pub threads: usize,
    pub policy: Policy,
    pub idle_unload: Duration,
    pub verbose: bool,
}

/// Why a request could not get a slot; the client should retry after [retry_after], unless [broken].
#[derive(Debug)]
pub struct Busy {
    pub message: String,
    pub level: Level,
    pub retry_after: Duration,
    pub available_mb: u64,
    /// The model cannot load at all (missing or corrupt files): waiting will not help.
    pub broken: bool,
}

#[derive(Clone, Copy, Debug, Default)]
pub struct Counters {
    pub sentences: u64,
    pub audio_ms: u64,
    pub generate_ms: u64,
    pub failures: u64,
    pub loads: u64,
    pub unloads: u64,
    pub refused: u64,
}

struct State {
    tts: Option<Arc<Tts>>,
    loading: bool,
    load_error: Option<String>,
    active: usize,
    waiting: usize,
    budget: Budget,
    snapshot: Snapshot,
    /// Most sentences that ran at once since the model loaded: what its arena is sized for.
    grown: usize,
    /// Admit nothing until running sentences finish, then unload (to recycle or to stop).
    draining: bool,
    last_used: Instant,
    /// Process memory just before the last load.
    before_load: u64,
    /// Highest process memory seen since the last load.
    peak: u64,
    slot_cost: u64,
    load_cost: u64,
    critical_since: Option<Instant>,
    low_since: Option<Instant>,
    cleared_at: Instant,
    cooldown: Duration,
    loaded_at: Option<Instant>,
    load_failures: u32,
    counters: Counters,
}

pub struct Engine {
    settings: Settings,
    state: Mutex<State>,
    changed: Condvar,
    started: Instant,
}

/// A slot on the model; dropping it frees the slot.
pub struct Lease<'a> {
    engine: &'a Engine,
    tts: Option<Arc<Tts>>,
}

impl Lease<'_> {
    pub fn tts(&self) -> &Tts {
        self.tts.as_deref().expect("a live lease holds the model")
    }
}

impl Drop for Lease<'_> {
    fn drop(&mut self) {
        // Let go of the model first, so an unload below really frees it.
        drop(self.tts.take());
        let mut state = self.engine.lock();
        state.active -= 1;
        state.last_used = Instant::now();
        let unload = state.draining && state.active == 0;
        drop(state);
        self.engine.changed.notify_all();
        if unload {
            self.engine.unload("memory");
        }
    }
}

impl Engine {
    pub fn new(settings: Settings) -> Engine {
        let snapshot = memory::snapshot();
        let budget = memory::decide(&snapshot, &settings.policy, Level::Normal, 0, memory::DEFAULT_SLOT_COST);
        Engine {
            settings,
            state: Mutex::new(State {
                tts: None,
                loading: false,
                load_error: None,
                active: 0,
                waiting: 0,
                budget,
                snapshot,
                grown: 0,
                draining: false,
                last_used: Instant::now(),
                before_load: 0,
                peak: 0,
                slot_cost: memory::DEFAULT_SLOT_COST,
                load_cost: DEFAULT_LOAD_COST,
                critical_since: None,
                low_since: None,
                cooldown: CRITICAL_COOLDOWN,
                loaded_at: None,
                load_failures: 0,
                cleared_at: Instant::now() - CRITICAL_COOLDOWN,
                counters: Counters::default(),
            }),
            changed: Condvar::new(),
            started: Instant::now(),
        }
    }

    pub fn settings(&self) -> &Settings {
        &self.settings
    }

    fn lock(&self) -> MutexGuard<'_, State> {
        // A panic while holding the lock leaves plain counters behind; keep serving.
        self.state.lock().unwrap_or_else(|poisoned| poisoned.into_inner())
    }

    /// Takes a generation slot, loading the model first when needed. Waits up to [patience] for a
    /// slot while memory allows fewer slots than requests; refuses at once when generation is stopped.
    pub fn acquire(&self, patience: Duration) -> Result<Lease<'_>, Busy> {
        let deadline = Instant::now() + patience;
        let mut state = self.lock();
        state.waiting += 1;
        let result = loop {
            if state.budget.slots == 0 {
                break Err(self.busy(&state, "is low on memory and has stopped generating"));
            }
            if state.tts.is_none() && !state.loading && !state.draining {
                if let Some(refusal) = self.load_refusal(&state) {
                    break Err(refusal);
                }
                state = self.load_locked(state);
                if state.tts.is_none() {
                    let error = state.load_error.clone().unwrap_or_default();
                    let mut busy = self.busy(&state, &format!("could not load the model: {error}"));
                    busy.retry_after = Duration::from_secs(30);
                    busy.broken = state.load_failures >= LOAD_FAILURES_BEFORE_ERROR;
                    break Err(busy);
                }
                continue;
            }
            if let Some(tts) = state.tts.clone() {
                if !state.draining && state.active < state.budget.slots {
                    state.active += 1;
                    state.grown = state.grown.max(state.active);
                    break Ok(Lease { engine: self, tts: Some(tts) });
                }
            }
            let now = Instant::now();
            if now >= deadline {
                break Err(self.busy(&state, "is busy"));
            }
            state = self.changed.wait_timeout(state, deadline - now).unwrap_or_else(|p| p.into_inner()).0;
        };
        state.waiting -= 1;
        if result.is_err() {
            state.counters.refused += 1;
        }
        result
    }

    /// The message stays the same while the situation does (no changing numbers), so the app's
    /// screens do not update on every retry; the free memory travels separately.
    fn busy(&self, state: &State, what: &str) -> Busy {
        let level = state.budget.level;
        Busy {
            message: what.to_string(),
            level,
            retry_after: memory::retry_after(level),
            available_mb: state.snapshot.available / MIB,
            broken: false,
        }
    }

    /// Why loading the model now would push the machine too far, if it would.
    fn load_refusal(&self, state: &State) -> Option<Busy> {
        let policy = &self.settings.policy;
        let since_cleared = state.cleared_at.elapsed();
        let cooling = since_cleared < state.cooldown && state.counters.unloads > 0 && state.critical_since.is_none();
        let mut room = state.snapshot.available.saturating_sub(policy.critical);
        if let Some(limit_room) = state.snapshot.limit_room {
            room = room.min(limit_room);
        }
        // Loading must leave a margin above the critical line, or the load itself would cross it.
        if !cooling && state.critical_since.is_none() && room >= state.load_cost + memory::HYSTERESIS {
            return None;
        }
        let mut busy = self.busy(state, "does not have the memory to load the model");
        busy.level = Level::Critical;
        // Ask again when loading may be allowed: when the cooldown ends, or later if memory is short.
        busy.retry_after = if cooling {
            (state.cooldown - since_cleared).max(Duration::from_secs(1))
        } else {
            memory::retry_after(if state.critical_since.is_some() { Level::Critical } else { Level::Low })
        };
        Some(busy)
    }

    /// Loads the model with the lock released, so status requests keep answering.
    fn load_locked<'a>(&'a self, mut state: MutexGuard<'a, State>) -> MutexGuard<'a, State> {
        state.loading = true;
        drop(state);
        let before = memory::snapshot().own;
        let started = Instant::now();
        let loaded = Tts::load(&self.settings.model_dir, self.settings.threads);
        let own = memory::snapshot().own;
        let mut state = self.lock();
        state.loading = false;
        match loaded {
            Ok(tts) => {
                log(&format!("Model loaded in {} ms ({} MB in use)", started.elapsed().as_millis(), own / MIB));
                state.tts = Some(Arc::new(tts));
                state.load_error = None;
                state.load_failures = 0;
                state.before_load = before;
                state.peak = own;
                state.grown = 0;
                state.counters.loads += 1;
                // A preload is use: the idle timer must not unload it before the first sentence.
                state.last_used = Instant::now();
                state.loaded_at = Some(Instant::now());
                // Costs are re-learned from this load's peaks, so one unusual chapter does not
                // lower the slot count forever.
                state.slot_cost = ((state.slot_cost + memory::DEFAULT_SLOT_COST) / 2).max(memory::DEFAULT_SLOT_COST);
                state.load_cost = ((state.load_cost + DEFAULT_LOAD_COST) / 2).max(DEFAULT_LOAD_COST);
            }
            Err(error) => {
                log(&format!("Model failed to load: {error}"));
                state.load_error = Some(error);
                state.load_failures += 1;
            }
        }
        self.changed.notify_all();
        state
    }

    /// Loads the model ahead of the first sentence (the app asks for /v1/info first).
    pub fn preload(&self) {
        let state = self.lock();
        if state.tts.is_some() || state.loading || state.draining || state.budget.slots == 0 || self.load_refusal(&state).is_some() {
            return;
        }
        drop(self.load_locked(state));
    }

    /// Frees the model now; only called with no sentence running.
    fn unload(&self, why: &str) {
        let mut state = self.lock();
        state.draining = false;
        let Some(tts) = state.tts.take() else { return };
        state.grown = 0;
        state.counters.unloads += 1;
        drop(state);
        drop(tts);
        memory::release_free_heap();
        let own = memory::snapshot().own;
        log(&format!("Model unloaded ({why}); {} MB in use", own / MIB));
        self.changed.notify_all();
    }

    /// One governor step: read memory, set the slots, recycle or unload when needed.
    pub fn govern(&self) {
        let snapshot = memory::snapshot();
        let mut state = self.lock();
        let previous = state.budget;
        state.snapshot = snapshot;
        if state.tts.is_some() {
            state.peak = state.peak.max(snapshot.own);
            // Learn what loading and each extra slot really cost here, from the peak since loading.
            let grown_by = state.peak.saturating_sub(state.before_load);
            if state.grown == 1 {
                state.load_cost = state.load_cost.max(grown_by).min(4096 * MIB);
            } else if state.grown > 1 {
                let per_slot = grown_by.saturating_sub(state.load_cost) / (state.grown as u64 - 1);
                state.slot_cost = state.slot_cost.max(per_slot).min(2048 * MIB);
            }
        }
        let budget = memory::decide(&snapshot, &self.settings.policy, previous.level, state.grown, state.slot_cost);
        state.budget = budget;
        let now = Instant::now();
        state.low_since = if budget.level == Level::Low { state.low_since.or(Some(now)) } else { None };
        match budget.level {
            Level::Critical => {
                if state.critical_since.is_none() {
                    state.critical_since = Some(now);
                    // Reloading soon after the last stop led straight back here: wait longer next time.
                    let regret = state.loaded_at.is_some_and(|at| now - at < RELOAD_REGRET) && state.counters.unloads > 0;
                    state.cooldown = if regret { (state.cooldown * 2).min(MAX_COOLDOWN) } else { CRITICAL_COOLDOWN };
                }
            }
            _ => {
                if state.critical_since.take().is_some() {
                    state.cleared_at = now;
                }
            }
        }
        let loaded = state.tts.is_some();
        // Stop: unload as soon as running sentences finish. Low: give back what extra slots grew.
        let low_for_long = state.low_since.is_some_and(|since| now - since >= LOW_BEFORE_SHRINK);
        let shed = loaded && (budget.level == Level::Critical || (low_for_long && state.grown > 1));
        let idle = loaded && state.active == 0 && state.waiting == 0 && state.last_used.elapsed() >= self.settings.idle_unload;
        if shed && !state.draining {
            state.draining = true;
        }
        let unload_now = (state.draining || idle) && state.active == 0 && loaded;
        let reason = if idle && !shed { "idle" } else { "memory" };
        drop(state);
        if previous != budget {
            log(&format!(
                "Memory {}: {} MB available, {} MB in use, {} slot(s)",
                budget.level.name(),
                snapshot.available / MIB,
                snapshot.own / MIB,
                budget.slots
            ));
            self.changed.notify_all();
        }
        if unload_now {
            self.unload(reason);
        }
    }

    pub fn record(&self, audio_ms: u64, generate_ms: u64, ok: bool) {
        let mut state = self.lock();
        if ok {
            state.counters.sentences += 1;
            state.counters.audio_ms += audio_ms;
            state.counters.generate_ms += generate_ms;
        } else {
            state.counters.failures += 1;
        }
    }

    pub fn slots_now(&self) -> usize {
        self.lock().budget.slots
    }

    /// Everything `/v1/status` reports.
    pub fn status(&self) -> serde_json::Value {
        let state = self.lock();
        let c = state.counters;
        serde_json::json!({
            "uptimeS": self.started.elapsed().as_secs(),
            "memory": {
                "level": state.budget.level.name(),
                "slots": state.budget.slots,
                "maxSlots": self.settings.policy.max_slots,
                "availableMb": state.snapshot.available / MIB,
                "totalMb": state.snapshot.total / MIB,
                "reserveMb": self.settings.policy.reserve / MIB,
                "criticalMb": self.settings.policy.critical / MIB,
                "serverMb": state.snapshot.own / MIB,
                "slotCostMb": state.slot_cost / MIB,
                "loadCostMb": state.load_cost / MIB,
                "stallSome": state.snapshot.stall_some,
                "stallFull": state.snapshot.stall_full,
                "limitRoomMb": state.snapshot.limit_room.map(|room| room / MIB),
            },
            "model": {
                "loaded": state.tts.is_some(),
                "loading": state.loading,
                "active": state.active,
                "waiting": state.waiting,
                "grownTo": state.grown,
                "loads": c.loads,
                "unloads": c.unloads,
                "idleUnloadS": self.settings.idle_unload.as_secs(),
                "error": state.load_error,
            },
            "work": {
                "sentences": c.sentences,
                "audioS": c.audio_ms / 1000,
                "sentenceSpeed": if c.generate_ms > 0 { (c.audio_ms as f64 / c.generate_ms as f64 * 100.0).round() / 100.0 } else { 0.0 },
                "failures": c.failures,
                "refused": c.refused,
            },
        })
    }
}

pub fn log(message: &str) {
    eprintln!("{message}");
}

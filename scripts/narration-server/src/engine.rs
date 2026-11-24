//! One model, its generation slots, and the memory governor that sizes them. The server has one
//! [Engine] per installed model (see `models.rs`); each loads, shrinks and unloads on its own.
//!
//! The model runs in a worker process (`worker.rs`), so unloading it gives every byte back, swap
//! included. Requests take a slot ([Engine::acquire]) and generate in the worker. The governor
//! ([Engine::govern_with], once a second) reads the machine's memory and acts in this order:
//! 1. plenty of memory: up to `max_slots`;
//! 2. under the reserve: free first (pause new sentences for a moment so the worker drops its
//!    between-sentence buffers; unload an idle model sooner), then one slot fewer per slot's worth
//!    of memory missing, and when that leaves the worker much bigger than its slots need, restart
//!    it at the smaller size (a "recycle", ~2 s);
//! 3. no memory left: no slots, the worker ends once running sentences finish, and requests get
//!    503 + Retry-After until memory comes back;
//! 4. far below that: the worker is killed at once.
//!
//! An idle model is unloaded too, so a server nobody uses holds a few MB.

use crate::memory::{self, Budget, Level, Policy, Snapshot, MIB};
use crate::models::{Kind, VoiceRef};
use crate::worker::{Spec, Worker, WorkerError};
use std::path::PathBuf;
use std::sync::{Arc, Condvar, Mutex, MutexGuard};
use std::time::{Duration, Instant};

/// After a critical stop, memory must stay out of the critical zone this long before loading again.
/// It doubles (up to [MAX_COOLDOWN]) each time a reload is followed by another critical stop within
/// [RELOAD_REGRET], so a machine hovering at the line is not hit by a reload every few seconds.
const CRITICAL_COOLDOWN: Duration = Duration::from_secs(15);
const MAX_COOLDOWN: Duration = Duration::from_secs(300);
const RELOAD_REGRET: Duration = Duration::from_secs(60);
/// After a worker dies (killed for memory, crashed), wait this long before starting another; doubled
/// when the next one also dies within [RELOAD_REGRET], up to [MAX_COOLDOWN].
const LOST_COOLDOWN: Duration = Duration::from_secs(15);
/// A model that fails to load this many times in a row is reported as broken (500), not busy. Only
/// errors the worker reports count; a worker that dies while loading is a memory problem.
const LOAD_FAILURES_BEFORE_ERROR: u32 = 2;
/// Slots must sit this far below what the worker grew for this long before it is recycled, so a
/// brief dip (a compile, a browser tab) does not cost a reload.
const LOW_BEFORE_SHRINK: Duration = Duration::from_secs(10);
/// Recycles are at least this far apart; the gap doubles (up to [MAX_RECYCLE_GAP]) when the next one
/// is needed soon after, so memory sitting at a line cannot cause a reload loop.
const RECYCLE_GAP: Duration = Duration::from_secs(60);
const MAX_RECYCLE_GAP: Duration = Duration::from_secs(600);
/// How long new sentences may be held back so the running ones finish and the worker frees its
/// between-sentence buffers, and how often that may happen.
const QUIESCE: Duration = Duration::from_secs(3);
const QUIESCE_EVERY: Duration = Duration::from_secs(30);
/// Idle unload while memory is under the reserve.
const IDLE_UNLOAD_UNDER_RESERVE: Duration = Duration::from_secs(60);
/// While the model is unloaded, what its learned load cost holds above the default halves this often.
/// The cost is learned from the peak of a whole session (a long sentence can grow it to the 4 GB cap)
/// and only a load corrects it, so without this, one tall peak could refuse every later load on a
/// machine that has plenty of memory for a fresh one. A load that does grow too far is stopped by the
/// critical line, and its cooldown keeps it from reloading at once.
const LOAD_COST_HALF_LIFE: Duration = Duration::from_secs(60);

#[derive(Clone, Debug)]
pub struct Settings {
    pub kind: Kind,
    pub model_dir: PathBuf,
    pub threads: usize,
    /// Kokoro v1.0's fast path, where it applies (see `fast.rs`).
    pub fast: Option<crate::fast::Options>,
    pub policy: Policy,
    pub idle_unload: Duration,
    pub verbose: bool,
}

/// How a model loads. Kokoro v1.0 has two: its fast path, and the normal path, which speaks the same
/// but needs about 0.7 GB less and runs slower; it is the lower-capacity way to keep going.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Variant {
    Fast = 0,
    Plain = 1,
}

impl Variant {
    fn name(self) -> &'static str {
        match self {
            Variant::Fast => "fast",
            Variant::Plain => "normal",
        }
    }
}

impl Settings {
    /// Memory a freshly loaded worker needs, its first sentence included.
    fn default_load_cost(&self, variant: Variant) -> u64 {
        match variant {
            Variant::Fast => crate::fast::LOAD_COST,
            Variant::Plain => self.kind.default_load_cost(),
        }
    }

    /// The ways this model can load, preferred first.
    fn variants(&self) -> &'static [Variant] {
        if self.fast.is_some() {
            &[Variant::Fast, Variant::Plain]
        } else {
            &[Variant::Plain]
        }
    }
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
    /// Workers that died (killed for memory, crashed) rather than being unloaded.
    pub lost: u64,
    pub recycles: u64,
    pub quiesces: u64,
}

struct State {
    worker: Option<Arc<Worker>>,
    /// Counts loads, so news about a dead worker is applied to that worker only.
    generation: u64,
    variant: Variant,
    loading: bool,
    load_error: Option<String>,
    active: usize,
    waiting: usize,
    budget: Budget,
    snapshot: Snapshot,
    /// Most sentences that ran at once since the worker started: what its memory is sized for.
    grown: usize,
    /// Slots a recycled worker keeps counting as paid for, so a recycle steps down rather than
    /// falling to one slot.
    target: usize,
    /// Admit nothing until running sentences finish, then unload (to recycle or to stop).
    draining: bool,
    drain_reason: &'static str,
    last_used: Instant,
    /// The worker's resident memory and swap at the last reading.
    worker_memory: (u64, u64),
    /// Highest worker memory (resident + swap) seen since it started.
    peak: u64,
    slot_cost: u64,
    /// Learned per variant while loaded; read them through [Engine::load_cost], which lets them decay
    /// once unloaded.
    load_costs: [u64; 2],
    /// When the model was last unloaded; None while it is loaded or before its first load.
    unloaded_at: Option<Instant>,
    critical_since: Option<Instant>,
    /// Since when slots have sat below what the worker grew.
    shrink_since: Option<Instant>,
    /// When memory last left the critical zone; None until it has been there.
    cleared_at: Option<Instant>,
    cooldown: Duration,
    loaded_at: Option<Instant>,
    load_failures: u32,
    lost_at: Option<Instant>,
    lost_cooldown: Duration,
    /// New sentences wait until then, or until the running ones finish, whichever is first.
    quiet_until: Option<Instant>,
    last_quiet: Option<Instant>,
    last_recycle: Option<Instant>,
    recycle_gap: Duration,
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
    worker: Option<Arc<Worker>>,
    generation: u64,
}

impl Lease<'_> {
    pub fn generate(&self, text: &str, voice: &VoiceRef) -> Result<(u32, Vec<u8>), WorkerError> {
        self.worker.as_deref().expect("a live lease holds the worker").generate(text, voice)
    }

    pub fn generation(&self) -> u64 {
        self.generation
    }
}

impl Drop for Lease<'_> {
    fn drop(&mut self) {
        // Let go of the worker first, so an unload below really ends it.
        drop(self.worker.take());
        let mut state = self.engine.lock();
        state.active -= 1;
        state.last_used = Instant::now();
        if state.active == 0 {
            // The pause did its job: the worker has freed its buffers.
            state.quiet_until = None;
        }
        let unload = state.draining && state.active == 0;
        let reason = state.drain_reason;
        drop(state);
        self.engine.changed.notify_all();
        if unload {
            self.engine.unload(reason);
        }
    }
}

impl Engine {
    pub fn new(settings: Settings) -> Engine {
        let snapshot = memory::snapshot();
        let budget = memory::decide(&snapshot, &settings.policy, &Budget::start(&settings.policy), 0, memory::DEFAULT_SLOT_COST);
        let load_costs = [settings.default_load_cost(Variant::Fast), settings.default_load_cost(Variant::Plain)];
        Engine {
            settings,
            state: Mutex::new(State {
                worker: None,
                generation: 0,
                variant: Variant::Plain,
                loading: false,
                load_error: None,
                active: 0,
                waiting: 0,
                budget,
                snapshot,
                grown: 0,
                target: 0,
                draining: false,
                drain_reason: "memory",
                last_used: Instant::now(),
                worker_memory: (0, 0),
                peak: 0,
                slot_cost: memory::DEFAULT_SLOT_COST,
                load_costs,
                unloaded_at: None,
                critical_since: None,
                shrink_since: None,
                cleared_at: None,
                cooldown: CRITICAL_COOLDOWN,
                loaded_at: None,
                load_failures: 0,
                lost_at: None,
                lost_cooldown: LOST_COOLDOWN,
                quiet_until: None,
                last_quiet: None,
                last_recycle: None,
                recycle_gap: RECYCLE_GAP,
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
            if state.worker.is_none() && !state.loading && !state.draining {
                let variant = match self.choose_variant(&state) {
                    Ok(variant) => variant,
                    Err(refusal) => break Err(refusal),
                };
                let failures = state.load_failures;
                state = self.load_locked(state, variant);
                if state.worker.is_none() {
                    let error = state.load_error.clone().unwrap_or_default();
                    if state.load_failures == failures {
                        // The worker died while loading: memory, most likely. Not the model's fault.
                        break Err(self.lost_busy(&state));
                    }
                    let mut busy = self.busy(&state, &format!("could not load the model: {error}"));
                    busy.retry_after = Duration::from_secs(30);
                    busy.broken = state.load_failures >= LOAD_FAILURES_BEFORE_ERROR;
                    break Err(busy);
                }
                continue;
            }
            let now = Instant::now();
            let quiet = state.quiet_until.filter(|until| now < *until && state.active > 0);
            if let Some(worker) = state.worker.clone() {
                if !state.draining && quiet.is_none() && state.active < state.budget.slots {
                    state.active += 1;
                    state.grown = state.grown.max(state.active);
                    let generation = state.generation;
                    break Ok(Lease { engine: self, worker: Some(worker), generation });
                }
            }
            if now >= deadline {
                break Err(self.busy(&state, "is busy"));
            }
            let until = quiet.map_or(deadline, |quiet| quiet.min(deadline));
            let wait = until.saturating_duration_since(now).max(Duration::from_millis(10));
            state = self.changed.wait_timeout(state, wait).unwrap_or_else(|p| p.into_inner()).0;
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

    /// What a request is told while the worker is gone and another may not start yet. It reads as a
    /// stop, so the app holds this computer's sentences until then instead of retrying each one.
    fn lost_busy(&self, state: &State) -> Busy {
        let mut busy = self.busy(state, "lost its model to a memory shortage and will load it again");
        busy.level = Level::Critical;
        let left = state.lost_at.map_or(Duration::ZERO, |at| state.lost_cooldown.saturating_sub(at.elapsed()));
        busy.retry_after = left.max(Duration::from_secs(5));
        busy
    }

    /// Which way to load the model now: the fast path when it fits above the stop line, else the
    /// normal path, else a refusal saying when to ask again.
    fn choose_variant(&self, state: &State) -> Result<Variant, Busy> {
        let policy = &self.settings.policy;
        let since_cleared = state.cleared_at.map_or(Duration::MAX, |at| at.elapsed());
        let cooling = since_cleared < state.cooldown && state.counters.unloads > 0 && state.critical_since.is_none();
        let lost_left = state.lost_at.map_or(Duration::ZERO, |at| state.lost_cooldown.saturating_sub(at.elapsed()));
        if !lost_left.is_zero() {
            return Err(self.lost_busy(state));
        }
        let mut room = state.snapshot.available.saturating_sub(memory::effective_stop(policy, state.budget.swap_full));
        if let Some(limit_room) = state.snapshot.limit_room {
            room = room.min(limit_room);
        }
        if !cooling && state.critical_since.is_none() {
            // The learned cost is a measured peak, first sentence included; the emergency line backs
            // it up, so no further margin is kept.
            if let Some(variant) = self.settings.variants().iter().copied().find(|variant| room >= self.load_cost(state, *variant)) {
                return Ok(variant);
            }
        }
        let mut busy = self.busy(state, "does not have the memory to load the model");
        busy.level = Level::Critical;
        // Ask again when loading may be allowed: when the cooldown ends, or later if memory is short.
        busy.retry_after = if cooling {
            (state.cooldown - since_cleared).max(Duration::from_secs(1))
        } else {
            memory::retry_after(if state.critical_since.is_some() { Level::Critical } else { Level::Low })
        };
        Err(busy)
    }

    /// Whether a model could load again right after a recycle ends its worker. Without this,
    /// recycling just above the stop line would turn "fewer slots" into a stop: the worker would end
    /// and the reload be refused.
    fn reload_would_fit(&self, state: &State) -> bool {
        let freed = state.worker_memory.0;
        // Ending the worker raises Linux's available memory by what it held; macOS's figure never moved.
        let returned = if memory::AVAILABLE_INCLUDES_OWN { 0 } else { freed };
        let stop = memory::effective_stop(&self.settings.policy, state.budget.swap_full);
        let mut room = (state.snapshot.available + returned).saturating_sub(stop);
        if let Some(limit_room) = state.snapshot.limit_room {
            room = room.min(limit_room + freed);
        }
        self.settings.variants().iter().any(|variant| room >= state.load_costs[*variant as usize])
    }

    /// Memory a load needs now: the learned cost while loaded, decaying toward the default once
    /// unloaded (see [LOAD_COST_HALF_LIFE]).
    fn load_cost(&self, state: &State, variant: Variant) -> u64 {
        let learned = state.load_costs[variant as usize];
        let default = self.settings.default_load_cost(variant);
        let Some(since) = state.unloaded_at.filter(|_| state.worker.is_none()) else { return learned };
        let excess = learned.saturating_sub(default) as f64;
        let halvings = since.elapsed().as_secs_f64() / LOAD_COST_HALF_LIFE.as_secs_f64();
        default + (excess * 0.5f64.powf(halvings)) as u64
    }

    /// Starts a worker with the lock released, so status requests keep answering.
    fn load_locked<'a>(&'a self, mut state: MutexGuard<'a, State>, variant: Variant) -> MutexGuard<'a, State> {
        state.loading = true;
        let slots = state.budget.slots.max(state.target).max(1);
        let fast = match variant {
            Variant::Fast => self.settings.fast.as_ref().map(|options| options.for_slots(slots)),
            Variant::Plain => None,
        };
        drop(state);
        let spec = Spec { kind: self.settings.kind, model_dir: self.settings.model_dir.clone(), threads: self.settings.threads, fast };
        let started = Instant::now();
        let loaded = Worker::start(&spec);
        let mut state = self.lock();
        state.loading = false;
        let now = Instant::now();
        match loaded {
            Ok(worker) => {
                // The fast path falls back to the normal one by itself; learn the cost of what loaded.
                let variant = if worker.fast { Variant::Fast } else { Variant::Plain };
                let load_cost = self.load_cost(&state, variant);
                let used = worker.memory().unwrap_or_default();
                let with = if self.settings.fast.is_some() { format!(", {} path", variant.name()) } else { String::new() };
                log(&format!(
                    "{} loaded in {} ms in worker {} ({} MB{with})",
                    self.name(),
                    started.elapsed().as_millis(),
                    worker.pid(),
                    used.0 / MIB
                ));
                state.worker = Some(Arc::new(worker));
                state.generation += 1;
                state.variant = variant;
                state.load_error = None;
                state.load_failures = 0;
                state.unloaded_at = None;
                state.worker_memory = used;
                state.peak = used.0 + used.1;
                state.grown = 0;
                state.counters.loads += 1;
                // A preload is use: the idle timer must not unload it before the first sentence.
                state.last_used = now;
                state.loaded_at = Some(now);
                // Costs are re-learned from this load's peaks, so one unusual chapter does not
                // lower the slot count forever.
                state.slot_cost = ((state.slot_cost + memory::DEFAULT_SLOT_COST) / 2).max(memory::DEFAULT_SLOT_COST);
                let default = self.settings.default_load_cost(variant);
                state.load_costs[variant as usize] = ((load_cost + default) / 2).max(default);
            }
            Err(WorkerError::Failed(error)) => {
                log(&format!("{} failed to load: {error}", self.name()));
                state.load_error = Some(error);
                state.load_failures += 1;
            }
            Err(WorkerError::Lost(error)) => {
                log(&format!("{} could not load: {error}", self.name()));
                state.load_error = Some(error);
                note_lost(&mut state, now);
            }
        }
        self.changed.notify_all();
        state
    }

    /// Loads the model ahead of the first sentence (the app asks for /v1/info first).
    pub fn preload(&self) {
        let state = self.lock();
        if state.worker.is_some() || state.loading || state.draining || state.budget.slots == 0 {
            return;
        }
        let Ok(variant) = self.choose_variant(&state) else { return };
        drop(self.load_locked(state, variant));
    }

    /// Ends the worker now; only called with no sentence running.
    fn unload(&self, why: &str) {
        let mut state = self.lock();
        // A sentence may have taken a slot since the governor looked (a new chapter arriving just as
        // the idle timer ran out): the model stays, and a second worker is not started beside it.
        if state.active > 0 {
            return;
        }
        state.draining = false;
        let Some(worker) = state.worker.take() else { return };
        let held = state.worker_memory;
        state.grown = 0;
        state.worker_memory = (0, 0);
        state.unloaded_at = Some(Instant::now());
        state.counters.unloads += 1;
        drop(state);
        drop(worker);
        log(&format!("{} unloaded ({why}); gave back {} MB and {} MB of swap", self.name(), held.0 / MIB, held.1 / MIB));
        self.refresh_memory();
        self.changed.notify_all();
    }

    /// The worker of load [generation] died, or was killed for memory: forget it at once, even with
    /// sentences still in it (they fail and are retried later). [lost]: count it as a loss, which
    /// holds back the next load for a while; an emergency kill is a stop and has its own cooldown.
    pub fn report_lost(&self, generation: u64, why: &str, lost: bool) {
        let mut state = self.lock();
        if state.generation != generation {
            return;
        }
        let now = Instant::now();
        let Some((worker, held)) = take_worker(&mut state, now) else { return };
        if lost {
            note_lost(&mut state, now);
        }
        drop(state);
        drop(worker);
        log(&format!("{} lost its worker ({why}); it held {} MB and {} MB of swap", self.name(), held.0 / MIB, held.1 / MIB));
        self.refresh_memory();
        self.changed.notify_all();
    }

    /// Reads memory again after a worker ended, so a request that loads next (a recycle's waiting
    /// sentences, another model) sees the memory it gave back, not the governor's last reading.
    fn refresh_memory(&self) {
        if cfg!(test) {
            return; // Tests set the memory they mean.
        }
        let fresh = memory::snapshot();
        self.lock().snapshot = fresh;
    }

    /// What `/v1/synthesize` answers after a sentence's worker was lost.
    pub fn busy_after_loss(&self) -> Busy {
        let state = self.lock();
        if state.budget.slots == 0 {
            return self.busy(&state, "is low on memory and has stopped generating");
        }
        self.lost_busy(&state)
    }

    pub fn name(&self) -> &'static str {
        self.settings.kind.name()
    }

    /// One governor step on a fresh memory reading, for a server with one model.
    #[allow(dead_code)]
    pub fn govern(&self) {
        self.govern_with(memory::snapshot());
    }

    /// Whether this engine holds its model, or is loading it.
    pub fn is_loaded(&self) -> bool {
        let state = self.lock();
        state.worker.is_some() || state.loading
    }

    /// Unloads the model if nothing is using or waiting for it: another model is about to be needed,
    /// and two would share one machine's memory. Returns whether it unloaded.
    pub fn unload_if_idle(&self, why: &str) -> bool {
        let state = self.lock();
        if state.worker.is_none() || state.loading || state.active > 0 || state.waiting > 0 {
            return false;
        }
        drop(state);
        self.unload(why);
        // Slots kept from a recycle are not this model's next load's business.
        self.lock().target = 0;
        true
    }

    /// One governor step: given the machine's memory, set the slots; pause, recycle, unload or kill
    /// when needed. Each engine measures its own worker, so several loaded models do not blur what
    /// each costs.
    pub fn govern_with(&self, snapshot: Snapshot) {
        let mut state = self.lock();
        let previous = state.budget;
        state.snapshot = snapshot;
        let now = Instant::now();
        let mut dead = None;
        if let Some(worker) = state.worker.clone().filter(|worker| {
            // A dead worker reads as empty: keep its last reading for the log.
            let alive = worker.alive();
            if !alive {
                dead = Some(state.generation);
            }
            alive
        }) {
            // An exited worker reads as nothing: keep its last reading for the log.
            let used = worker.memory().unwrap_or(state.worker_memory);
            state.worker_memory = used;
            state.peak = state.peak.max(used.0 + used.1);
            // Learn what loading and each extra slot really cost here, from the peak since loading.
            let variant = state.variant as usize;
            if state.grown == 1 {
                state.load_costs[variant] = state.load_costs[variant].max(state.peak).min(4096 * MIB);
            } else if state.grown > 1 {
                let per_slot = state.peak.saturating_sub(state.load_costs[variant]) / (state.grown as u64 - 1);
                state.slot_cost = state.slot_cost.max(per_slot).min(2048 * MIB);
            }
        }
        let paid = state.grown.max(state.target);
        let budget = memory::decide(&snapshot, &self.settings.policy, &previous, paid, state.slot_cost);
        state.budget = budget;
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
                    state.cleared_at = Some(now);
                }
            }
        }
        let loaded = state.worker.is_some() && dead.is_none();
        let shrinking = loaded && budget.level == Level::Low && budget.slots < state.grown;
        // Slots well below what the worker grew for: what a recycle answers, timed on its own.
        let far_below = shrinking && (budget.slots + 2 <= state.grown || budget.slots == 1);
        state.shrink_since = if far_below { state.shrink_since.or(Some(now)) } else { None };
        if state.quiet_until.is_some_and(|until| now >= until) {
            state.quiet_until = None;
        }
        // Free first: hold new sentences back for a moment, so the running ones finish and the worker
        // drops the buffers they grew (the fast path keeps 1-2 GB after a long sentence otherwise).
        let mut paused = false;
        if shrinking && state.active > 0 && state.quiet_until.is_none() && state.last_quiet.is_none_or(|at| now - at >= QUIESCE_EVERY) {
            state.quiet_until = Some(now + QUIESCE);
            state.last_quiet = Some(now);
            state.counters.quiesces += 1;
            paused = true;
        }
        // Then restart the worker at the smaller size, when slots sit well below what it grew for.
        let shrunk_for_long = state.shrink_since.is_some_and(|since| now - since >= LOW_BEFORE_SHRINK);
        let gap_over = state.last_recycle.is_none_or(|at| now - at >= state.recycle_gap);
        let mut recycled = false;
        if shrunk_for_long && far_below && gap_over && !state.draining && self.reload_would_fit(&state) {
            state.recycle_gap = match state.last_recycle {
                Some(at) if now - at < state.recycle_gap + Duration::from_secs(120) => (state.recycle_gap * 2).min(MAX_RECYCLE_GAP),
                _ => RECYCLE_GAP,
            };
            state.last_recycle = Some(now);
            state.target = budget.slots;
            state.draining = true;
            state.drain_reason = "recycled to fewer slots";
            state.counters.recycles += 1;
            recycled = true;
        }
        if loaded && budget.level == Level::Critical {
            // Also over a recycle in progress: this is a stop, and nothing is kept for the reload.
            state.draining = true;
            state.drain_reason = "memory";
            state.target = 0;
        }
        let emergency = loaded && budget.emergency;
        // Out of the state before it is killed, so a sentence that sees it die cannot count it as a
        // loss first: this is a stop, with the critical cooldown.
        let killed = if emergency { take_worker(&mut state, now) } else { None };
        let idle_limit = if budget.level != Level::Normal && self.settings.idle_unload != Duration::MAX {
            self.settings.idle_unload.min(IDLE_UNLOAD_UNDER_RESERVE)
        } else {
            self.settings.idle_unload
        };
        let idle = loaded && state.active == 0 && state.waiting == 0 && state.last_used.elapsed() >= idle_limit;
        if idle && !state.draining {
            state.target = 0;
        }
        let unload_now = (state.draining || idle) && state.active == 0 && loaded && !emergency;
        let reason = if state.draining { state.drain_reason } else { "idle" };
        let (grown, held) = (state.grown, state.worker_memory.0);
        drop(state);
        // Slots alone move with every sentence's buffers while under the reserve; log them on request.
        let slots_only = previous.level == budget.level && previous.reason == budget.reason;
        if previous != budget {
            // Waiting sentences may now fit (or must wait), logged or not.
            self.changed.notify_all();
        }
        if previous != budget && (!slots_only || self.settings.verbose) {
            log(&format!(
                "{} memory {} ({}): {} MB available, worker {} MB, {} slot(s)",
                self.name(),
                budget.level.name(),
                budget.reason,
                snapshot.available / MIB,
                held / MIB,
                budget.slots
            ));
            self.changed.notify_all();
        }
        if paused {
            log(&format!("{}: holding new sentences briefly so the worker frees its buffers", self.name()));
        }
        if recycled {
            log(&format!("{}: recycling the worker from {grown} to {} slot(s) to give back memory", self.name(), budget.slots));
        }
        if let Some((worker, held)) = killed {
            worker.kill();
            drop(worker);
            log(&format!(
                "{} killed its worker: the machine is out of memory; it held {} MB and {} MB of swap",
                self.name(),
                held.0 / MIB,
                held.1 / MIB
            ));
            self.refresh_memory();
            self.changed.notify_all();
        } else if let Some(generation) = dead {
            self.report_lost(generation, "the worker exited", true);
        } else if unload_now {
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
        let policy = &self.settings.policy;
        let loaded = state.worker.is_some();
        serde_json::json!({
            "uptimeS": self.started.elapsed().as_secs(),
            "memory": {
                "level": state.budget.level.name(),
                "reason": state.budget.reason,
                "slots": state.budget.slots,
                "maxSlots": policy.max_slots,
                "availableMb": state.snapshot.available / MIB,
                "totalMb": state.snapshot.total / MIB,
                "reserveMb": policy.reserve / MIB,
                "criticalMb": memory::effective_stop(policy, state.budget.swap_full) / MIB,
                "emergencyMb": policy.emergency / MIB,
                "swapFull": state.budget.swap_full,
                "serverMb": state.snapshot.own / MIB,
                "slotCostMb": state.slot_cost / MIB,
                "loadCostMb": self.load_cost(&state, self.settings.variants()[0]) / MIB,
                "stallSome": state.snapshot.stall_some,
                "stallFull": state.snapshot.stall_full,
                "limitRoomMb": state.snapshot.limit_room.map(|room| room / MIB),
            },
            "model": {
                "name": self.name(),
                "loaded": loaded,
                "loading": state.loading,
                "variant": if loaded { Some(state.variant.name()) } else { None },
                "workerPid": state.worker.as_ref().map(|worker| worker.pid()),
                "workerMb": state.worker_memory.0 / MIB,
                "workerSwapMb": state.worker_memory.1 / MIB,
                "active": state.active,
                "waiting": state.waiting,
                "grownTo": state.grown,
                "keptSlots": state.target,
                "loads": c.loads,
                "unloads": c.unloads,
                "lost": c.lost,
                "recycles": c.recycles,
                "pauses": c.quiesces,
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

/// Takes the worker out of the state at once, sentences in it or not, as when it died or is about to
/// be killed. Returns it with the memory it held.
fn take_worker(state: &mut State, now: Instant) -> Option<(Arc<Worker>, (u64, u64))> {
    let worker = state.worker.take()?;
    let held = state.worker_memory;
    state.grown = 0;
    state.target = 0;
    state.draining = false;
    state.quiet_until = None;
    state.worker_memory = (0, 0);
    state.unloaded_at = Some(now);
    state.counters.unloads += 1;
    Some((worker, held))
}

/// A worker died (or could not start): hold back the next one, longer if this keeps happening.
fn note_lost(state: &mut State, now: Instant) {
    let regret = state.lost_at.is_some_and(|at| now - at < RELOAD_REGRET + state.lost_cooldown);
    state.lost_cooldown = if regret { (state.lost_cooldown * 2).min(MAX_COOLDOWN) } else { LOST_COOLDOWN };
    state.lost_at = Some(now);
    state.counters.lost += 1;
}

pub fn log(message: &str) {
    eprintln!("{message}");
}

#[cfg(test)]
mod tests {
    use super::*;

    const GIB: u64 = 1024 * MIB;
    const RESERVE: u64 = 3 * GIB;

    fn engine_with(fast: bool) -> Engine {
        let policy = Policy { reserve: RESERVE, critical: 640 * MIB, emergency: 320 * MIB, max_slots: 4 };
        Engine::new(Settings {
            kind: if fast { Kind::KokoroV1 } else { Kind::KokoroV019 },
            model_dir: PathBuf::from("/nonexistent"),
            threads: 1,
            fast: fast.then(|| crate::fast::Options { library: PathBuf::from("/nonexistent"), precision: "bf16", streams: 2 }),
            policy,
            idle_unload: Duration::MAX,
            verbose: false,
        })
    }

    fn engine() -> Engine {
        engine_with(false)
    }

    fn machine(available: u64) -> Snapshot {
        Snapshot { total: 16 * GIB, available, swap_total: 8 * GIB, swap_free: 8 * GIB, own: 10 * MIB, ..Snapshot::default() }
    }

    /// Puts a stand-in worker in the engine, as a load would.
    fn load_fake(engine: &Engine, grown: usize) -> u64 {
        let mut state = engine.lock();
        state.worker = Some(Arc::new(Worker::fake()));
        state.generation += 1;
        state.grown = grown;
        state.loaded_at = Some(Instant::now());
        state.generation
    }

    #[test]
    fn a_learned_load_cost_decays_once_unloaded() {
        let engine = engine();
        let mut state = engine.lock();
        // A long sentence on one slot taught it the cap.
        state.load_costs[Variant::Plain as usize] = 4096 * MIB;
        state.snapshot = machine(4000 * MIB);
        // Never unloaded: the learned cost stands.
        assert_eq!(engine.load_cost(&state, Variant::Plain), 4096 * MIB);
        // Just unloaded: refused with 4 GB available (3.4 GB above the stop line).
        state.unloaded_at = Some(Instant::now());
        assert!(engine.choose_variant(&state).is_err());
        // Two half-lives later the excess is a quarter: 900 + 799 MB, and the load goes ahead.
        state.unloaded_at = Some(Instant::now() - 2 * LOAD_COST_HALF_LIFE);
        let cost = engine.load_cost(&state, Variant::Plain) / MIB;
        assert!((1698..=1700).contains(&cost), "{cost}");
        assert_eq!(engine.choose_variant(&state).unwrap(), Variant::Plain);
        // Long unloaded, it is back near the default.
        state.unloaded_at = Some(Instant::now() - 10 * LOAD_COST_HALF_LIFE);
        assert!(engine.load_cost(&state, Variant::Plain) < 904 * MIB);
    }

    #[test]
    fn the_cheaper_variant_loads_when_the_fast_path_does_not_fit() {
        let engine = engine_with(true);
        let mut state = engine.lock();
        state.snapshot = machine(640 * MIB + 1700 * MIB);
        assert_eq!(engine.choose_variant(&state).unwrap(), Variant::Fast);
        state.snapshot = machine(640 * MIB + 1000 * MIB);
        assert_eq!(engine.choose_variant(&state).unwrap(), Variant::Plain);
        state.snapshot = machine(640 * MIB + 800 * MIB);
        assert!(engine.choose_variant(&state).is_err());
        // Swap nearly full raises the line both use.
        state.snapshot = machine(640 * MIB + 1700 * MIB);
        state.budget.swap_full = true;
        assert_eq!(engine.choose_variant(&state).unwrap(), Variant::Plain);
    }

    #[test]
    fn recycling_needs_room_to_load_again() {
        let engine = engine();
        let mut state = engine.lock();
        state.worker_memory = (1300 * MIB, 0);
        state.snapshot = machine(3000 * MIB);
        assert!(engine.reload_would_fit(&state));
        // Just above the stop line: only what ending the worker gives back makes room.
        state.snapshot.available = 700 * MIB;
        assert_eq!(engine.reload_would_fit(&state), !memory::AVAILABLE_INCLUDES_OWN);
        state.worker_memory = (300 * MIB, 0);
        assert!(!engine.reload_would_fit(&state));
        // Under a memory limit, ending the worker always gives its memory back.
        state.snapshot = Snapshot { limit_room: Some(100 * MIB), ..machine(8000 * MIB) };
        state.worker_memory = (1220 * MIB, 0);
        assert!(engine.reload_would_fit(&state));
        state.worker_memory = (500 * MIB, 0);
        assert!(!engine.reload_would_fit(&state));
    }

    #[test]
    fn a_lost_worker_is_forgotten_at_once_and_held_back_longer_each_time() {
        let engine = engine();
        engine.govern_with(machine(8 * GIB));
        let generation = load_fake(&engine, 4);
        engine.lock().active = 3;
        // News about an older worker changes nothing.
        engine.report_lost(generation - 1, "old", true);
        assert!(engine.is_loaded());
        engine.report_lost(generation, "killed", true);
        let state = engine.lock();
        assert!(state.worker.is_none());
        assert_eq!((state.counters.lost, state.grown), (1, 0));
        // The next load waits for the cooldown, and the refusal reads as a stop.
        let busy = engine.choose_variant(&state).unwrap_err();
        assert_eq!(busy.level, Level::Critical);
        assert!(busy.retry_after >= Duration::from_secs(5) && busy.retry_after <= LOST_COOLDOWN);
        drop(state);
        let generation = load_fake(&engine, 1);
        engine.report_lost(generation, "killed again", true);
        assert_eq!(engine.lock().lost_cooldown, LOST_COOLDOWN * 2);
    }

    #[test]
    fn a_recycle_steps_down_instead_of_falling_to_one_slot() {
        let engine = engine();
        engine.govern_with(machine(8 * GIB));
        load_fake(&engine, 4);
        // Two slots' worth under the reserve: two slots.
        let tight = machine(RESERVE - 2 * memory::DEFAULT_SLOT_COST + MIB);
        engine.govern_with(tight);
        assert_eq!(engine.slots_now(), 2);
        // Ten seconds later the worker is recycled and keeps two slots.
        engine.lock().shrink_since = Some(Instant::now() - LOW_BEFORE_SHRINK);
        engine.govern_with(tight);
        {
            let state = engine.lock();
            assert!(state.worker.is_none(), "an idle worker ends at once");
            assert_eq!((state.target, state.counters.recycles), (2, 1));
        }
        // The two slots kept count as paid for: back at the reserve (still Low, inside the margin) it
        // runs two, not one, and above the margin it grows from two.
        engine.govern_with(machine(RESERVE + memory::HYSTERESIS / 2));
        let budget = engine.lock().budget;
        assert_eq!((budget.level, budget.slots), (Level::Low, 2));
        engine.govern_with(machine(RESERVE + memory::HYSTERESIS + MIB));
        assert_eq!(engine.slots_now(), 3);
    }

    #[test]
    fn shrinking_pauses_new_sentences_first() {
        let engine = engine();
        engine.govern_with(machine(8 * GIB));
        load_fake(&engine, 4);
        engine.lock().active = 2;
        engine.govern_with(machine(RESERVE - MIB));
        let state = engine.lock();
        assert!(state.quiet_until.is_some());
        assert_eq!(state.counters.quiesces, 1);
        assert!(!state.draining, "no recycle before the pause has had its chance");
    }

    #[test]
    fn the_emergency_line_kills_the_worker_with_sentences_running() {
        let engine = engine();
        engine.govern_with(machine(8 * GIB));
        load_fake(&engine, 2);
        engine.lock().active = 2;
        engine.govern_with(machine(100 * MIB));
        let state = engine.lock();
        assert!(state.worker.is_none());
        assert_eq!(state.budget.slots, 0);
        // A stop, not a loss: the critical cooldown governs the next load.
        assert_eq!(state.counters.lost, 0);
    }

    #[test]
    fn under_the_reserve_an_idle_model_unloads_sooner() {
        let mut engine = engine();
        engine.settings.idle_unload = Duration::from_secs(300);
        engine.govern_with(machine(8 * GIB));
        load_fake(&engine, 1);
        engine.lock().last_used = Instant::now() - Duration::from_secs(90);
        engine.govern_with(machine(8 * GIB));
        assert!(engine.is_loaded());
        engine.govern_with(machine(RESERVE - MIB));
        assert!(!engine.is_loaded());
    }
}

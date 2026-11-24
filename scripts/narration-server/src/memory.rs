//! Reads how much memory the machine has left, and decides how hard the server may work.
//!
//! The governor runs once a second. It turns a [Snapshot] into a [Budget]: how many sentences may
//! generate at once (0 stops generation), and whether to give memory back by unloading the model.
//! See docs/tts/remote-narration-servers.md, "Memory".

use std::time::Duration;

pub const MIB: u64 = 1024 * 1024;

/// What the machine reports right now. Byte counts; pressure values are percentages (0-100).
#[derive(Clone, Copy, Debug, Default)]
pub struct Snapshot {
    pub total: u64,
    /// Memory the machine can hand out without swapping (Linux MemAvailable); on macOS, memory
    /// neither wired nor compressed (see [AVAILABLE_INCLUDES_OWN]).
    pub available: u64,
    /// Room left under this process's cgroup memory limit, when it has one (a systemd MemoryMax,
    /// a container). Hitting it throttles or kills only this server, so it caps slots but never
    /// reads as the machine running out.
    pub limit_room: Option<u64>,
    pub swap_total: u64,
    pub swap_free: u64,
    /// Share of the last 10 s some (or all) tasks stalled waiting for memory (Linux PSI).
    pub stall_some: f32,
    pub stall_full: f32,
    /// macOS's own verdict: 1 normal, 2 warning, 4 critical. 0 when unknown (Linux).
    pub os_pressure: u32,
    /// This process's resident memory.
    pub own: u64,
}

/// How hard the server may work. Ordered from worst to best.
#[derive(Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord)]
pub enum Level {
    /// No memory left: stop generating and unload the model.
    Critical,
    /// Under the reserve or under pressure: fewer sentences at once, and give back what can be.
    Low,
    /// Some room: as many sentences as fit.
    Normal,
}

impl Level {
    pub fn name(self) -> &'static str {
        match self {
            Level::Critical => "critical",
            Level::Low => "low",
            Level::Normal => "normal",
        }
    }
}

/// Thresholds, derived from the machine's size unless set on the command line.
#[derive(Clone, Copy, Debug)]
pub struct Policy {
    /// Slots grow only while this much stays available for everything else; under it they step down.
    pub reserve: u64,
    /// Below this much available, stop and unload: the machine has no memory left to give.
    pub critical: u64,
    /// Below this, do not wait for running sentences: end the model's process now.
    pub emergency: u64,
    /// Most sentences at once.
    pub max_slots: usize,
}

impl Policy {
    /// Reserve 12% of RAM (1-3 GiB); stop at 4% (384-768 MiB); emergency at half of that.
    pub fn for_machine(total: u64, max_slots: usize) -> Policy {
        let reserve = (total / 100 * 12).clamp(1024 * MIB, 3072 * MIB).min(total / 2);
        let critical = (total / 25).clamp(384 * MIB, 768 * MIB).min(reserve);
        Policy { reserve, critical, emergency: critical / 2, max_slots: max_slots.max(1) }
    }
}

/// What the governor decided.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Budget {
    pub level: Level,
    /// Sentences allowed to generate at once; 0 stops generation.
    pub slots: usize,
    /// The rule that set the level, for the log and `/v1/status`.
    pub reason: &'static str,
    /// Swap (zram) is nearly full; kept between readings so it is left only past a margin.
    pub swap_full: bool,
    /// Tasks are stalling on memory (PSI); kept so the stall must halve before it counts as over.
    pub stalled: bool,
    /// Memory is so short that running sentences are not waited for.
    pub emergency: bool,
}

impl Budget {
    /// The state before any reading: full speed, nothing remembered.
    pub fn start(policy: &Policy) -> Budget {
        Budget { level: Level::Normal, slots: policy.max_slots, reason: "start", swap_full: false, stalled: false, emergency: false }
    }
}

/// The memory one more sentence in flight costs, learned from the server's own peaks; starts at a
/// measured value for kokoro-en-v0_19 (200-250 MiB per concurrent sentence on x86-64).
pub const DEFAULT_SLOT_COST: u64 = 256 * MIB;

/// How far memory must clear a level's line before the server leaves that level, and how much the
/// stop line rises while swap is nearly full.
pub const HYSTERESIS: u64 = 384 * MIB;

/// The stop line now: higher while swap is nearly full, since the kernel then has nothing left to
/// push out of the way. Loading and recycling check against the same line.
pub fn effective_stop(p: &Policy, swap_full: bool) -> u64 {
    p.critical + if swap_full { HYSTERESIS } else { 0 }
}

/// Decides the budget. Pure, so the thresholds are unit-tested. [paid] is how many slots the
/// loaded model has already grown memory for (or was recycled to keep).
///
/// In order, as memory runs out:
/// - Normal: available above the reserve. Slots = paid + as many more as fit above it.
/// - Low: available under the reserve. One slot fewer than paid for each slot's worth of memory
///   under it (at least one). Also Low, with one slot fewer: Linux PSI `some` ≥ 20% or macOS
///   "warning"; with one slot: PSI `full` ≥ 10%.
/// - Critical: available under the stop line (raised while swap is nearly full), PSI `full` ≥ 10%
///   with available under the reserve, or macOS "critical". Stop.
/// - Emergency (Critical too): under the emergency line, or PSI `full` ≥ 40%. Kill the model now.
///
/// Swap in use does not slow anything down by itself: it only grows until a reboot, and Linux's
/// MemAvailable already says what is left. A level is left only once memory clears its line by
/// [HYSTERESIS], and PSI once it falls to half its threshold.
pub fn decide(s: &Snapshot, p: &Policy, previous: &Budget, paid: usize, slot_cost: u64) -> Budget {
    let machine = decide_machine(s, p, previous, paid, slot_cost);
    let Some(room) = s.limit_room else { return machine };
    if machine.slots == 0 {
        return machine;
    }
    // Under a limit: no more slots than fit in what is left of it; nearly full, back to one.
    if room < slot_cost / 4 {
        return Budget { level: machine.level.min(Level::Low), slots: 1, reason: "near the service's memory limit", ..machine };
    }
    let fit = (paid as u64 + room / slot_cost.max(1)).clamp(1, p.max_slots as u64) as usize;
    Budget { slots: machine.slots.min(fit), ..machine }
}

fn decide_machine(s: &Snapshot, p: &Policy, previous: &Budget, paid: usize, slot_cost: u64) -> Budget {
    let slot_cost = slot_cost.max(1);
    // Linux only (macOS reports no swap here): zram or a swap partition nearly full.
    let swap_full = s.swap_total > 0 && s.swap_free < s.swap_total / if previous.swap_full { 10 } else { 20 };
    let ease = if previous.stalled { 0.5 } else { 1.0 };
    let full_stall = s.stall_full >= 10.0 * ease;
    let some_stall = s.stall_some >= 20.0 * ease;
    let stalled = full_stall || some_stall;
    let margin = |level: Level| if previous.level <= level { HYSTERESIS } else { 0 };
    let stop = effective_stop(p, swap_full);
    let budget = |level: Level, slots: usize, reason: &'static str| Budget {
        level,
        slots: slots.min(p.max_slots),
        reason,
        swap_full,
        stalled,
        emergency: false,
    };
    if s.available < p.emergency || s.stall_full >= 40.0 {
        let reason = if s.available < p.emergency { "out of memory" } else { "the machine is thrashing (PSI full)" };
        return Budget { emergency: true, ..budget(Level::Critical, 0, reason) };
    }
    if s.available < stop + margin(Level::Critical) {
        let reason = if swap_full { "below the stop line, swap full" } else { "below the stop line" };
        return budget(Level::Critical, 0, reason);
    }
    if s.os_pressure >= 4 {
        return budget(Level::Critical, 0, "macOS memory pressure critical");
    }
    if full_stall && s.available < p.reserve {
        return budget(Level::Critical, 0, "memory stalls (PSI full) under the reserve");
    }
    if full_stall {
        return budget(Level::Low, 1, "memory stalls (PSI full)");
    }
    let paid = paid.max(1);
    if s.available < p.reserve + margin(Level::Low) {
        let under = p.reserve.saturating_sub(s.available);
        let steps = under.div_ceil(slot_cost) as usize;
        return budget(Level::Low, paid.saturating_sub(steps).max(1), "below the reserve");
    }
    if some_stall {
        return budget(Level::Low, paid.saturating_sub(1).max(1), "memory stalls (PSI some)");
    }
    if s.os_pressure >= 2 {
        return budget(Level::Low, paid.saturating_sub(1).max(1), "macOS memory pressure warning");
    }
    let spare = (s.available - p.reserve) / slot_cost;
    let slots = (paid as u64 + spare).clamp(1, p.max_slots as u64) as usize;
    budget(Level::Normal, slots, "above the reserve")
}

/// Whether [Snapshot::available] counts this process's own memory as available. True on macOS,
/// whose figure is the memory neither wired nor compressed, whoever holds it: loading or unloading
/// the model does not move it (measured). Linux's MemAvailable drops as the server grows.
pub const AVAILABLE_INCLUDES_OWN: bool = cfg!(target_os = "macos");

/// How long a client should wait before asking again.
pub fn retry_after(level: Level) -> Duration {
    match level {
        Level::Critical => Duration::from_secs(20),
        Level::Low => Duration::from_secs(10),
        Level::Normal => Duration::from_secs(5),
    }
}

// ---------------------------------------------------------------------------------------------
// Probes

#[cfg(target_os = "linux")]
pub fn snapshot() -> Snapshot {
    let mut s = Snapshot::default();
    if let Ok(text) = std::fs::read_to_string("/proc/meminfo") {
        for line in text.lines() {
            let mut parts = line.split_whitespace();
            let (Some(key), Some(value)) = (parts.next(), parts.next()) else { continue };
            let bytes = value.parse::<u64>().unwrap_or(0) * 1024;
            match key {
                "MemTotal:" => s.total = bytes,
                "MemAvailable:" => s.available = bytes,
                "SwapTotal:" => s.swap_total = bytes,
                "SwapFree:" => s.swap_free = bytes,
                _ => {}
            }
        }
    }
    if let Ok(text) = std::fs::read_to_string("/proc/pressure/memory") {
        for line in text.lines() {
            let avg10 = line
                .split_whitespace()
                .find_map(|field| field.strip_prefix("avg10="))
                .and_then(|value| value.parse::<f32>().ok())
                .unwrap_or(0.0);
            if line.starts_with("some") {
                s.stall_some = avg10;
            } else if line.starts_with("full") {
                s.stall_full = avg10;
            }
        }
    }
    s.limit_room = cgroup_room();
    s.own = std::fs::read_to_string("/proc/self/status")
        .ok()
        .and_then(|text| {
            text.lines()
                .find(|line| line.starts_with("VmRSS:"))
                .and_then(|line| line.split_whitespace().nth(1))
                .and_then(|kb| kb.parse::<u64>().ok())
        })
        .map(|kb| kb * 1024)
        .unwrap_or(0);
    s
}

/// The tightest room left under a cgroup v2 memory limit (memory.high or memory.max) on this
/// process's cgroup or any ancestor; None when nothing limits it. Page cache the kernel can drop
/// (inactive files, half the active ones) does not count as used.
#[cfg(target_os = "linux")]
fn cgroup_room() -> Option<u64> {
    let own = std::fs::read_to_string("/proc/self/cgroup").ok()?;
    let relative = own.lines().find_map(|line| line.strip_prefix("0::"))?.trim().to_string();
    let mut dir = std::path::PathBuf::from("/sys/fs/cgroup").join(relative.trim_start_matches('/'));
    let mut room: Option<u64> = None;
    loop {
        let read = |name: &str| std::fs::read_to_string(dir.join(name)).ok().and_then(|v| v.trim().parse::<u64>().ok());
        if let Some(charged) = read("memory.current") {
            let stat = std::fs::read_to_string(dir.join("memory.stat")).unwrap_or_default();
            let field = |name: &str| {
                stat.lines().find_map(|line| line.strip_prefix(name).and_then(|rest| rest.trim().parse::<u64>().ok())).unwrap_or(0)
            };
            let current = charged.saturating_sub(field("inactive_file ") + field("active_file ") / 2);
            for limit in [read("memory.high"), read("memory.max")].into_iter().flatten() {
                let left = limit.saturating_sub(current);
                room = Some(room.map_or(left, |r| r.min(left)));
            }
        }
        if dir.as_os_str() == "/sys/fs/cgroup" || !dir.pop() {
            break;
        }
    }
    room
}

/// Another process's memory in RAM (anonymous and shared) and in swap, in bytes (the server's workers).
/// None once the process is gone.
#[cfg(target_os = "linux")]
pub fn process_memory(pid: u32) -> Option<(u64, u64)> {
    let text = std::fs::read_to_string(format!("/proc/{pid}/status")).ok()?;
    let field = |name: &str| {
        text.lines()
            .find(|line| line.starts_with(name))
            .and_then(|line| line.split_whitespace().nth(1))
            .and_then(|kb| kb.parse::<u64>().ok())
            .map(|kb| kb * 1024)
    };
    // Anonymous and shared memory: what ending it gives back. Its file-backed pages (libraries,
    // mapped weights) already count as available, as the kernel can drop them any time. A process
    // that has exited but not been reaped has none of these lines.
    let resident = field("RssAnon:")? + field("RssShmem:").unwrap_or(0);
    Some((resident, field("VmSwap:").unwrap_or(0)))
}

/// macOS: the process's footprint, which already counts its compressed and swapped memory.
#[cfg(target_os = "macos")]
pub fn process_memory(pid: u32) -> Option<(u64, u64)> {
    mac::footprint(pid as i32).map(|footprint| (footprint, 0))
}

#[cfg(not(any(target_os = "linux", target_os = "macos")))]
pub fn process_memory(_pid: u32) -> Option<(u64, u64)> {
    None
}

#[cfg(target_os = "macos")]
pub fn snapshot() -> Snapshot {
    mac::snapshot()
}

#[cfg(not(any(target_os = "linux", target_os = "macos")))]
pub fn snapshot() -> Snapshot {
    // Unknown system: report plenty, so the server runs at its configured size.
    Snapshot { total: 16 * 1024 * MIB, available: 16 * 1024 * MIB, ..Snapshot::default() }
}

#[cfg(target_os = "macos")]
mod mac {
    use super::Snapshot;
    use std::ffi::{c_char, c_int, c_void};

    #[repr(C)]
    #[derive(Default)]
    struct VmStatistics64 {
        free_count: u32,
        active_count: u32,
        inactive_count: u32,
        wire_count: u32,
        zero_fill_count: u64,
        reactivations: u64,
        pageins: u64,
        pageouts: u64,
        faults: u64,
        cow_faults: u64,
        lookups: u64,
        hits: u64,
        purges: u64,
        purgeable_count: u32,
        speculative_count: u32,
        decompressions: u64,
        compressions: u64,
        swapins: u64,
        swapouts: u64,
        compressor_page_count: u32,
        throttled_count: u32,
        external_page_count: u32,
        internal_page_count: u32,
        total_uncompressed_pages_in_compressor: u64,
    }

    #[repr(C)]
    #[derive(Default)]
    struct RusageInfoV0 {
        uuid: [u8; 16],
        user_time: u64,
        system_time: u64,
        pkg_idle_wkups: u64,
        interrupt_wkups: u64,
        pageins: u64,
        wired_size: u64,
        resident_size: u64,
        phys_footprint: u64,
        proc_start_abstime: u64,
        proc_exit_abstime: u64,
    }

    const HOST_VM_INFO64: c_int = 4;

    extern "C" {
        fn sysctlbyname(name: *const c_char, old: *mut c_void, oldlen: *mut usize, new: *mut c_void, newlen: usize) -> c_int;
        fn mach_host_self() -> u32;
        fn host_statistics64(host: u32, flavor: c_int, info: *mut c_int, count: *mut u32) -> c_int;
        fn proc_pid_rusage(pid: c_int, flavor: c_int, buffer: *mut c_void) -> c_int;
        fn getpid() -> c_int;
    }

    /// mach_host_self() takes a port reference on every call; take one for the process.
    fn host_port() -> u32 {
        static PORT: std::sync::OnceLock<u32> = std::sync::OnceLock::new();
        // SAFETY: plain Mach call.
        *PORT.get_or_init(|| unsafe { mach_host_self() })
    }

    fn sysctl<T: Default>(name: &str) -> Option<T> {
        let name = std::ffi::CString::new(name).ok()?;
        let mut value = T::default();
        let mut size = std::mem::size_of::<T>();
        // SAFETY: value is a writable buffer of `size` bytes.
        let status = unsafe { sysctlbyname(name.as_ptr(), &mut value as *mut T as *mut c_void, &mut size, std::ptr::null_mut(), 0) };
        (status == 0).then_some(value)
    }

    pub fn snapshot() -> Snapshot {
        let mut s = Snapshot { total: sysctl::<u64>("hw.memsize").unwrap_or(0), ..Snapshot::default() };
        // The kernel's own estimate (memorystatus: the share of memory it can hand out, in percent),
        // the same figure its pressure levels come from. Older systems: free + inactive + purgeable
        // pages (free already includes speculative ones).
        if let Some(percent) = sysctl::<u32>("kern.memorystatus_level").filter(|p| (1..=100).contains(p)) {
            s.available = s.total / 100 * u64::from(percent);
        } else {
            let page = sysctl::<u64>("hw.pagesize").or_else(|| sysctl::<u32>("hw.pagesize").map(u64::from)).unwrap_or(16384);
            let mut vm = VmStatistics64::default();
            let mut count = (std::mem::size_of::<VmStatistics64>() / std::mem::size_of::<c_int>()) as u32;
            // SAFETY: vm is a writable vm_statistics64 and count is its size in integers.
            if unsafe { host_statistics64(host_port(), HOST_VM_INFO64, &mut vm as *mut _ as *mut c_int, &mut count) } == 0 {
                let pages = vm.free_count as u64 + vm.inactive_count as u64 + vm.purgeable_count as u64;
                s.available = (pages * page).min(s.total);
            }
        }
        // macOS swap grows on demand (its swapfiles are often nearly full while all is well), so it
        // says nothing about running out; the pressure level does. swap_total stays 0 here.
        s.os_pressure = sysctl::<u32>("kern.memorystatus_vm_pressure_level").unwrap_or(0);
        // SAFETY: plain libc call.
        s.own = footprint(unsafe { getpid() }).unwrap_or(0);
        s
    }

    /// A process's physical footprint (what Activity Monitor shows as its memory).
    pub fn footprint(pid: c_int) -> Option<u64> {
        let mut usage = RusageInfoV0::default();
        // SAFETY: usage is a writable rusage_info_v0 (flavor 0).
        (unsafe { proc_pid_rusage(pid, 0, &mut usage as *mut _ as *mut c_void) } == 0).then_some(usage.phys_footprint)
    }
}

/// Hands freed heap pages back to the system (glibc keeps them otherwise; measured 1.3 GiB kept
/// after unloading four model copies). On macOS this returns the small blocks; the large ones go
/// back as they are freed, see [tune_allocator].
pub fn release_free_heap() {
    #[cfg(all(target_os = "linux", target_env = "gnu"))]
    {
        extern "C" {
            fn malloc_trim(pad: usize) -> i32;
        }
        // SAFETY: plain libc call.
        unsafe { malloc_trim(0) };
    }
    #[cfg(target_os = "macos")]
    {
        extern "C" {
            fn malloc_zone_pressure_relief(zone: *mut std::ffi::c_void, goal: usize) -> usize;
        }
        // SAFETY: plain libc call; a null zone means every zone, goal 0 means as much as possible.
        unsafe { malloc_zone_pressure_relief(std::ptr::null_mut(), 0) };
    }
}

/// Allocator tuning, applied once at start, before any other thread exists.
///
/// glibc: few malloc arenas, so memory freed by one thread is reused by the next instead of
/// stranded in a per-thread arena.
///
/// macOS: no cache of freed large blocks. With the cache, an unloaded model's memory stays
/// charged to the process and nothing gives it back on request (measured on an M1 Pro, macOS 26:
/// 1560 MB held after unloading; without the cache 16 MB, 250 MB less while generating, and the
/// same speed). The allocator reads `MallocLargeCache` only as the process starts, so the server
/// starts itself again with it set.
pub fn tune_allocator() {
    #[cfg(all(target_os = "linux", target_env = "gnu"))]
    {
        extern "C" {
            fn mallopt(param: i32, value: i32) -> i32;
        }
        const M_ARENA_MAX: i32 = -8;
        // SAFETY: plain libc call, before any worker thread starts.
        unsafe { mallopt(M_ARENA_MAX, 2) };
    }
    #[cfg(target_os = "macos")]
    {
        use std::os::unix::process::CommandExt;
        const LARGE_CACHE: &str = "MallocLargeCache";
        if std::env::var_os(LARGE_CACHE).is_none() {
            if let Ok(exe) = std::env::current_exe() {
                // Replaces this process (same pid, so launchd keeps tracking it). It returns only
                // when it fails; then carry on with the cache.
                let error = std::process::Command::new(exe).args(std::env::args_os().skip(1)).env(LARGE_CACHE, "0").exec();
                eprintln!("Could not restart with {LARGE_CACHE}=0 ({error}); freed memory will be returned late.");
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const GIB: u64 = 1024 * MIB;

    fn machine(available: u64) -> Snapshot {
        Snapshot { total: 16 * GIB, available, swap_total: 8 * GIB, swap_free: 8 * GIB, ..Snapshot::default() }
    }

    fn policy() -> Policy {
        Policy::for_machine(16 * GIB, 4)
    }

    fn after(level: Level) -> Budget {
        Budget { level, ..Budget::start(&policy()) }
    }

    fn normal() -> Budget {
        after(Level::Normal)
    }

    #[test]
    fn policy_scales_with_the_machine() {
        let big = Policy::for_machine(16 * GIB, 4);
        assert!(big.reserve > 1800 * MIB && big.reserve < 2 * GIB, "{}", big.reserve / MIB);
        assert!(big.critical > 600 * MIB && big.critical < 700 * MIB, "{}", big.critical / MIB);
        assert_eq!(big.emergency, big.critical / 2);
        let small = Policy::for_machine(4 * GIB, 4);
        assert_eq!(small.reserve, GIB);
        assert_eq!(small.critical, 384 * MIB);
        let huge = Policy::for_machine(64 * GIB, 4);
        assert_eq!((huge.reserve, huge.critical), (3 * GIB, 768 * MIB));
    }

    #[test]
    fn plenty_of_memory_runs_every_slot() {
        let b = decide(&machine(10 * GIB), &policy(), &normal(), 0, DEFAULT_SLOT_COST);
        assert_eq!((b.level, b.slots), (Level::Normal, 4));
    }

    #[test]
    fn slots_grow_only_with_room_above_the_reserve() {
        let p = policy();
        let s = machine(p.reserve + DEFAULT_SLOT_COST + MIB);
        assert_eq!(decide(&s, &p, &normal(), 2, DEFAULT_SLOT_COST).slots, 3);
        assert_eq!(decide(&s, &p, &normal(), 0, DEFAULT_SLOT_COST).slots, 2);
    }

    #[test]
    fn under_the_reserve_slots_step_down_one_at_a_time() {
        let p = policy();
        let step = |available: u64| decide(&machine(available), &p, &normal(), 4, DEFAULT_SLOT_COST);
        assert_eq!((step(p.reserve - MIB).level, step(p.reserve - MIB).slots), (Level::Low, 3));
        assert_eq!(step(p.reserve - DEFAULT_SLOT_COST - MIB).slots, 2);
        assert_eq!(step(p.reserve - 2 * DEFAULT_SLOT_COST - MIB).slots, 1);
        // Still one slot, not a stop, all the way down to the stop line.
        let near_stop = step(p.critical + HYSTERESIS + MIB);
        assert_eq!((near_stop.level, near_stop.slots), (Level::Low, 1));
        // A model that has not grown yet runs one.
        assert_eq!(decide(&machine(p.reserve - MIB), &p, &normal(), 0, DEFAULT_SLOT_COST).slots, 1);
    }

    #[test]
    fn swap_in_use_does_not_slow_anything_down() {
        let p = policy();
        let mut s = machine(3 * GIB);
        s.swap_free = s.swap_total / 5;
        let b = decide(&s, &p, &normal(), 4, DEFAULT_SLOT_COST);
        assert_eq!((b.level, b.slots), (Level::Normal, 4));
        // The journal's typical stop before this change: 3 GB available, zram nearly full.
        s.swap_free = s.swap_total / 50;
        let b = decide(&s, &p, &normal(), 4, DEFAULT_SLOT_COST);
        assert_eq!((b.level, b.slots), (Level::Normal, 4));
        assert!(b.swap_full);
    }

    #[test]
    fn full_swap_raises_the_stop_line_with_a_margin_to_leave() {
        let p = policy();
        let mut s = machine(p.critical + 200 * MIB);
        assert_eq!(decide(&s, &p, &after(Level::Low), 1, DEFAULT_SLOT_COST).level, Level::Low);
        s.swap_free = s.swap_total / 50;
        let b = decide(&s, &p, &after(Level::Low), 1, DEFAULT_SLOT_COST);
        assert_eq!((b.level, b.reason), (Level::Critical, "below the stop line, swap full"));
        // Between 5% and 10% free, full swap is left only if it was not full before.
        s.swap_free = s.swap_total / 15;
        assert!(decide(&s, &p, &b, 0, DEFAULT_SLOT_COST).swap_full);
        assert!(!decide(&s, &p, &normal(), 0, DEFAULT_SLOT_COST).swap_full);
        assert_eq!(effective_stop(&p, true), p.critical + HYSTERESIS);
    }

    #[test]
    fn levels_are_left_only_past_a_margin() {
        let p = policy();
        let just_above = machine(p.reserve + HYSTERESIS / 2);
        assert_eq!(decide(&just_above, &p, &normal(), 4, DEFAULT_SLOT_COST).level, Level::Normal);
        let staying = decide(&just_above, &p, &after(Level::Low), 3, DEFAULT_SLOT_COST);
        assert_eq!((staying.level, staying.slots), (Level::Low, 3));
        assert_eq!(decide(&machine(p.reserve + HYSTERESIS + MIB), &p, &after(Level::Low), 1, DEFAULT_SLOT_COST).level, Level::Normal);
        let above_critical = machine(p.critical + HYSTERESIS / 2);
        assert_eq!(decide(&above_critical, &p, &after(Level::Critical), 0, DEFAULT_SLOT_COST).slots, 0);
        assert_eq!(decide(&above_critical, &p, &after(Level::Low), 1, DEFAULT_SLOT_COST).level, Level::Low);
    }

    #[test]
    fn stops_only_when_memory_is_gone_and_kills_at_the_emergency_line() {
        let p = policy();
        let stop = decide(&machine(p.critical - MIB), &p, &after(Level::Low), 1, DEFAULT_SLOT_COST);
        assert_eq!((stop.level, stop.slots, stop.emergency), (Level::Critical, 0, false));
        let gone = decide(&machine(p.emergency - MIB), &p, &after(Level::Low), 1, DEFAULT_SLOT_COST);
        assert!(gone.emergency);
        let mut thrash = machine(8 * GIB);
        thrash.stall_full = 45.0;
        assert!(decide(&thrash, &p, &normal(), 4, DEFAULT_SLOT_COST).emergency);
    }

    #[test]
    fn stalls_slow_down_before_they_stop() {
        let p = policy();
        let mut s = machine(8 * GIB);
        s.stall_some = 25.0;
        let b = decide(&s, &p, &normal(), 4, DEFAULT_SLOT_COST);
        assert_eq!((b.level, b.slots), (Level::Low, 3));
        // Leaving needs the stall to halve.
        s.stall_some = 15.0;
        assert_eq!(decide(&s, &p, &b, 4, DEFAULT_SLOT_COST).level, Level::Low);
        s.stall_some = 5.0;
        assert_eq!(decide(&s, &p, &b, 4, DEFAULT_SLOT_COST).level, Level::Normal);
        // Under the reserve without a stall before, the full thresholds hold (not halved).
        let mut tight = machine(p.reserve - MIB);
        tight.stall_full = 6.0;
        assert_eq!(decide(&tight, &p, &after(Level::Low), 4, DEFAULT_SLOT_COST).level, Level::Low);
        s.stall_full = 12.0;
        let b = decide(&s, &p, &normal(), 4, DEFAULT_SLOT_COST);
        assert_eq!((b.level, b.slots), (Level::Low, 1));
        s.available = p.reserve - MIB;
        assert_eq!(decide(&s, &p, &normal(), 4, DEFAULT_SLOT_COST).level, Level::Critical);
    }

    #[test]
    fn a_cgroup_limit_caps_slots_without_stopping() {
        let p = policy();
        let mut s = machine(10 * GIB);
        s.limit_room = Some(DEFAULT_SLOT_COST + MIB);
        let b = decide(&s, &p, &normal(), 1, DEFAULT_SLOT_COST);
        assert_eq!((b.level, b.slots), (Level::Normal, 2));
        s.limit_room = Some(10 * MIB);
        let b = decide(&s, &p, &normal(), 4, DEFAULT_SLOT_COST);
        assert_eq!((b.level, b.slots), (Level::Low, 1));
        // The machine itself running out still wins.
        s.available = p.critical - MIB;
        assert_eq!(decide(&s, &p, &normal(), 1, DEFAULT_SLOT_COST).slots, 0);
    }

    #[test]
    fn macos_pressure_levels() {
        let p = policy();
        let mut s = machine(10 * GIB);
        s.swap_total = 0;
        s.os_pressure = 2;
        let b = decide(&s, &p, &normal(), 4, DEFAULT_SLOT_COST);
        assert_eq!((b.level, b.slots), (Level::Low, 3));
        s.os_pressure = 4;
        assert_eq!(decide(&s, &p, &normal(), 4, DEFAULT_SLOT_COST).level, Level::Critical);
    }

    #[test]
    fn this_machine_reports_memory() {
        let s = snapshot();
        assert!(s.total > 0 && s.available > 0 && s.available <= s.total.max(s.available));
        assert!(s.own > 0);
        let (resident, _) = process_memory(std::process::id()).unwrap();
        assert!(resident > 0);
    }
}

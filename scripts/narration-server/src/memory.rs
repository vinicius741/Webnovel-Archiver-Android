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
    /// The machine is about to run out: stop generating and unload the model.
    Critical,
    /// Below the reserve: one sentence at a time, and give back what the model is not using.
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
    /// Keep at least this much available for everything else on the machine.
    pub reserve: u64,
    /// Below this much available, stop and unload.
    pub critical: u64,
    /// Most sentences at once.
    pub max_slots: usize,
}

impl Policy {
    /// Reserve 20% of RAM (at least 1.5 GiB, at most 4 GiB); critical at a third of that (at least 600 MiB).
    pub fn for_machine(total: u64, max_slots: usize) -> Policy {
        let reserve = (total / 5).clamp(1536 * MIB, 4096 * MIB).min(total / 2);
        let critical = (reserve / 3).max(600 * MIB).min(reserve);
        Policy { reserve, critical, max_slots: max_slots.max(1) }
    }
}

/// What the governor decided.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Budget {
    pub level: Level,
    /// Sentences allowed to generate at once; 0 stops generation.
    pub slots: usize,
}

/// The memory one more sentence in flight costs, learned from the server's own peaks; starts at a
/// measured value for kokoro-en-v0_19 (200-250 MiB per concurrent sentence on x86-64).
pub const DEFAULT_SLOT_COST: u64 = 256 * MIB;

/// How far memory must clear a level's line before the server leaves that level.
pub const HYSTERESIS: u64 = 384 * MIB;

/// Decides the budget. Pure, so the thresholds are unit-tested.
///
/// - Critical: available is under `critical`, tasks stalled on memory 10% of the last 10 s (PSI
///   full), swap is nearly exhausted while memory is low, or macOS says critical.
/// - Low: available is under `reserve`, some task stalled 20% of the time, swap is three-quarters
///   used, or macOS warns. One slot.
/// - Normal: slots = the ones already paid for (`grown`) plus as many more as fit above the
///   reserve, at `slot_cost` each.
///
/// A level is left only when memory clears its line by [HYSTERESIS], so the server does not flap
/// between levels while the kernel holds memory right at a threshold (zram does).
pub fn decide(s: &Snapshot, p: &Policy, previous: Level, grown: usize, slot_cost: u64) -> Budget {
    let machine = decide_machine(s, p, previous, grown, slot_cost);
    let Some(room) = s.limit_room else { return machine };
    if machine.slots == 0 {
        return machine;
    }
    // Under a limit: no more slots than fit in what is left of it; nearly full, back to one.
    if room < slot_cost / 4 {
        return Budget { level: machine.level.min(Level::Low), slots: 1 };
    }
    let fit = (grown as u64 + room / slot_cost.max(1)).clamp(1, p.max_slots as u64) as usize;
    Budget { level: machine.level, slots: machine.slots.min(fit) }
}

fn decide_machine(s: &Snapshot, p: &Policy, previous: Level, grown: usize, slot_cost: u64) -> Budget {
    // Linux only (macOS reports no swap here): zram or a swap partition nearly full.
    let swap_exhausted = s.swap_total > 0 && s.swap_free < s.swap_total / 20;
    let swap_low = s.swap_total > 0 && s.swap_free < s.swap_total / 4;
    let margin = |level: Level| if previous <= level { HYSTERESIS } else { 0 };
    if s.available < p.critical + margin(Level::Critical)
        || s.stall_full >= 10.0
        || s.os_pressure >= 4
        || (swap_exhausted && s.available < p.reserve)
    {
        return Budget { level: Level::Critical, slots: 0 };
    }
    if s.available < p.reserve + margin(Level::Low) || s.stall_some >= 20.0 || swap_low || s.os_pressure >= 2 {
        return Budget { level: Level::Low, slots: 1 };
    }
    let spare = s.available.saturating_sub(p.reserve) / slot_cost.max(1);
    let slots = (grown as u64 + spare).clamp(1, p.max_slots as u64) as usize;
    Budget { level: Level::Normal, slots }
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
        let mut usage = RusageInfoV0::default();
        // SAFETY: usage is a writable rusage_info_v0 (flavor 0).
        if unsafe { proc_pid_rusage(getpid(), 0, &mut usage as *mut _ as *mut c_void) } == 0 {
            s.own = usage.phys_footprint;
        }
        s
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

    #[test]
    fn policy_scales_with_the_machine() {
        let big = Policy::for_machine(16 * GIB, 4);
        assert!(big.reserve > 3 * GIB && big.reserve < 4 * GIB);
        assert!(big.critical >= GIB);
        let small = Policy::for_machine(4 * GIB, 4);
        assert_eq!(small.reserve, 1536 * MIB);
        assert_eq!(small.critical, 600 * MIB);
    }

    #[test]
    fn plenty_of_memory_runs_every_slot() {
        assert_eq!(decide(&machine(10 * GIB), &policy(), Level::Normal, 0, DEFAULT_SLOT_COST), Budget { level: Level::Normal, slots: 4 });
    }

    #[test]
    fn slots_shrink_toward_the_reserve() {
        let p = policy();
        // Room for one more slot above the reserve, and two already paid for.
        let s = machine(p.reserve + DEFAULT_SLOT_COST + MIB);
        assert_eq!(decide(&s, &p, Level::Normal, 2, DEFAULT_SLOT_COST).slots, 3);
        assert_eq!(decide(&s, &p, Level::Normal, 0, DEFAULT_SLOT_COST).slots, 1);
    }

    #[test]
    fn below_the_reserve_runs_one_slot() {
        let p = policy();
        assert_eq!(decide(&machine(p.reserve - MIB), &p, Level::Normal, 4, DEFAULT_SLOT_COST), Budget { level: Level::Low, slots: 1 });
    }

    #[test]
    fn levels_are_left_only_past_a_margin() {
        let p = policy();
        let just_above = machine(p.reserve + HYSTERESIS / 2);
        assert_eq!(decide(&just_above, &p, Level::Normal, 4, DEFAULT_SLOT_COST).level, Level::Normal);
        assert_eq!(decide(&just_above, &p, Level::Low, 1, DEFAULT_SLOT_COST).level, Level::Low);
        assert_eq!(decide(&machine(p.reserve + HYSTERESIS + MIB), &p, Level::Low, 1, DEFAULT_SLOT_COST).level, Level::Normal);
        let above_critical = machine(p.critical + HYSTERESIS / 2);
        assert_eq!(decide(&above_critical, &p, Level::Critical, 0, DEFAULT_SLOT_COST).slots, 0);
        assert_eq!(decide(&above_critical, &p, Level::Low, 1, DEFAULT_SLOT_COST).level, Level::Low);
    }

    #[test]
    fn near_exhaustion_stops() {
        let p = policy();
        assert_eq!(decide(&machine(p.critical - MIB), &p, Level::Normal, 4, DEFAULT_SLOT_COST).level, Level::Critical);
        let mut stalled = machine(10 * GIB);
        stalled.stall_full = 12.0;
        assert_eq!(decide(&stalled, &p, Level::Normal, 1, DEFAULT_SLOT_COST).slots, 0);
        let mut swapless = machine(p.reserve - MIB);
        swapless.swap_free = 100 * MIB;
        assert_eq!(decide(&swapless, &p, Level::Normal, 1, DEFAULT_SLOT_COST).level, Level::Critical);
    }

    #[test]
    fn a_cgroup_limit_caps_slots_without_stopping() {
        let p = policy();
        let mut s = machine(10 * GIB);
        s.limit_room = Some(DEFAULT_SLOT_COST + MIB);
        assert_eq!(decide(&s, &p, Level::Normal, 1, DEFAULT_SLOT_COST), Budget { level: Level::Normal, slots: 2 });
        s.limit_room = Some(10 * MIB);
        assert_eq!(decide(&s, &p, Level::Normal, 4, DEFAULT_SLOT_COST), Budget { level: Level::Low, slots: 1 });
        // The machine itself running out still wins.
        s.available = p.critical - MIB;
        assert_eq!(decide(&s, &p, Level::Normal, 1, DEFAULT_SLOT_COST).slots, 0);
    }

    #[test]
    fn swap_filling_up_slows_down() {
        let p = policy();
        let mut s = machine(10 * GIB);
        s.swap_free = s.swap_total / 5;
        assert_eq!(decide(&s, &p, Level::Normal, 4, DEFAULT_SLOT_COST), Budget { level: Level::Low, slots: 1 });
        s.stall_some = 25.0;
        s.swap_free = s.swap_total;
        assert_eq!(decide(&s, &p, Level::Normal, 4, DEFAULT_SLOT_COST).level, Level::Low);
    }

    #[test]
    fn macos_pressure_levels() {
        let p = policy();
        let mut s = machine(10 * GIB);
        s.os_pressure = 2;
        assert_eq!(decide(&s, &p, Level::Normal, 4, DEFAULT_SLOT_COST).level, Level::Low);
        s.os_pressure = 4;
        assert_eq!(decide(&s, &p, Level::Normal, 4, DEFAULT_SLOT_COST).level, Level::Critical);
    }

    #[test]
    fn this_machine_reports_memory() {
        let s = snapshot();
        assert!(s.total > 0 && s.available > 0 && s.available <= s.total.max(s.available));
        assert!(s.own > 0);
    }
}

#!/usr/bin/env bash
# Cold-start comparison of the profiling build with and without its Baseline Profile compiled.
# See docs/architecture/baseline-profile.md.
#
# Usage: scripts/perf/baseline_profile_startup.sh [runs]   (default 10 runs per state)
#   Build the APK first: android/gradlew -p android :app:assembleBenchmarkRelease
#   APK=<path> overrides the APK (default: the benchmarkRelease output, minified like the release).
#
# Installs the `.profiling` app (its own library; never the debug app), seeds its fixture library,
# then measures `am start -W` TotalTime for:
#   none     `cmd package compile --reset` before every run: a fresh sideload before any dexopt.
#   profile  ProfileInstaller writes the shipped profile, then `compile -f -m speed-profile`: the
#            state after the first background dexopt.
# Leaves the profiling app installed; remove it with `adb -s <serial> uninstall <package>`.
set -euo pipefail

cd "$(dirname "$0")/../.."

RUNS="${1:-10}"
PKG="com.vinicius741.webnovelarchiver.nativeapp.profiling"
ACTIVITY="com.vinicius741.webnovelarchiver.app.MainActivity"
APK="${APK:-android/app/build/outputs/apk/benchmarkRelease/app-benchmarkRelease.apk}"
RECEIVER="com.vinicius741.webnovelarchiver.profiling.FixtureLibraryReceiver"
ADB="${ADB:-$(command -v adb || echo adb)}"

serials=()
while read -r serial state _rest; do
  if [[ "$serial" == emulator-* && "$state" == "device" ]]; then serials+=("$serial"); fi
done < <(timeout 20 "$ADB" devices -l | tail -n +2)
if [[ ${#serials[@]} -ne 1 ]]; then
  echo "Need exactly one running emulator (found ${#serials[@]})." >&2
  exit 1
fi
adb_e() { timeout 120 "$ADB" -s "${serials[0]}" "$@"; }

[[ -f "$APK" ]] || { echo "APK not found: $APK" >&2; exit 1; }
adb_e install -r "$APK" >/dev/null
echo "Installed $APK on ${serials[0]}"
# `pm art dump <package>` (Android 14+) shows only this app; `dumpsys package dexopt` lists every app.
compiler_filter() { adb_e shell pm art dump "$PKG" 2>/dev/null | grep -m1 -o '\[status=[^]]*\]' || echo unknown; }
echo "Compiler filter right after install: $(compiler_filter)"
adb_e shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS || true
adb_e shell am broadcast -f 0x20 -n "$PKG/$RECEIVER" | grep -q 'result=-1' || { echo "Fixture seeding failed" >&2; exit 1; }

cold_start() {
  adb_e shell am force-stop "$PKG"
  sleep 1
  adb_e shell am start -W -n "$PKG/$ACTIVITY" | awk -F': ' '/TotalTime/ {print $2}' | tr -d '\r'
  sleep 3
  adb_e shell am force-stop "$PKG"
}

summary() {
  sort -n | awk -v label="$1" '{a[NR]=$1; s+=$1} END {
    m = (NR % 2) ? a[(NR+1)/2] : (a[NR/2] + a[NR/2+1]) / 2
    printf "%-8s runs=%d median=%.0f ms mean=%.0f ms min=%d ms max=%d ms\n", label, NR, m, s/NR, a[1], a[NR]
  }'
}

# A warm-up start so both states begin with the same disk cache and seeded library loaded once.
cold_start >/dev/null

none=()
for _ in $(seq "$RUNS"); do
  adb_e shell cmd package compile --reset "$PKG" >/dev/null
  none+=("$(cold_start)")
done
echo "none:    ${none[*]}"

adb_e shell cmd package compile --reset "$PKG" >/dev/null
# ProfileInstaller result 1 = RESULT_INSTALL_SUCCESS (written to ART's reference profile).
adb_e shell am broadcast -f 0x20 -a androidx.profileinstaller.action.INSTALL_PROFILE "$PKG/androidx.profileinstaller.ProfileInstallReceiver" | grep -o 'result=[0-9-]*'
adb_e shell cmd package compile -f -m speed-profile "$PKG" >/dev/null
echo "Compiler filter with profile: $(compiler_filter)"
profile=()
for _ in $(seq "$RUNS"); do profile+=("$(cold_start)"); done
echo "profile: ${profile[*]}"

printf '%s\n' "${none[@]}" | summary none
printf '%s\n' "${profile[@]}" | summary profile

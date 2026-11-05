---
name: emulator-recovery
description: >
  Diagnose and fix the webnovel_api36 Android emulator when it will not start or dies during
  boot: a device panel or `emulator` command reporting "failed to boot", "could not start",
  "killed by SIGSEGV", adb "error: closed" while waiting for the device, a qemu core dump, or
  no emulator-* serial in `adb devices`. USE THIS whenever the emulator is needed (QA,
  screenshots, installs, redeploy) and it is not running or crashes, before falling back to
  another device. The known fix on this Linux host is booting with the host GPU through
  scripts/emulator/start_emulator.sh. Do NOT use for app crashes inside a healthy emulator
  (use logcat) or for the owner's physical phone.
---

# Emulator recovery (webnovel_api36)

Goal: get `emulator-5554` booted with the user's debug library intact, then continue the
original task. Never fix it by wiping data.

## Fast path (try first)

```bash
scripts/emulator/start_emulator.sh
```

It starts `webnovel_api36` detached as the systemd user unit `webnovel-emulator-5554`, headless,
with `-gpu host` and `-dns-server 8.8.8.8,1.1.1.1`, and waits for `sys.boot_completed`. It is a
no-op when the emulator is already up. Log: `~/.local/state/webnovel-emulator/emulator.log`.

If it prints `✓ emulator-5554 booted`, you are done. A device panel (T3 `device_open`) then
attaches to the running `emulator-5554`: call `device_list` and open the `emulator-5554` id, not
the stopped `webnovel_api36` AVD entry.

Why the panel cannot boot it itself: it launches with `-gpu auto`, which segfaults here (see
below), and it has no setting for emulator flags. Always start the emulator with the script
first.

## Known failure: software renderer segfault (fixed 2026-11-05)

Symptoms:
- The device panel's boot fails with "Failed while waiting for webnovel_api36 to come online"
  plus `adb ... getprop` → `error: closed`, or "was killed by SIGSEGV before coming online".
- A manual `emulator -avd webnovel_api36 ...` exits with code 139 right after the log line
  `emulatorSetupEnvironment: Environment scene is not required`.

Cause: on this host (Bazzite / Fedora 44, kernel 7.x, Android Emulator 36.6.11), the bundled
software GL renderers crash during boot. `-gpu auto` selects lavapipe/SwANGLE and
`-gpu swiftshader_indirect` selects SwiftShader. Both jump to a bad pointer from the
emulator's `lib64/gles_swiftshader/libGLESv2.so`. The snapshot, the display variables, KVM and
the AVD data are not the cause. Cold boots and `-no-snapshot-load` crash the same way.

Fix: boot with the host GPU (`-gpu host`). The script does this, and
`scripts/emulator/fold7-config.ini` sets `hw.gpu.enabled=yes` / `hw.gpu.mode=host` in the AVD.

Confirm it is this failure before applying anything else:

```bash
coredumpctl list --no-pager | grep qemu-system | tail -3
coredumpctl debug <PID> --debugger-arguments="-batch -ex 'x/2gx \$rsp' -ex 'info proc mappings'" --no-pager \
  | grep -E "libGLESv2|SwiftShader|lvp_icd"
```

A return address inside `.../emulator/lib64/gles_swiftshader/libGLESv2.so` (or SwiftShader /
lavapipe mappings) means this is the renderer crash. Use `-gpu host`.

## If the fast path still fails

Work through these in order. Read the log after each attempt.

1. **Already running or half-started?** `pgrep -af -- "-avd webnovel_api36"`,
   `systemctl --user status webnovel-emulator-5554`, `adb devices -l`. Stop a wedged one with
   `adb -s emulator-5554 emu kill`, or `systemctl --user stop webnovel-emulator-5554`.
2. **Profile drifted?** `grep -n "hw.gpu" "$ANDROID_AVD_HOME/webnovel_api36.avd/config.ini"`. The
   last `hw.gpu.mode` must be `host`. Reapply with the emulator stopped:
   `python3 scripts/emulator/configure_fold7.py` (backs up config, keeps data images).
3. **KVM:** `ls -l /dev/kvm` and `"$ANDROID_HOME/emulator/emulator" -accel-check` must report KVM
   usable.
4. **Disk:** `df -h "$ANDROID_HOME"`. The AVD lives on the loop-mounted ext4 image at
   `/var/mnt/android-avd` (`ANDROID_AVD_HOME=/mnt/android-avd/avd`). Note
   `~/.android/avd/webnovel_api36.avd` is a stale July copy; do not edit it.
5. **Host GPU also crashing** (new Mesa/driver or emulator update): check the core dump as
   above and the frames' libraries. Try `scripts/emulator/start_emulator.sh --window` once to
   rule out headless-only problems. Then report to the user with the log lines and backtrace
   library. Do not keep relaunching.
6. **Corrupt quick-boot snapshot** (crash inside snapshot load, not GL): the profile already
   forces cold boot (`fastboot.forceColdBoot=yes`). Add `-no-snapshot-load` only for a one-off
   test.

## Never

- `-wipe-data`, `pm clear`, deleting `userdata-qemu.img*`, or recreating the AVD. The user's
  debug library lives in this AVD.
- Fall back to the owner's physical phone without explicit permission in the current message.
- Use `-gpu auto`, `-gpu swiftshader_indirect`, or `-gpu guest` as the "fix".

## After recovery

- Reinstall/relaunch through the normal skills (`build-and-install-apk`, `dev-launch-screen`).
- If you learned a new failure mode and its fix, add a section above with symptoms, cause,
  confirmation command and fix, dated.

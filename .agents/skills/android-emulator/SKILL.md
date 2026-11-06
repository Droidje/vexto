---
name: android-emulator
description: Set up, run, inspect, and test Android SDK emulators on Windows through ADB, including background operation without a visible window. Use for Android virtual devices, APK deployment, emulator UI testing, and Android logs; not for unrelated desktop automation.
---

# Android emulator through SDK and ADB

Prefer SDK command-line tools to Android Studio or desktop clicks. Default to a headless emulator for agent work so it does not obstruct the user's desktop. Open a visible phone window when the user asks to see or interact with it. Reuse a suitable running device, including a visible device the user is using; do not restart it just to enforce the default.

## Shared Vexto configuration

- For Vexto the emulator MUST have **4096 MB RAM**, as recorded in the repository's `AGENTS.md`.
- Use a normal phone profile. Pixel 7 uses **1080 x 2400, density 420**. Fit the desktop window by scaling it, not by changing Android resolution or density.
- Prefer an existing compatible AVD. The reference profile is `Pixel_7`, Google Play x86_64; API 36.1 has been verified, but follow the app's SDK requirements when the build is configured.
- Resolve machine paths and available AVDs on each developer's computer. If `.agents/local/android-emulator.md` exists, read it for that checkout's verified paths and window preferences; it is ignored by Git. Never publish those values as shared defaults.
- Keep changes to this skill in `.agents/skills/android-emulator/`, with this repository as the source of truth. Avoid a divergent personal copy.
- Store temporary logs, screenshots, and configuration backups in `.agents/work/` (ignored). Shared operational guidance belongs in this skill or `AGENTS.md`.

## Discover and launch

Read applicable project instructions first. Resolve the SDK from valid `ANDROID_HOME` / `ANDROID_SDK_ROOT`, the checkout's local notes, or the existing Android Studio installation. In sandboxed Windows processes, `LOCALAPPDATA` may point into a sandbox package rather than the actual SDK. Validate the tools exist rather than reinstalling. Set only process-local environment variables as needed (the example assumes `$sdkPath` has been resolved):

```powershell
$adbPath = Join-Path $sdkPath 'platform-tools\adb.exe'
$emulatorPath = Join-Path $sdkPath 'emulator\emulator.exe'
$env:ANDROID_HOME = $sdkPath
$env:ANDROID_USER_HOME = Join-Path $env:USERPROFILE '.android'
$env:ANDROID_AVD_HOME = Join-Path $env:ANDROID_USER_HOME 'avd'
& $emulatorPath -list-avds
& $adbPath devices -l
```

Identify a running AVD with `adb -s SERIAL emu avd name`; do not assume `emulator-5554` always belongs to Pixel 7. Use `-s SERIAL` on device-specific commands, including when physical devices are attached.

For a new background run, use `Start-Process` with `-WindowStyle Hidden` and arguments:

```powershell
$launchArgs = @('-avd', 'Pixel_7', '-memory', '4096', '-no-window')
```

Substitute a verified `$avdName` for `Pixel_7` on other machines. Preserve an explicitly configured Android user/AVD directory instead of overriding it with the example defaults. Use the same SDK's explicit ADB path consistently, since another project may have a different ADB on PATH.

Redirect stdout and stderr to distinct files under `.agents/work/` (create it if necessary). A hidden launcher alone is insufficient: **`-no-window` prevents the emulator GUI**. To show the phone, omit `-no-window` and allow a visible window; explain when a headless instance must be restarted to do this. Use `-no-snapshot-load` only when cold boot is needed, not on every launch. Preserve AVD user data and existing snapshots.

Hardware acceleration and local ADB sockets may be inaccessible inside Codex's sandbox. A false acceleration failure was observed inside the sandbox while WHPX worked outside it. If this occurs, request the required execution permissions and recheck outside the sandbox before suggesting BIOS, Windows feature, or administrator changes. Do not silently bypass a permission rejection.

## Ready means Android has booted

Discover the actual serial after launch. Poll with a bounded deadline (typically 120 seconds), breaking tool waits into short intervals so progress remains visible:

- `adb devices -l` reports `device`, not `offline`.
- `adb -s SERIAL shell getprop sys.boot_completed` returns `1`.
- `adb -s SERIAL shell wm size` and `wm density` confirm the intended display.
- `Pixel_7.avd\hardware-qemu.ini` reports `hw.ramSize = 4096` for Vexto. `/proc/meminfo` will report slightly less usable memory; that is expected.

Do not report readiness based only on a running process or successful ADB transport. If the deadline expires, inspect the launch logs and `logcat`; stop repeated identical retries.

## Operate without a desktop window

Use the device serial with these SDK commands, substituting verified package names and activity names from the app/build:

```text
adb -s SERIAL install -r APK_PATH
adb -s SERIAL shell am start -n PACKAGE/ACTIVITY
adb -s SERIAL shell input tap X Y
adb -s SERIAL shell input swipe X1 Y1 X2 Y2 DURATION_MS
adb -s SERIAL shell input keyevent KEYCODE_BACK
adb -s SERIAL shell input text SIMPLE_ASCII_TEXT
adb -s SERIAL shell uiautomator dump /sdcard/codex-window.xml
adb -s SERIAL pull /sdcard/codex-window.xml LOCAL_XML
adb -s SERIAL shell screencap -p /sdcard/codex-screen.png
adb -s SERIAL pull /sdcard/codex-screen.png LOCAL_PNG
adb -s SERIAL logcat -d -t 300
```

Inspect the current screenshot or UI hierarchy before choosing taps; refresh after screen changes. `input text` has shell escaping and Unicode limitations: do not blindly interpolate arbitrary user text. Prefer instrumentation for complex text entry or semantic assertions. Read actual build instructions before choosing Gradle or other build commands.

Pull screenshots into `.agents/work/` and inspect with the image viewer. Save user-facing screenshots to the task's authorized output directory. Avoid PowerShell text pipelines for PNG bytes. Limit log collection to the relevant time or process; do not clear the user's logs or print unrelated sensitive data.

Desktop automation is a fallback for emulator window controls only, using the available computer-use skill. Android UI interaction normally uses ADB, hierarchy dumps, screenshots, or project test tooling.

## Stop, change configuration, or switch visibility

Stop the selected AVD with `adb -s SERIAL emu kill`, then **wait for its process to exit** before modifying configuration or relaunching the same AVD. A successful kill response can precede actual shutdown. Confirm that the process belongs to this AVD before force-stopping anything. Do not remove lock files while a process is still using the AVD.

Edit `config.ini` or `emulator-user.ini` only while the AVD is stopped; back up relevant configuration to `.agents/work/` before edits. Runtime settings can overwrite live edits. Preserve 4096 MB RAM and the chosen phone's native display for Vexto. `hardware-qemu.ini` is generated: inspect it, do not use it as the persistent source of configuration.

Do not use `-wipe-data`, clear app data, recreate AVDs, or uninstall the app just to resolve launch issues without task-specific authorization. Never restart the shared ADB server casually when other devices or tasks may be using it.

## References

Use local `emulator.exe -help` and `adb.exe help` for the installed versions. Consult official docs for unfamiliar options rather than migrating the user's working SDK unnecessarily:

- [Emulator command line, including headless mode](https://developer.android.com/studio/run/emulator-commandline)
- [Android Debug Bridge](https://developer.android.com/tools/adb)

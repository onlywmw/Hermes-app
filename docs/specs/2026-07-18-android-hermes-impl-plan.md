> 恢复自 049a256, 为融合设计基线; M1 实际架构见 docs/ARCHITECTURE.md

# Android Hermes Implementation Plan

This plan implements the design in `2026-07-18-android-hermes-design.md` milestone by milestone.

## Milestone 1 — Bootstrapped Dashboard

**Goal:** APK builds and launches to a Hermes dashboard; installs Termux bootstrap, Python, Hermes, and OpenSSH; keeps SSH running; shows status in dashboard.

### Tasks

1. **Project layout**
   - Use `termux-app-master/termux-app-master` as the Android project root.
   - Copy `constraints-termux.txt` into `app/src/main/assets/`.
   - Add Hermes plugin for Android bridge in `app/src/main/assets/plugins/android_bridge/`.

2. **Manifest changes**
   - Add all permissions from the design doc.
   - Add `HermesActivity` as `MAIN`/`LAUNCHER`.
   - Add `HermesService` foreground service.
   - Add `BootReceiver`.
   - Keep existing Termux activities/services for fallback.

3. **Native Android UI**
   - Create `com.hermes.ui.HermesActivity`.
   - Layout: `activity_hermes.xml` with dashboard cards (Status, SSH, Terminal button, Settings).
   - Bind to `HermesService` and observe status via `LiveData`/`StateFlow`.

4. **HermesService**
   - Extend `Service`.
   - `startForeground` with notification channel.
   - Lifecycle: `onStartCommand` → setup if needed → start SSH → start Hermes process.
   - Expose binder with status LiveData.

5. **HermesSetup / HermesService**
   - Run Termux commands via `AppShell`:
     - `pkg update && pkg install -y python python-pip nodejs git openssh`
     - `python -m venv $HOME/.hermes-venv`
     - `pip install hermes-agent[termux] --constraint constraints-termux.txt`
   - Write default `config.yaml`, `.env` template, and Android bridge plugin to `$HOME/.hermes/`.

6. **SshManager**
   - Generate host keys once: `ssh-keygen -A` or per-user keys.
   - Write `sshd_config` with port 8022, pubkey-only auth.
   - Start `sshd -p 8022 -f ~/.ssh/sshd_config`.
   - Monitor PID and restart if dead.
   - Expose IP/port/fingerprint to UI.

7. **Build verification**
   - Run `./gradlew assembleDebug`.
   - Fix any compile errors.

## Milestone 2 — Agent + Bridge

**Goal:** Hermes Python process starts and talks to Android UI via Unix socket; at least one tool works end-to-end.

### Tasks

1. **HermesSession**
   - Start `$HOME/.hermes-venv/bin/python -m hermes_cli.main` with bridge socket env var.
   - Use `AppShell` so process is attached to Termux environment.

2. **HermesSocketServer**
   - Unix domain socket server in `HermesService`.
   - JSON-RPC 2.0 protocol.

3. **AndroidToolBridge**
   - Implement `clipboard_read` and `clipboard_write`.
   - Wire to socket server.

4. **hermes-android-bridge Python package**
   - Client that connects to socket and exposes `android_bridge` toolset to Hermes.

5. **End-to-end test**
   - Send message to Hermes that reads clipboard; verify result returns to UI.

## Milestone 3 — Full Permissions & Tools

**Goal:** All declared permissions requested and all tool categories implemented.

### Tasks

1. Permission request flows in `HermesActivity`.
2. Implement each tool category in `AndroidToolBridge`.
3. AccessibilityService implementation (`HermesAccessibilityService`) with dump/click/input tools and settings shortcut.
4. Device admin receiver stub (not implemented).
5. Boot receiver and `KeepAliveWorker`.

## Milestone 4 — Polish

**Goal:** Production-ready UI, FCM, settings, CI.

### Tasks

1. Native chat UI with RecyclerView.
2. FCM integration for remote wake.
3. Settings/config editor.
4. Release signing config.
5. GitHub Actions workflow.

## Status

- **Milestone 1** — Code complete and APK builds successfully. Runtime behavior (bootstrap install, SSH start, Hermes install) requires an Android device/emulator to verify.
- **Milestone 2** — Code complete. The bridge socket server, `AndroidToolBridge`, and Hermes plugin are implemented. End-to-end verification requires device runtime.
- **Milestone 3** — Partially started. Notification posting, vibrate, torch, battery, shell, root shell, and accessibility (dump/click/input) tools added. Sensitive permissions declared; runtime permission flows and accessibility service enablement need device testing.
- **Milestone 4** — Not started.

## Build Output

Universal debug APK:
`termux-app-master/termux-app-master/app/build/outputs/apk/debug/termux-app_apt-android-7-debug_universal.apk` (~120 MB, includes Termux bootstrap).

## Known Limitations / Next Steps

1. Runtime testing on Android device is needed to verify bootstrap extraction, `pkg install`, SSH startup, Hermes agent startup, and bridge tool calls.
2. The `hermes-agent[termux]` pip install on device may take several minutes and could fail on specific architectures; logs will be needed.
3. Root shell and Accessibility tools are implemented; Shizuku, ADB wireless, and Device Admin are not yet implemented.
4. FCM wake-up and native chat UI are not implemented.
5. Gradle wrapper was temporarily switched from `gradle-9.2.1` to `gradle-8.14.2` because the cached `9.2.1` download was incomplete/slow. This is compatible with AGP 8.13.2 but may be reverted once `gradle-9.2.1` is available locally.

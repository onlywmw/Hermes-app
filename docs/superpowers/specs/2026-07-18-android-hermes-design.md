# Android Hermes Design

## 1. Goal

Combine the Termux Android terminal environment with the Hermes AI agent to create a self-hosted, full-permission Android agent. The result is a single Android app that:

- Runs Hermes continuously on the device using Termux's Linux userspace.
- Presents a dashboard-first native UI for status, tools, memory, tasks, and chat.
- Exposes Android system capabilities to Hermes as tools (clipboard, notifications, sensors, calls, SMS, camera, root, Shizuku, accessibility, etc.).
- Keeps an SSH server running at all times for remote access.
- Persists across reboots via foreground service, WorkManager, and optional FCM wake-up.

## 2. Non-Goals

- Do not rebuild the Termux bootstrap or rename the package in the first milestone (keeps `com.termux`).
- Do not embed LLM weights; the app uses cloud LLM APIs only.
- Do not support voice local transcription (`faster-whisper`) in MVP; cloud STT or typed input only.
- Do not ship a fully native Android UI framework in MVP; dashboard is native Android with WebView for rich content where needed.

## 3. Context

### 3.1 Hermes Agent

Hermes is a Python-based general-purpose AI agent from Nous Research. It already supports Termux as a Tier-2 platform:

- `setup-hermes.sh` detects Termux and falls back from `uv` to `venv` + `pip`.
- `constraints-termux.txt` pins Android-compatible versions of dependencies.
- `pyproject.toml` provides `[termux]` and `[termux-all]` extras.
- Entry points: `hermes_cli.main:main`, `run_agent:main`, `mcp_serve`, `gateway/run.py`, `tui_gateway/entry.py`, `hermes_cli/web_server.py`.
- UI surfaces: classic CLI, Ink/React TUI, Vite/React web dashboard, Electron desktop, messaging gateways.
- Heavy deps: Python 3.11–3.13, Node.js ≥20 for TUI/web, git, ripgrep, ffmpeg, OpenSSH.

### 3.2 Termux App

Termux is an Android terminal emulator that ships a minimal Linux rootfs as a native library and extracts it on first launch:

- `TermuxActivity` is the launcher with `TerminalView`.
- `TermuxService` is a foreground service owning all sessions/tasks.
- `terminal-emulator` provides VT emulation and JNI pty/fork/exec.
- `termux-shared` provides environment, command runners, plugin utilities, and `termux-am-socket`.
- `RunCommandService` lets external apps run commands in Termux via intent (`com.termux.RUN_COMMAND`).
- `termux-am` lets shell processes call Android `am` commands through a Unix socket.

## 4. Architecture

```
┌─────────────────────────────────────────┐
│  Hermes Dashboard (HermesActivity)      │
│  Cards: Status / Chat / Tools / Tasks   │
│         Memory / SSH / Permissions      │
└──────────────┬──────────────────────────┘
               │ Local JSON-RPC over Unix Socket
┌──────────────▼──────────────────────────┐
│  HermesService (Foreground Service)     │
│  - bootstrap check & install            │
│  - pkg install python nodejs git openssh│
│  - pip install hermes[termux]           │
│  - start sshd                           │
│  - start Hermes agent / gateway         │
└──────────────┬──────────────────────────┘
               │ AppShell / TerminalSession / JNI
┌──────────────▼──────────────────────────┐
│  Termux native layer                    │
│  $PREFIX / apt / pkg / termux-am        │
└─────────────────────────────────────────┘
```

## 5. Components

| Component | Location | Responsibility |
|---|---|---|
| `HermesActivity` | `app/src/main/java/com/hermes/ui/` | Launcher, dashboard, permission flows, binds to `HermesService`. |
| `HermesService` | `app/src/main/java/com/hermes/service/` | Foreground service, dependency bootstrap, SSH keepalive, Hermes process lifecycle. |
| `HermesSetupTask` | `app/src/main/java/com/hermes/setup/` | One-time setup: install Termux packages, pip install Hermes, write default config and .env template. |
| `HermesSession` | `app/src/main/java/com/hermes/session/` | Wraps `AppShell`/`TerminalSession` to run `python -m hermes_cli.main` or `python run_agent.py`. |
| `AndroidToolBridge` | `app/src/main/java/com/hermes/bridge/` | Receives JSON-RPC from Hermes Python and executes Android operations. |
| `HermesSocketServer` | `app/src/main/java/com/hermes/bridge/` | Unix domain socket server inside the app process; protocol is JSON-RPC 2.0. |
| `SshManager` | `app/src/main/java/com/hermes/ssh/` | Installs OpenSSH, generates host keys, starts/stops/monitors `sshd`, exposes IP/port/fingerprint. |
| `BootReceiver` | `app/src/main/java/com/hermes/receiver/` | Receives `BOOT_COMPLETED`/`QUICKBOOT_POWERON` and starts `HermesService`. |
| `KeepAliveWorker` | `app/src/main/java/com/hermes/worker/` | WorkManager periodic worker that restarts `HermesService` if dead. |
| `hermes_android` Python package | `hermes-android-bridge/` | Hermes-side client that talks to `HermesSocketServer`; exposes tools to the agent. |

## 6. Data Flow

1. User installs APK and opens `HermesActivity`.
2. `HermesActivity` requests all dangerous permissions and battery optimization exemption.
3. User taps "Start Agent"; `HermesActivity` starts `HermesService`.
4. `HermesService` calls `TermuxInstaller.setupBootstrapIfNeeded(...)`.
5. `HermesSetupTask` runs in background Termux shell:
   - `pkg update && pkg install -y python nodejs git openssh`
   - `python -m venv $HOME/.hermes-venv`
   - Activate venv and install Hermes. Prefer local source copy shipped in APK assets: `pip install ./hermes-agent-main[termux]` with `constraints-termux.txt` copied from assets to `$PREFIX/tmp/`.
   - Write `$HOME/.hermes/config.yaml` and `$HOME/.hermes/.env` from app assets.
6. `SshManager` starts `sshd -p 8022`.
7. `HermesSession` starts `$HOME/.hermes-venv/bin/python -m hermes_cli.main` in non-interactive/agent mode (exact CLI flags to be validated against Hermes `hermes_cli/main.py`) with the socket path exported via environment variable.
8. Hermes Python connects to `HermesSocketServer` and registers available Android tools.
9. Dashboard cards update via `LiveData`/`StateFlow` bound to `HermesService` binder or socket events.
10. User sends a message in Chat card → forwarded to Hermes via stdin or socket → Hermes decides to use an Android tool → calls `AndroidToolBridge` via socket → result returned to Hermes → UI updated.

## 7. Android Capability Bridge

`AndroidToolBridge` exposes these tool categories to Hermes:

### 7.1 Termux-style controls
- `clipboard_read` / `clipboard_write`
- `notification_show` / `notification_cancel`
- `vibrate` / `torch` / `ringtone` / `media_play`
- `wifi_status` / `wifi_toggle` / `bluetooth_status` / `bluetooth_toggle`
- `battery_status` / `screen_brightness` / `screen_lock`

### 7.2 Standard Android permissions
- `camera_capture` / `video_record`
- `audio_record`
- `location_get` / `location_start_updates`
- `contacts_read`
- `sms_read` / `sms_send`
- `call_log_read` / `phone_dial`

### 7.3 High-privilege controls
- `shell_su` – run command as root via `su -c`.
- `shell_shizuku` – run command via Shizuku binder.
- `shell_adb` – pair/connect wireless ADB and run shell commands.
- `accessibility_click` / `accessibility_scroll` / `accessibility_input` – requires AccessibilityService.
- `device_admin_lock` / `device_admin_wipe` – requires device admin.

### 7.4 UI / System
- `screenshot`
- `start_activity` / `send_broadcast`
- `toast` / `dialog`
- `overlay_show` (SYSTEM_ALERT_WINDOW)

Each tool is a JSON-RPC method: `{"jsonrpc":"2.0","method":"clipboard_read","params":{},"id":1}`.

## 8. Background Persistence

| Mechanism | Purpose |
|---|---|
| `HermesService.startForeground` | Persistent notification; OS considers it a foreground process. |
| `BOOT_COMPLETED` / `QUICKBOOT_POWERON` receiver | Auto-start after reboot. |
| `KeepAliveWorker` (WorkManager, 15 min) | Detect and restart `HermesService` if killed. |
| `FCM` (optional) | Remote push wakes device and starts service for critical tasks. |
| `WAKE_LOCK` | Keep CPU awake during active agent tasks. |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Ask user to whitelist app from Doze/App Standby. |

## 9. SSH Always-On

- Package: `openssh` installed via `pkg`.
- Config: `$HOME/.ssh/sshd_config` with `Port 8022`, `PasswordAuthentication no`, `PubkeyAuthentication yes`.
- Host keys generated once into `$HOME/.ssh/`.
- `HermesService` monitors `sshd` PID; if dead, restarts it.
- Dashboard SSH card shows: LAN IP, port, fingerprint, authorized keys count, active connections.
- Authorized keys can be imported from app assets or pasted in UI.

## 10. UI Design

### 10.1 Dashboard cards
1. **Status** – service running, Hermes version, last heartbeat.
2. **Chat** – last messages, quick send button.
3. **Tools** – toggle Android tool categories on/off.
4. **Tasks** – running cron/gateway tasks from Hermes state.
5. **Memory** – recent memory entries from Hermes.
6. **SSH** – IP/port/fingerprint, toggle on/off.
7. **Permissions** – checklist of granted Android permissions.
8. **Terminal** – button to open legacy Termux terminal.
9. **Logs** – tail of Hermes/Termux output.

### 10.2 Navigation
- Bottom navigation: Dashboard | Chat | Terminal | Settings.
- Drawer: Tools, Memory, SSH keys, Cron editor, Config editor.

## 11. Permissions

The following permissions are declared in `AndroidManifest.xml` and requested at runtime:

- Normal: `INTERNET`, `ACCESS_NETWORK_STATE`, `FOREGROUND_SERVICE`, `RECEIVE_BOOT_COMPLETED`, `WAKE_LOCK`, `VIBRATE`, `FLASHLIGHT`.
- Dangerous: `CAMERA`, `RECORD_AUDIO`, `ACCESS_FINE_LOCATION`, `READ_CONTACTS`, `READ_SMS`, `SEND_SMS`, `READ_CALL_LOG`, `READ_PHONE_STATE`, `READ_EXTERNAL_STORAGE`, `WRITE_EXTERNAL_STORAGE` (legacy, targetSdk 28), `POST_NOTIFICATIONS`.
  - Hermes data stays in Termux private storage; shared storage is only used when the user explicitly exports/imports files via Storage Access Framework.
- Special: `SYSTEM_ALERT_WINDOW`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, `BIND_ACCESSIBILITY_SERVICE`, `DEVICE_ADMIN`.
- Termux: `com.termux.permission.RUN_COMMAND`.

Root/Shizuku/ADB are not permissions in the manifest; they are runtime capabilities granted by the user or by external apps.

## 12. Configuration

Default `~/.hermes/config.yaml` shipped as asset:

```yaml
model:
  provider: openrouter  # or openai/anthropic/gemini
  # api_key loaded from ~/.hermes/.env

toolsets:
  - android_control
  - termux
  - shell
  - file
  - web_search

terminal:
  backend: termux

server:
  dashboard: false  # native dashboard replaces web dashboard
  gateway: true

android_bridge:
  socket_path: /data/data/com.termux/files/usr/tmp/hermes_bridge.sock
```

The bridge socket directory (`$PREFIX/tmp`) is created by Termux bootstrap; `HermesSocketServer` verifies and creates it if missing.

`.env.example` prompts user for `OPENROUTER_API_KEY` or provider key.

## 13. Security Considerations

- `.env` and SSH host/user keys live in Termux private storage; not accessible to other apps.
- SSH disabled password auth by default.
- Dangerous tools (`shell_su`, `accessibility_click`, `device_admin_wipe`) require explicit user approval per invocation through an Android dialog.
- Cloud API keys never leave device storage except to call the provider.
- `allow-external-apps=true` in `termux.properties` is set only if needed for plugin integration.

## 14. Build & Packaging

- Base: Termux `termux-app-master` Gradle project.
- Add new package `com.hermes.*` under `app/src/main/java/`.
- Add `hermes-android-bridge/` Python package as asset or copy into Hermes install path.
- Min SDK 24 (Termux default is 21; Hermes Python needs 7+), target SDK 28.
- NDK version as required by Termux.
- Output APK includes Termux bootstrap; Hermes Python deps downloaded on first run.
- CI: GitHub Actions to build APK and run lint.

## 15. Milestones

### MVP 1 – Bootstrapped Dashboard
- Replace launcher with `HermesActivity` dashboard.
- `HermesService` starts, checks bootstrap, installs Python/Hermes/OpenSSH.
- Dashboard shows status and SSH address.
- Open legacy terminal from dashboard.

### MVP 2 – Agent + Bridge
- Start Hermes agent process from service.
- Establish Unix socket bridge.
- Expose one Android tool (clipboard read/write) end-to-end.

### MVP 3 – Full Permissions & Tools
- Declare and request all permissions.
- Implement all Android tool categories.
- Add boot receiver and keepalive worker.

### MVP 4 – Polish
- Native chat UI.
- FCM wake-up.
- Settings/config editor.
- Release signing and CI.

## 16. Open Questions / Future Work

- Whether to rebrand package name later (requires bootstrap rebuild).
- Whether to embed a prebuilt Python/Hermes wheel set into APK to avoid first-run download.
- Local model support via `llama.cpp` or MLC LLM.
- Voice input using Android `SpeechRecognizer` instead of local whisper.

## 17. Decision Log

| Date | Decision | Rationale |
|---|---|---|
| 2026-07-18 | Use Termux as runtime host | Hermes already supports Termux; avoids rebuilding Python runtime from scratch. |
| 2026-07-18 | Keep `com.termux` package name in MVP | Avoids rebuilding Termux bootstrap; fastest path to working APK. |
| 2026-07-18 | Cloud LLM only | No on-device model weights; smaller APK and simpler architecture. |
| 2026-07-18 | Dashboard + cards main UI | Matches user requirement; native Android dashboard is easier than terminal-first on mobile. |
| 2026-07-18 | Full background persistence | Required for an always-on agent. |
| 2026-07-18 | SSH always on port 8022 | Enables remote management and aligns with "权限全都要" goal. |

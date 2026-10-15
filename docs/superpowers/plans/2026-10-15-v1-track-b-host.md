# v1 Track B: PC Remote Host (Windows), Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax. Every task is TDD: write the listed tests first, watch them fail, implement, watch them pass, then commit.

**Goal:** A tiny Rust + Tauri v2 Windows app that installs and hides Sunshine, runs the agent from `docs/protocol.md`, and gives the PC side a modern Midnight UI with QR pairing.

**Architecture:** One process, `pc-remote-host.exe`:
- `agent` (tokio, a WSS server on 47999),
- `sunshine` (a REST client for localhost:47990),
- `win` (SendInput, power, DPAPI),
- `store` (devices/config JSON in `%APPDATA%\PC Remote`),
- a Tauri shell (tray + window).

The frontend is plain HTML/CSS/TypeScript with Vite (no framework). Closing the window destroys the webview; the tray keeps running.

**Tech Stack:** Rust stable (MSVC), Tauri 2, tokio, tokio-tungstenite + tokio-rustls, rcgen, serde/serde_json, reqwest (rustls), windows-rs, qrcode, window-vibrancy; Vite + TypeScript.

**Spec:** `docs/superpowers/specs/2026-10-15-pc-remote-v1-design.md`. **Protocol:** `docs/protocol.md`.

## Global Constraints
- All code lives in `host/` (`host/src-tauri` for Rust, `host/ui` for the frontend). Don't touch `app/`.
- Release binary under 10 MB. Tray-idle RAM under 25 MB with ~0% CPU (measure with `Get-Process`). No polling loops while idle: everything is event-driven or runs on timers only while subscribed.
- No plaintext listener. The agent binds `0.0.0.0:47999` TLS only.
- Secrets (TLS key, Sunshine credentials, device tokens) are encrypted at rest with DPAPI (`CryptProtectData`, user scope).
- Received files are confined to the configured folder (canonicalise and check the prefix).
- UI colours, radii and type mirror the Midnight tokens from the spec, declared once as CSS variables in `host/ui/src/theme.css`.
- Tests: `cd host/src-tauri && cargo test`. Lint: `cargo clippy -- -D warnings`.

## Review Focus
1. **Two phones connected, one is revoked in the Devices tab** → only that phone's socket is closed with `revoked`; the other stays connected. Test: `agent::tests::revoke_closes_only_that_device`.
2. **Phone disconnects while holding Shift or a mouse button** → the host releases them (no stuck keys). Test: `input::tests::releases_held_on_drop`.
3. **File name `..\..\Windows\evil.dll` or `CON.txt`** → it's saved as a safe name inside the folder. Test: `files::tests::sanitize_rejects_traversal_and_reserved`.
4. **Sunshine service stopped** → the host restarts it and reports `sunshine:false` until it's up. Test: `sunshine::tests::status_false_when_unreachable` (mock server).
5. **OTP used twice or after 120 s** → `bad_otp`. Test: `store::tests::otp_single_use_and_expiry`.

---

### Task B1: Scaffold
**Files:** `host/package.json`, `host/vite.config.ts`, `host/ui/index.html`, `host/ui/src/{main.ts,theme.css}`, `host/src-tauri/{Cargo.toml,tauri.conf.json,build.rs,src/main.rs,src/lib.rs}`, `host/.gitignore` (target, node_modules, dist).
**Do:**
- Create the Tauri 2 app `PC Remote Host` (identifier `com.pcremote.host`), single instance (`tauri-plugin-single-instance`), tray icon, and a 420×640 non-resizable window with `decorations:false` (custom titlebar) and Mica via `window-vibrancy`.
- On close-requested, hide and destroy the webview window; the tray left-click recreates it.
- Release profile: `opt-level="s"`, `lto=true`, `codegen-units=1`, `strip=true`, `panic="abort"`.

**Check:** `npm run tauri build -- --no-bundle` succeeds; the exe is under 10 MB; launching shows the tray and window; closing the window keeps the tray; RAM after close is under 25 MB.
- [ ] Build → check → commit `feat(host): Tauri scaffold with tray and Mica window`

### Task B2: Protocol types
**Files:** `src/proto.rs`; tests in the same file.
**Produces:** `#[serde(tag="t")] enum ClientMsg { Pair{otp,device,pin}, Hello{v,token,device}, Ping, MouseMove{dx,dy}, MouseButton{b,down}, MouseScroll{dy,dx}, Key{vk,down}, Text{s}, Pad{..}, PadOff{id}, MediaSub{on}, MediaCmd{c,v:Option}, ClipSub{on}, Clip{mime,data}, FileBegin{..}, FileEnd{..}, FileAccept{..}, FileReject{id}, Power{a}, ThumbSub{on}, Net }` and `enum ServerMsg { Paired{token,pc}, Welcome{v,pc,caps}, Pong, Error{code,msg,of}, Rumble{..}, Media{..}, Clip{..}, FileReady{..}, FileDone{..}, FileError{..}, FileOffer{..}, Ok{of}, Status{streaming,to}, Net{lan,public,anywhere,upnp} }`, with the dotted names from protocol.md given via `#[serde(rename)]`. `fn parse_binary(&[u8]) -> Option<Binary>` where `Binary` is `FileChunk{id:[u8;16],offset:u64,data}` or `Thumb(Vec<u8>)`.
**Tests:** every protocol.md example JSON parses into the expected variant; unknown `t` → error (the caller ignores it); unknown fields ignored; binary chunk round-trip; a chunk shorter than 25 bytes → None; `mouse.move` dx is clamped to ±2000 by `ClientMsg::sanitize()`.
- [ ] RED → GREEN → commit `feat(host): protocol types`

### Task B3: Secrets and TLS identity
**Files:** `src/win/dpapi.rs`, `src/tls.rs`.
**Produces:** `dpapi::protect(&[u8]) -> Vec<u8>`, `unprotect`; `tls::Identity::load_or_create(dir) -> Identity { cert_der, key_der, fingerprint_hex }` (rcgen ECDSA P-256, 10 years, SAN `pc-remote.local`), with the key stored DPAPI-encrypted.
**Tests:** DPAPI round-trip; create then load gives the same fingerprint; the fingerprint is 64 lowercase hex chars and equals the SHA-256 of `cert_der`.
- [ ] RED → GREEN → commit `feat(host): DPAPI secrets and persistent TLS identity`

### Task B4: Store (devices, OTP, config)
**Files:** `src/store.rs`.
**Produces:**
- `Store::open(dir)`.
- `new_otp() -> String` (128-bit base64url, 120 s, single use) and `take_otp(&str) -> bool`.
- `add_device(name) -> (id, token)` (256-bit), `auth(token) -> Option<DeviceId>`, `revoke(id)`, `devices()`.
- `config()`/`set_config(Config{autostart, display, max_quality, allow_files, allow_clip, anywhere, files_dir})`.
- Tokens are stored as SHA-256 hashes, so the raw token is never on disk.

**Tests:** `otp_single_use_and_expiry` (injectable clock); auth after add; auth fails after revoke; persistence across reopen; the raw token isn't present in the file bytes.
- [ ] RED → GREEN → commit `feat(host): device/OTP/config store`

### Task B5: Agent server
**Files:** `src/agent/mod.rs`, `src/agent/session.rs`.
**Produces:**
- `Agent::start(identity, store, handlers) -> AgentHandle`.
- Per connection: TLS accept → WebSocket at `/v1` → the first message must be `pair` or `hello` (else `unauthorized`, close) → session loop dispatching to a `Handlers` trait (`input`, `media`, `clip`, `files`, `power`, `thumb`, `net`).
- `AgentHandle::revoke(device_id)` closes only that device's sessions with `revoked`.
- `AgentHandle::events()` is a broadcast of `Connected{device}`/`Disconnected{device}` for the UI.
- `pair` calls `Sunshine::submit_pin` (Task B6) in the background and replies `paired` once the token is stored.
- Ping/pong, plus a 30 s idle timeout.

**Tests (integration, using a real TLS client with the pinned fingerprint against `127.0.0.1:0`):**
- `hello_with_valid_token_gets_welcome`
- `first_message_not_auth_closes_unauthorized`
- `pair_with_otp_returns_token_and_otp_is_consumed`
- `revoke_closes_only_that_device`
- `idle_timeout_closes`
- [ ] RED → GREEN → commit `feat(host): TLS WebSocket agent with pair/hello`

### Task B6: Sunshine bridge
**Files:** `src/sunshine.rs`.
**Produces:**
- `Sunshine::ensure_credentials(store)`: on first run, generate a random user/password, run `"C:\Program Files\Sunshine\sunshine.exe" --creds <u> <p>`, restart the `SunshineService` service, and store the credentials with DPAPI.
- `submit_pin(pin, name)`: `POST /api/pin`, retrying every 500 ms for up to 30 s.
- `status() -> SunshineStatus{running, streaming}`.
- `set_config(key, value)` (`output_name`, `upnp`).
- `restart_service()`.
- Uses reqwest with basic auth and accepts Sunshine's self-signed cert for localhost only.

**Tests (wiremock):** `submit_pin` retries until 200, then Ok; times out → `PinTimeout`; `status_false_when_unreachable`; `set_config` posts the expected JSON.
- [ ] RED → GREEN → commit `feat(host): Sunshine REST bridge`

### Task B7: Input injection
**Files:** `src/input.rs`.
**Produces:** `Input` (a `Handlers::input` impl): mouse move/button/scroll and key via `SendInput`; text via `KEYEVENTF_UNICODE`; it tracks held keys and buttons per session, and `release_all()` runs on drop.
**Tests:** the pure part (`HeldSet`) is tested: press/release bookkeeping; `releases_held_on_drop` checks that `release_all` emits up-events for exactly the held set (via an injectable `Emitter` trait; the real impl calls `SendInput`).
- [ ] RED → GREEN → commit `feat(host): SendInput injection with stuck-key protection`

### Task B8: Power
**Files:** `src/power.rs`.
**Produces:** `power(action)`: `LockWorkStation`; `SetSuspendState(false,…)`; `ExitWindowsEx` (restart/shutdown/logoff) after enabling `SeShutdownPrivilege`.
**Tests:** action string parsing (`lock|sleep|restart|shutdown|signout`; anything else → `bad_request`). The real calls are verified manually (lock only).
- [ ] RED → GREEN → commit `feat(host): power actions`

### Task B9: UI
**Files:** `host/ui/src/{main.ts, views/home.ts, views/devices.ts, views/settings.ts, views/about.ts, theme.css, titlebar.ts}`; Tauri commands in `src/commands.rs` (`get_state`, `new_qr`, `revoke`, `rename`, `get_config`, `set_config`, `open_files_dir`, `check_update`).
**Design:** Midnight tokens as CSS variables; the system font stack `"Segoe UI Variable", "Segoe UI", system-ui`.
- **Home:** a large QR (SVG from the `qrcode` crate, generated in Rust) inside a glass card with a 120 s ring countdown; the PC name; a status pill; a connected-devices list with live dots.
- **Devices:** rows with rename/revoke and a confirm.
- **Settings:** toggles and a display picker (from `EnumDisplayDevices`).
- **About:** version, update check against GitHub releases.
- Transitions are 200 ms spring-like CSS. A custom titlebar with minimise/close. No framework, with `host/ui` JS under 40 KB gzipped.

**Tests:** `commands.rs` pure helpers: QR payload format matches protocol.md (`pcremote://pair?n=..&h=..&p=47999&fp=..&t=..`), including the URL-encoded name and LAN IPs sorted with private ranges first.
**Check:** a visual check of every view; screenshots are saved to `host/docs/screens/` for the README.
- [ ] RED → GREEN → commit `feat(host): Midnight UI with QR pairing`

### Task B10: Installer
**Files:** `host/src-tauri/tauri.conf.json` bundle (NSIS, per-machine, `installMode: perMachine`), `host/src-tauri/installer/hooks.nsh`, and `host/scripts/fetch-deps.ps1` (downloads `Sunshine-Windows-AMD64-installer.msi` from `SadikTD/Sunshine` releases and the ViGEmBus installer into `host/src-tauri/installer/deps/`, which is git-ignored).
**Hooks:**
- Post-install: `msiexec /i Sunshine.msi /qn`; ViGEmBus silent; firewall rule for TCP 47999; the autostart registry `Run` key.
- Pre-uninstall: remove the firewall rule and autostart, and ask whether to keep Sunshine.

**Check:** run the installer on this PC → the tray app starts, Sunshine is running, and port 47999 is listening; uninstall cleans up.
- [ ] Build → check → commit `feat(host): NSIS installer bundling Sunshine and ViGEmBus`

### Task B11: Release check
Measure: binary size, installer size, tray-idle RAM after 60 s, CPU over 60 s idle. Record the numbers in `host/README.md`. Attach the installer to the `v1.0.0` release on `SadikTD/pc-remote` **only after the user confirms**.

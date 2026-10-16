# PC Remote v3: Always Reachable, Self-Updating, Redesigned

Date: 2026-10-17
Status: Approved (one release, 3.0.0)

## What the user asked for
1. Reliable, fast, professional file transfer in both directions.
2. Wake the PC from anywhere, even when it is shut down.
3. A complete, professional redesign of both apps.
4. Silent automatic updates on PC and phone.
5. The PC app starts at boot, silently, hidden.
6. Full control on the lock screen, on UAC prompts and with the monitor asleep.
7. Unlock the locked PC with the phone's fingerprint.

## Decisions
| Area | Decision | Why |
|---|---|---|
| Boot + lock screen + UAC | A Windows service (`--service`, SYSTEM, session 0) launches the host server (`--server`) into the active console session as SYSTEM; the server follows the input desktop (Default / Winlogon) for capture and input | Only SYSTEM processes may read and inject into the secure desktop; same model as RustDesk and Parsec |
| UI | The tray + window run as the logged-in user and talk to the server over a named pipe | A SYSTEM-owned window with file dialogs is a privilege-escalation hole |
| Data | `C:\ProgramData\PC Remote`, ACL SYSTEM + Administrators; migrated once from `%APPDATA%\PC Remote` | SYSTEM cannot read the user's roaming profile; the TLS key is re-protected with machine DPAPI |
| Elevation | The "PC Remote" scheduled task and HKCU Run key go away; the service replaces both | SYSTEM already reaches admin windows |
| Monitor asleep | On connect and stream start the server forces the display on (`SetThreadExecutionState` + a zero mouse move) | DXGI cannot duplicate a powered-off display |
| Fingerprint unlock | The phone keeps the Windows PIN/password in an Android Keystore key that needs biometric auth; `unlock` over the agent makes the server wake the screen, dismiss the curtain, type the secret and press Enter | No credential provider DLL to break Windows sign-in; the PC's own fingerprint reader is unaffected |
| PC updates | The service checks GitHub Releases every 6 h, verifies the installer with minisign (public key built in), runs it with `/S` as SYSTEM | Silent, no UAC; the signature stops a tampered release |
| Phone updates | WorkManager-free: a check on app start and every 6 h while running, download, `PackageInstaller` with `USER_ACTION_NOT_REQUIRED` | Silent on Android 12+ after the first self-update; Android checks the APK signature |
| File transfer | A second agent connection carries file data; multi-file and folder pick; queue with progress/speed/ETA; pause/resume/cancel; browse and download PC files | Control and screen never wait behind a large file |
| Wake from anywhere | The phone calls Home Assistant's `wake_on_lan.send_magic_packet` (URL + long-lived token in phone settings) | A shut-down PC can only be woken by something on its LAN |
| WoL readiness | The installer turns off Fast Startup and enables magic-packet wake on wired adapters | Fast Startup is a hybrid hibernate that often ignores magic packets |
| Look | Dark premium glass: near-black surfaces, frosted panels (Mica on PC, blur on Android 12+), one vivid accent, motion between screens | User's choice |

## Process model (PC)
```
SCM ─► pc-remote-host --service (SYSTEM, session 0)
         ├─ launches/relaunches ─► pc-remote-host --server (SYSTEM, console session)
         │                           agent · engine · input · files · clip · mdns · rendezvous
         │                           ⇅ named pipe \\.\pipe\pc-remote (JSON lines)
         │                         pc-remote-host [--hidden] (user) tray + window
         └─ update check every 6 h ─► verify ─► setup.exe /S
```
- The service watches session changes (`WTS_CONSOLE_CONNECT`, logon/logoff) and keeps one server in the console session.
- The user UI is started at logon by the server (`CreateProcessAsUser` with the session user's token), so autostart needs no Run key.
- Pipe security: the pipe accepts only clients in the same session; requests are the existing Tauri commands by name.
- Per-user work the server needs (received-files folder, toasts) uses the console user's token.

## Protocol additions (docs/protocol.md)
- `welcome.caps` gains `unlock`, `fsbrowse`; `welcome` gains `locked: bool`; host sends `{"t":"lock","locked":bool}` on change.
- `→ {"t":"unlock","secret":"…"}` / `← {"t":"unlock.result","ok":bool}`.
- `→ {"t":"fs.list","path":"…"}` / `← {"t":"fs.list","path","entries":[{name,dir,size,mtime}]}`; `""` lists drives and known folders.
- `→ {"t":"fs.get","path":"…"}`: host answers with the normal `file.offer` flow.
- `→ {"t":"hello", …, "role":"files"}` opens a file-only connection (binary chunks only travel there when present).

## Out of scope
- A Windows credential provider tile.
- Waking without Home Assistant (router forwarding, spare phone).
- Peer-to-peer reliable file transport over UDP (relay-limited speed off-LAN without UPnP is accepted).

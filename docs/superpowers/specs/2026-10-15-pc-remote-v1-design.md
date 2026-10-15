# PC Remote v1: Product Design

Date: 2026-10-15
Status: Draft, awaiting review
Builds on: `2026-10-15-pc-remote-stage1-design.md` (shipped as v0.1.0)

## Goal

Turn the working test build into a polished product that beats Monect PC Remote and similar apps on features, ease of use and looks, while staying very lightweight on both the phone and the PC.

**What the user said:**
- Feature parity with Monect or better: "all the features we need".
- Extremely lightweight on Android and on Windows.
- UI/UX "out of this world": professional, minimal, dynamic, interesting.
- Both the Android app and the Windows side must look professional; today they look like 1980s software.
- Build everything together. The user delegated all design choices.

**Success criteria:**
- A new user goes from installing to controlling their PC in under 60 seconds, with no typing: install the PC app, scan a QR code, done.
- Release APK under 8 MB (arm64). PC companion idle in the tray under 25 MB RAM and ~0% CPU.
- Covers every Monect feature category in the table below, except the ones explicitly deferred.
- Streaming smoothness stays at stage 1 levels (Moonlight core untouched).

## The three parts

```
┌──────────── Android: PC Remote ────────────┐        ┌──────────── Windows ─────────────┐
│ Compose UI (new design system)             │        │ PC Remote Host (Rust + Tauri)    │
│  ├─ Screen mode ── Moonlight core ─────────┼─video──┼─► Sunshine (hidden service)     │
│  ├─ Remote / Media / Slides / Gamepad* ────┼─WSS────┼─► Host agent: input, clipboard,  │
│  ├─ Files, Clipboard, Power                │        │   files, power, pairing, status  │
│  └─ Pairing via QR ────────────────────────┼─WSS────┼─► submits PIN to Sunshine API    │
└────────────────────────────────────────────┘        └──────────────────────────────────┘
   * Gamepad uses the Sunshine stream when the screen is shown, the agent otherwise
```

1. **Android app:** all screens rebuilt on one design system. Moonlight's streaming core stays as-is.
2. **PC Remote Host (Windows):** a small Rust + Tauri app that installs and hides Sunshine, adds its own lightweight agent, and gives the PC side a modern UI. The user never sees Sunshine's web page.
3. **Features:** split across the two, talking over one secure WebSocket.

## Design language ("Midnight")

One language shared by phone and PC.

- **Mood:** calm, dark-first, precise. Content (the PC screen) is the hero; chrome stays out of the way until it's needed.
- **Colour:** near-black surfaces (`#0B0D12`, raised `#141821`, `#1C2130`), hairline borders `#FFFFFF14`, one accent: electric indigo `#7C8CFF`, with a cyan `#4FD1FF` gradient for live/active states. Status colours: green `#3DDC97`, amber `#FFC857`, red `#FF5D73`. Light theme is derived from the same tokens. Optional Material You dynamic colour on Android 12+.
- **Type:** system sans (Roboto Flex on Android, Segoe UI Variable on Windows). No bundled fonts, which keeps both apps light. Large, tight-tracked display numbers for status (latency, battery, PIN).
- **Shape:** 24 dp cards, 16 dp controls, fully round pills for toggles. Glass panels (blur, 70% surface) for overlays on the stream.
- **Motion:** spring physics everywhere (no linear easing). Shared-element transitions from PC card → mode screen. Live elements breathe: the connection dot pulses, latency numbers roll.
- **Haptics:** every control gives a subtle tick; clicks on the trackpad give a firmer tap. All haptics can be turned off.
- **Icons:** Material Symbols Rounded (vector, only the icons we use are included).

## Android app

### Screens

1. **Onboarding (first run only), 3 steps:**
   1. Welcome: a one-line value prop over an animated hero.
   2. "Get PC Remote for Windows": shows the short download link and a QR code for it.
   3. "Scan the code on your PC": opens the camera and pairs automatically (see Pairing).

   Manual IP entry is a small link, not the main path.
2. **Home:**
   - Paired PCs as large cards: name, online state, OS user, and a live mini-thumbnail when the PC is online and awake.
   - One big primary action per PC (**Open**), plus a row of mode chips: Screen · Remote · Media · Slides · Gamepad · Files.
   - Offline PCs show **Wake**.
   - A "+" adds a PC by QR.
3. **Screen mode (stream):** the stage 1 stream, restyled.
   - The toolbar FAB is replaced by an **edge handle**: swipe in from the right edge to open a glass side panel with keyboard, modifier keys, shortcuts, media, mode switch, quality and disconnect.
   - A **keyboard bar** with sticky Ctrl/Alt/Shift/Win/Esc/Tab/arrows/F-keys sits above the soft keyboard.
   - First-run gesture coach marks (shown once).
   - Stats appear as a small pill that expands on tap.
4. **Remote mode (no video):**
   - A full-screen trackpad over a subtle grid, with a scroll strip on the right edge.
   - Left/right click buttons can be toggled on.
   - Keyboard bar and quick actions.
   - Uses about 1% of the battery Screen mode uses, so it's ideal for a couch/TV setup.
5. **Media mode:**
   - Big play/pause, previous/next, a volume dial (rotate gesture), mute.
   - Now-playing title and artist read from Windows media sessions (via the agent).
   - Phone volume keys control PC volume.
6. **Slides mode (presenter):**
   - Huge Next and Previous areas, with phone volume keys as a clicker.
   - Elapsed timer with haptic warnings at the 5-minute and 1-minute marks.
   - Black-screen toggle (B) and laser-pointer mode (the phone's gyro moves the mouse pointer while held).
7. **Gamepad mode:**
   - Layouts: Xbox, Racing (gyro steering), Shooter (gyro aim on the right stick), Minimal (D-pad + 4 buttons).
   - Layout editor: drag, resize, opacity, haptic strength.
   - Uses Moonlight's virtual controller over the stream when Screen mode is on, otherwise sends gamepad input through the agent.
   - The PC sees an Xbox 360 controller (ViGEm).
8. **Files & Clipboard:**
   - Send files or photos to the PC: picker, or Android share sheet → "Send to PC". Files land in `Downloads\PC Remote`.
   - Receive files the PC sends.
   - Clipboard sync toggle: two-way, text and images, LAN only.
9. **Power:** Lock, Sleep, Restart, Shut down, Sign out, each with a confirm sheet. Also Wake-on-LAN.
10. **Settings:** Appearance (Dark / Light / System, dynamic colour), Stream quality (Auto / Smooth / Sharp / Battery / Advanced), Controls (haptics, natural scrolling, pointer speed, gyro sensitivity), Connection (access from anywhere), About.

### Lightweight targets
- R8 shrinking and resource shrinking on. Separate APKs per ABI; the default GitHub release ships `arm64-v8a`, with an `armeabi-v7a` build alongside.
- Remove leftover Moonlight UI: AppView, PcView, the old preference screens once replaced, grid adapters and poster assets, TV/Leanback bits, and unused translations of removed screens.
- Target: release APK under 8 MB, cold start under 400 ms on a mid-range phone, and idle Home with no background services. Discovery runs only while Home is visible.

## PC Remote Host (Windows)

### What the user sees
- **Installer:** one signed-style NSIS installer, about 40 MB because it bundles the Sunshine MSI. It installs Sunshine silently, configures it, creates Sunshine credentials the user never needs to see, and adds firewall rules. It also installs the ViGEmBus gamepad driver, which Sunshine also uses, so gamepads work without video too. Next, Next, Done.
- **Main window:** opened from the tray or Start menu. Small, 420×640, Mica-style translucent background.
  - **Home:** a big pairing QR code (refreshes every 2 minutes), the PC name, status ("Ready", "Streaming to Pixel 8", "Remote connected") and connected devices with a disconnect button.
  - **Devices:** paired phones; rename or revoke.
  - **Settings:** start with Windows, the display to stream (multi-monitor picker), max quality, allow files and clipboard, access from anywhere, received-files folder.
  - **About / Update:** checks GitHub releases on `SadikTD/pc-remote`.
- **Tray:** a minimal monochrome icon, with a status dot overlay when something is connected. Left-click opens the window; the right-click menu has Pause, Open, Quit.
- Closing the window keeps only the tray process (Tauri's webview is destroyed, so RAM drops).

### Host agent (inside the same Rust process)
- A WebSocket server on TCP 47999 (TLS, self-signed cert pinned during pairing).
- **Auth:** each paired phone gets a random 256-bit device token at pairing. Every connection must present it. LAN-only unless "access from anywhere" is on.
- **Input:** mouse, keyboard, scroll, media keys and gamepad via `SendInput` and ViGEm. Used by Remote, Media, Slides and Gamepad modes without video.
- **Media info:** title and artist from Windows `GlobalSystemMediaTransportControlsSessionManager`.
- **Clipboard:** watches the Windows clipboard (`AddClipboardFormatListener`) and pushes to and from the phone (text, PNG).
- **Files:** chunked upload and download over the same socket, with progress. Received files go to the configured folder; a toast offers "Show in folder".
- **Power:** lock (`LockWorkStation`), sleep, restart, shut down, sign out.
- **Thumbnails:** a 320 px JPEG of the primary display every 5 s, only while a phone's Home screen is open, so the PC card can show it.
- **Sunshine control:** uses Sunshine's local REST API on 47990 with the hidden credentials. It submits pairing PINs, sets the output display, toggles UPnP for access from anywhere, and reads stream status.

### Lightweight targets
- Release binary under 10 MB (excluding the bundled Sunshine MSI). Idle in the tray under 25 MB RAM and ~0% CPU. The window costs about 60 MB extra only while it's open.
- No polling while idle; event-driven only.

## Pairing (QR, zero typing)

1. The PC window shows a QR code containing `pcremote://pair?h=<LAN IPs>&p=47999&fp=<cert SHA-256>&t=<one-time token, 2 min>`.
2. The phone scans it (CameraX + ML Kit barcode, unbundled model so it isn't added to the APK), connects to the agent over WSS, and verifies the certificate fingerprint matches `fp`.
3. The phone presents the one-time token. The agent replies with a long-lived device token, and the phone generates a Moonlight PIN.
4. The agent submits that PIN to Sunshine's `/api/pin` itself, so Moonlight pairing completes in the background.
5. The phone saves the PC (agent token + Sunshine cert) and lands on Home with the PC online. Total: one scan.

The stage 1 PIN flow stays as a fallback for PCs that run plain Sunshine without the companion.

## Access from anywhere

- Off by default. Turning it on (on the PC) enables Sunshine's UPnP and maps 47999 via UPnP as well. Both apps then show the public address.
- The phone learns the external address the next time it's on the LAN, using Moonlight's existing STUN discovery plus the agent reporting the public IP.
- If UPnP isn't available, the PC app explains how to forward ports manually, or how to use a VPN such as Tailscale (which works with no extra code).
- Deferred: a relay or hole-punching service. That needs a server and is out of scope.

## Feature coverage vs Monect

| Monect category | PC Remote v1 |
|---|---|
| Screen mirroring PC → phone with control | Screen mode (Moonlight/Sunshine, lower latency) |
| Touchpad / mouse | Remote mode + Screen mode trackpad |
| Keyboard with special keys | Keyboard bar (sticky modifiers, F-keys, arrows) |
| Media control | Media mode with now-playing and volume dial |
| Presentation | Slides mode with timer, black screen, gyro laser |
| Gamepad layouts, gyro, Xbox emulation | Gamepad mode + editor (ViGEm Xbox 360) |
| File transfer | Files mode + share sheet |
| Clipboard | Two-way clipboard sync |
| Power controls | Power sheet + Wake-on-LAN |
| Remote access over the internet | Access from anywhere (UPnP / manual / VPN) |
| Virtual displays | **Deferred** (needs a virtual display driver; heavy) |
| Phone as webcam | **Deferred** (Windows 11 virtual camera API; large separate piece) |
| Phone screen → PC projection | **Deferred** |
| Input scripts / macros | **Deferred** (custom shortcut buttons cover common cases) |

## Error handling
- Every network action has a visible state: connecting, connected, retrying, failed with a reason and an action.
- Agent unreachable but Sunshine reachable: Screen mode still works, and other modes show "Update or start PC Remote on your PC".
- Agent reachable but Sunshine down: the PC app restarts the Sunshine service automatically, and the phone shows "Starting screen sharing…".
- A token revoked on the PC tells the phone "This phone was removed. Scan to pair again."
- File transfers resume from the last chunk after a drop.
- Stage 1 behaviour (auto-reconnect, friendly errors, lower-quality offer) is kept.

## Security
- All agent traffic is TLS with a certificate pinned at pairing. No plaintext fallback.
- Device tokens are stored in Android Keystore-encrypted prefs, and on Windows with DPAPI.
- Pairing tokens are one-time, last 2 minutes, and are only shown in the PC window.
- Power and file actions need a paired token. File writes are confined to the chosen folder, with path traversal rejected.
- Sunshine's admin credentials are random, stored with DPAPI, and never shown.

## Testing
- **Android:** JVM unit tests for all new pure logic (protocol encode/decode, pairing URI parsing, gesture mapping, slide timer, gamepad layout model, file-chunk resume). Compose UI smoke tests on the emulator for each screen.
- **Host:** Rust unit tests for protocol, auth, path confinement and the QR payload; an integration test that runs the agent locally and drives it with a test client.
- **End to end (manual, on the user's phone):** QR pairing, each mode, file transfer both ways, clipboard both ways, power lock.

## Repos and build
- `SadikTD/pc-remote`: Android app (as today) plus a new `host/` folder for the Tauri app, in one repo with shared protocol docs.
- GitHub Releases ship `PC-Remote-<ver>-arm64.apk`, `PC-Remote-<ver>-armv7.apk` and `PC-Remote-Host-<ver>-setup.exe`.
- The bundled Sunshine MSI comes from `SadikTD/Sunshine` releases.

## Build order (parallel tracks once the shared protocol is fixed)
1. **Shared foundation:** the agent protocol spec (`docs/protocol.md`) and the Midnight design tokens.
2. **Track A, Android UI:** design system, onboarding, Home, the Screen-mode restyle, Settings, lightweight pass.
3. **Track B, PC Host:** Tauri app, installer with Sunshine bundling, agent core (auth, input, power), QR pairing.
4. **Track C, features:** Remote, Media, Slides, Gamepad, Files, Clipboard, Access from anywhere. These are built on both sides once A and B have their foundations.

Each track gets its own implementation plan.

# v1 Track C: Features (both sides), Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. Every task is TDD on both sides: Kotlin tests in `app/src/test/java/com/pcremote/`, Rust tests via `cargo test` in `host/src-tauri`.

**Goal:** Remote, Media, Slides, Gamepad, Files, Clipboard, Power, thumbnails and access from anywhere, end to end.

**Prerequisites:** Track A tasks A1–A7 (design system, agent client, store, navigation, Home) and Track B tasks B1–B7 (agent server, input) are merged into `main`. Start Track C only then.

**Spec:** `docs/superpowers/specs/2026-10-15-pc-remote-v1-design.md`. **Protocol:** `docs/protocol.md` (the source of truth for every message).

## Global Constraints
- Same constraints as Tracks A and B (Midnight tokens only, strings in `strings.xml`, DPAPI for secrets, path confinement, no idle polling).
- Each feature is gated by the `caps` in `welcome`. The phone hides modes the host doesn't advertise.
- Phone modes keep the screen on and use `FLAG_KEEP_SCREEN_ON` only while open.

## Review Focus
1. **Phone locks or the app goes to the background mid-file-transfer** → the transfer resumes from the last chunk when reopened. Tests: `FileSenderTest.resumesFromServerOffset` (phone), `files::tests::resume_appends_to_part` (host).
2. **Clipboard echo loop** (the phone sets the PC clipboard → the PC reports a change → the phone sets it again …) → never loops. Tests: `ClipSyncTest.noEcho`, `clip::tests::ignores_own_write`.
3. **Volume keys in Slides/Media mode while the screen is off** → still work (a media session), and stop as soon as the mode closes. Emulator check, plus `VolumeKeyRouterTest`.
4. **Gyro on a phone without a gyroscope** → gyro layouts are hidden or disabled, with a note. Test: `PadLayoutsTest.gyroHiddenWithoutSensor`.
5. **UPnP unavailable on the router** → "access from anywhere" shows manual steps instead of failing silently. Test: `net::tests::upnp_unavailable_reports_manual`.

---

### Task C1: Remote mode (no video)
- **Phone:** `com/pcremote/modes/remote/{RemoteScreen.kt, TrackpadModel.kt}`.
  - A full-screen trackpad with a subtle dot grid. Gestures: 1-finger move, tap = left, 2-finger tap = right, 2-finger drag = scroll, 3-finger tap = middle, tap-hold-drag = drag.
  - A right-edge scroll strip, an optional left/right button bar, the keyboard bar (reused from A8), and pointer acceleration using the Settings pointer speed.
  - Sends `mouse.*`, `key` and `text`.
- **Tests (`TrackpadModelTest`):** gesture → message sequences for each gesture; acceleration curve is monotonic, and 1× at slow speeds; natural scrolling inverts.
- **Host:** already handled by B7.
- [ ] RED → GREEN → commit `feat(remote): no-video trackpad mode`

### Task C2: Media
- **Host:** `src/media.rs`.
  - Now-playing via `GlobalSystemMediaTransportControlsSessionManager` (windows-rs `Media::Control`), subscribing to `CurrentSessionChanged`/`MediaPropertiesChanged`/`PlaybackInfoChanged`, and pushing `media` only while subscribed.
  - Volume and mute via `IAudioEndpointVolume`, with change notifications (`IAudioEndpointVolumeCallback`).
  - `media.cmd`: play/pause/next/prev use the session first and fall back to media virtual keys.
  - **Tests:** command parsing; `vol_set` is clamped to 0..100; the `MediaState` diff only pushes on change.
- **Phone:** `modes/media/MediaScreen.kt`.
  - A big glass now-playing card (title, artist, app), play/pause/prev/next, a circular volume dial (rotate gesture, haptic detents every 5%), mute.
  - Volume keys are routed to the PC while open (`VolumeKeyRouter`).
  - **Tests (`MediaModelTest`, `VolumeKeyRouterTest`):** the dial angle maps to 0..100 with clamping; a volume key sends `vol_up`/`vol_down` only while active.
- [ ] RED → GREEN → commit `feat(media): now-playing and volume control`

### Task C3: Slides (presenter)
- **Phone:** `modes/slides/{SlidesScreen.kt, SlidesModel.kt}`.
  - Two huge zones (Previous ⅓, Next ⅔); volume keys work as a clicker; an elapsed timer with haptic pulses at the target −5 min and −1 min (the target is set by tapping the timer).
  - A black-screen toggle (sends `key` B), and a laser mode: while the button is held, gyro deltas become `mouse.move` (with sensitivity from Settings).
- **Tests (`SlidesModelTest`):** next/prev keys are Right/Left (`vk` 0x27/0x25); haptic warnings fire once at each threshold; the gyro→mouse mapping ignores a deadzone under 0.02 rad/s.
- [ ] RED → GREEN → commit `feat(slides): presenter mode with timer and gyro pointer`

### Task C4: Gamepad
- **Host:** `src/pad.rs`.
  - A ViGEm client (the `vigem-client` crate) creates one Xbox 360 target per pad id on first use, updates it from `pad`, and removes it on `pad.off` or 5 s after disconnect.
  - Rumble notifications → `rumble`.
  - **Tests:** id 0..3 accepted and others rejected; the lifecycle state machine (create, update, idle-remove) with a fake bus.
- **Phone:** `modes/pad/{PadScreen.kt, PadLayouts.kt, PadEditor.kt, GyroSource.kt}`.
  - Layouts: Xbox, Racing (gyro tilt → left stick X), Shooter (gyro → right stick), Minimal.
  - The editor drags and resizes elements and sets opacity and haptic strength; layouts are saved as JSON in prefs.
  - Pad state is sent at most every 8 ms, and only on change. Rumble → vibrator.
  - When Screen mode is active, it sends through Moonlight's `ControllerHandler` instead (one source of truth: `PadState`).
- **Tests (`PadLayoutsTest`, `PadStateTest`):** XInput button masks are correct; stick values are clamped to int16; `gyroHiddenWithoutSensor`; a layout JSON round-trip; the send throttle merges rapid changes.
- [ ] RED → GREEN → commit `feat(pad): gamepad layouts, editor, gyro, Xbox emulation`

### Task C5: Files
- **Host:** `src/files.rs`.
  - Receive `file.begin` → sanitise the name → `.part` file → `file.ready{offset}` → write chunks at the offset → `file.end` checks SHA-256 → rename → toast ("Show in folder").
  - Send: a "Send to phone" action in the UI (file picker) and an Explorer context-menu verb `Send to phone` (registry, per-user) → `file.offer`.
  - **Tests:** `sanitize_rejects_traversal_and_reserved` (`..\`, `/`, `CON`, `NUL.txt`, trailing dots/spaces); `resume_appends_to_part`; `bad_hash_deletes_part`; a clash makes ` (1)`.
- **Phone:** `modes/files/{FilesScreen.kt, FileSender.kt, FileReceiver.kt}`, and a share-target activity `ShareToPcActivity` (`ACTION_SEND` / `SEND_MULTIPLE`, any MIME).
  - Transfers run in a foreground service with a progress notification. Received files go to `Downloads/PC Remote` via MediaStore. A transfer list shows progress rings.
  - **Tests (`FileSenderTest`):** chunk framing matches the protocol layout; `resumesFromServerOffset`; SHA-256 computed while streaming equals the whole-file hash.
- [ ] RED → GREEN → commit `feat(files): two-way file transfer with resume and share sheet`

### Task C6: Clipboard sync
- **Host:** `src/clip.rs`.
  - `AddClipboardFormatListener` on a hidden message window; reads `CF_UNICODETEXT` / PNG; pushes `clip` while subscribed.
  - Writes from the phone set the clipboard and record a hash, so the resulting change event is ignored.
  - **Tests:** `ignores_own_write` (hash-based); size limits enforced.
- **Phone:** `com/pcremote/clip/ClipSync.kt`.
  - Active while any mode is open, or always while a foreground transfer/remote service is running.
  - Android 10+ only allows clipboard reads in the foreground, so "phone → PC" happens on app resume and through a "Send clipboard" quick action.
  - **Tests (`ClipSyncTest`):** `noEcho`; an image larger than 8 MB isn't sent.
- [ ] RED → GREEN → commit `feat(clip): two-way clipboard sync`

### Task C7: Power sheet
- **Phone:** `home/PowerSheet.kt` from a card long-press and from the mode top bar: Lock, Sleep, Restart, Shut down, Sign out (a confirm for anything except Lock), plus Wake-on-LAN for offline PCs.
- **Test:** `PowerSheetModelTest`: destructive actions require a confirm.
- **Host:** B8.
- [ ] RED → GREEN → commit `feat(power): power sheet`

### Task C8: Live thumbnails
- **Host:** `src/thumb.rs`.
  - Captures the primary display with DXGI Desktop Duplication (`IDXGIOutputDuplication`, single frame) → scale to 320 px wide → JPEG q60 (`image` crate) every 5 s, only while subscribed. Capture resources are released when nobody is subscribed.
  - **Tests:** the scaling keeps the aspect ratio; the subscriber-count state machine starts and stops the timer.
- **Phone:** the Home card cross-fades the newest thumbnail.
- [ ] RED → GREEN → commit `feat(thumb): live PC thumbnails on Home`

### Task C9: Access from anywhere
- **Host:** `src/net.rs`.
  - When enabled: Sunshine `upnp=enabled` (B6 `set_config`), plus an IGD port mapping for 47999 via the `igd-next` crate.
  - Public IP from the IGD external address, or from STUN as a fallback.
  - `net` message; the UI shows a status row, and manual port-forward/VPN steps when UPnP fails.
  - **Tests:** `upnp_unavailable_reports_manual`; the mapping is removed when disabled.
- **Phone:**
  - It stores the `public` address from `net` and tries it after the LAN hosts when off-LAN. The Moonlight remote address is updated through `ComputerManagerService`.
  - A Settings toggle (it sends to the host which config to set).
  - **Test (`HostOrderTest`):** LAN hosts are tried first, then the public address.
- [ ] RED → GREEN → commit `feat(net): access from anywhere`

### Task C10: End-to-end pass
On this PC (with the installed host) and the emulator, with the phone on the same LAN:
- pair by QR;
- Remote, Media, Slides and Gamepad (check with `joy.cpl`);
- files both ways;
- clipboard both ways;
- Lock.

Record pass or fail for each in `docs/superpowers/plans/2026-10-15-v1-e2e-results.md`. Fix anything that fails with a regression test first.

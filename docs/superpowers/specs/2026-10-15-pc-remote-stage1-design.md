# PC Remote — Stage 1 Design

Date: 2026-10-15
Status: Draft, awaiting review

## Goal

An Android app that controls a Windows PC while showing its screen live — like Monect PC Remote, but smoother, ad-free and easier to use. Priority #1: low latency and no stutter.

## Scope

The full product is built in stages; each gets its own spec:

1. **Core (this spec):** discovery, pairing, live screen, touchpad, keyboard, media keys, on home Wi-Fi.
2. Gaming: virtual Xbox controller layouts, gyro steering.
3. Remote access over the internet.
4. Extras: virtual displays, phone-as-webcam, file transfer, input scripts.

Out of scope for stage 1: iOS, internet access, any custom PC-side code.

## Decisions

| Decision | Choice | Why |
|---|---|---|
| Phone platform | Native Android (Kotlin) | Full sensor/haptics access, hardware decoder control |
| PC host | Stock [Sunshine](https://github.com/LizardByte/Sunshine), unmodified | Proven ~10–30 ms LAN latency; already does capture, HW encode, input, virtual gamepad |
| Streaming core on phone | Fork of [moonlight-android](https://github.com/moonlight-stream/moonlight-android) | Battle-tested protocol (`moonlight-common-c`), decoder tuning, pairing, discovery |
| UI | New Kotlin + Jetpack Compose | Where we beat Monect |
| License | GPL-3 (open source) | Required by Moonlight/Sunshine; accepted |

## Architecture

```
[Windows PC]                         [Android phone]
 Sunshine  ──H.264/HEVC video (UDP)──►  moonlight core ─► MediaCodec HW decode ─► SurfaceView
           ◄──input (touch/keys/pad)──  new Kotlin UI (touchpad, keyboard, toolbar)
           ◄──mDNS discovery + PIN pairing──┘
```

- No PC code in stage 1. Media keys are sent as standard virtual-key codes, which Sunshine already forwards.
- Moonlight's streaming core (JNI + Java streaming classes) stays as it is. All new code sits in the `ui/` packages and calls into the existing core.

## Screens

1. **PC list** — auto-discovered PCs (mDNS) plus manual IP entry; one-tap connect; a guided pairing flow showing where to type the PIN in Sunshine's web UI; a Wake-on-LAN button for sleeping PCs.
2. **Stream view** — full-screen live video.
   - *Trackpad mode* (default): relative cursor; tap = left click; two-finger tap = right click; two-finger drag = scroll; double-tap-hold = drag.
   - *Direct touch mode*: absolute pointer at the touched spot.
   - Pinch zooms into the PC image locally; one-finger drag while zoomed pans.
3. **Floating toolbar** (collapsible) — keyboard, media keys (play/pause, prev/next, volume), shortcuts (Win, Alt+Tab, Ctrl+C/V, Esc), mode toggle, live latency/FPS readout, disconnect.
4. **Settings** — presets: *Smooth* (prioritise FPS), *Sharp* (prioritise resolution), *Battery saver*; an advanced section with manual resolution/FPS/bitrate/codec.

## Connection quality

- **Auto setup:** a short bandwidth probe on connect picks resolution, FPS and bitrate.
- **Adaptive bitrate:** on rising packet loss, lower the bitrate first (keep frame rate), then recover. A connection indicator goes green/yellow/red.
- **Low-latency decode:** keep Moonlight's per-SoC MediaCodec low-latency flags; present frames immediately, with no jitter buffer beyond what Moonlight uses.
- **Input priority:** touch events are sent immediately on their own channel.
- **Network hints:** detect a 2.4 GHz connection or a weak signal and show a one-time plain-language tip.

Target: under 40 ms end-to-end at 1080p60 on 5 GHz Wi-Fi.

## Error handling

| Situation | Behaviour |
|---|---|
| Stream drops | Freeze the last frame with a spinner, auto-reconnect 3×, then show "PC unreachable" + Retry |
| PC asleep | Wake-on-LAN, wait, then connect |
| Sunshine not running or not installed | Explain it and link to the setup guide |
| Pairing fails | Step-by-step screen showing where to enter the PIN |
| HEVC decode fails | Fall back to H.264 automatically and remember that per device |

## Project structure

```
PC Remote/
├─ android/                  # fork of moonlight-android
│  ├─ app/src/main/jni/      # moonlight-common-c + decoder (unchanged)
│  ├─ app/.../streaming/     # Moonlight Java streaming core (unchanged)
│  └─ app/.../ui/            # NEW Compose UI: pclist/, stream/, settings/
├─ docs/                     # Sunshine setup guide, specs
└─ LICENSE                   # GPL-3
```

- Build: Android Studio, Gradle, NDK. minSdk 26 (Android 8.0), latest targetSdk.
- New app name, icon and applicationId so it installs alongside Moonlight.
- Track upstream Moonlight as a git remote and merge its fixes periodically.

## Testing

- Unit tests for gesture → input mapping (the new logic): tap, two-finger tap/scroll, drag, pinch/pan, and trackpad vs direct mode.
- Manual checks on real devices using the latency/FPS overlay against the 40 ms target.
- Moonlight's streaming internals are not re-tested.

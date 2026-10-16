# PC Remote v2: Our Own Streaming Engine and Remote Access

Date: 2026-10-16
Status: Draft, awaiting review
Replaces: Sunshine (PC side) and Moonlight's streaming core (phone side) from v1.

## What the user asked for
- Run **one small app** on the PC, silently in the background, without slowing it down.
- **No other people's apps.** No Sunshine; only PC Remote.
- The phone **connects automatically** when it's on the same network.
- Use it **from anywhere**, fast, with no lag or latency.
- Everything must work perfectly, tested on the user's own phone (Xiaomi 24117RK2CC, HyperOS, Android 16, connected over ADB).

## Decisions
| Area | Decision | Why |
|---|---|---|
| Screen capture | DXGI Desktop Duplication (already in the host) | Built into Windows, GPU-resident, lowest latency |
| Colour conversion | D3D11 Video Processor, BGRA→NV12 on the GPU | Zero copy, no CPU load |
| Video encode | Windows Media Foundation hardware encoder (H.264 and HEVC; AV1 where the GPU has it) | Built into Windows for NVIDIA, AMD and Intel GPUs; no SDKs needed |
| Audio | WASAPI loopback capture → Opus 48 kHz, 10 ms frames (libopus linked into the host) | Opus is the low-latency codec (~5 ms algorithmic delay); Android decodes it natively |
| Transport | **Our own UDP protocol** ("PRS"): AES-256-GCM per packet, Reed-Solomon FEC, NACK, loss-driven adaptive bitrate | UDP has no head-of-line blocking; FEC hides loss without retransmit round-trips |
| Control | The existing TLS WebSocket agent (pairing, modes, files, clipboard) | Already built and tested |
| Phone decode | Android MediaCodec in low-latency mode, rendering straight to a SurfaceView; Opus via MediaCodec → AudioTrack (low-latency path) | Hardware decode with no extra libraries |
| LAN discovery | mDNS `_pcremote._udp` advertised by the host; the phone's NsdManager finds paired PCs by id | Auto-connect on the same Wi-Fi, no IP typing |
| Away from home | 1) UPnP/NAT-PMP port map; 2) else UDP hole punching using `stun.cloudflare.com`; 3) else relay through Cloudflare Realtime TURN | Direct whenever possible (lowest latency), relay only as a last resort |
| Rendezvous | A tiny Cloudflare Worker + Durable Object on the user's account: the host keeps one hibernating WebSocket open, and the phone reaches it from anywhere | Works behind any NAT; free tier |
| Gamepad | Keep the ViGEmBus driver, installed silently; without it, Gamepad mode maps to keyboard and mouse | Windows requires a signed kernel driver for virtual controllers; building and signing our own isn't possible without a Microsoft-attested EV certificate |

## PC side: PC Remote Host (one process)
```
DXGI capture ─► GPU NV12 ─► MF HW encoder ─┐
WASAPI loopback ─► Opus ───────────────────┤─► PRS packetizer ─► FEC ─► AES-GCM ─► UDP socket
Input (UDP) ◄─ decrypt ◄───────────────────┘      ▲ bitrate controller ◄── receiver reports (loss, jitter, RTT)
TLS WebSocket agent (control) · mDNS advertiser · Rendezvous client (Worker) · UPnP
```
- **Idle (nobody streaming):** no capture, no encoder, no timers. Target under 5 MB RAM and ~0% CPU.
- **Streaming:** the encoder runs on the GPU. Target under 3% CPU at 1080p60 on a modern PC.
- **Cursor:** sent as its own tiny message (shape plus position) and drawn by the phone, so it moves at network speed rather than video-frame speed.
- **Encoder settings:** low-latency mode, CBR, no B-frames, infinite GOP with IDR on request. Bitrate changes apply instantly, since MF supports changing it mid-stream.
- **Multi-monitor:** a display picker, plus GPU scaling to the stream resolution.
- **Removed:** the Sunshine bundle, the Sunshine credentials, the tray-hiding workarounds and the PIN bridge. The installer shrinks from 39 MB to about 8 MB.

## Phone side
- Screen mode is rebuilt on our receiver: UDP socket → decrypt → FEC → frame assembly → MediaCodec → SurfaceView.
- The touch, trackpad, zoom, keyboard bar and edge panel are kept, and now send input over PRS UDP.
- **All Moonlight code is removed** (`com.limelight.*`, `moonlight-common-c`, the JNI). The expected APK size is about 3 MB.
- **Per-chip low-latency decoder flags:** `KEY_LOW_LATENCY` (Android 11+), plus a small table of vendor keys for Qualcomm, MediaTek and Exynos. Verified on the user's phone.
- **Auto-connect:** on opening the app, or the Home screen, paired PCs found by mDNS connect without any taps. Away from home, the app goes through the rendezvous.

## PRS protocol (sketch; full spec in `docs/stream-protocol.md`)
- **Session setup** over the TLS control channel:
  - phone sends `stream.start{w,h,fps,codecs,maxBitrate}`;
  - host replies `stream.ready{codec,port,key(32 B),sessionId}`.
  - The key only ever travels over TLS.
- **Packet:** a 16 B header (version, type, sessionId, seq, frameId, fragIdx/fragCount, flags), then the AES-256-GCM payload (nonce = sessionId‖seq, the header as AAD). MTU is 1200 B.
- **Types:**
  - video and audio fragments;
  - FEC parity (RS over GF(256), adaptive 5–30%);
  - input events (sent 3× redundant);
  - receiver report (every 100 ms);
  - IDR request;
  - cursor.
- **Adaptive bitrate:** AIMD on loss and RTT trend, from 2 to 80 Mbps. Resolution drops a step when the bitrate floor is hit.

## Remote access ("from anywhere")
1. **Same network:** mDNS, then direct UDP. Typically 2–10 ms network latency.
2. **Away, router allows UPnP:** a direct UDP connection to the mapped port.
3. **Away, no UPnP:** both sides learn their public address from `stun.cloudflare.com` and exchange it through the rendezvous Worker, then hole-punch UDP. This works for most home NATs.
4. **Strict NATs (symmetric or carrier-grade):** both sides relay through Cloudflare TURN, the nearest edge via anycast, over Cloudflare's backbone. It adds roughly 5–20 ms. Short-lived credentials are issued by the Worker.

**Cost:** STUN is free. TURN is free for the first 1,000 GB a month, then $0.05/GB; at about 15 Mbps, that's roughly 150 hours of relayed streaming free per month. Workers and Durable Objects stay within the free tier.

**Privacy:** the Worker only forwards opaque signaling, authenticated by device-token hashes. Media is end-to-end AES-GCM encrypted, so TURN relays ciphertext.

## Error handling
- No route to the PC → "PC is offline or asleep", with a Wake button (LAN only).
- A stalled decoder or a burst of loss → automatic IDR request, then a bitrate step down.
- Lost relay credentials → re-fetched from the Worker transparently.
- Every state the user can see has a plain-language message (the v1 pattern continues).

## Testing
- **Unit:** the PRS packetizer/depacketizer, FEC encode/decode (cross-language test vectors: Rust ↔ Kotlin), AES-GCM framing, the bitrate controller, the jitter/frame assembler, and NAT candidate selection.
- **Integration (host):** the capture → encode → packetize loop to a local test receiver, measuring frame latency.
- **On the user's phone over ADB:** LAN pairing and auto-connect, a Screen stream with measured glass-to-glass latency (timestamp overlay), all modes, a remote path (phone on mobile data) through hole punch and through TURN, and 30-minute stability.
- **Targets:** under 30 ms end to end on LAN at 1080p60; under 60 ms plus network RTT when remote.

## Phases (each shippable)
1. **Engine on LAN:** capture, encode, PRS, phone decoder, audio, auto-discovery. Screen mode runs on our engine.
2. **Robustness:** FEC tuning, adaptive bitrate, input over UDP, cursor channel.
3. **From anywhere:** the Worker rendezvous, STUN hole punching, the TURN fallback, UPnP.
4. **Cleanup:** remove Sunshine from the installer and Moonlight from the app, add a migration (existing pairings keep working), and release v2.0.

## What's needed from the user
- Approval of this design.
- Phase 3 needs a Cloudflare account; the user already has one connected. I'll deploy the Worker and create a Realtime TURN key, which may need a one-time `wrangler login` in the browser.

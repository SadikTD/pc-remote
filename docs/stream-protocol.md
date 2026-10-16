# PRS: PC Remote Stream Protocol v1

The real-time media and input channel between PC Remote Host (`host/`) and the Android app (`app/`). Control (pairing, session setup) stays on the TLS WebSocket agent (`docs/protocol.md`). **Both sides must match this file byte for byte.** Change the doc first, then both sides; `docs/prs-vectors.json` holds the shared test vectors.

All integers are big-endian.

## Session setup (over the TLS agent)

```json
→ {"t":"stream.start","w":1920,"h":1080,"fps":60,"codecs":["hevc","h264"],"maxKbps":40000,"audio":true}
← {"t":"stream.ready","codec":"hevc","w":1920,"h":1080,"fps":60,"port":51234,"session":3735928559,"key":"<base64, 32 bytes>"}
→ {"t":"stream.stop"}
← {"t":"stream.error","code":"no_encoder|capture_failed|busy","msg":"…"}
← {"t":"cursor.shape","png":"<base64>","hx":0,"hy":0}
```

- `stream.start` field rules:
  - `codecs` lists what the phone can decode, best first, from `"av1"`, `"hevc"` and `"h264"`. The host picks the first one it can encode.
  - `w`/`h` is the size the phone wants. The host scales on the GPU, keeps the aspect ratio of the captured display, and reports the final size.
  - Requesting `w`/`h` as 0 means "the display's native size".
- `stream.ready` field rules:
  - `session` is a random u32.
  - `key` is a fresh random AES-256 key for this session only. It travels over TLS and nowhere else.
  - `port` is the host's UDP port for this session (the agent advertises `stream` in `caps`).
- `cursor.shape` is sent whenever the pointer shape changes: a PNG with a premultiplied-alpha source. `hx`/`hy` is the hotspot in pixels.
- `stream.stop`, or closing the agent connection, ends the session. The host also ends it after 5 s without any packet from the phone.

## UDP packets

```
 0      1      2      4              8              12      14      16
 +------+------+------+--------------+--------------+-------+-------+----------------------+
 |VT    |flags |  rsv |     seq      |   frameId    | frag  | count |  ciphertext + 16B tag |
 +------+------+------+--------------+--------------+-------+-------+----------------------+
```

| Bytes | Field |
|---|---|
| 0 | `VT`: version (high 4 bits, = 1) and type (low 4 bits) |
| 1 | `flags`: for VIDEO/FEC packets, the number of parity shards `m`; 0 otherwise |
| 2–3 | reserved, 0 |
| 4–7 | `seq`: per-direction packet counter starting at 0, incremented for every packet sent |
| 8–11 | `frameId`: video frame number (VIDEO/FEC), audio packet number (AUDIO), otherwise 0 |
| 12–13 | `frag`: shard index (VIDEO/FEC), otherwise 0 |
| 14–15 | `count`: total shards `n = k + m` (VIDEO/FEC), otherwise 0 |

- **Encryption:** AES-256-GCM. The nonce (12 B) is `session` (4 B) ‖ `dir` (1 B: 0 = host→phone, 1 = phone→host) ‖ `00 00 00` ‖ `seq` (4 B). The AAD is the 16-byte header. Packets that fail authentication are dropped silently.
- **Size:** a whole UDP payload is ≤ 1200 bytes, so plaintext is ≤ **1168** bytes.

### Types
| Type | Name | Direction | Plaintext |
|---|---|---|---|
| 1 | VIDEO | host→phone | one data shard (exactly `SHARD` = 1152 bytes, see below) |
| 2 | FEC | host→phone | one parity shard (1152 bytes) |
| 3 | AUDIO | host→phone | `captureUs` u64 ‖ one Opus packet (48 kHz stereo, 10 ms) |
| 4 | INPUT | phone→host | UTF-8 JSON array of input events (see below) |
| 5 | REPORT | phone→host | 16 bytes, see below |
| 6 | IDR | phone→host | empty: "send a keyframe" |
| 7 | CURSOR | host→phone | `x` i16 ‖ `y` i16 (stream pixel coordinates) ‖ `visible` u8 |
| 8 | PROBE | both | `t` u64 (sender's monotonic µs) ‖ `echo` u64 (last `t` received from the peer, or 0) |
| 9 | BYE | both | empty |

### Video frames and FEC
- **Frame payload:** a 16-byte frame header followed by the encoded frame (Annex B, with parameter sets prepended on keyframes):

  | Field | Size | Notes |
  |---|---|---|
  | `len` | u32 | bitstream length |
  | `captureUs` | u64 | host capture time (host clock) |
  | `flags` | u8 | bit 0 = keyframe |
  | `codec` | u8 | 1 = H.264, 2 = HEVC, 3 = AV1 |
  | reserved | u16 | 0 |

- **Shards:** the payload is split into `k = ceil((16 + len) / 1152)` shards of 1152 bytes, with the last zero-padded.
- **Parity:** `m` parity shards are computed with **Reed-Solomon over GF(2⁸)**, identical to Backblaze JavaReedSolomon and the Rust `reed-solomon-erasure` crate's `galois_8::ReedSolomon`:
  - polynomial `0x11D`, generator 2;
  - systematic encoding matrix = Vandermonde(n×k) × inverse(top k×k).
- **Shard limits:** `n = k + m ≤ 255`, and `m = ceil(k × fecPercent / 100)` with `fecPercent` between 5 and 50 (default 15).
- **Recovery:** any `k` of the `n` shards rebuild the frame. If a frame can't be rebuilt within 50 ms of its first shard (or a later frame completes first), the phone drops it and keeps decoding.
- **Keyframe on loss:** after a dropped frame the phone sends IDR, at most once per 250 ms, and the host answers with a keyframe. There are no periodic keyframes.

### Audio
- One Opus packet per datagram, with no FEC (Opus packet loss concealment covers gaps).
- The phone plays packets as they arrive, with a 20–60 ms adaptive buffer.

### Input (phone→host)
```json
[{"i":1041,"t":"mouse.move","dx":3,"dy":-1},{"i":1042,"t":"mouse.button","b":"left","down":true}]
```
- Events use the same shapes as the agent input messages in `docs/protocol.md` (`mouse.*`, `key`, `text`, `pad`), plus `"i"`: a per-session increasing event id.
- Every packet carries the newest event plus the previous two (so each event is sent 3×), and the host applies each `i` only once. `mouse.move` is relative, so duplicates must never be re-applied.

### REPORT (phone→host, every 100 ms)
| Field | Size | Meaning |
|---|---|---|
| `lastFrame` | u32 | newest frame decoded |
| `lossPermille` | u16 | packets lost over the last report period, ×1000 |
| `jitterMs` | u16 | inter-arrival jitter |
| `decodeMs` | u16 | average decode time |
| `rttMs` | u16 | from PROBE echo |
| reserved | u32 | 0 |

### PROBE and connection setup
- The phone sends PROBE every 200 ms from the socket it will receive on, until it gets the first VIDEO packet, then once a second as keepalive.
- The host learns the phone's address (and NAT mapping) from the first authenticated PROBE, and only sends media to that address. A new authenticated PROBE from a different address moves the session to that address (network change).
- RTT is measured from the `echo` field.

### Adaptive bitrate (host)
- Loss above 2% over 300 ms → bitrate × 0.75, and FEC up to 25%.
- Loss below 0.5% and stable RTT for 2 s → bitrate × 1.08, up to `maxKbps`.
- RTT trend more than 30 ms above the session minimum → treated as congestion (× 0.85).
- Floor 2 Mbps. At the floor for 3 s, the stream steps down one resolution notch (1080 → 900 → 720 → 540) and tells the phone with a new `stream.ready`, keeping the same `session`, `port` and `key`.

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
  - `port` is the host's UDP port for this session: 47998 when free (the port UPnP maps for access from anywhere), otherwise any. The agent advertises `stream` in `caps`.
- `cursor.shape` is sent whenever the pointer shape changes: a PNG with a premultiplied-alpha source. `hx`/`hy` is the hotspot in pixels.
- Displays:
  ```json
  ← {"t":"stream.displays","list":[{"id":"\\\\.\\DISPLAY1","name":"Display 1 (1920×1080)","primary":true}],"current":"\\\\.\\DISPLAY1"}
  → {"t":"stream.display","id":"\\\\.\\DISPLAY2"}
  ```
  - `stream.start` may carry `display` (an `id`; absent or empty = the display set on the PC).
  - The host sends `stream.displays` once streaming starts and after every switch.
  - `stream.display` switches the running stream: same `session`, `port`, `key` and codec, a new `stream.ready` with the new size, then a keyframe. The PC's pointer moves to the centre of the new display. `mouse.pos` maps onto the streamed display.
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
| 1 | `flags`: for VIDEO/FEC packets, the frame's FEC percentage `p` (5–50); 0 otherwise |
| 2–3 | `k`: for VIDEO/FEC packets, the number of data shards in the whole frame; 0 otherwise |
| 4–7 | `seq`: per-direction packet counter starting at 0, incremented for every packet sent |
| 8–11 | `frameId`: video frame number (VIDEO/FEC), audio packet number (AUDIO), otherwise 0 |
| 12–13 | `frag`: shard index within the frame, in layout order (VIDEO/FEC), otherwise 0 |
| 14–15 | `count`: total shards in the frame, data plus parity (VIDEO/FEC), otherwise 0 |

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

- **Shards:** the payload is split into `k = max(1, ceil((16 + len) / 1152))` shards of 1152 bytes, with the last zero-padded.
- **Blocks:** the data shards are grouped into FEC blocks of at most **64** data shards each, in order; only the last block can be smaller. Each block `b` with `k_b` data shards gets `m_b = max(1, ceil(k_b × p / 100))` parity shards.
- **Layout:** block 0 data, block 0 parity, block 1 data, block 1 parity, and so on. `frag` numbers shards in this order, `count` is the grand total, and the header's `k` and `p` let the receiver derive the same layout.
- **Parity:** computed per block with **Reed-Solomon over GF(2⁸)**, identical to Backblaze JavaReedSolomon and the Rust `reed-solomon-erasure` crate's `galois_8::ReedSolomon`:
  - polynomial `0x11D`, generator 2;
  - systematic encoding matrix = Vandermonde(n×k) × inverse(top k×k).
- **FEC percentage:** `p` defaults to 15, and the host adapts it between 5 and 50.
- **Recovery:** any `k_b` of a block's `k_b + m_b` shards rebuild that block. If a frame can't be rebuilt within 50 ms of its first shard (or a later frame completes first), the phone drops it and keeps decoding.
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
- Loss above 2% over 300 ms → FEC up to 25% (50% above 10% loss), and bitrate × 0.75 when above 6 Mbps or when RTT shows queueing. Below 6 Mbps without queueing, loss is treated as random (radio) loss and answered with FEC only.
- Streams start at 12 Mbps (or `maxKbps` if lower).
- Loss below 0.5% and stable RTT for 1 s → bitrate × 1.10, up to `maxKbps`.
- Smoothed RTT (EWMA, 1/8 per report) above the session minimum by more than max(30 ms, half the minimum), for 3 reports in a row → treated as congestion (× 0.85). Single jitter spikes don't count.
- Clean for 4 s with at least 3× the floor → one resolution notch back up.
- Floor 2 Mbps. Still queueing at the floor for 3 s, the stream steps down one resolution notch (1080 → 900 → 720 → 540) and tells the phone with a new `stream.ready`, keeping the same `session`, `port` and `key`.

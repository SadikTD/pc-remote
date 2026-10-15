# PC Remote Agent Protocol v1

The contract between the Android app (`app/`) and PC Remote Host (`host/`). Both sides must match this file exactly; change it first, then both sides.

## Transport

- WebSocket over TLS: `wss://<host>:47999/v1`.
- The host uses a self-signed certificate generated on first run (ECDSA P-256, 10 years). The phone pins its SHA-256 fingerprint (lowercase hex of the DER) from the pairing QR code and rejects any other certificate. There is no CA validation and no plaintext fallback.
- **Text frames:** one JSON object each, with a mandatory string field `t` (the type). Unknown types are ignored. Unknown fields are ignored.
- **Binary frames:** byte 0 is the kind: `1` = file chunk, `2` = thumbnail.
- **Keepalive:** the client sends `{"t":"ping"}` every 10 s, and the host answers `{"t":"pong"}`. Either side closes the connection after 30 s of silence.

## Pairing QR code

```
pcremote://pair?n=<url-encoded PC name>&h=<ip1>,<ip2>&p=47999&fp=<64 hex>&t=<otp>
```

- `h`: the host's LAN IPv4 addresses, best first. The phone tries them in order.
- `t`: a one-time token, 22 chars base64url (128 bits). It's valid for 120 s or one use, and is regenerated when it expires or is used.

## Session start

The first frame from the client must be either `pair` or `hello`. Anything else gets `error{code:"unauthorized"}` and the connection closes.

### Pair (first time)
```json
→ {"t":"pair","otp":"<t from QR>","device":"Pixel 8","pin":"4821"}
← {"t":"paired","token":"<43 chars base64url, 256-bit>","pc":{...PcInfo}}
```
- `pin`: a 4-digit Moonlight PIN the phone generated. Right after sending `pair`, the phone starts Moonlight pairing with this PIN. The host submits it to Sunshine (`POST https://localhost:47990/api/pin {"pin","name"}`), retrying every 500 ms for up to 30 s while Sunshine has no pending pair request.
- `paired` is sent once the device token is stored, and doesn't wait for Moonlight pairing to finish.
- Errors: `bad_otp` (wrong or expired), `pin_timeout` (Sunshine never accepted the PIN).
- After `paired`, the session is authenticated, exactly as if `hello` had succeeded.

### Hello (every later connection)
```json
→ {"t":"hello","v":1,"token":"<device token>","device":"Pixel 8"}
← {"t":"welcome","v":1,"pc":{...PcInfo},"caps":["input","media","clip","files","power","thumb","pad","net"]}
```
- Errors: `unauthorized` (unknown token), `revoked` (the device was removed on the PC). The host closes the connection after either one.

### PcInfo
```json
{"id":"<stable uuid>","name":"DESKTOP-ABC","user":"Sadik","macs":["AA:BB:CC:DD:EE:FF"],"os":"Windows 11 Pro 26300","sunshine":true}
```
`sunshine: false` means Sunshine isn't running. The app then disables Screen mode and shows "Starting screen sharing…".

## Input (cap `input`)

| Message | Meaning |
|---|---|
| `{"t":"mouse.move","dx":-3,"dy":12}` | Relative move in pixels (int, clamped to ±2000). |
| `{"t":"mouse.button","b":"left","down":true}` | `b` is one of `left`, `right`, `middle`, `x1`, `x2`. |
| `{"t":"mouse.scroll","dy":120,"dx":0}` | Wheel units (120 = one notch). Positive `dy` scrolls up. |
| `{"t":"key","vk":91,"down":true}` | Windows virtual-key code. |
| `{"t":"text","s":"héllo"}` | Types Unicode text (`KEYEVENTF_UNICODE`). Max 1000 chars. |

The host releases every key and button that is still held when a connection closes.

## Gamepad (cap `pad`)
```json
→ {"t":"pad","id":0,"btn":4096,"lt":0,"rt":255,"lx":0,"ly":-32768,"rx":1200,"ry":0}
```
- XInput state. `btn` is the XInput button mask; triggers are 0–255; sticks are int16.
- The host creates a virtual Xbox 360 controller (ViGEm) per `id` (0–3) on the first `pad` message, and removes it 5 s after the connection closes.
- `{"t":"pad.off","id":0}` removes it immediately.
- Host → client: `{"t":"rumble","id":0,"lo":0-255,"hi":0-255}`.

## Media (cap `media`)
```json
→ {"t":"media.sub","on":true}
← {"t":"media","title":"…","artist":"…","app":"Spotify","playing":true,"vol":45,"muted":false}
→ {"t":"media.cmd","c":"play_pause"}
```
- `c` is one of `play_pause`, `next`, `prev`, `vol_up`, `vol_down`, `mute`, `vol_set` (with `"v":0-100`).
- While subscribed, the host pushes `media` on every change, and once immediately.
- `title`, `artist` and `app` may be empty strings.

## Clipboard (cap `clip`)
```json
→ {"t":"clip.sub","on":true}
↔ {"t":"clip","mime":"text/plain","data":"…"}
↔ {"t":"clip","mime":"image/png","data":"<base64>"}
```
- Either side sends `clip` when its own clipboard changes (while subscribed). Receiving a `clip` sets the local clipboard without echoing it back.
- Size limits: text 1 MB, image 8 MB.

## Files (cap `files`)

**Phone → PC**
```json
→ {"t":"file.begin","id":"<uuid>","name":"photo.jpg","size":123456}
← {"t":"file.ready","id":"<uuid>","offset":0}
→ binary chunks
→ {"t":"file.end","id":"<uuid>","sha256":"<hex>"}
← {"t":"file.done","id":"<uuid>","path":"C:\\Users\\…\\Downloads\\PC Remote\\photo.jpg"}
```
- `offset > 0` in `file.ready` means a partial `<name>.part` file with the same id exists, and the phone resumes from there.
- `name` is reduced to its base name. Characters invalid on Windows are replaced with `_`. A clash adds ` (1)`, ` (2)` and so on. Paths are confined to the received-files folder.
- Errors: `file.error{id,code:"too_large|disk_full|bad_hash|denied"}`.

**PC → phone:** the host sends `{"t":"file.offer","id","name","size"}`. The phone replies `{"t":"file.accept","id","offset"}` or `{"t":"file.reject","id"}`. Chunks and `file.end` follow in the same format as above, in the other direction.

**Binary chunk layout:** `[0x01][16-byte uuid][8-byte offset, big-endian][data ≤ 256 KiB]`.

## Power (cap `power`)
```json
→ {"t":"power","a":"lock"}
← {"t":"ok","of":"power"}
```
`a` is one of `lock`, `sleep`, `restart`, `shutdown`, `signout`.

## Thumbnail (cap `thumb`)
```json
→ {"t":"thumb.sub","on":true}
```
- While subscribed, the host sends a binary frame `[0x02][JPEG]` with the primary display scaled to 320 px wide, quality 60, every 5 s, plus one immediately.

## Status and network (cap `net`)
```json
← {"t":"status","streaming":true,"to":"Pixel 8"}
→ {"t":"net"}
← {"t":"net","lan":["192.168.0.126"],"public":"203.0.113.7","anywhere":true,"upnp":true}
```
- `status` is pushed whenever Sunshine streaming starts or stops.
- `public` is null when unknown.

## Errors
```json
{"t":"error","code":"<code>","msg":"<human readable>","of":"<request t, optional>"}
```
Codes: `unauthorized`, `revoked`, `bad_otp`, `pin_timeout`, `bad_request`, `unsupported`, `internal`.

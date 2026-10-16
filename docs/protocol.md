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
- `pin`: optional and ignored (v1 used it to pair Moonlight with Sunshine).
- `paired` is sent once the device token is stored, followed by `remote` (docs/remote-protocol.md) when a rendezvous Worker is configured.
- Errors: `bad_otp` (wrong or expired).
- After `paired`, the session is authenticated, exactly as if `hello` had succeeded.

### Hello (every later connection)
```json
→ {"t":"hello","v":1,"token":"<device token>","device":"Pixel 8"}
← {"t":"welcome","v":1,"pc":{...PcInfo},"caps":["input","media","clip","files","power","thumb","pad","net","stream","unlock","fsbrowse"],"locked":false}
```
- `locked`: the PC shows the lock or sign-in screen. Afterwards the host sends `{"t":"lock","locked":bool}` to every connection whenever that changes.
- Errors: `unauthorized` (unknown token), `revoked` (the device was removed on the PC). The host closes the connection after either one.

### PcInfo
```json
{"id":"<stable uuid>","name":"DESKTOP-ABC","user":"Sadik","macs":["AA:BB:CC:DD:EE:FF"],"os":"Windows 11 Pro 26300"}
```

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
- `name` may be a relative path (`Trip/day 1/a.jpg`, `/` or `\` separators) to keep a folder's layout. Every component is sanitised: characters invalid on Windows become `_`, reserved names get a `_` prefix, and `.`, `..`, empty parts and drive/UNC prefixes are dropped, so paths are confined to the received-files folder. A clash adds ` (1)`, ` (2)` and so on.
- `{"t":"file.cancel","id"}` (either direction's id) stops a transfer; the host deletes the partial file.
- Errors: `file.error{id,code:"too_large|disk_full|bad_hash|denied"}`.

**PC → phone:** the host sends `{"t":"file.offer","id","name","size"}`. The phone replies `{"t":"file.accept","id","offset"}` or `{"t":"file.reject","id"}`. Chunks and `file.end` follow in the same format as above, in the other direction.

**Browsing the PC (cap `fsbrowse`, only while the PC allows files):**
```json
→ {"t":"fs.list","path":""}
← {"t":"fs.list","path":"","entries":[{"name":"Documents","path":"C:\\Users\\Sadik\\Documents","dir":true,"size":0,"mtime":0}, …]}
→ {"t":"fs.get","path":"C:\\Users\\Sadik\\Pictures\\Trip"}
← {"t":"file.offer","id","name":"Trip/a.jpg","size"} (one per file, at most 5000)
```
- `path: ""` lists the user's folders and the drives. Hidden and system entries are left out; folders come first.
- Offers from `fs.get` use relative names like the uploads above.

**Binary chunk layout:** `[0x01][16-byte uuid][8-byte offset, big-endian][data ≤ 256 KiB]`.

## Unlock (cap `unlock`)
```json
→ {"t":"unlock","secret":"<PIN or password>"}
← {"t":"unlock.result","ok":true}
```
- The host wakes the display, lifts the lock curtain, types the secret (digits as number keys, the rest as Unicode) and presses Enter if still locked. `ok` says whether the PC is unlocked a few seconds later.
- The phone keeps the secret in a biometric-protected Android Keystore key and sends it only after a fingerprint check. The host never stores or logs it.

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
- `status` is reserved (v1 pushed it while Sunshine streamed); v2 hosts don't send it.
- `public` is null when unknown.

## Streaming (cap `stream`)
Session setup for the real-time media channel: `stream.start`, `stream.ready`, `stream.stop`, `stream.error` and `cursor.shape`. See `docs/stream-protocol.md` (PRS over UDP). Absolute pointer input, used by direct-touch streaming, is added:
```json
→ {"t":"mouse.pos","x":0.5,"y":0.25}
```
`x` and `y` are 0..1 fractions of the streamed display.

## LAN discovery
The host advertises mDNS `_pcremote._udp.local`, port 47999, with TXT `id=<PcInfo.id>`, `v=2` and `name=<pc name>`.

## Errors
```json
{"t":"error","code":"<code>","msg":"<human readable>","of":"<request t, optional>"}
```
Codes: `unauthorized`, `revoked`, `bad_otp`, `bad_request`, `unsupported`, `internal`.

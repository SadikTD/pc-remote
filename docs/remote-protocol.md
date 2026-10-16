# Remote access: rendezvous, hole punching and relay

How the phone reaches the PC from outside the home network. The pieces:
- **Rendezvous:** a Cloudflare Worker (`cloud/rendezvous`) that carries the agent's control messages when the PC's TLS port isn't reachable.
- **Hole punching:** STUN-discovered UDP candidates for the PRS stream (`docs/stream-protocol.md`).
- **Relay:** Cloudflare Realtime TURN, used only when punching fails.

**Both sides must match this file.**

## Order of attempts (phone)
1. **LAN:** the mDNS address, then the stored LAN hosts (direct TLS agent on 47999).
2. **Public:** the PC's public host (UPnP-mapped 47999), when known.
3. **Rendezvous:** used when 1 and 2 fail within 4 s. The agent protocol runs unchanged over the Worker.

The PRS stream always tries direct UDP first: candidates plus punching. It falls back to TURN after 2.5 s without an authenticated packet.

## Keys and tickets
- **`relayKey`:** 32 random bytes that the host creates once and keeps in its store.
  - The host registers it with the Worker on its first connection (trust on first use).
  - After registration, every host connection must prove it holds the key.
- **Ticket:** `HMAC-SHA256(relayKey, "ticket:" + pcId + ":" + deviceId)`, 32 bytes.
  - The host gives it to each paired phone over the agent.
  - The Worker recomputes it from `relayKey`, so it stores nothing per phone.
- **Revocation:** the host removing a device sends `{"o":"revoke","dev":"<deviceId>"}` on its socket. The Worker keeps revoked ids per PC.
- All signatures cover a Unix-seconds timestamp `ts`, which must be within ±600 s of the Worker's clock.
- Hex encodes HMACs; base64url without padding encodes keys and tickets.

### Agent message (host → phone, after `welcome`, only when a Worker URL is configured)
```json
{"t":"remote","url":"wss://pcr-rendezvous.example.workers.dev","dev":"<deviceId>","ticket":"<base64url>"}
```
The phone stores it with the paired PC.

## Worker endpoints
| Endpoint | Who | Auth |
|---|---|---|
| `GET /v1/host?pc=<id>&ts=<ts>&sig=<hex>[&register=<base64url relayKey>]` (WebSocket) | host | `sig = HMAC(relayKey, "host:" + pc + ":" + ts)` |
| `GET /v1/phone?pc=<id>&dev=<deviceId>&ts=<ts>&sig=<hex>` (WebSocket) | phone | `sig = HMAC(ticket, "phone:" + pc + ":" + dev + ":" + ts)` |
| `POST /v1/turn?pc=…&dev=…&ts=…&sig=…` | phone | same as `/v1/phone` |
| `POST /v1/turn?role=host&pc=…&ts=…&sig=…` | host | same as `/v1/host` |

- **Unknown PC on `/v1/host`:** with `register`, the Worker stores the key and accepts the connection, after checking the `sig` made with that key. Without `register`, it answers 401.
- **Bad or stale auth:** 401.
- **Revoked device:** 403.
- **`/v1/phone` while the host isn't connected:** the Worker accepts the socket, then closes it at once with code `4404` (reason `pc_offline`).
- **`/v1/turn`:**
  - success returns `{"iceServers":[{"urls":[…],"username":"…","credential":"…"}]}`, generated with a 6 h TTL from the Worker secrets `TURN_KEY_ID`/`TURN_KEY_TOKEN`;
  - 503 if those secrets aren't set.

## Framing
- **Phone ↔ Worker:** plain agent frames, exactly as on the PC's `/v1` socket (text JSON, binary). The phone's agent client doesn't change; only its socket comes from the Worker.
- **Host ↔ Worker:**
  - text: JSON envelopes;
  - binary: `cid` (u32 big-endian) ‖ the phone's binary frame.

| Envelope | Direction | Meaning |
|---|---|---|
| `{"c":7,"o":"open"}` | W→H | phone connection 7 opened |
| `{"c":7,"o":"close"}` | both | connection 7 closed (from the host: disconnect that phone) |
| `{"c":7,"m":"<agent text frame>"}` | both | one text frame |
| `{"o":"revoke","dev":"<id>"}` | H→W | refuse this device from now on |

- **Liveness:**
  - The host sends the text frame `ping` every 60 s, and the Worker auto-answers `pong` (hibernation auto-response, no wake-up).
  - The host reconnects with backoff (1, 2, 5, 10, 30 s) whenever its socket drops.
  - A new host socket replaces the old one, and the Worker closes every phone connection of the old one.
- **Trust:** the agent's own `hello`/token auth runs inside the relay as usual. The Worker sees the agent messages (it's the user's own Cloudflare account).

## Stream candidates (additions to `docs/stream-protocol.md`)
- **`stream.start`** gains `"cands":["ip:port",…]`: the phone's own PRS socket addresses (local, plus STUN-reflexive).
- **`stream.ready`** gains `"cands":["ip:port",…]`: the host's PRS socket addresses, in this order:
  - LAN addresses;
  - the UPnP public address with port 47998, when mapped;
  - the STUN-reflexive address.
- **`stream.cand`** (phone → host over the agent) adds one candidate later: `{"t":"stream.cand","c":"ip:port"}`. Its main use is the phone's TURN relayed address.
- **Host punching:** until the first authenticated PROBE arrives, the host sends a PROBE to every phone candidate every 100 ms for 10 s. A new `stream.cand` restarts that window.
- **Phone:**
  - sends PROBE to every host candidate, and to the agent's host address with the `port` from `stream.ready`, every 100 ms;
  - accepts authenticated packets from any of those addresses;
  - after the first one, sends only to that address (later PROBEs still follow address changes, as in the stream protocol).

## STUN (both sides)
- **Server:** `stun.cloudflare.com:3478`. The request is sent from the PRS socket itself.
- **Request:** an RFC 5389 Binding request (type `0x0001`), magic cookie `0x2112A442`, random 96-bit transaction id.
- **Response:** read `XOR-MAPPED-ADDRESS` (`0x0020`, IPv4 only), falling back to `MAPPED-ADDRESS` (`0x0001`).
- **Retries:** at 0, 250 and 750 ms; give up after 1.5 s.
- **Telling packets apart on the same socket:**

  | First byte | Packet |
  |---|---|
  | `0x00`–`0x03` | STUN/TURN |
  | `0x40`–`0x7F` | TURN ChannelData |
  | `0x1?` | PRS (version 1) |

## TURN (both ends)
Carrier NATs can give the same PC (or phone) a different public IP for different destinations. A TURN permission is per exact IP, so the relay must talk relay-to-relay. Cloudflare routes that internally.
- **Phone:** after 2.5 s without an authenticated packet, the phone allocates (below) and sends `stream.cand` with its relayed address.
- **Host:** on the first `stream.cand` while it has no relay, the host fetches credentials (`/v1/turn?role=host`) and allocates on its stream socket. It then:
  1. permits every phone candidate;
  2. sends `{"t":"stream.cand","c":"<host relayed ip:port>"}` to the phone over the agent;
  3. punches through its relay to the phone's relayed address.
- **Phone, on the host's `stream.cand`:** adds the address as a target and permits it on its relay.
- **Peer addresses:** each side treats packets that arrive through its relay (ChannelData or Data indication) as coming from the peer address inside them, and sends back the same way: ChannelData when a channel is bound, otherwise a Send indication.

### TURN client details
- **Server:** RFC 8656 over UDP to the first `turn:` URL with `transport=udp` from `/v1/turn` (normally `turn.cloudflare.com:3478`). Authentication uses long-term credentials:
  1. a 401 reply brings `REALM` and `NONCE`;
  2. `MESSAGE-INTEGRITY` uses key = `MD5(username ":" realm ":" password)`.
- **Steps:**
  1. Allocate (`REQUESTED-TRANSPORT` = UDP) and read `XOR-RELAYED-ADDRESS`.
  2. CreatePermission for every host candidate IP.
  3. ChannelBind `0x4000` to the chosen host candidate.
  4. Send PRS packets as ChannelData (`0x4000`, len u16, data, padded to 4 bytes).
  5. Refresh the allocation every 5 minutes, and the permission and channel every 4 minutes.
- **Announcing the relay:** the phone sends `stream.cand` with the relayed address. The host punches to it like any other candidate, and the relay forwards that to the phone.

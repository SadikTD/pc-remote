// Signatures from docs/remote-protocol.md: HMAC-SHA256, hex; keys/tickets base64url (no padding).

const enc = new TextEncoder();
export const SKEW_S = 600;

export function b64urlDecode(s: string): Uint8Array | null {
  if (!/^[A-Za-z0-9_-]*$/.test(s)) return null;
  const b = atob(s.replace(/-/g, "+").replace(/_/g, "/") + "===".slice((s.length + 3) % 4));
  return Uint8Array.from(b, (c) => c.charCodeAt(0));
}

export function hex(b: ArrayBuffer | Uint8Array): string {
  return [...new Uint8Array(b)].map((x) => x.toString(16).padStart(2, "0")).join("");
}

export async function hmac(key: Uint8Array, msg: string): Promise<Uint8Array> {
  const k = await crypto.subtle.importKey("raw", key, { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  return new Uint8Array(await crypto.subtle.sign("HMAC", k, enc.encode(msg)));
}

/** Constant-time compare of two hex strings. */
export function sameHex(a: string, b: string): boolean {
  if (a.length !== b.length) return false;
  let d = 0;
  for (let i = 0; i < a.length; i++) d |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return d === 0;
}

export function freshTs(ts: string | null, nowS: number): boolean {
  const t = Number(ts);
  return Number.isInteger(t) && Math.abs(nowS - t) <= SKEW_S;
}

export async function hostSig(relayKey: Uint8Array, pc: string, ts: string): Promise<string> {
  return hex(await hmac(relayKey, `host:${pc}:${ts}`));
}

export async function ticket(relayKey: Uint8Array, pc: string, dev: string): Promise<Uint8Array> {
  return hmac(relayKey, `ticket:${pc}:${dev}`);
}

export async function phoneSig(relayKey: Uint8Array, pc: string, dev: string, ts: string): Promise<string> {
  return hex(await hmac(await ticket(relayKey, pc, dev), `phone:${pc}:${dev}:${ts}`));
}

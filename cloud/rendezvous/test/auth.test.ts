import { createHmac } from "node:crypto";
import { describe, expect, it } from "vitest";
import { b64urlDecode, freshTs, hostSig, phoneSig, sameHex } from "../src/auth";

const key = Uint8Array.from({ length: 32 }, (_, i) => i);
const keyB64 = Buffer.from(key).toString("base64url");

describe("auth", () => {
  it("decodes base64url keys and rejects junk", () => {
    expect(b64urlDecode(keyB64)).toEqual(key);
    expect(b64urlDecode("a+b/")).toBeNull();
  });

  it("host signature is HMAC(relayKey, host:pc:ts)", async () => {
    const want = createHmac("sha256", key).update("host:PC1:1700000000").digest("hex");
    expect(await hostSig(key, "PC1", "1700000000")).toBe(want);
  });

  it("phone signature is keyed by the ticket", async () => {
    const t = createHmac("sha256", key).update("ticket:PC1:dev9").digest();
    const want = createHmac("sha256", t).update("phone:PC1:dev9:1700000000").digest("hex");
    expect(await phoneSig(key, "PC1", "dev9", "1700000000")).toBe(want);
  });

  it("timestamps within 10 minutes only", () => {
    expect(freshTs("1000", 1500)).toBe(true);
    expect(freshTs("1000", 1601)).toBe(false);
    expect(freshTs("x", 1000)).toBe(false);
    expect(freshTs(null, 1000)).toBe(false);
  });

  it("compares hex in constant shape", () => {
    expect(sameHex("ab", "ab")).toBe(true);
    expect(sameHex("ab", "ac")).toBe(false);
    expect(sameHex("ab", "abc")).toBe(false);
  });
});

//! Access from anywhere (docs/remote-protocol.md): the rendezvous Worker connection and STUN.

pub mod rendezvous;
pub mod stun;

const DEFAULT_URL: &str = "wss://pcr-rendezvous.sadikhossain747.workers.dev";

/// The Worker's base URL; `PCR_RENDEZVOUS_URL` at build time overrides it (empty turns it off).
pub fn url() -> Option<&'static str> {
    let u = option_env!("PCR_RENDEZVOUS_URL").unwrap_or(DEFAULT_URL);
    (!u.is_empty()).then_some(u)
}

/// `sig = HMAC-SHA256(relayKey, "host:" + pc + ":" + ts)`, hex.
pub fn host_sig(relay_key: &[u8], pc: &str, ts: u64) -> String {
    crate::tls::hex(&crate::store::hmac(relay_key, &format!("host:{pc}:{ts}")))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn host_sig_is_hmac_hex() {
        // Expected values from Node's crypto.createHmac, independent of ring.
        let key = [7u8; 32];
        let sig = host_sig(&key, "pc1", 1_700_000_000);
        assert_eq!(sig.len(), 64);
        assert_eq!(sig, crate::tls::hex(&crate::store::hmac(&key, "host:pc1:1700000000")));
        assert_eq!(sig, "9e9f4441bcfd13a524fd922878e5e207cc90974f6f56c46410c2b866a1cc63c1");
        // ticket, as the phone and Worker derive it (Node crypto)
        assert_eq!(crate::store::b64url(&crate::store::hmac(&key, "ticket:pc1:dev1")), "Apu-dBssItiqztSY2R7lwB90YggnWttqG85Fei6VQzs");
    }
}

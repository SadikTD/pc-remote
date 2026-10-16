//! Access from anywhere (docs/remote-protocol.md): the rendezvous Worker connection and STUN.

pub mod rendezvous;
pub mod stun;
pub mod turn;

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

/// Fetches this host's TURN credentials from the Worker (`/v1/turn?role=host`) and returns the
/// first UDP TURN server. Blocking; call off the async runtime's worker threads.
pub fn turn_server(store: &std::sync::Mutex<crate::store::Store>) -> Option<(std::net::SocketAddr, String, String)> {
    use std::net::ToSocketAddrs;
    let base = url()?.replacen("wss://", "https://", 1).replacen("ws://", "http://", 1);
    let (key, pc) = {
        let s = store.lock().unwrap();
        (s.relay_key(), s.pc_id().to_string())
    };
    let ts = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).ok()?.as_secs();
    let url = format!("{base}/v1/turn?role=host&pc={pc}&ts={ts}&sig={}", host_sig(&key, &pc, ts));
    let body: serde_json::Value = tokio::runtime::Builder::new_current_thread().enable_all().build().ok()?.block_on(async {
        let r = match reqwest::Client::new().post(&url).timeout(std::time::Duration::from_secs(5)).send().await {
            Ok(r) => r,
            Err(e) => {
                crate::log::line(&format!("turn: credentials request failed: {e:?}"));
                return None;
            }
        };
        if !r.status().is_success() {
            crate::log::line(&format!("turn: credentials refused ({})", r.status()));
            return None;
        }
        r.json().await.map_err(|e| crate::log::line(&format!("turn: bad credentials reply: {e}"))).ok()
    })?;
    let ice = &body["iceServers"];
    let servers: Vec<&serde_json::Value> = ice.as_array().map(|a| a.iter().collect()).unwrap_or_else(|| vec![ice]);
    for s in servers {
        let (Some(user), Some(pass)) = (s["username"].as_str(), s["credential"].as_str()) else { continue };
        let urls: Vec<&str> = match &s["urls"] {
            serde_json::Value::Array(a) => a.iter().filter_map(|u| u.as_str()).collect(),
            u => u.as_str().into_iter().collect(),
        };
        for u in urls {
            if !u.starts_with("turn:") || (u.contains("transport=") && !u.contains("transport=udp")) {
                continue;
            }
            let hp = u.trim_start_matches("turn:").split('?').next()?;
            let hp = if hp.contains(':') { hp.to_string() } else { format!("{hp}:3478") };
            if let Some(addr) = hp.to_socket_addrs().ok()?.find(|a| a.is_ipv4()) {
                return Some((addr, user.to_string(), pass.to_string()));
            }
        }
    }
    crate::log::line("turn: no UDP TURN server in the reply");
    None
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

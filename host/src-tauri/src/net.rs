//! Access from anywhere: a UPnP port mapping for the agent and the public address.
//! (Sunshine maps its own streaming ports when its `upnp` setting is on.)

use crate::agent::BoxFut;
use crate::commands::PORT;
use igd_next::aio::tokio::Tokio;
use igd_next::aio::Gateway;
use igd_next::{PortMappingProtocol, SearchOptions};
use serde::Serialize;
use std::net::{IpAddr, Ipv4Addr, SocketAddr, SocketAddrV4};
use std::time::Duration;

#[derive(Debug, Clone, Default, PartialEq, Serialize)]
pub struct NetStatus {
    /// The agent port is mapped on the router.
    pub upnp: bool,
    pub public: Option<String>,
    /// Access from anywhere is on but UPnP failed: show manual port-forward / VPN steps.
    pub manual: bool,
}

pub trait Router: Sync {
    fn map(&self, port: u16, local: SocketAddrV4) -> BoxFut<Result<(), String>>;
    fn unmap(&self, port: u16) -> BoxFut<Result<(), String>>;
    fn external_ip(&self) -> BoxFut<Option<IpAddr>>;
    /// This PC's address on the router's network.
    fn local_ip(&self) -> Option<Ipv4Addr>;
}

/// Maps (or unmaps) the agent port and reports the outcome.
pub async fn apply<R: Router>(enabled: bool, router: Result<&R, String>) -> NetStatus {
    let manual = NetStatus { manual: true, ..Default::default() };
    match (enabled, router) {
        (false, Ok(r)) => {
            let _ = r.unmap(PORT).await;
            NetStatus::default()
        }
        (false, Err(_)) => NetStatus::default(),
        (true, Err(_)) => manual,
        (true, Ok(r)) => {
            let Some(ip) = r.local_ip() else { return manual };
            if r.map(PORT, SocketAddrV4::new(ip, PORT)).await.is_err() {
                return manual;
            }
            NetStatus { upnp: true, public: r.external_ip().await.map(|i| i.to_string()), manual: false }
        }
    }
}

/// The router found by UPnP discovery.
pub struct Igd(Gateway<Tokio>);

impl Igd {
    pub async fn discover() -> Result<Self, String> {
        let mut opts = SearchOptions::default();
        opts.timeout = Some(Duration::from_secs(3));
        igd_next::aio::tokio::search_gateway(opts).await.map(Igd).map_err(|e| e.to_string())
    }
}

impl Router for Igd {
    fn map(&self, port: u16, local: SocketAddrV4) -> BoxFut<Result<(), String>> {
        let g = self.0.clone();
        Box::pin(async move {
            // Lease 0 = permanent; some routers only accept a finite lease.
            let add = |lease| g.add_port(PortMappingProtocol::TCP, port, SocketAddr::V4(local), lease, "PC Remote");
            match add(0).await {
                Ok(()) => Ok(()),
                Err(_) => add(7 * 24 * 3600).await.map_err(|e| e.to_string()),
            }
        })
    }

    fn unmap(&self, port: u16) -> BoxFut<Result<(), String>> {
        let g = self.0.clone();
        Box::pin(async move { g.remove_port(PortMappingProtocol::TCP, port).await.map_err(|e| e.to_string()) })
    }

    fn external_ip(&self) -> BoxFut<Option<IpAddr>> {
        let g = self.0.clone();
        Box::pin(async move { g.get_external_ip().await.ok() })
    }

    fn local_ip(&self) -> Option<Ipv4Addr> {
        // The source address the OS would use to reach the router.
        let s = std::net::UdpSocket::bind("0.0.0.0:0").ok()?;
        s.connect(self.0.addr).ok()?;
        match s.local_addr().ok()?.ip() {
            IpAddr::V4(ip) => Some(ip),
            IpAddr::V6(_) => None,
        }
    }
}

/// Discovers the router and applies the setting.
pub async fn apply_now(enabled: bool) -> NetStatus {
    if !enabled {
        // Nothing to discover when off unless a mapping may exist; try once, quietly.
        return match Igd::discover().await {
            Ok(r) => apply(false, Ok(&r)).await,
            Err(_) => NetStatus::default(),
        };
    }
    let r = Igd::discover().await;
    apply(true, r.as_ref().map_err(Clone::clone)).await
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Mutex;

    #[derive(Default)]
    struct FakeRouter {
        calls: Mutex<Vec<String>>,
        refuse: bool,
    }

    impl Router for FakeRouter {
        fn map(&self, port: u16, local: SocketAddrV4) -> BoxFut<Result<(), String>> {
            self.calls.lock().unwrap().push(format!("map {port} {local}"));
            let r = if self.refuse { Err("conflict".to_string()) } else { Ok(()) };
            Box::pin(async move { r })
        }
        fn unmap(&self, port: u16) -> BoxFut<Result<(), String>> {
            self.calls.lock().unwrap().push(format!("unmap {port}"));
            Box::pin(async { Ok(()) })
        }
        fn external_ip(&self) -> BoxFut<Option<IpAddr>> {
            Box::pin(async { Some(IpAddr::from([203, 0, 113, 7])) })
        }
        fn local_ip(&self) -> Option<Ipv4Addr> {
            Some(Ipv4Addr::new(192, 168, 0, 126))
        }
    }

    #[tokio::test]
    async fn upnp_unavailable_reports_manual() {
        let s = apply(true, Err::<&FakeRouter, _>("no gateway".into())).await;
        assert_eq!(s, NetStatus { upnp: false, public: None, manual: true });
    }

    #[tokio::test]
    async fn router_refusing_the_mapping_reports_manual() {
        let r = FakeRouter { refuse: true, ..Default::default() };
        let s = apply(true, Ok(&r)).await;
        assert!(s.manual && !s.upnp);
    }

    #[tokio::test]
    async fn maps_agent_port_and_reports_public_ip() {
        let r = FakeRouter::default();
        let s = apply(true, Ok(&r)).await;
        assert_eq!(s, NetStatus { upnp: true, public: Some("203.0.113.7".into()), manual: false });
        assert_eq!(*r.calls.lock().unwrap(), ["map 47999 192.168.0.126:47999"]);
    }

    #[tokio::test]
    async fn mapping_removed_when_disabled() {
        let r = FakeRouter::default();
        let s = apply(false, Ok(&r)).await;
        assert_eq!(s, NetStatus::default());
        assert_eq!(*r.calls.lock().unwrap(), ["unmap 47999"]);
        assert_eq!(apply(false, Err::<&FakeRouter, _>("none".into())).await, NetStatus::default());
    }

    #[tokio::test]
    async fn discovers_this_lan_router_read_only() {
        match Igd::discover().await {
            Ok(r) => eprintln!("router {} local {:?} public {:?}", r.0.addr, r.local_ip(), r.external_ip().await),
            Err(e) => eprintln!("no UPnP router: {e}"),
        }
    }
}

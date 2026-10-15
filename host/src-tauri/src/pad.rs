//! Virtual Xbox 360 controllers (ViGEmBus), one per phone pad id.

use std::sync::Arc;
use std::time::Duration;

/// XInput state from a `pad` message.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct PadState {
    pub btn: u16,
    pub lt: u8,
    pub rt: u8,
    pub lx: i16,
    pub ly: i16,
    pub rx: i16,
    pub ry: i16,
}

/// Rumble from a game: (lo = large motor, hi = small motor).
pub type Rumble = Box<dyn Fn(u8, u8) + Send>;

/// The controller bus; `plug` creates a controller that is unplugged when dropped.
pub trait Bus: Send + Sync {
    fn plug(&self, id: u8, rumble: Rumble) -> Option<Box<dyn Target>>;
}

pub trait Target: Send {
    fn update(&mut self, s: &PadState) -> bool;
}

/// One session's controllers, ids 0..=3.
pub struct Pads {
    bus: Arc<dyn Bus>,
    on_rumble: Arc<dyn Fn(u8, u8, u8) + Send + Sync>,
    targets: [Option<Box<dyn Target>>; 4],
}

impl Pads {
    pub fn new(bus: Arc<dyn Bus>, on_rumble: Box<dyn Fn(u8, u8, u8) + Send + Sync>) -> Self {
        Pads { bus, on_rumble: on_rumble.into(), targets: Default::default() }
    }

    /// Creates the controller on first use, then updates it.
    pub fn pad(&mut self, id: u8, s: &PadState) -> Result<(), &'static str> {
        let slot = self.targets.get_mut(id as usize).ok_or("bad_request")?;
        if slot.is_none() {
            let cb = self.on_rumble.clone();
            *slot = Some(self.bus.plug(id, Box::new(move |lo, hi| cb(id, lo, hi))).ok_or("internal")?);
        }
        if slot.as_mut().is_some_and(|t| t.update(s)) {
            Ok(())
        } else {
            *slot = None; // re-plug on the next message
            Err("internal")
        }
    }

    pub fn off(&mut self, id: u8) {
        if let Some(slot) = self.targets.get_mut(id as usize) {
            *slot = None;
        }
    }

    /// After a disconnect, keeps the controllers for `d` so a game doesn't drop them on a blip.
    pub fn linger(&mut self, d: Duration) {
        let targets = std::mem::take(&mut self.targets);
        if targets.iter().any(Option::is_some) {
            tokio::spawn(async move {
                tokio::time::sleep(d).await;
                drop(targets);
            });
        }
    }
}

/// The real bus: ViGEmBus. None when the driver isn't installed (then `pad` isn't advertised).
pub struct ViGEm(Arc<vigem_client::Client>);

impl ViGEm {
    pub fn connect() -> Option<Self> {
        vigem_client::Client::connect().ok().map(|c| ViGEm(Arc::new(c)))
    }
}

struct X360(vigem_client::Xbox360Wired<Arc<vigem_client::Client>>);

impl Bus for ViGEm {
    fn plug(&self, _id: u8, rumble: Rumble) -> Option<Box<dyn Target>> {
        let mut t = vigem_client::Xbox360Wired::new(self.0.clone(), vigem_client::TargetId::XBOX360_WIRED);
        t.plugin().ok()?;
        t.wait_ready().ok()?;
        // The notification thread ends by itself when the target is unplugged.
        if let Ok(n) = t.request_notification() {
            n.spawn_thread(move |_, x| rumble(x.large_motor, x.small_motor));
        }
        Some(Box::new(X360(t)))
    }
}

impl Target for X360 {
    fn update(&mut self, s: &PadState) -> bool {
        let g = vigem_client::XGamepad {
            buttons: vigem_client::XButtons { raw: s.btn },
            left_trigger: s.lt,
            right_trigger: s.rt,
            thumb_lx: s.lx,
            thumb_ly: s.ly,
            thumb_rx: s.rx,
            thumb_ry: s.ry,
        };
        // Like the official ViGEmClient: only a gone target is a failure. ERROR_NO_MORE_ITEMS (259)
        // just means no XInput reader has taken the previous report yet.
        !matches!(self.0.update(&g), Err(vigem_client::Error::NotPluggedIn | vigem_client::Error::WinError(5)))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::{Arc, Mutex};
    use std::time::Duration;

    #[derive(Default)]
    struct Log(Mutex<Vec<String>>);

    struct FakeBus(Arc<Log>);
    struct FakeTarget(u8, Arc<Log>);

    impl Bus for FakeBus {
        fn plug(&self, id: u8, _rumble: Rumble) -> Option<Box<dyn Target>> {
            self.0 .0.lock().unwrap().push(format!("plug {id}"));
            Some(Box::new(FakeTarget(id, self.0.clone())))
        }
    }
    impl Target for FakeTarget {
        fn update(&mut self, s: &PadState) -> bool {
            self.1 .0.lock().unwrap().push(format!("update {} {}", self.0, s.btn));
            true
        }
    }
    impl Drop for FakeTarget {
        fn drop(&mut self) {
            self.1 .0.lock().unwrap().push(format!("unplug {}", self.0));
        }
    }

    fn st(btn: u16) -> PadState {
        PadState { btn, lt: 0, rt: 0, lx: 0, ly: 0, rx: 0, ry: 0 }
    }

    fn rig() -> (Pads, Arc<Log>) {
        let log = Arc::new(Log::default());
        (Pads::new(Arc::new(FakeBus(log.clone())), Box::new(|_, _, _| {})), log)
    }

    #[test]
    fn ids_0_to_3_only() {
        let (mut p, _) = rig();
        for id in 0..4 {
            assert_eq!(p.pad(id, &st(1)), Ok(()));
        }
        assert_eq!(p.pad(4, &st(1)), Err("bad_request"));
        assert_eq!(p.pad(255, &st(1)), Err("bad_request"));
    }

    #[test]
    fn creates_once_updates_and_off_removes() {
        let (mut p, log) = rig();
        p.pad(1, &st(4096)).unwrap();
        p.pad(1, &st(0)).unwrap();
        p.off(1);
        p.off(1);
        p.pad(1, &st(2)).unwrap();
        assert_eq!(*log.0.lock().unwrap(), ["plug 1", "update 1 4096", "update 1 0", "unplug 1", "plug 1", "update 1 2"]);
    }

    #[tokio::test(start_paused = true)]
    async fn removed_5s_after_disconnect() {
        let (mut p, log) = rig();
        p.pad(0, &st(1)).unwrap();
        p.pad(2, &st(1)).unwrap();
        p.linger(Duration::from_secs(5));
        tokio::time::sleep(Duration::from_millis(4900)).await;
        assert!(!log.0.lock().unwrap().iter().any(|l| l.starts_with("unplug")));
        tokio::time::sleep(Duration::from_millis(200)).await;
        let l = log.0.lock().unwrap();
        assert!(l.contains(&"unplug 0".to_string()) && l.contains(&"unplug 2".to_string()));
    }

    #[test]
    fn real_vigem_plug_update_unplug() {
        let Some(bus) = ViGEm::connect() else {
            eprintln!("ViGEmBus not installed: skipped");
            return;
        };
        let mut t = bus.plug(0, Box::new(|_, _| {})).expect("plug");
        for i in 0..50 {
            assert!(t.update(&st(i)));
        }
        drop(t);
    }
}

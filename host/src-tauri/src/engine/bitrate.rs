//! REPORT-driven adaptive bitrate and FEC (docs/stream-protocol.md, "Adaptive bitrate").

use std::time::Duration;

pub const FLOOR_KBPS: u32 = 2000;
/// Resolution notches the stream steps down through at the floor.
pub const NOTCHES: [u32; 4] = [1080, 900, 720, 540];

const CUT_EVERY: Duration = Duration::from_millis(300);
const RAISE_AFTER: Duration = Duration::from_secs(2);
const STEP_DOWN_AFTER: Duration = Duration::from_secs(3);
/// A notch back up once the link has been clean this long with room above the floor.
const STEP_UP_AFTER: Duration = Duration::from_secs(4);

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Target {
    pub kbps: u32,
    pub fec: u8,
    /// How many resolution notches below the requested size.
    pub notch: u8,
}

pub struct Bitrate {
    t: Target,
    max: u32,
    max_notch: u8,
    /// Loss of the last three reports (100 ms each), newest last.
    loss: Vec<u16>,
    min_rtt: u16,
    /// Smoothed RTT (EWMA, 1/8 per report), ms.
    srtt: f64,
    /// Consecutive reports with the smoothed RTT above the congestion line.
    high_rtt: u8,
    last_cut: Option<Duration>,
    good_since: Option<Duration>,
    floor_since: Option<Duration>,
    clean_since: Option<Duration>,
}

impl Bitrate {
    /// `max_notch`: how many notches below the start height exist (see [notch_size]).
    pub fn new(max_kbps: u32, max_notch: u8) -> Self {
        let max = max_kbps.max(500);
        Bitrate {
            t: Target { kbps: max, fec: 15, notch: 0 },
            max,
            max_notch,
            loss: Vec::new(),
            min_rtt: u16::MAX,
            srtt: 0.0,
            high_rtt: 0,
            last_cut: None,
            good_since: None,
            floor_since: None,
            clean_since: None,
        }
    }

    pub fn target(&self) -> Target {
        self.t
    }

    fn floor(&self) -> u32 {
        FLOOR_KBPS.min(self.max)
    }

    /// Feeds one REPORT (`now` is any monotonic time); returns the new target when it changed.
    pub fn on_report(&mut self, now: Duration, loss_permille: u16, rtt_ms: u16) -> Option<Target> {
        let before = self.t;
        if rtt_ms > 0 {
            self.min_rtt = self.min_rtt.min(rtt_ms);
            self.srtt = if self.srtt == 0.0 { rtt_ms as f64 } else { self.srtt + (rtt_ms as f64 - self.srtt) / 8.0 };
        }
        self.loss.push(loss_permille);
        if self.loss.len() > 3 {
            self.loss.remove(0);
        }
        let avg_loss = self.loss.iter().map(|&l| l as u32).sum::<u32>() / self.loss.len() as u32;
        // Queueing, not jitter: the smoothed RTT stays above the line for 300 ms. The line scales with
        // the path (a 50 ms relay path jitters more than a 3 ms LAN).
        let line = self.min_rtt as f64 + 30f64.max(self.min_rtt as f64 / 2.0);
        self.high_rtt = if self.min_rtt != u16::MAX && self.srtt > line { self.high_rtt.saturating_add(1) } else { 0 };
        let congested = self.high_rtt >= 3;
        let can_cut = self.last_cut.is_none_or(|t| now.saturating_sub(t) >= CUT_EVERY);

        if self.loss.len() == 3 && avg_loss > 20 && can_cut {
            self.cut(now, 0.75);
            self.t.fec = self.t.fec.max(if avg_loss > 250 { 50 } else { 25 });
        } else if congested && can_cut {
            self.cut(now, 0.85);
        } else if loss_permille < 5 && !congested {
            let since = *self.good_since.get_or_insert(now);
            if now.saturating_sub(since) >= RAISE_AFTER {
                self.t.kbps = ((self.t.kbps as f64 * 1.08) as u32).min(self.max);
                self.t.fec = self.t.fec.saturating_sub(5).max(5);
                self.good_since = Some(now);
            }
        } else {
            self.good_since = None;
        }

        if self.t.kbps <= self.floor() {
            let since = *self.floor_since.get_or_insert(now);
            if now.saturating_sub(since) >= STEP_DOWN_AFTER && self.t.notch < self.max_notch {
                self.t.notch += 1;
                self.floor_since = Some(now);
            }
        } else {
            self.floor_since = None;
            // recovered: clean for a while with at least 3x the floor -> a notch back up
            if loss_permille < 5 && !congested {
                let since = *self.clean_since.get_or_insert(now);
                if self.t.notch > 0 && self.t.kbps >= 3 * self.floor() && now.saturating_sub(since) >= STEP_UP_AFTER {
                    self.t.notch -= 1;
                    self.clean_since = Some(now);
                }
            } else {
                self.clean_since = None;
            }
        }
        (self.t != before).then_some(self.t)
    }

    fn cut(&mut self, now: Duration, by: f64) {
        self.t.kbps = ((self.t.kbps as f64 * by) as u32).max(self.floor());
        self.last_cut = Some(now);
        self.good_since = None;
        self.loss.clear();
    }
}

/// Notches strictly below the start height.
pub fn notches_below(h: u32) -> Vec<u32> {
    NOTCHES.into_iter().filter(|&n| n < h).collect()
}

/// Stream size at `notch` for a stream that started at `w`×`h` (even dimensions, same aspect).
pub fn notch_size(w: u32, h: u32, notch: u8) -> (u32, u32) {
    match notch.checked_sub(1).and_then(|i| notches_below(h).get(i as usize).copied()) {
        None => (w, h),
        Some(nh) => (((w as u64 * nh as u64 / h as u64) as u32) & !1, nh),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn ms(v: u64) -> Duration {
        Duration::from_millis(v)
    }

    #[test]
    fn loss_over_300ms_cuts_by_quarter_and_raises_fec() {
        let mut b = Bitrate::new(20000, 3);
        assert_eq!(b.on_report(ms(100), 30, 10), None, "one report is not 300 ms");
        assert_eq!(b.on_report(ms(200), 30, 10), None);
        assert_eq!(b.on_report(ms(300), 30, 10), Some(Target { kbps: 15000, fec: 25, notch: 0 }));
        // the window restarts after a cut
        assert_eq!(b.on_report(ms(400), 30, 10), None);
    }

    #[test]
    fn stable_for_2s_raises_by_8_percent_up_to_max() {
        let mut b = Bitrate::new(20000, 3);
        for i in 1..=3 {
            b.on_report(ms(i * 100), 30, 10);
        }
        assert_eq!(b.target().kbps, 15000);
        let mut t = 400;
        while b.target().kbps == 15000 {
            b.on_report(ms(t), 0, 10);
            t += 100;
        }
        assert_eq!(b.target(), Target { kbps: 16200, fec: 20, notch: 0 });
        assert!(t - 400 >= 2000, "raised after {} ms", t - 400);
        for _ in 0..1000 {
            b.on_report(ms(t), 0, 10);
            t += 100;
        }
        assert_eq!(b.target().kbps, 20000, "never above maxKbps");
        assert_eq!(b.target().fec, 5);
    }

    #[test]
    fn sustained_rtt_rise_is_congestion() {
        let mut b = Bitrate::new(20000, 3);
        b.on_report(ms(100), 0, 10);
        let mut t = 100;
        let mut cut_at = None;
        while cut_at.is_none() && t < 3000 {
            t += 100;
            if b.on_report(ms(t), 0, 200).is_some() {
                cut_at = Some(t);
            }
        }
        assert_eq!(b.target().kbps, 17000);
        assert!(cut_at.unwrap() >= 400, "needs the smoothed RTT high for 300 ms");
    }

    #[test]
    fn mobile_jitter_is_not_congestion() {
        // a 50 ms relay path with spikes to 110 ms every few reports
        let mut b = Bitrate::new(20000, 3);
        for i in 1..=200u64 {
            let rtt = if i % 4 == 0 { 110 } else { 50 };
            b.on_report(ms(i * 100), 0, rtt);
        }
        assert_eq!(b.target().kbps, 20000);
        assert_eq!(b.target().notch, 0);
    }

    #[test]
    fn resolution_steps_back_up_after_recovery() {
        let mut b = Bitrate::new(20000, 3);
        let mut t = 0;
        while b.target().notch == 0 {
            t += 100;
            b.on_report(ms(t), 100, 10);
        }
        assert_eq!(b.target().notch, 1);
        let mut steps = 0;
        while b.target().notch > 0 && steps < 2000 {
            t += 100;
            steps += 1;
            b.on_report(ms(t), 0, 10);
        }
        assert_eq!(b.target().notch, 0, "back to full size once clean");
    }

    #[test]
    fn floor_then_step_down_after_3s() {
        let mut b = Bitrate::new(4000, 3);
        let mut t = 0;
        while b.target().kbps > FLOOR_KBPS {
            t += 100;
            b.on_report(ms(t), 100, 10);
        }
        assert_eq!(b.target().kbps, FLOOR_KBPS);
        let at_floor = t;
        while b.target().notch == 0 {
            t += 100;
            b.on_report(ms(t), 100, 10);
        }
        assert_eq!(t - at_floor, 3000);
        assert_eq!(b.target().kbps, FLOOR_KBPS);
        for _ in 0..200 {
            t += 100;
            b.on_report(ms(t), 100, 10);
        }
        assert_eq!(b.target().notch, 3, "stops at the last notch");
    }

    #[test]
    fn notch_sizes() {
        assert_eq!(notch_size(1920, 1080, 0), (1920, 1080));
        assert_eq!(notch_size(1920, 1080, 1), (1600, 900));
        assert_eq!(notch_size(1920, 1080, 3), (960, 540));
        assert_eq!(notch_size(2560, 1440, 1), (1920, 1080));
        assert_eq!(notch_size(1280, 720, 1), (960, 540));
        assert_eq!(notches_below(720).len(), 1);
    }
}

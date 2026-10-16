//! PRS INPUT packets: each event travels in three packets, so apply each id `i` only once.

use crate::proto::ClientMsg;

#[derive(Default)]
pub struct Dedup {
    last: Option<u64>,
}

impl Dedup {
    /// Events from one INPUT payload that were not applied yet, in `i` order, sanitised.
    pub fn take(&mut self, payload: &[u8]) -> Vec<ClientMsg> {
        let Ok(items) = serde_json::from_slice::<Vec<serde_json::Value>>(payload) else { return Vec::new() };
        let mut evs: Vec<(u64, ClientMsg)> = items
            .into_iter()
            .filter_map(|v| {
                let i = v.get("i")?.as_u64()?;
                Some((i, serde_json::from_value::<ClientMsg>(v).ok()?))
            })
            .filter(|(i, _)| self.last.is_none_or(|l| *i > l))
            .collect();
        evs.sort_by_key(|(i, _)| *i);
        evs.dedup_by_key(|(i, _)| *i);
        if let Some((i, _)) = evs.last() {
            self.last = Some(*i);
        }
        evs.into_iter().map(|(_, m)| m.sanitize()).collect()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn mv(dx: i32) -> ClientMsg {
        ClientMsg::MouseMove { dx, dy: 0 }
    }

    #[test]
    fn dedup_by_event_id() {
        let mut d = Dedup::default();
        let p = |ids: &[u64]| {
            let v: Vec<String> = ids.iter().map(|i| format!(r#"{{"i":{i},"t":"mouse.move","dx":{i},"dy":0}}"#)).collect();
            format!("[{}]", v.join(","))
        };
        assert_eq!(d.take(p(&[1]).as_bytes()), vec![mv(1)]);
        assert_eq!(d.take(p(&[1, 2]).as_bytes()), vec![mv(2)], "repeats are not re-applied");
        assert_eq!(d.take(p(&[3, 1, 2]).as_bytes()), vec![mv(3)]);
        // a packet was lost: the next one still carries the two before it, applied in order
        assert_eq!(d.take(p(&[6, 4, 5]).as_bytes()), vec![mv(4), mv(5), mv(6)]);
        // a late, reordered packet changes nothing
        assert_eq!(d.take(p(&[3, 4, 5]).as_bytes()), vec![]);
        assert_eq!(d.take(b"not json"), vec![]);
        assert_eq!(d.take(br#"[{"t":"mouse.move","dx":1,"dy":0}]"#), vec![], "events need an id");
    }

    #[test]
    fn sanitises_like_the_agent() {
        let mut d = Dedup::default();
        assert_eq!(d.take(br#"[{"i":0,"t":"mouse.move","dx":99999,"dy":-5}]"#), vec![ClientMsg::MouseMove { dx: 2000, dy: -5 }]);
    }
}

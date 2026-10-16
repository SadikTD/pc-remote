//! LAN discovery: advertises `_pcremote._udp.local` (docs/protocol.md) through Windows' own
//! mDNS responder (DnsServiceRegister, Windows 10 1809+). No thread or socket lives in this
//! process, and Windows announces on every interface, following network changes itself.

use windows::core::{HSTRING, PCWSTR};
use windows::Win32::Foundation::DNS_REQUEST_PENDING;
use windows::Win32::NetworkManagement::Dns::*;

pub const PORT: u16 = 47999;
const SERVICE: &str = "_pcremote._udp.local";

pub fn txt(id: &str, name: &str) -> Vec<(&'static str, String)> {
    vec![("id", id.to_string()), ("v", "2".into()), ("name", name.to_string())]
}

/// `<pc name>._pcremote._udp.local`; the instance label can't hold dots and is ≤ 63 bytes.
pub fn instance(name: &str) -> String {
    let mut label: String = name.chars().map(|c| if c == '.' { '-' } else { c }).collect();
    while label.len() > 63 {
        label.pop();
    }
    if label.is_empty() {
        label = "PC".into();
    }
    format!("{label}.{SERVICE}")
}

pub struct Advert {
    /// Leaked on purpose: Windows may still read it while deregistering asynchronously.
    req: &'static DNS_SERVICE_REGISTER_REQUEST,
}

// SAFETY: the request is only touched on register and in Drop; Windows copies what it keeps.
unsafe impl Send for Advert {}
unsafe impl Sync for Advert {}

// ponytail: the instance (a few hundred bytes, once per run) is never freed: Windows doesn't
// say whether the completion callback's pointer is ours, and a double free is worse than a leak.
unsafe extern "system" fn done(_status: u32, _ctx: *const core::ffi::c_void, _inst: *const DNS_SERVICE_INSTANCE) {}

impl Advert {
    /// Starts advertising; None when this Windows has no DNS-SD API or refused.
    pub fn start(id: &str, name: &str) -> Option<Advert> {
        let host = HSTRING::from(format!("{}.local", crate::win::sys::pc_name()));
        let inst_name = HSTRING::from(instance(name));
        let pairs = txt(id, name);
        let keys: Vec<HSTRING> = pairs.iter().map(|(k, _)| HSTRING::from(*k)).collect();
        let vals: Vec<HSTRING> = pairs.iter().map(|(_, v)| HSTRING::from(v.as_str())).collect();
        let (kp, vp): (Vec<PCWSTR>, Vec<PCWSTR>) = keys.iter().zip(&vals).map(|(k, v)| (PCWSTR(k.as_ptr()), PCWSTR(v.as_ptr()))).unzip();
        // SAFETY: the strings outlive the construct call, which copies them; the request is
        // never freed.
        unsafe {
            let inst = DnsServiceConstructInstance(&inst_name, &host, None, None, PORT, 0, 0, kp.len() as u32, kp.as_ptr(), vp.as_ptr());
            if inst.is_null() {
                return None;
            }
            let req = Box::leak(Box::new(DNS_SERVICE_REGISTER_REQUEST {
                Version: DNS_QUERY_REQUEST_VERSION1.0,
                InterfaceIndex: 0,
                pServiceInstance: inst,
                pRegisterCompletionCallback: Some(done),
                pQueryContext: std::ptr::null_mut(),
                hCredentials: Default::default(),
                unicastEnabled: false.into(),
            }));
            let r = DnsServiceRegister(req, None);
            if r != DNS_REQUEST_PENDING as u32 {
                DnsServiceFreeInstance(inst);
                return None;
            }
            Some(Advert { req })
        }
    }
}

impl Drop for Advert {
    fn drop(&mut self) {
        // SAFETY: deregisters the request registered in `start`.
        unsafe {
            DnsServiceDeRegister(self.req, None);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn txt_contents() {
        assert_eq!(txt("abc123", "DESKTOP-1"), vec![("id", "abc123".into()), ("v", "2".into()), ("name", "DESKTOP-1".into())]);
        assert_eq!(PORT, 47999);
    }

    #[test]
    fn instance_names() {
        assert_eq!(instance("DESKTOP-1"), "DESKTOP-1._pcremote._udp.local");
        assert_eq!(instance("my.pc"), "my-pc._pcremote._udp.local");
        assert_eq!(instance(""), "PC._pcremote._udp.local");
        assert_eq!(instance(&"x".repeat(80)).len(), 63 + 1 + SERVICE.len());
    }

    #[test]
    #[ignore = "manual: keeps the advert up for 8 s for an outside mDNS query"]
    fn advertises_for_a_while() {
        let _a = Advert::start("test-id", "PC Remote test").unwrap();
        std::thread::sleep(std::time::Duration::from_secs(8));
    }

    #[test]
    fn registers_on_this_pc() {
        let a = Advert::start("test-id", "PC Remote test");
        assert!(a.is_some(), "DnsServiceRegister refused");
        drop(a);
    }
}

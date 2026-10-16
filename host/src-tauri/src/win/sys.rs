//! Small Windows queries and settings: names, adapters, file pickers, the Explorer verb.

use std::net::Ipv4Addr;
use windows::core::{w, PCWSTR};
use windows::Win32::NetworkManagement::IpHelper::{
    GetAdaptersAddresses, GAA_FLAG_INCLUDE_GATEWAYS, GAA_FLAG_SKIP_ANYCAST, GAA_FLAG_SKIP_DNS_SERVER,
    GAA_FLAG_SKIP_MULTICAST, IP_ADAPTER_ADDRESSES_LH,
};
use windows::Win32::NetworkManagement::Ndis::IfOperStatusUp;
use windows::Win32::Networking::WinSock::{AF_INET, SOCKADDR_IN};
use windows::Win32::System::Registry::{RegGetValueW, RegSetKeyValueW, HKEY_CURRENT_USER, HKEY_LOCAL_MACHINE, REG_SZ, RRF_RT_REG_SZ};
const IF_TYPE_SOFTWARE_LOOPBACK: u32 = 24;

pub fn pc_name() -> String {
    std::env::var("COMPUTERNAME").unwrap_or_else(|_| "PC".into())
}

fn reg_sz(name: PCWSTR) -> Option<String> {
    let mut buf = [0u16; 256];
    let mut len = (buf.len() * 2) as u32;
    // SAFETY: buf/len describe a writable buffer of `len` bytes.
    unsafe {
        RegGetValueW(
            HKEY_LOCAL_MACHINE,
            w!(r"SOFTWARE\Microsoft\Windows NT\CurrentVersion"),
            name,
            RRF_RT_REG_SZ,
            None,
            Some(buf.as_mut_ptr().cast()),
            Some(&mut len),
        )
        .ok()
        .ok()?;
    }
    let n = (len as usize / 2).saturating_sub(1);
    Some(String::from_utf16_lossy(&buf[..n]))
}

/// e.g. "Windows 11 Pro 26300". ProductName still says "Windows 10" on 11, so fix it from the build.
pub fn os_name() -> String {
    let product = reg_sz(w!("ProductName")).unwrap_or_else(|| "Windows".into());
    let build = reg_sz(w!("CurrentBuild")).unwrap_or_default();
    let product = match build.parse::<u32>() {
        Ok(b) if b >= 22000 => product.replace("Windows 10", "Windows 11"),
        _ => product,
    };
    format!("{product} {build}").trim().to_string()
}

/// Up, non-loopback IPv4 addresses (with "has a gateway") and MAC addresses.
pub fn adapters() -> (Vec<(Ipv4Addr, bool)>, Vec<String>) {
    let (mut ips, mut macs) = (vec![], vec![]);
    let flags = GAA_FLAG_INCLUDE_GATEWAYS | GAA_FLAG_SKIP_ANYCAST | GAA_FLAG_SKIP_MULTICAST | GAA_FLAG_SKIP_DNS_SERVER;
    let mut size = 16 * 1024u32;
    let mut buf: Vec<u64> = vec![];
    // SAFETY: the buffer is sized as GetAdaptersAddresses asks and walked only while it lives.
    unsafe {
        for _ in 0..3 {
            buf = vec![0u64; (size as usize).div_ceil(8)];
            let r = GetAdaptersAddresses(AF_INET.0 as u32, flags, None, Some(buf.as_mut_ptr().cast()), &mut size);
            if r == 0 {
                break;
            }
            if r != 111 {
                return (ips, macs); // anything but ERROR_BUFFER_OVERFLOW
            }
        }
        let mut a = buf.as_ptr() as *const IP_ADAPTER_ADDRESSES_LH;
        while !a.is_null() {
            let ad = &*a;
            a = ad.Next;
            if ad.OperStatus != IfOperStatusUp || ad.IfType == IF_TYPE_SOFTWARE_LOOPBACK {
                continue;
            }
            let gw = !ad.FirstGatewayAddress.is_null();
            let mut u = ad.FirstUnicastAddress;
            while !u.is_null() {
                let sa = (*u).Address.lpSockaddr;
                if !sa.is_null() && (*sa).sa_family == AF_INET {
                    let sin = &*(sa as *const SOCKADDR_IN);
                    ips.push((Ipv4Addr::from(sin.sin_addr.S_un.S_addr.to_ne_bytes()), gw));
                }
                u = (*u).Next;
            }
            if ad.PhysicalAddressLength == 6 && gw {
                let m = &ad.PhysicalAddress[..6];
                macs.push(m.iter().map(|b| format!("{b:02X}")).collect::<Vec<_>>().join(":"));
            }
        }
    }
    (ips, macs)
}

/// The system file (or folder) picker. Blocking and modal to `owner`; call on the UI thread.
pub fn pick(owner: Option<windows::Win32::Foundation::HWND>, folder: bool) -> Option<std::path::PathBuf> {
    use windows::Win32::System::Com::{CoCreateInstance, CoTaskMemFree, CLSCTX_INPROC_SERVER};
    use windows::Win32::UI::Shell::{FileOpenDialog, IFileOpenDialog, FOS_PICKFOLDERS, SIGDN_FILESYSPATH};
    // SAFETY: COM calls on the UI thread (already COM-initialised by the window system);
    // the returned path buffer is freed with CoTaskMemFree.
    unsafe {
        let d: IFileOpenDialog = CoCreateInstance(&FileOpenDialog, None, CLSCTX_INPROC_SERVER).ok()?;
        if folder {
            d.SetOptions(d.GetOptions().ok()? | FOS_PICKFOLDERS).ok()?;
        }
        d.Show(owner).ok()?;
        let p = d.GetResult().ok()?.GetDisplayName(SIGDN_FILESYSPATH).ok()?;
        let s = p.to_string().ok();
        CoTaskMemFree(Some(p.0 as _));
        s.map(Into::into)
    }
}

const SEND_VERB: PCWSTR = w!(r"Software\Classes\*\shell\PCRemote.Send");
const SEND_CMD: PCWSTR = w!(r"Software\Classes\*\shell\PCRemote.Send\command");

/// Explorer right-click → "Send to phone" (per user): runs `"exe" --send "%1"`.
pub fn register_send_verb() -> windows::core::Result<()> {
    let exe = std::env::current_exe().map_err(|e| windows::core::Error::new(windows::core::HRESULT(-1), e.to_string()))?;
    let set = |key: PCWSTR, name: PCWSTR, v: String| {
        let w: Vec<u16> = v.encode_utf16().chain([0]).collect();
        // SAFETY: valid NUL-terminated wide strings for the call.
        unsafe { RegSetKeyValueW(HKEY_CURRENT_USER, key, name, REG_SZ.0, Some(w.as_ptr().cast()), (w.len() * 2) as u32).ok() }
    };
    set(SEND_VERB, PCWSTR::null(), "Send to phone".into())?;
    set(SEND_VERB, w!("Icon"), format!("\"{}\"", exe.display()))?;
    set(SEND_CMD, PCWSTR::null(), format!("\"{}\" --send \"%1\"", exe.display()))
}

#[cfg(test)]
mod tests {
    #[test]
    fn os_and_adapters_on_this_pc() {
        let os = super::os_name();
        assert!(os.starts_with("Windows"), "{os}");
        let (ips, macs) = super::adapters();
        assert!(ips.iter().all(|(ip, _)| !ip.is_loopback()));
        assert!(!ips.is_empty() && !macs.is_empty());
        assert!(macs.iter().all(|m| m.len() == 17));
        eprintln!("{os} {ips:?} {macs:?} {:?}", crate::commands::rank_ips(ips.clone()));
    }
}

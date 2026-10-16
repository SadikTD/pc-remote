//! Live thumbnails: the primary display, 320 px wide JPEG, every 5 s while subscribed.

use crate::agent::Outbox;
use std::time::Duration;
use windows::core::Interface;
use windows::Win32::Graphics::Direct3D::D3D_DRIVER_TYPE_UNKNOWN;
use windows::Win32::Graphics::Direct3D11::*;
use windows::Win32::Graphics::Dxgi::*;

const WIDTH: u32 = 320;

pub fn scaled_size(w: u32, h: u32) -> (u32, u32) {
    let h = if w == 0 { 1 } else { ((h as f64 * WIDTH as f64 / w as f64).round() as u32).max(1) };
    (WIDTH, h)
}

/// Box-filters a BGRA image (row `pitch` bytes) down to `tw`×`th` RGB.
pub fn downscale(src: &[u8], w: u32, h: u32, pitch: usize, tw: u32, th: u32) -> Vec<u8> {
    let mut out = Vec::with_capacity((tw * th * 3) as usize);
    for ty in 0..th {
        let (y0, y1) = (ty * h / th, ((ty + 1) * h / th).max(ty * h / th + 1));
        for tx in 0..tw {
            let (x0, x1) = (tx * w / tw, ((tx + 1) * w / tw).max(tx * w / tw + 1));
            let mut sum = [0u32; 3];
            for y in y0..y1.min(h) {
                for x in x0..x1.min(w) {
                    let p = y as usize * pitch + x as usize * 4;
                    sum[0] += src[p + 2] as u32;
                    sum[1] += src[p + 1] as u32;
                    sum[2] += src[p] as u32;
                }
            }
            let n = ((y1.min(h) - y0) * (x1.min(w) - x0)).max(1);
            out.extend(sum.map(|s| (s / n) as u8));
        }
    }
    out
}

pub fn jpeg(rgb: &[u8], w: u32, h: u32) -> Option<Vec<u8>> {
    let mut buf = Vec::new();
    image::codecs::jpeg::JpegEncoder::new_with_quality(&mut buf, 60)
        .encode(rgb, w, h, image::ExtendedColorType::Rgb8)
        .ok()?;
    Some(buf)
}

/// True if every sampled pixel is black (what DXGI returns before a frame has been presented).
fn is_black(bgra: &[u8]) -> bool {
    bgra.as_chunks::<4>().0.iter().step_by(97).all(|p| p[0] < 4 && p[1] < 4 && p[2] < 4)
}

/// One BGRA frame of the primary display via DXGI Desktop Duplication. Every resource
/// is created for this call and released after it, so nothing is held between thumbnails.
fn capture() -> Option<(Vec<u8>, u32, u32, usize)> {
    crate::win::desk::follow();
    // SAFETY: COM/D3D calls with valid out-params; the mapped staging texture is only read
    // between Map and Unmap.
    unsafe {
        let factory: IDXGIFactory1 = CreateDXGIFactory1().ok()?;
        let (adapter, output) = (0..)
            .map_while(|i| factory.EnumAdapters1(i).ok())
            .flat_map(|a| (0..).map_while(move |j| a.EnumOutputs(j).ok().map(|o| (a.clone(), o))))
            .find(|(_, o)| o.GetDesc().is_ok_and(|d| d.DesktopCoordinates.left == 0 && d.DesktopCoordinates.top == 0))?;
        let (mut dev, mut ctx) = (None, None);
        D3D11CreateDevice(&adapter, D3D_DRIVER_TYPE_UNKNOWN, Default::default(), D3D11_CREATE_DEVICE_BGRA_SUPPORT, None, D3D11_SDK_VERSION, Some(&mut dev), None, Some(&mut ctx)).ok()?;
        let (dev, ctx): (ID3D11Device, ID3D11DeviceContext) = (dev?, ctx?);
        let dup = output.cast::<IDXGIOutput1>().ok()?.DuplicateOutput(&dev).ok()?;
        // The first frame after DuplicateOutput is often blank: wait (briefly) for a presented one
        let mut tex: Option<ID3D11Texture2D> = None;
        for attempt in 0..6 {
            let mut info = DXGI_OUTDUPL_FRAME_INFO::default();
            let mut res = None;
            if dup.AcquireNextFrame(if attempt == 0 { 500 } else { 100 }, &mut info, &mut res).is_err() {
                break; // nothing new on a static desktop: let the caller fall back
            }
            if info.LastPresentTime != 0 {
                tex = res.and_then(|r| r.cast().ok());
                break;
            }
            let _ = dup.ReleaseFrame();
        }
        let tex = tex?;
        let mut desc = D3D11_TEXTURE2D_DESC::default();
        tex.GetDesc(&mut desc);
        desc.Usage = D3D11_USAGE_STAGING;
        desc.BindFlags = 0;
        desc.CPUAccessFlags = D3D11_CPU_ACCESS_READ.0 as u32;
        desc.MiscFlags = 0;
        let mut staging = None;
        dev.CreateTexture2D(&desc, None, Some(&mut staging)).ok()?;
        let staging = staging?;
        ctx.CopyResource(&staging, &tex);
        let _ = dup.ReleaseFrame();
        let mut map = D3D11_MAPPED_SUBRESOURCE::default();
        ctx.Map(&staging, 0, D3D11_MAP_READ, 0, Some(&mut map)).ok()?;
        let pitch = map.RowPitch as usize;
        let bytes = std::slice::from_raw_parts(map.pData as *const u8, pitch * desc.Height as usize).to_vec();
        ctx.Unmap(&staging, 0);
        Some((bytes, desc.Width, desc.Height, pitch))
    }
}

/// DXGI first; GDI when DXGI gives nothing or a blank image (static desktop, some drivers).
fn grab() -> Option<(Vec<u8>, u32, u32, usize)> {
    capture().filter(|(b, ..)| !is_black(b)).or_else(capture_gdi)
}

/// Primary display via GDI BitBlt, as top-down BGRA.
fn capture_gdi() -> Option<(Vec<u8>, u32, u32, usize)> {
    use windows::Win32::Graphics::Gdi::*;
    use windows::Win32::UI::WindowsAndMessaging::{GetSystemMetrics, SM_CXSCREEN, SM_CYSCREEN};
    // SAFETY: plain GDI calls on handles created and released here.
    unsafe {
        let (w, h) = (GetSystemMetrics(SM_CXSCREEN), GetSystemMetrics(SM_CYSCREEN));
        if w <= 0 || h <= 0 {
            return None;
        }
        let screen = GetDC(None);
        let mem = CreateCompatibleDC(Some(screen));
        let bmp = CreateCompatibleBitmap(screen, w, h);
        let old = SelectObject(mem, bmp.into());
        let ok = BitBlt(mem, 0, 0, w, h, Some(screen), 0, 0, SRCCOPY | CAPTUREBLT).is_ok();
        let mut bi = BITMAPINFO::default();
        bi.bmiHeader.biSize = std::mem::size_of::<BITMAPINFOHEADER>() as u32;
        bi.bmiHeader.biWidth = w;
        bi.bmiHeader.biHeight = -h; // top-down
        bi.bmiHeader.biPlanes = 1;
        bi.bmiHeader.biBitCount = 32;
        let mut buf = vec![0u8; (w * h * 4) as usize];
        let lines = if ok { GetDIBits(mem, bmp, 0, h as u32, Some(buf.as_mut_ptr().cast()), &mut bi, DIB_RGB_COLORS) } else { 0 };
        SelectObject(mem, old);
        let _ = DeleteObject(bmp.into());
        let _ = DeleteDC(mem);
        ReleaseDC(None, screen);
        (lines == h).then_some((buf, w as u32, h as u32, w as usize * 4))
    }
}

/// A ready-to-send thumbnail frame `[0x02][JPEG]`, or None (e.g. on the lock screen).
pub fn frame() -> Option<Vec<u8>> {
    let (bgra, w, h, pitch) = grab()?;
    let (tw, th) = scaled_size(w, h);
    let mut f = vec![2u8];
    f.extend(jpeg(&downscale(&bgra, w, h, pitch, tw, th), tw, th)?);
    Some(f)
}

/// While subscribed: a thumbnail now, then every 5 s.
pub fn subscribe(out: Outbox) -> tokio::task::JoinHandle<()> {
    tokio::spawn(async move {
        loop {
            if let Ok(Some(f)) = tokio::task::spawn_blocking(frame).await {
                if !out.send_bin(f).await {
                    break;
                }
            }
            tokio::time::sleep(Duration::from_secs(5)).await;
        }
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn scaling_keeps_aspect() {
        assert_eq!(scaled_size(2560, 1440), (320, 180));
        assert_eq!(scaled_size(1920, 1200), (320, 200));
        assert_eq!(scaled_size(1080, 1920), (320, 569));
        assert_eq!(scaled_size(200, 100), (320, 160));
        assert_eq!(scaled_size(0, 0), (320, 1));
    }

    #[test]
    fn downscale_averages_bgra_to_rgb() {
        // 4x2 BGRA, row pitch 20 (4 padding bytes): left half red, right half blue.
        let mut src = vec![0u8; 20 * 2];
        for y in 0..2 {
            for x in 0..4 {
                let p = y * 20 + x * 4;
                src[p..p + 4].copy_from_slice(if x < 2 { &[0, 0, 255, 255] } else { &[255, 0, 0, 255] });
            }
        }
        let rgb = downscale(&src, 4, 2, 20, 2, 1);
        assert_eq!(rgb, vec![255, 0, 0, 0, 0, 255]);
    }

    #[test]
    fn jpeg_has_markers() {
        let rgb = vec![128u8; 320 * 180 * 3];
        let j = jpeg(&rgb, 320, 180).unwrap();
        assert_eq!(&j[..2], &[0xFF, 0xD8]);
        assert_eq!(&j[j.len() - 2..], &[0xFF, 0xD9]);
    }

    #[test]
    fn captures_this_pc_when_unlocked() {
        // The secure desktop (lock screen) can't be duplicated; then there is simply no frame.
        match frame() {
            Some(j) => assert_eq!(&j[..3], &[2, 0xFF, 0xD8]),
            None => eprintln!("no frame (locked or no display)"),
        }
    }

    #[test]
    fn capture_is_not_all_black_when_unlocked() {
        // DXGI hands back an all-black first frame unless we wait for a presented one
        if let Some((bgra, _, _, _)) = grab() {
            assert!(!is_black(&bgra), "captured an all-black frame");
        }
    }
}

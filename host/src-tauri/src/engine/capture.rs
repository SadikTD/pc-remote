//! Desktop capture of the primary display via DXGI Desktop Duplication, re-opened after
//! access-lost (mode change, rotation, UAC/lock screen, full-screen game switching modes).

use super::qpc_to_us;
use std::time::Duration;
use windows::core::Interface;
use windows::Win32::Graphics::Direct3D::D3D_DRIVER_TYPE_UNKNOWN;
use windows::Win32::Graphics::Direct3D11::*;
use windows::Win32::Graphics::Dxgi::*;

/// Lost the duplication: re-open it.
#[derive(Debug)]
pub struct Lost;

pub trait Duplicator {
    type Frame;
    /// `Ok(None)` on timeout (nothing changed).
    fn acquire(&mut self, timeout_ms: u32) -> Result<Option<Self::Frame>, Lost>;
    fn size(&self) -> (u32, u32);
}

/// A duplicator that re-opens itself. `reinit` is set whenever a new duplication was opened
/// (the first one too): the caller rebuilds size-dependent state and sends a keyframe.
pub struct Capture<D> {
    open: Box<dyn FnMut() -> Option<D>>,
    dup: Option<D>,
    pub reinit: bool,
}

impl<D: Duplicator> Capture<D> {
    /// `first`: an already opened duplication, if any.
    pub fn new(first: Option<D>, open: impl FnMut() -> Option<D> + 'static) -> Self {
        Capture { open: Box::new(open), reinit: first.is_some(), dup: first }
    }

    pub fn size(&self) -> Option<(u32, u32)> {
        self.dup.as_ref().map(|d| d.size())
    }

    /// The next changed frame, or None (timeout, or no duplication available right now).
    pub fn next(&mut self, timeout: Duration) -> Option<D::Frame> {
        for _ in 0..2 {
            if self.dup.is_none() {
                self.dup = (self.open)();
                if self.dup.is_none() {
                    // secure desktop or no display: try again later, never in a tight loop
                    std::thread::sleep(timeout.max(Duration::from_millis(200)));
                    return None;
                }
                self.reinit = true;
            }
            match self.dup.as_mut()?.acquire(timeout.as_millis() as u32) {
                Ok(f) => return f,
                Err(Lost) => self.dup = None,
            }
        }
        None
    }
}

pub struct Pointer {
    pub x: i32,
    pub y: i32,
    pub visible: bool,
}

pub struct Shape {
    pub typ: u32,
    pub w: u32,
    pub h: u32,
    pub pitch: u32,
    pub hx: i32,
    pub hy: i32,
    pub data: Vec<u8>,
}

pub struct GpuFrame {
    /// The new desktop image (always the same texture, overwritten on the GPU), or None
    /// when only the pointer moved.
    pub tex: Option<ID3D11Texture2D>,
    pub capture_us: u64,
    pub pointer: Option<Pointer>,
    pub shape: Option<Shape>,
}

/// A display the user can pick in Settings: `id` is the Windows device name (`\\.\DISPLAY2`).
#[derive(Debug, Clone, PartialEq, serde::Serialize)]
pub struct Display {
    pub id: String,
    pub name: String,
    pub primary: bool,
}

fn outputs() -> Vec<(IDXGIAdapter1, IDXGIOutput)> {
    // SAFETY: COM enumeration with valid out-params.
    unsafe {
        let Ok(factory) = CreateDXGIFactory1::<IDXGIFactory1>() else { return vec![] };
        (0..)
            .map_while(|i| factory.EnumAdapters1(i).ok())
            .flat_map(|a| (0..).map_while(move |j| a.EnumOutputs(j).ok().map(|o| (a.clone(), o))))
            .collect()
    }
}

fn describe(o: &IDXGIOutput) -> Option<Display> {
    // SAFETY: GetDesc fills a plain struct.
    let d = unsafe { o.GetDesc().ok()? };
    let n = d.DeviceName.iter().position(|c| *c == 0).unwrap_or(d.DeviceName.len());
    let id = String::from_utf16_lossy(&d.DeviceName[..n]);
    let r = d.DesktopCoordinates;
    let label = id.trim_start_matches(r"\\.\").replace("DISPLAY", "Display ");
    Some(Display { name: format!("{label} ({}×{})", r.right - r.left, r.bottom - r.top), primary: r.left == 0 && r.top == 0, id })
}

/// Every display attached to the desktop.
pub fn displays() -> Vec<Display> {
    outputs().iter().filter_map(|(_, o)| describe(o)).collect()
}

/// The adapter that drives `display` (a device name; empty or unknown = the primary display),
/// and a D3D11 device on it for capture, conversion and encoding.
pub fn device(display: &str) -> Option<(ID3D11Device, ID3D11DeviceContext, IDXGIOutput1, i64)> {
    let all = outputs();
    let pick = |want: &dyn Fn(&Display) -> bool| all.iter().find(|(_, o)| describe(o).is_some_and(|d| want(&d))).cloned();
    let (adapter, output) = pick(&|d| !display.is_empty() && d.id == display).or_else(|| pick(&|d| d.primary))?;
    // SAFETY: COM/D3D calls with valid out-params.
    unsafe {
        let (mut dev, mut ctx) = (None, None);
        D3D11CreateDevice(
            &adapter,
            D3D_DRIVER_TYPE_UNKNOWN,
            Default::default(),
            D3D11_CREATE_DEVICE_BGRA_SUPPORT | D3D11_CREATE_DEVICE_VIDEO_SUPPORT,
            None,
            D3D11_SDK_VERSION,
            Some(&mut dev),
            None,
            Some(&mut ctx),
        )
        .ok()?;
        let (dev, ctx): (ID3D11Device, ID3D11DeviceContext) = (dev?, ctx?);
        // Media Foundation uses the device from its own threads.
        if let Ok(mt) = dev.cast::<ID3D11Multithread>() {
            let _ = mt.SetMultithreadProtected(true);
        }
        let luid = adapter.GetDesc1().ok()?.AdapterLuid;
        let luid = ((luid.HighPart as i64) << 32) | luid.LowPart as i64;
        Some((dev, ctx, output.cast().ok()?, luid))
    }
}

pub struct Dxgi {
    dev: ID3D11Device,
    ctx: ID3D11DeviceContext,
    dup: IDXGIOutputDuplication,
    w: u32,
    h: u32,
    copy: Option<ID3D11Texture2D>,
}

impl Dxgi {
    pub fn open(dev: &ID3D11Device, ctx: &ID3D11DeviceContext, output: &IDXGIOutput1) -> Option<Dxgi> {
        // SAFETY: D3D calls on live objects.
        unsafe {
            let dup = output.DuplicateOutput(dev).ok()?;
            let d = dup.GetDesc();
            Some(Dxgi { dev: dev.clone(), ctx: ctx.clone(), dup, w: d.ModeDesc.Width, h: d.ModeDesc.Height, copy: None })
        }
    }
}

impl Duplicator for Dxgi {
    type Frame = GpuFrame;

    fn size(&self) -> (u32, u32) {
        (self.w, self.h)
    }

    fn acquire(&mut self, timeout_ms: u32) -> Result<Option<GpuFrame>, Lost> {
        // SAFETY: AcquireNextFrame/ReleaseFrame are paired; the shape buffer is sized by DXGI.
        unsafe {
            let mut info = DXGI_OUTDUPL_FRAME_INFO::default();
            let mut res = None;
            if let Err(e) = self.dup.AcquireNextFrame(timeout_ms, &mut info, &mut res) {
                return if e.code() == DXGI_ERROR_WAIT_TIMEOUT { Ok(None) } else { Err(Lost) };
            }
            let mut f = GpuFrame { tex: None, capture_us: super::now_us(), pointer: None, shape: None };
            if info.LastPresentTime != 0 {
                let src: Option<ID3D11Texture2D> = res.and_then(|r| r.cast().ok());
                if let Some(src) = src {
                    let mut desc = D3D11_TEXTURE2D_DESC::default();
                    src.GetDesc(&mut desc);
                    if (desc.Width, desc.Height) != (self.w, self.h) {
                        let _ = self.dup.ReleaseFrame();
                        return Err(Lost); // mode change
                    }
                    if self.copy.is_none() {
                        desc.Usage = D3D11_USAGE_DEFAULT;
                        desc.BindFlags = D3D11_BIND_SHADER_RESOURCE.0 as u32 | D3D11_BIND_RENDER_TARGET.0 as u32;
                        desc.CPUAccessFlags = 0;
                        desc.MiscFlags = 0;
                        let mut t = None;
                        if self.dev.CreateTexture2D(&desc, None, Some(&mut t)).is_err() {
                            let _ = self.dup.ReleaseFrame();
                            return Err(Lost);
                        }
                        self.copy = t;
                    }
                    let copy = self.copy.clone().expect("created above");
                    self.ctx.CopyResource(&copy, &src);
                    f.tex = Some(copy);
                    f.capture_us = qpc_to_us(info.LastPresentTime);
                }
            }
            if info.LastMouseUpdateTime != 0 {
                let p = info.PointerPosition;
                f.pointer = Some(Pointer { x: p.Position.x, y: p.Position.y, visible: p.Visible.as_bool() });
            }
            if info.PointerShapeBufferSize > 0 {
                let mut data = vec![0u8; info.PointerShapeBufferSize as usize];
                let (mut need, mut si) = (0, DXGI_OUTDUPL_POINTER_SHAPE_INFO::default());
                if self.dup.GetFramePointerShape(data.len() as u32, data.as_mut_ptr().cast(), &mut need, &mut si).is_ok() {
                    f.shape = Some(Shape {
                        typ: si.Type,
                        w: si.Width,
                        h: si.Height,
                        pitch: si.Pitch,
                        hx: si.HotSpot.x,
                        hy: si.HotSpot.y,
                        data,
                    });
                }
            }
            let _ = self.dup.ReleaseFrame();
            Ok(Some(f))
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::{AtomicU32, Ordering};
    use std::sync::Arc;

    /// Yields `frames` frames, then reports access lost (a mode change).
    struct Fake {
        size: (u32, u32),
        frames: u32,
    }

    impl Duplicator for Fake {
        type Frame = (u32, u32);
        fn acquire(&mut self, _: u32) -> Result<Option<(u32, u32)>, Lost> {
            if self.frames == 0 {
                return Err(Lost);
            }
            self.frames -= 1;
            Ok(Some(self.size))
        }
        fn size(&self) -> (u32, u32) {
            self.size
        }
    }

    #[test]
    fn reinit_on_mode_change() {
        let opens = Arc::new(AtomicU32::new(0));
        let o = opens.clone();
        let mut cap = Capture::new(None, move || {
            let n = o.fetch_add(1, Ordering::SeqCst);
            // the "display" switches from 1920x1080 to 1280x720 after the first duplication
            Some(Fake { size: if n == 0 { (1920, 1080) } else { (1280, 720) }, frames: 2 })
        });
        assert_eq!(cap.next(Duration::ZERO), Some((1920, 1080)));
        assert!(std::mem::take(&mut cap.reinit), "first open counts as a (re)init");
        assert_eq!(cap.next(Duration::ZERO), Some((1920, 1080)));
        assert!(!cap.reinit);
        // access lost → re-opened in the same call, new size, flagged for a keyframe
        assert_eq!(cap.next(Duration::ZERO), Some((1280, 720)));
        assert!(cap.reinit);
        assert_eq!(cap.size(), Some((1280, 720)));
        assert_eq!(opens.load(Ordering::SeqCst), 2);
    }

    #[test]
    fn no_display_returns_none_and_retries() {
        let opens = Arc::new(AtomicU32::new(0));
        let o = opens.clone();
        let mut cap = Capture::<Fake>::new(None, move || {
            o.fetch_add(1, Ordering::SeqCst);
            None
        });
        assert!(cap.next(Duration::ZERO).is_none());
        assert!(cap.next(Duration::ZERO).is_none());
        assert_eq!(opens.load(Ordering::SeqCst), 2);
    }
}

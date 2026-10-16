//! BGRA desktop → NV12 (BT.709, limited range) with scaling, on the GPU's video processor.

use windows::core::Interface;
use windows::Win32::Graphics::Direct3D11::*;
use windows::Win32::Graphics::Dxgi::Common::*;

/// Output textures in flight: the async encoder may still read one while the next is written.
const RING: usize = 3;

pub struct Convert {
    vdev: ID3D11VideoDevice,
    vctx: ID3D11VideoContext1,
    en: ID3D11VideoProcessorEnumerator,
    vp: ID3D11VideoProcessor,
    out: Vec<(ID3D11Texture2D, ID3D11VideoProcessorOutputView)>,
    next: usize,
    input: Option<(usize, ID3D11VideoProcessorInputView)>,
}

impl Convert {
    pub fn new(dev: &ID3D11Device, ctx: &ID3D11DeviceContext, (iw, ih): (u32, u32), (ow, oh): (u32, u32)) -> windows::core::Result<Convert> {
        // SAFETY: D3D11 video calls on live objects with initialised descriptors.
        unsafe {
            let vdev: ID3D11VideoDevice = dev.cast()?;
            let vctx: ID3D11VideoContext1 = ctx.cast()?;
            let rate = DXGI_RATIONAL { Numerator: 60, Denominator: 1 };
            let cd = D3D11_VIDEO_PROCESSOR_CONTENT_DESC {
                InputFrameFormat: D3D11_VIDEO_FRAME_FORMAT_PROGRESSIVE,
                InputFrameRate: rate,
                InputWidth: iw,
                InputHeight: ih,
                OutputFrameRate: rate,
                OutputWidth: ow,
                OutputHeight: oh,
                Usage: D3D11_VIDEO_USAGE_OPTIMAL_SPEED,
            };
            let en = vdev.CreateVideoProcessorEnumerator(&cd)?;
            let vp = vdev.CreateVideoProcessor(&en, 0)?;
            vctx.VideoProcessorSetStreamAutoProcessingMode(&vp, 0, false);
            vctx.VideoProcessorSetStreamFrameFormat(&vp, 0, D3D11_VIDEO_FRAME_FORMAT_PROGRESSIVE);
            vctx.VideoProcessorSetStreamColorSpace1(&vp, 0, DXGI_COLOR_SPACE_RGB_FULL_G22_NONE_P709);
            vctx.VideoProcessorSetOutputColorSpace1(&vp, DXGI_COLOR_SPACE_YCBCR_STUDIO_G22_LEFT_P709);
            let desc = D3D11_TEXTURE2D_DESC {
                Width: ow,
                Height: oh,
                MipLevels: 1,
                ArraySize: 1,
                Format: DXGI_FORMAT_NV12,
                SampleDesc: DXGI_SAMPLE_DESC { Count: 1, Quality: 0 },
                Usage: D3D11_USAGE_DEFAULT,
                BindFlags: D3D11_BIND_RENDER_TARGET.0 as u32 | D3D11_BIND_VIDEO_ENCODER.0 as u32,
                CPUAccessFlags: 0,
                MiscFlags: 0,
            };
            let mut out = Vec::with_capacity(RING);
            for _ in 0..RING {
                let mut t = None;
                dev.CreateTexture2D(&desc, None, Some(&mut t))?;
                let t = t.expect("texture");
                let vd = D3D11_VIDEO_PROCESSOR_OUTPUT_VIEW_DESC { ViewDimension: D3D11_VPOV_DIMENSION_TEXTURE2D, ..Default::default() };
                let mut v = None;
                vdev.CreateVideoProcessorOutputView(&t, &en, &vd, Some(&mut v))?;
                out.push((t, v.expect("view")));
            }
            Ok(Convert { vdev, vctx, en, vp, out, next: 0, input: None })
        }
    }

    /// Converts `src` (BGRA, the input size) into the next NV12 texture of the ring.
    pub fn convert(&mut self, src: &ID3D11Texture2D) -> windows::core::Result<ID3D11Texture2D> {
        // SAFETY: as above; the stream struct's view reference is released after the Blt.
        unsafe {
            let key = src.as_raw() as usize;
            if self.input.as_ref().is_none_or(|(k, _)| *k != key) {
                let d = D3D11_VIDEO_PROCESSOR_INPUT_VIEW_DESC { ViewDimension: D3D11_VPIV_DIMENSION_TEXTURE2D, ..Default::default() };
                let mut v = None;
                self.vdev.CreateVideoProcessorInputView(src, &self.en, &d, Some(&mut v))?;
                self.input = Some((key, v.expect("view")));
            }
            let (tex, view) = &self.out[self.next];
            self.next = (self.next + 1) % RING;
            let stream = D3D11_VIDEO_PROCESSOR_STREAM {
                Enable: true.into(),
                pInputSurface: std::mem::ManuallyDrop::new(Some(self.input.as_ref().expect("set").1.clone())),
                ..Default::default()
            };
            let mut streams = [stream];
            let r = self.vctx.VideoProcessorBlt(&self.vp, view, 0, &streams);
            std::mem::ManuallyDrop::drop(&mut streams[0].pInputSurface);
            r?;
            Ok(tex.clone())
        }
    }
}

/// The stream size for a `want` request (0×0 = native): fits inside it, keeps the display's
/// aspect ratio, never upscales, even dimensions (NV12).
pub fn fit((dw, dh): (u32, u32), (ww, wh): (u32, u32)) -> (u32, u32) {
    let (ww, wh) = if ww == 0 || wh == 0 { (dw, dh) } else { (ww.min(dw), wh.min(dh)) };
    let s = (ww as f64 / dw as f64).min(wh as f64 / dh as f64);
    let even = |v: f64| ((v.round() as u32) & !1).max(2);
    (even(dw as f64 * s), even(dh as f64 * s))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn fit_keeps_aspect_and_is_even() {
        assert_eq!(fit((2560, 1440), (0, 0)), (2560, 1440));
        assert_eq!(fit((2560, 1440), (1920, 1080)), (1920, 1080));
        assert_eq!(fit((2560, 1600), (1920, 1080)), (1728, 1080));
        assert_eq!(fit((1920, 1080), (2400, 1080)), (1920, 1080), "never upscales");
        assert_eq!(fit((1366, 768), (1280, 720)), (1280, 720));
        assert_eq!(fit((1080, 1920), (1920, 1080)), (608, 1080), "portrait display");
    }

    #[test]
    fn converts_on_this_gpu() {
        let Some((dev, ctx, _, _)) = crate::engine::capture::device() else { return eprintln!("no D3D11 device") };
        // SAFETY: test-only texture creation.
        let src = unsafe {
            let desc = D3D11_TEXTURE2D_DESC {
                Width: 640,
                Height: 360,
                MipLevels: 1,
                ArraySize: 1,
                Format: DXGI_FORMAT_B8G8R8A8_UNORM,
                SampleDesc: DXGI_SAMPLE_DESC { Count: 1, Quality: 0 },
                Usage: D3D11_USAGE_DEFAULT,
                BindFlags: D3D11_BIND_RENDER_TARGET.0 as u32 | D3D11_BIND_SHADER_RESOURCE.0 as u32,
                CPUAccessFlags: 0,
                MiscFlags: 0,
            };
            let mut t = None;
            dev.CreateTexture2D(&desc, None, Some(&mut t)).unwrap();
            t.unwrap()
        };
        let mut c = Convert::new(&dev, &ctx, (640, 360), (320, 180)).unwrap();
        let a = c.convert(&src).unwrap();
        let b = c.convert(&src).unwrap();
        assert_ne!(a.as_raw(), b.as_raw(), "ring of output textures");
        let mut d = D3D11_TEXTURE2D_DESC::default();
        // SAFETY: GetDesc on a live texture.
        unsafe { a.GetDesc(&mut d) };
        assert_eq!((d.Width, d.Height, d.Format), (320, 180, DXGI_FORMAT_NV12));
    }
}

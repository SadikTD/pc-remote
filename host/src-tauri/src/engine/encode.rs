//! Hardware video encoding on a Media Foundation async MFT (the GPU vendor's encoder), fed
//! NV12 D3D11 textures: low-latency, CBR, no B-frames, keyframes only on request.

use crate::prs::frame::Codec;
use std::collections::VecDeque;
use std::mem::ManuallyDrop;
use std::sync::mpsc;
use std::time::{Duration, Instant};
use windows::core::{Interface, GUID};
use windows::Win32::Graphics::Direct3D11::{ID3D11Device, ID3D11Texture2D};
use windows::Win32::Media::MediaFoundation::*;
use windows::Win32::System::Com::CoTaskMemFree;
use windows::Win32::System::Variant::VARIANT;

pub struct Encoded {
    pub data: Vec<u8>,
    pub key: bool,
    pub capture_us: u64,
}

pub fn codec_name(c: Codec) -> &'static str {
    match c {
        Codec::H264 => "h264",
        Codec::Hevc => "hevc",
        Codec::Av1 => "av1",
    }
}

pub fn parse_codec(s: &str) -> Option<Codec> {
    Some(match s {
        "h264" => Codec::H264,
        "hevc" => Codec::Hevc,
        "av1" => Codec::Av1,
        _ => return None,
    })
}

fn subtype(c: Codec) -> GUID {
    match c {
        Codec::H264 => MFVideoFormat_H264,
        Codec::Hevc => MFVideoFormat_HEVC,
        Codec::Av1 => MFVideoFormat_AV1,
    }
}

/// Media Foundation, started for as long as this lives.
struct Mf;

impl Mf {
    fn start() -> windows::core::Result<Mf> {
        // SAFETY: balanced by MFShutdown in Drop.
        unsafe { MFStartup(MF_VERSION, MFSTARTUP_LITE)? };
        Ok(Mf)
    }
}

impl Drop for Mf {
    fn drop(&mut self) {
        // SAFETY: paired with MFStartup.
        unsafe {
            let _ = MFShutdown();
        }
    }
}

/// The first hardware encoder for `codec` on the adapter `luid`.
fn find(codec: Codec, luid: i64) -> Option<IMFTransform> {
    // SAFETY: MFTEnum2 returns a CoTaskMem array of `n` activates, each released by taking it.
    unsafe {
        let mut attrs = None;
        MFCreateAttributes(&mut attrs, 1).ok()?;
        let attrs = attrs?;
        attrs.SetBlob(&MFT_ENUM_ADAPTER_LUID, &luid.to_le_bytes()).ok()?;
        let input = MFT_REGISTER_TYPE_INFO { guidMajorType: MFMediaType_Video, guidSubtype: MFVideoFormat_NV12 };
        let output = MFT_REGISTER_TYPE_INFO { guidMajorType: MFMediaType_Video, guidSubtype: subtype(codec) };
        let (mut pp, mut n) = (std::ptr::null_mut(), 0u32);
        MFTEnum2(
            MFT_CATEGORY_VIDEO_ENCODER,
            MFT_ENUM_FLAG(MFT_ENUM_FLAG_HARDWARE.0 | MFT_ENUM_FLAG_SORTANDFILTER.0),
            Some(&input),
            Some(&output),
            &attrs,
            &mut pp,
            &mut n,
        )
        .ok()?;
        if pp.is_null() {
            return None;
        }
        let list = std::slice::from_raw_parts_mut(pp, n as usize);
        let first = list.iter_mut().find_map(|a| a.take().and_then(|a| a.ActivateObject::<IMFTransform>().ok()));
        list.iter_mut().for_each(|a| drop(a.take()));
        CoTaskMemFree(Some(pp as _));
        first
    }
}

/// Codecs this adapter can encode in hardware, best first.
pub fn codecs_available(luid: i64) -> Vec<Codec> {
    let Ok(_mf) = Mf::start() else { return Vec::new() };
    [Codec::Av1, Codec::Hevc, Codec::H264].into_iter().filter(|&c| find(c, luid).is_some()).collect()
}

struct SendGen(IMFMediaEventGenerator);
// SAFETY: MF event generators are free-threaded (GetEvent may be called from any thread).
unsafe impl Send for SendGen {}

pub struct Encoder {
    mft: IMFTransform,
    api: ICodecAPI,
    _mgr: IMFDXGIDeviceManager,
    events: mpsc::Receiver<i32>,
    pump: Option<std::thread::JoinHandle<()>>,
    codec: Codec,
    fps: u32,
    frame: i64,
    need_input: u32,
    /// (sample time, captureUs) of inputs still inside the encoder.
    inflight: VecDeque<(i64, u64)>,
    seq_header: Option<Vec<u8>>,
    pub size: (u32, u32),
    _mf: Mf,
}

fn set(api: &ICodecAPI, key: &GUID, v: VARIANT) -> windows::core::Result<()> {
    // SAFETY: valid GUID and VARIANT pointers for the call.
    unsafe { api.SetValue(key, &v) }
}

impl Encoder {
    pub fn new(dev: &ID3D11Device, luid: i64, codec: Codec, (w, h): (u32, u32), fps: u32, kbps: u32) -> windows::core::Result<Encoder> {
        let mf = Mf::start()?;
        let mft = find(codec, luid).ok_or_else(|| windows::core::Error::from(MF_E_TOPO_CODEC_NOT_FOUND))?;
        // SAFETY: MF/COM calls on live objects; the device manager outlives the MFT's use of it.
        unsafe {
            let attrs = mft.GetAttributes()?;
            attrs.SetUINT32(&MF_TRANSFORM_ASYNC_UNLOCK, 1)?;
            let _ = attrs.SetUINT32(&MF_LOW_LATENCY, 1);
            let mut token = 0;
            let mut mgr = None;
            MFCreateDXGIDeviceManager(&mut token, &mut mgr)?;
            let mgr = mgr.expect("device manager");
            mgr.ResetDevice(dev, token)?;
            mft.ProcessMessage(MFT_MESSAGE_SET_D3D_MANAGER, mgr.as_raw() as usize)?;

            let api: ICodecAPI = mft.cast()?;
            let _ = set(&api, &CODECAPI_AVEncCommonRateControlMode, VARIANT::from(eAVEncCommonRateControlMode_CBR.0 as u32));
            set(&api, &CODECAPI_AVEncCommonMeanBitRate, VARIANT::from(kbps * 1000))?;
            let _ = set(&api, &CODECAPI_AVLowLatencyMode, VARIANT::from(true));
            let _ = set(&api, &CODECAPI_AVEncMPVDefaultBPictureCount, VARIANT::from(0u32));
            // No periodic keyframes: the phone asks for one after a loss.
            let _ = set(&api, &CODECAPI_AVEncMPVGOPSize, VARIANT::from(u32::MAX >> 1));

            let size = ((w as u64) << 32) | h as u64;
            let rate = ((fps as u64) << 32) | 1;
            let out = MFCreateMediaType()?;
            out.SetGUID(&MF_MT_MAJOR_TYPE, &MFMediaType_Video)?;
            out.SetGUID(&MF_MT_SUBTYPE, &subtype(codec))?;
            out.SetUINT32(&MF_MT_AVG_BITRATE, kbps * 1000)?;
            out.SetUINT64(&MF_MT_FRAME_SIZE, size)?;
            out.SetUINT64(&MF_MT_FRAME_RATE, rate)?;
            out.SetUINT64(&MF_MT_PIXEL_ASPECT_RATIO, (1 << 32) | 1)?;
            out.SetUINT32(&MF_MT_INTERLACE_MODE, MFVideoInterlace_Progressive.0 as u32)?;
            out.SetUINT32(&MF_MT_VIDEO_NOMINAL_RANGE, MFNominalRange_16_235.0 as u32)?;
            out.SetUINT32(&MF_MT_YUV_MATRIX, MFVideoTransferMatrix_BT709.0 as u32)?;
            if codec == Codec::H264 {
                out.SetUINT32(&MF_MT_MPEG2_PROFILE, eAVEncH264VProfile_High.0 as u32)?;
            }
            mft.SetOutputType(0, &out, 0)?;
            let inp = MFCreateMediaType()?;
            inp.SetGUID(&MF_MT_MAJOR_TYPE, &MFMediaType_Video)?;
            inp.SetGUID(&MF_MT_SUBTYPE, &MFVideoFormat_NV12)?;
            inp.SetUINT64(&MF_MT_FRAME_SIZE, size)?;
            inp.SetUINT64(&MF_MT_FRAME_RATE, rate)?;
            inp.SetUINT32(&MF_MT_INTERLACE_MODE, MFVideoInterlace_Progressive.0 as u32)?;
            mft.SetInputType(0, &inp, 0)?;

            let gen = SendGen(mft.cast()?);
            let (tx, events) = mpsc::channel();
            let pump = std::thread::spawn(move || {
                let gen = gen;
                // GetEvent blocks until an event or shutdown.
                while let Ok(ev) = gen.0.GetEvent(MEDIA_EVENT_GENERATOR_GET_EVENT_FLAGS(0)) {
                    let t = ev.GetType().unwrap_or(0) as i32;
                    if tx.send(t).is_err() {
                        break;
                    }
                }
            });
            mft.ProcessMessage(MFT_MESSAGE_NOTIFY_BEGIN_STREAMING, 0)?;
            mft.ProcessMessage(MFT_MESSAGE_NOTIFY_START_OF_STREAM, 0)?;
            Ok(Encoder {
                mft,
                api,
                _mgr: mgr,
                events,
                pump: Some(pump),
                codec,
                fps: fps.max(1),
                frame: 0,
                need_input: 0,
                inflight: VecDeque::new(),
                seq_header: None,
                size: (w, h),
                _mf: mf,
            })
        }
    }

    pub fn codec(&self) -> Codec {
        self.codec
    }

    /// Takes effect on the next frame, without restarting the encoder.
    pub fn set_bitrate(&self, kbps: u32) -> windows::core::Result<()> {
        set(&self.api, &CODECAPI_AVEncCommonMeanBitRate, VARIANT::from(kbps * 1000))
    }

    pub fn bitrate(&self) -> Option<u32> {
        // SAFETY: valid GUID pointer.
        let v = unsafe { self.api.GetValue(&CODECAPI_AVEncCommonMeanBitRate) }.ok()?;
        u32::try_from(&v).ok().map(|b| b / 1000)
    }

    fn handle(&mut self, ev: i32, out: &mut Vec<Encoded>) -> windows::core::Result<()> {
        if ev == METransformNeedInput.0 {
            self.need_input += 1;
        } else if ev == METransformHaveOutput.0 {
            if let Some(e) = self.output()? {
                out.push(e);
            }
        }
        Ok(())
    }

    /// Encodes one NV12 frame. Returns what the encoder finished (normally exactly this frame).
    pub fn encode(&mut self, tex: &ID3D11Texture2D, capture_us: u64, force_idr: bool) -> windows::core::Result<Vec<Encoded>> {
        let mut out = Vec::new();
        while let Ok(ev) = self.events.try_recv() {
            self.handle(ev, &mut out)?;
        }
        let deadline = Instant::now() + Duration::from_secs(1);
        while self.need_input == 0 {
            let left = deadline.saturating_duration_since(Instant::now());
            match self.events.recv_timeout(left) {
                Ok(ev) => self.handle(ev, &mut out)?,
                Err(_) => return Err(MF_E_TRANSFORM_NEED_MORE_INPUT.into()), // encoder stalled
            }
        }
        // SAFETY: MF sample wraps the D3D texture (kept alive by the buffer) for ProcessInput.
        unsafe {
            if force_idr {
                let _ = set(&self.api, &CODECAPI_AVEncVideoForceKeyFrame, VARIANT::from(1u32));
            }
            let buf = MFCreateDXGISurfaceBuffer(&ID3D11Texture2D::IID, tex, 0, false)?;
            let sample = MFCreateSample()?;
            sample.AddBuffer(&buf)?;
            let dur = 10_000_000 / self.fps as i64;
            let t = self.frame * dur;
            self.frame += 1;
            sample.SetSampleTime(t)?;
            sample.SetSampleDuration(dur)?;
            self.mft.ProcessInput(0, &sample, 0)?;
            self.need_input -= 1;
            self.inflight.push_back((t, capture_us));
        }
        // Low latency: wait (briefly) for this frame's output instead of picking it up next time.
        let deadline = Instant::now() + Duration::from_millis(40);
        while !self.inflight.is_empty() {
            let left = deadline.saturating_duration_since(Instant::now());
            match self.events.recv_timeout(left) {
                Ok(ev) => self.handle(ev, &mut out)?,
                Err(_) => break,
            }
        }
        Ok(out)
    }

    fn output(&mut self) -> windows::core::Result<Option<Encoded>> {
        // SAFETY: the output data buffer's sample/events are taken back out of ManuallyDrop
        // and released exactly once.
        unsafe {
            let info = self.mft.GetOutputStreamInfo(0)?;
            let provides = info.dwFlags & (MFT_OUTPUT_STREAM_PROVIDES_SAMPLES.0 | MFT_OUTPUT_STREAM_CAN_PROVIDE_SAMPLES.0) as u32 != 0;
            let own = if provides {
                None
            } else {
                let s = MFCreateSample()?;
                s.AddBuffer(&MFCreateMemoryBuffer(info.cbSize.max(4 << 20))?)?;
                Some(s)
            };
            let mut db = [MFT_OUTPUT_DATA_BUFFER { dwStreamID: 0, pSample: ManuallyDrop::new(own), dwStatus: 0, pEvents: ManuallyDrop::new(None) }];
            let mut status = 0;
            let r = self.mft.ProcessOutput(0, &mut db, &mut status);
            let sample = ManuallyDrop::take(&mut db[0].pSample);
            drop(ManuallyDrop::take(&mut db[0].pEvents));
            if let Err(e) = r {
                if e.code() == MF_E_TRANSFORM_STREAM_CHANGE {
                    let t = self.mft.GetOutputAvailableType(0, 0)?;
                    self.mft.SetOutputType(0, &t, 0)?;
                    return Ok(None);
                }
                return Err(e);
            }
            let Some(sample) = sample else { return Ok(None) };
            let time = sample.GetSampleTime().unwrap_or(-1);
            let key = sample.GetUINT32(&MFSampleExtension_CleanPoint).unwrap_or(0) == 1;
            let buf = sample.ConvertToContiguousBuffer()?;
            let (mut p, mut len) = (std::ptr::null_mut(), 0u32);
            buf.Lock(&mut p, None, Some(&mut len))?;
            let mut data = std::slice::from_raw_parts(p, len as usize).to_vec();
            buf.Unlock()?;
            let mut capture_us = 0;
            while let Some((t, c)) = self.inflight.pop_front() {
                capture_us = c;
                if t >= time {
                    break;
                }
            }
            if key && !has_param_sets(self.codec, &data) {
                if self.seq_header.is_none() {
                    self.seq_header = self.mft.GetOutputCurrentType(0).ok().and_then(|t| blob(&t, &MF_MT_MPEG_SEQUENCE_HEADER));
                }
                if let Some(h) = &self.seq_header {
                    data.splice(0..0, h.iter().copied());
                }
            }
            Ok(Some(Encoded { data, key, capture_us }))
        }
    }
}

fn blob(a: &IMFMediaType, key: &GUID) -> Option<Vec<u8>> {
    // SAFETY: the buffer is sized from GetBlobSize.
    unsafe {
        let n = a.GetBlobSize(key).ok()?;
        let mut v = vec![0u8; n as usize];
        a.GetBlob(key, &mut v, None).ok()?;
        Some(v)
    }
}

/// True if an Annex B keyframe already carries its parameter sets (SPS for H.264, VPS for HEVC).
pub fn has_param_sets(codec: Codec, d: &[u8]) -> bool {
    let want = |nal: u8| match codec {
        Codec::H264 => nal & 0x1F == 7,
        Codec::Hevc => (nal >> 1) & 0x3F == 32,
        Codec::Av1 => true,
    };
    codec == Codec::Av1 || d.windows(4).any(|w| w[..3] == [0, 0, 1] && want(w[3]))
}

impl Drop for Encoder {
    fn drop(&mut self) {
        // SAFETY: shutting the MFT down ends its event queue, which ends the pump thread.
        unsafe {
            let _ = self.mft.ProcessMessage(MFT_MESSAGE_NOTIFY_END_OF_STREAM, 0);
            let shut = self.mft.cast::<IMFShutdown>().and_then(|s| s.Shutdown()).is_ok();
            if let Some(p) = self.pump.take().filter(|_| shut) {
                let _ = p.join();
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::engine::{capture, convert::Convert};
    use windows::Win32::Graphics::Direct3D11::*;
    use windows::Win32::Graphics::Dxgi::Common::*;

    #[test]
    fn param_set_detection() {
        assert!(has_param_sets(Codec::H264, &[0, 0, 0, 1, 0x67, 1, 2]));
        assert!(!has_param_sets(Codec::H264, &[0, 0, 0, 1, 0x65, 1, 2]));
        assert!(has_param_sets(Codec::Hevc, &[0, 0, 1, 0x40, 1]));
        assert!(!has_param_sets(Codec::Hevc, &[0, 0, 1, 0x26, 1]));
    }

    fn bgra(dev: &ID3D11Device, w: u32, h: u32) -> ID3D11Texture2D {
        let px: Vec<u8> = (0..w * h).flat_map(|i| [(i % 251) as u8, (i % 127) as u8, (i / w % 256) as u8, 255]).collect();
        let desc = D3D11_TEXTURE2D_DESC {
            Width: w,
            Height: h,
            MipLevels: 1,
            ArraySize: 1,
            Format: DXGI_FORMAT_B8G8R8A8_UNORM,
            SampleDesc: DXGI_SAMPLE_DESC { Count: 1, Quality: 0 },
            Usage: D3D11_USAGE_DEFAULT,
            BindFlags: D3D11_BIND_RENDER_TARGET.0 as u32 | D3D11_BIND_SHADER_RESOURCE.0 as u32,
            CPUAccessFlags: 0,
            MiscFlags: 0,
        };
        let init = D3D11_SUBRESOURCE_DATA { pSysMem: px.as_ptr().cast(), SysMemPitch: w * 4, SysMemSlicePitch: 0 };
        let mut t = None;
        // SAFETY: `px` outlives the call.
        unsafe { dev.CreateTexture2D(&desc, Some(&init), Some(&mut t)).unwrap() };
        t.unwrap()
    }

    /// (device, luid, a converter + NV12 source) or None when this PC has no hardware encoder.
    fn rig(codec: Codec) -> Option<(ID3D11Device, i64, Convert, ID3D11Texture2D)> {
        let (dev, ctx, _, luid) = capture::device("")?;
        if !codecs_available(luid).contains(&codec) {
            eprintln!("no hardware {codec:?} encoder on this PC");
            return None;
        }
        let src = bgra(&dev, 1280, 720);
        let conv = Convert::new(&dev, &ctx, (1280, 720), (1280, 720)).ok()?;
        Some((dev, luid, conv, src))
    }

    fn start_code(d: &[u8]) -> bool {
        d.starts_with(&[0, 0, 0, 1]) || d.starts_with(&[0, 0, 1])
    }

    #[test]
    fn encoder_produces_annexb_keyframe_first() {
        for codec in [Codec::H264, Codec::Hevc] {
            let _com = crate::engine::Com::init();
            let Some((dev, luid, mut conv, src)) = rig(codec) else { continue };
            let mut enc = Encoder::new(&dev, luid, codec, (1280, 720), 60, 8000).unwrap();
            let mut got = Vec::new();
            for i in 0..10 {
                let nv12 = conv.convert(&src).unwrap();
                got.extend(enc.encode(&nv12, 1000 + i, false).unwrap());
            }
            assert!(got.len() >= 8, "{codec:?}: one output per input, got {}", got.len());
            assert!(got[0].key, "{codec:?}: first frame is a keyframe");
            assert!(start_code(&got[0].data) && has_param_sets(codec, &got[0].data), "{codec:?}: Annex B with parameter sets");
            assert_eq!(got[0].capture_us, 1000);
            assert!(got[1..].iter().all(|e| !e.key), "{codec:?}: no periodic keyframes");
            // keyframe on demand
            let nv12 = conv.convert(&src).unwrap();
            let idr = enc.encode(&nv12, 2000, true).unwrap();
            assert!(idr.iter().any(|e| e.key && has_param_sets(codec, &e.data)), "{codec:?}: forced keyframe");
        }
    }

    #[test]
    fn set_bitrate_applies_without_restart() {
        let _com = crate::engine::Com::init();
        let Some((dev, luid, mut conv, src)) = rig(Codec::H264) else { return };
        let mut enc = Encoder::new(&dev, luid, Codec::H264, (1280, 720), 60, 8000).unwrap();
        for i in 0..3 {
            enc.encode(&conv.convert(&src).unwrap(), i, false).unwrap();
        }
        enc.set_bitrate(3000).unwrap();
        assert_eq!(enc.bitrate(), Some(3000));
        let after: usize = (0..5).map(|i| enc.encode(&conv.convert(&src).unwrap(), 10 + i, false).unwrap().len()).sum();
        assert!(after >= 4, "keeps encoding after the change");
    }
}

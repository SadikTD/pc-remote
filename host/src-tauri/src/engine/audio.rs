//! What the PC plays (WASAPI loopback on the default output, converted to 48 kHz stereo by
//! Windows) → 10 ms Opus packets.

use std::sync::atomic::{AtomicBool, Ordering};
use std::time::{Duration, Instant};
use windows::Win32::Foundation::{CloseHandle, HANDLE};
use windows::Win32::Media::Audio::*;
use windows::Win32::System::Com::{CoCreateInstance, CoTaskMemFree, CLSCTX_ALL};
use windows::Win32::System::Threading::{CreateEventW, WaitForSingleObject};

pub const RATE: u32 = 48_000;
/// Samples per channel in one 10 ms packet.
pub const FRAME: usize = 480;

/// The device went away (unplugged, or another default output was chosen).
pub struct Gone;

pub trait Source {
    /// Appends interleaved stereo f32 samples; returns the capture time (µs, host clock) of the
    /// first sample appended, or None if nothing came. Blocks for at most a few tens of ms.
    fn read(&mut self, pcm: &mut Vec<f32>) -> Result<Option<u64>, Gone>;
}

pub struct Opus(opus::Encoder);

impl Opus {
    pub fn new() -> Option<Opus> {
        let mut e = opus::Encoder::new(RATE, opus::Channels::Stereo, opus::Application::LowDelay).ok()?;
        e.set_bitrate(opus::Bitrate::Bits(128_000)).ok()?;
        Some(Opus(e))
    }

    /// One packet from exactly [FRAME] stereo samples.
    pub fn encode(&mut self, pcm: &[f32]) -> Option<Vec<u8>> {
        let mut out = vec![0u8; 1100];
        let n = self.0.encode_float(pcm, &mut out).ok()?;
        out.truncate(n);
        Some(out)
    }
}

/// Captures until `stop`, re-opening the source when it is gone; `send(opus, captureUs)`.
pub fn run<S: Source>(mut open: impl FnMut() -> Option<S>, stop: &AtomicBool, mut send: impl FnMut(&[u8], u64)) {
    let Some(mut enc) = Opus::new() else { return };
    let (mut src, mut pcm, mut t0) = (None::<S>, Vec::new(), 0u64);
    while !stop.load(Ordering::Relaxed) {
        let Some(s) = src.as_mut() else {
            src = open();
            pcm.clear();
            if src.is_none() {
                std::thread::sleep(Duration::from_millis(500));
            }
            continue;
        };
        let was_empty = pcm.is_empty();
        match s.read(&mut pcm) {
            Ok(Some(t)) if was_empty => t0 = t,
            Ok(_) => {}
            Err(Gone) => {
                src = None;
                continue;
            }
        }
        while pcm.len() >= FRAME * 2 {
            if let Some(p) = enc.encode(&pcm[..FRAME * 2]) {
                send(&p, t0);
            }
            pcm.drain(..FRAME * 2);
            t0 += 10_000;
        }
    }
}

pub struct Wasapi {
    client: IAudioClient,
    cap: IAudioCaptureClient,
    event: HANDLE,
    devices: IMMDeviceEnumerator,
    id: String,
    checked: Instant,
}

fn default_device(e: &IMMDeviceEnumerator) -> Option<(IMMDevice, String)> {
    // SAFETY: GetId returns a CoTaskMem string we free after copying.
    unsafe {
        let d = e.GetDefaultAudioEndpoint(eRender, eConsole).ok()?;
        let p = d.GetId().ok()?;
        let id = p.to_string().unwrap_or_default();
        CoTaskMemFree(Some(p.0 as _));
        Some((d, id))
    }
}

impl Wasapi {
    /// Loopback on the default output. COM must be initialised on this thread.
    pub fn open() -> Option<Wasapi> {
        // SAFETY: WASAPI calls with a valid format; the event handle is closed in Drop.
        unsafe {
            let devices: IMMDeviceEnumerator = CoCreateInstance(&MMDeviceEnumerator, None, CLSCTX_ALL).ok()?;
            let (dev, id) = default_device(&devices)?;
            let client: IAudioClient = dev.Activate(CLSCTX_ALL, None).ok()?;
            let fmt = WAVEFORMATEX {
                wFormatTag: 3, // WAVE_FORMAT_IEEE_FLOAT
                nChannels: 2,
                nSamplesPerSec: RATE,
                nAvgBytesPerSec: RATE * 8,
                nBlockAlign: 8,
                wBitsPerSample: 32,
                cbSize: 0,
            };
            let flags = AUDCLNT_STREAMFLAGS_LOOPBACK
                | AUDCLNT_STREAMFLAGS_EVENTCALLBACK
                | AUDCLNT_STREAMFLAGS_AUTOCONVERTPCM
                | AUDCLNT_STREAMFLAGS_SRC_DEFAULT_QUALITY;
            client.Initialize(AUDCLNT_SHAREMODE_SHARED, flags, 200_000, 0, &fmt, None).ok()?;
            let event = CreateEventW(None, false, false, None).ok()?;
            let ok = client.SetEventHandle(event).is_ok();
            let cap = client.GetService::<IAudioCaptureClient>().ok().filter(|_| ok);
            let Some(cap) = cap.filter(|_| client.Start().is_ok()) else {
                let _ = CloseHandle(event);
                return None;
            };
            Some(Wasapi { client, cap, event, devices, id, checked: Instant::now() })
        }
    }
}

impl Source for Wasapi {
    fn read(&mut self, pcm: &mut Vec<f32>) -> Result<Option<u64>, Gone> {
        if self.checked.elapsed() > Duration::from_secs(1) {
            self.checked = Instant::now();
            if default_device(&self.devices).is_none_or(|(_, id)| id != self.id) {
                return Err(Gone); // the user picked another output
            }
        }
        let mut first = None;
        // SAFETY: GetBuffer/ReleaseBuffer are paired; `frames` stereo f32 samples are readable.
        unsafe {
            // Loopback events are not signalled on every Windows build: wake at least every 20 ms.
            WaitForSingleObject(self.event, 20);
            loop {
                match self.cap.GetNextPacketSize() {
                    Ok(0) => break,
                    Ok(_) => {}
                    Err(_) => return Err(Gone),
                }
                let (mut data, mut frames, mut flags, mut qpc) = (std::ptr::null_mut(), 0u32, 0u32, 0u64);
                if self.cap.GetBuffer(&mut data, &mut frames, &mut flags, None, Some(&mut qpc)).is_err() {
                    return Err(Gone);
                }
                first.get_or_insert(qpc / 10); // 100 ns units of the QPC clock
                let n = frames as usize * 2;
                if flags & AUDCLNT_BUFFERFLAGS_SILENT.0 as u32 != 0 || data.is_null() {
                    pcm.resize(pcm.len() + n, 0.0);
                } else {
                    pcm.extend_from_slice(std::slice::from_raw_parts(data as *const f32, n));
                }
                let _ = self.cap.ReleaseBuffer(frames);
            }
        }
        Ok(first)
    }
}

impl Drop for Wasapi {
    fn drop(&mut self) {
        // SAFETY: stopping our own client and closing our own handle.
        unsafe {
            let _ = self.client.Stop();
            let _ = CloseHandle(self.event);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::AtomicU32;
    use std::sync::Arc;

    #[test]
    fn frames_are_10ms_and_silence_round_trips() {
        assert_eq!(FRAME as u32 * 100, RATE);
        let mut enc = Opus::new().unwrap();
        assert!(enc.encode(&[0.0; 400 * 2]).is_none(), "only whole 10 ms frames");
        let p = enc.encode(&[0.0; FRAME * 2]).unwrap();
        let mut dec = opus::Decoder::new(RATE, opus::Channels::Stereo).unwrap();
        let mut out = [1.0f32; FRAME * 2];
        assert_eq!(dec.decode_float(&p, &mut out, false).unwrap(), FRAME);
        assert!(out.iter().all(|s| s.abs() < 1e-3));
    }

    /// Gone on its first read; the next one delivers 25 ms of audio, then stops the run.
    struct Fake {
        n: u32,
        stop: Arc<AtomicBool>,
    }

    impl Source for Fake {
        fn read(&mut self, pcm: &mut Vec<f32>) -> Result<Option<u64>, Gone> {
            if self.n == 0 {
                return Err(Gone);
            }
            pcm.extend(std::iter::repeat_n(0.1f32, 1200 * 2));
            self.stop.store(true, Ordering::SeqCst);
            Ok(Some(5_000))
        }
    }

    #[test]
    fn device_change_restarts_capture() {
        let stop = Arc::new(AtomicBool::new(false));
        let opens = AtomicU32::new(0);
        let mut got = Vec::new();
        let s = stop.clone();
        run(|| Some(Fake { n: opens.fetch_add(1, Ordering::SeqCst), stop: s.clone() }), &stop, |p, t| got.push((p.len(), t)));
        assert_eq!(opens.load(Ordering::SeqCst), 2, "re-opened after the device went away");
        assert_eq!(got.iter().map(|g| g.1).collect::<Vec<_>>(), vec![5_000, 15_000], "two whole 10 ms packets, timed");
    }

    #[test]
    fn loopback_opens_on_this_pc() {
        let _com = crate::engine::Com::init();
        match Wasapi::open() {
            Some(mut w) => {
                let mut pcm = Vec::new();
                for _ in 0..5 {
                    assert!(w.read(&mut pcm).is_ok());
                }
                assert_eq!(pcm.len() % 2, 0);
            }
            None => eprintln!("no audio output device"),
        }
    }
}

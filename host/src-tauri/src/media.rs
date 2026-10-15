//! Now playing (Windows media sessions) and system volume, for Media mode.

use crate::agent::Outbox;
use crate::input::{Emitter, Ev, SendInputEmitter};
use crate::proto::ServerMsg;
use std::time::Duration;
use windows::Media::Control::{
    GlobalSystemMediaTransportControlsSession as GsmtcSession,
    GlobalSystemMediaTransportControlsSessionManager as GsmtcManager,
    GlobalSystemMediaTransportControlsSessionPlaybackStatus as Status,
};
use windows::Win32::Media::Audio::Endpoints::IAudioEndpointVolume;
use windows::Win32::Media::Audio::{eConsole, eRender, IMMDeviceEnumerator, MMDeviceEnumerator};
use windows::Win32::System::Com::{CoCreateInstance, CoInitializeEx, CLSCTX_ALL, COINIT_MULTITHREADED};

#[derive(Debug, Clone, Copy, PartialEq)]
pub enum Cmd {
    PlayPause,
    Next,
    Prev,
    VolUp,
    VolDown,
    Mute,
    VolSet(u8),
}

impl Cmd {
    pub fn parse(c: &str, v: Option<u8>) -> Result<Self, &'static str> {
        Ok(match c {
            "play_pause" => Cmd::PlayPause,
            "next" => Cmd::Next,
            "prev" => Cmd::Prev,
            "vol_up" => Cmd::VolUp,
            "vol_down" => Cmd::VolDown,
            "mute" => Cmd::Mute,
            "vol_set" => Cmd::VolSet(v.ok_or("bad_request")?.min(100)),
            _ => return Err("bad_request"),
        })
    }
}

#[derive(Debug, Clone, PartialEq, Default)]
pub struct MediaState {
    pub title: String,
    pub artist: String,
    pub app: String,
    pub playing: bool,
    pub vol: u8,
    pub muted: bool,
}

/// Remembers the last pushed state so `media` is only sent on change.
#[derive(Default)]
pub struct Diff(Option<MediaState>);

impl Diff {
    pub fn changed(&mut self, s: MediaState) -> Option<ServerMsg> {
        if self.0.as_ref() == Some(&s) {
            return None;
        }
        self.0 = Some(s.clone());
        Some(ServerMsg::Media { title: s.title, artist: s.artist, app: s.app, playing: s.playing, vol: s.vol, muted: s.muted })
    }
}

/// "SpotifyAB.SpotifyMusic_x!Spotify" → "Spotify", "chrome.exe" → "chrome".
pub fn app_name(aumid: &str) -> String {
    let s = aumid.rsplit('!').next().unwrap_or(aumid);
    s.strip_suffix(".exe").unwrap_or(s).to_string()
}

fn com() {
    // SAFETY: per-thread COM init; repeated calls just return S_FALSE.
    unsafe {
        let _ = CoInitializeEx(None, COINIT_MULTITHREADED);
    }
}

fn session() -> Option<GsmtcSession> {
    GsmtcManager::RequestAsync().ok()?.join().ok()?.GetCurrentSession().ok()
}

fn endpoint() -> windows::core::Result<IAudioEndpointVolume> {
    // SAFETY: standard MMDevice activation on a COM-initialised thread.
    unsafe {
        let e: IMMDeviceEnumerator = CoCreateInstance(&MMDeviceEnumerator, None, CLSCTX_ALL)?;
        e.GetDefaultAudioEndpoint(eRender, eConsole)?.Activate(CLSCTX_ALL, None)
    }
}

/// Current now-playing and volume. Blocking: call from a blocking thread.
pub fn read() -> MediaState {
    com();
    let mut st = MediaState::default();
    if let Some(s) = session() {
        st.app = app_name(&s.SourceAppUserModelId().map(|h| h.to_string()).unwrap_or_default());
        if let Ok(p) = s.TryGetMediaPropertiesAsync().and_then(|a| a.join()) {
            st.title = p.Title().map(|h| h.to_string()).unwrap_or_default();
            st.artist = p.Artist().map(|h| h.to_string()).unwrap_or_default();
        }
        st.playing = s.GetPlaybackInfo().and_then(|i| i.PlaybackStatus()).is_ok_and(|x| x == Status::Playing);
    }
    if let Ok(v) = endpoint() {
        // SAFETY: plain getters on a live interface.
        unsafe {
            st.vol = v.GetMasterVolumeLevelScalar().map(|x| (x * 100.0).round() as u8).unwrap_or(0);
            st.muted = v.GetMute().map(|b| b.as_bool()).unwrap_or(false);
        }
    }
    st
}

/// Runs a command. Transport commands use the media session, else the media keys.
pub fn run(cmd: Cmd) {
    com();
    let transport = |vk: u16, f: fn(&GsmtcSession) -> windows::core::Result<bool>| {
        if !session().is_some_and(|s| f(&s).unwrap_or(false)) {
            SendInputEmitter.emit(&[Ev::Key { vk, down: true }, Ev::Key { vk, down: false }]);
        }
    };
    match cmd {
        Cmd::PlayPause => transport(0xB3, |s| s.TryTogglePlayPauseAsync()?.join()),
        Cmd::Next => transport(0xB0, |s| s.TrySkipNextAsync()?.join()),
        Cmd::Prev => transport(0xB1, |s| s.TrySkipPreviousAsync()?.join()),
        Cmd::VolUp | Cmd::VolDown | Cmd::Mute | Cmd::VolSet(_) => {
            let Ok(v) = endpoint() else { return };
            // SAFETY: plain setters on a live interface.
            unsafe {
                let _ = match cmd {
                    Cmd::VolUp => v.VolumeStepUp(std::ptr::null()),
                    Cmd::VolDown => v.VolumeStepDown(std::ptr::null()),
                    Cmd::Mute => v.GetMute().and_then(|m| v.SetMute(!m.as_bool(), std::ptr::null())),
                    Cmd::VolSet(x) => v.SetMasterVolumeLevelScalar(x as f32 / 100.0, std::ptr::null()),
                    _ => Ok(()),
                };
            }
        }
    }
}

/// While subscribed: push `media` once now, then whenever it changes.
/// ponytail: 1 s check while subscribed (allowed by the no-idle-polling rule); switch to
/// GSMTC/IAudioEndpointVolumeCallback events if the latency shows.
pub fn subscribe(out: Outbox) -> tokio::task::JoinHandle<()> {
    tokio::spawn(async move {
        let mut diff = Diff::default();
        while !out.is_closed() {
            if let Ok(st) = tokio::task::spawn_blocking(read).await {
                if let Some(m) = diff.changed(st) {
                    out.send(m);
                }
            }
            tokio::time::sleep(Duration::from_secs(1)).await;
        }
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_commands() {
        assert_eq!(Cmd::parse("play_pause", None), Ok(Cmd::PlayPause));
        assert_eq!(Cmd::parse("next", None), Ok(Cmd::Next));
        assert_eq!(Cmd::parse("prev", None), Ok(Cmd::Prev));
        assert_eq!(Cmd::parse("vol_up", None), Ok(Cmd::VolUp));
        assert_eq!(Cmd::parse("vol_down", None), Ok(Cmd::VolDown));
        assert_eq!(Cmd::parse("mute", None), Ok(Cmd::Mute));
        assert_eq!(Cmd::parse("vol_set", Some(45)), Ok(Cmd::VolSet(45)));
        assert_eq!(Cmd::parse("vol_set", None), Err("bad_request"));
        assert_eq!(Cmd::parse("rewind", None), Err("bad_request"));
    }

    #[test]
    fn vol_set_is_clamped() {
        assert_eq!(Cmd::parse("vol_set", Some(250)), Ok(Cmd::VolSet(100)));
        assert_eq!(Cmd::parse("vol_set", Some(0)), Ok(Cmd::VolSet(0)));
    }

    fn st(title: &str, vol: u8) -> MediaState {
        MediaState { title: title.into(), artist: "A".into(), app: "Spotify".into(), playing: true, vol, muted: false }
    }

    #[test]
    fn diff_pushes_only_on_change() {
        let mut d = Diff::default();
        assert!(d.changed(st("x", 10)).is_some(), "first state is always pushed");
        assert!(d.changed(st("x", 10)).is_none());
        let m = d.changed(st("y", 10)).unwrap();
        assert_eq!(m, ServerMsg::Media { title: "y".into(), artist: "A".into(), app: "Spotify".into(), playing: true, vol: 10, muted: false });
        assert!(d.changed(st("y", 11)).is_some());
    }

    #[test]
    fn app_names_from_ids() {
        assert_eq!(app_name("Spotify.exe"), "Spotify");
        assert_eq!(app_name("SpotifyAB.SpotifyMusic_zpdnekdrzrea0!Spotify"), "Spotify");
        assert_eq!(app_name("Microsoft.ZuneMusic_8wekyb3d8bbwe!Microsoft.ZuneMusic"), "Microsoft.ZuneMusic");
        assert_eq!(app_name("chrome"), "chrome");
        assert_eq!(app_name(""), "");
    }

    #[test]
    fn reads_this_pc() {
        let st = read();
        assert!(st.vol <= 100);
        eprintln!("{st:?}");
    }
}

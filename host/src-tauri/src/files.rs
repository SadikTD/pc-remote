//! File transfer: phone → PC into the received-files folder (resumable), and PC → phone offers.

use crate::proto::{chunk_frame, parse_binary, Binary, ServerMsg};
use crate::tls::hex;
use ring::digest::{Context, SHA256};
use std::collections::HashMap;
use std::fs::{File, OpenOptions};
use std::io::{Read, Seek, SeekFrom, Write};
use std::path::{Path, PathBuf};

/// Chunk payload size, per protocol (≤ 256 KiB).
pub const CHUNK: usize = 256 * 1024;

/// A safe Windows file name: base name only, no reserved device names or invalid characters.
pub fn sanitize(name: &str) -> String {
    let base = name.rsplit(['/', '\\']).next().unwrap_or("");
    let mut s: String = base
        .chars()
        .map(|c| if c < ' ' || "<>:\"/\\|?*".contains(c) { '_' } else { c })
        .take(200)
        .collect();
    s = s.trim_end_matches(['.', ' ']).trim_start().to_string();
    if s.is_empty() {
        return "file".into();
    }
    let stem = s.split('.').next().unwrap_or("").to_ascii_uppercase();
    let reserved = matches!(stem.as_str(), "CON" | "PRN" | "AUX" | "NUL")
        || (stem.len() == 4
            && (stem.starts_with("COM") || stem.starts_with("LPT"))
            && stem.as_bytes()[3].is_ascii_digit()
            && stem.as_bytes()[3] != b'0');
    if reserved {
        s.insert(0, '_');
    }
    s
}

/// `dir/name`, or `name (1)`, `name (2)`… if taken.
pub fn unique_path(dir: &Path, name: &str) -> PathBuf {
    let (stem, ext) = match name.rfind('.') {
        Some(i) if i > 0 => (&name[..i], &name[i..]),
        _ => (name, ""),
    };
    let mut p = dir.join(name);
    let mut n = 1;
    while p.exists() {
        p = dir.join(format!("{stem} ({n}){ext}"));
        n += 1;
    }
    p
}

pub fn parse_uuid(s: &str) -> Option<[u8; 16]> {
    let h: String = s.chars().filter(|c| *c != '-').collect();
    if h.len() != 32 {
        return None;
    }
    let mut out = [0u8; 16];
    for (i, b) in out.iter_mut().enumerate() {
        *b = u8::from_str_radix(h.get(i * 2..i * 2 + 2)?, 16).ok()?;
    }
    Some(out)
}

pub fn format_uuid(b: &[u8; 16]) -> String {
    let h = hex(b);
    format!("{}-{}-{}-{}-{}", &h[..8], &h[8..12], &h[12..16], &h[16..20], &h[20..])
}

fn free_space(dir: &Path) -> Option<u64> {
    use windows::core::HSTRING;
    use windows::Win32::Storage::FileSystem::GetDiskFreeSpaceExW;
    let mut free = 0u64;
    // SAFETY: valid path string and out-pointer for the duration of the call.
    unsafe { GetDiskFreeSpaceExW(&HSTRING::from(dir.as_os_str()), Some(&mut free), None, None).ok()? };
    Some(free)
}

struct Rx {
    id: String,
    name: String,
    part: PathBuf,
    file: File,
    len: u64,
    size: u64,
    hash: Context,
}

/// One session's phone → PC transfers. `.part` files survive a dropped connection,
/// so a new `file.begin` with the same id and name resumes.
pub struct Incoming {
    dir: PathBuf,
    active: HashMap<[u8; 16], Rx>,
}

impl Incoming {
    pub fn new(dir: PathBuf) -> Self {
        Incoming { dir, active: HashMap::new() }
    }

    fn error(id: &str, code: &str) -> ServerMsg {
        ServerMsg::FileError { id: id.into(), code: code.into() }
    }

    pub fn begin(&mut self, id: &str, name: &str, size: u64) -> ServerMsg {
        let Some(key) = parse_uuid(id) else { return Self::error(id, "denied") };
        let name = sanitize(name);
        if std::fs::create_dir_all(&self.dir).is_err() {
            return Self::error(id, "denied");
        }
        let part = self.dir.join(format!("{name}.{}.part", &hex(&key)[..12]));
        let Ok(mut file) = OpenOptions::new().create(true).truncate(false).read(true).write(true).open(&part) else {
            return Self::error(id, "denied");
        };
        let mut len = file.metadata().map(|m| m.len()).unwrap_or(0);
        if len > size {
            let _ = file.set_len(0);
            len = 0;
        }
        if free_space(&self.dir).is_some_and(|f| f < size - len) {
            return Self::error(id, "disk_full");
        }
        // Resuming: the final hash covers the whole file, so fold in what is already there.
        let mut hash = Context::new(&SHA256);
        let mut buf = vec![0u8; CHUNK];
        let mut left = len;
        while left > 0 {
            match file.read(&mut buf[..CHUNK.min(left as usize)]) {
                Ok(0) | Err(_) => return Self::error(id, "denied"),
                Ok(n) => {
                    hash.update(&buf[..n]);
                    left -= n as u64;
                }
            }
        }
        let _ = file.seek(SeekFrom::End(0));
        self.active.insert(key, Rx { id: id.into(), name, part, file, len, size, hash });
        ServerMsg::FileReady { id: id.into(), offset: len }
    }

    /// Writes a chunk at the current end of its `.part`; stale or unknown chunks are ignored.
    pub fn chunk(&mut self, frame: &[u8]) -> Option<ServerMsg> {
        let Some(Binary::FileChunk { id, offset, data }) = parse_binary(frame) else { return None };
        let rx = self.active.get_mut(&id)?;
        if offset != rx.len {
            return None;
        }
        if rx.len + data.len() as u64 > rx.size {
            let rx = self.active.remove(&id)?;
            drop(rx.file);
            let _ = std::fs::remove_file(&rx.part);
            return Some(Self::error(&rx.id, "too_large"));
        }
        if let Err(e) = rx.file.write_all(&data) {
            let code = if e.kind() == std::io::ErrorKind::StorageFull { "disk_full" } else { "denied" };
            let id = rx.id.clone();
            self.active.remove(&parse_uuid(&id)?);
            return Some(Self::error(&id, code));
        }
        rx.hash.update(&data);
        rx.len += data.len() as u64;
        None
    }

    /// Checks the SHA-256 and moves the file into place.
    pub fn end(&mut self, id: &str, sha256: &str) -> ServerMsg {
        let Some(rx) = parse_uuid(id).and_then(|k| self.active.remove(&k)) else { return Self::error(id, "denied") };
        drop(rx.file);
        if !hex(rx.hash.finish().as_ref()).eq_ignore_ascii_case(sha256) || rx.len != rx.size {
            let _ = std::fs::remove_file(&rx.part);
            return Self::error(id, "bad_hash");
        }
        let dest = unique_path(&self.dir, &rx.name);
        match std::fs::rename(&rx.part, &dest) {
            Ok(()) => {
                crate::notify::file_received(&dest);
                ServerMsg::FileDone { id: id.into(), path: dest.to_string_lossy().into() }
            }
            Err(_) => Self::error(id, "denied"),
        }
    }
}

/// Files offered to phones, by transfer id, until accepted or rejected.
pub type Outgoing = std::sync::Arc<std::sync::Mutex<HashMap<String, PathBuf>>>;

/// Registers `path` and returns the `file.offer` to send.
pub fn offer(outgoing: &Outgoing, path: &Path) -> std::io::Result<ServerMsg> {
    let size = std::fs::metadata(path)?.len();
    let id = format_uuid(&crate::store::random::<16>());
    let name = path.file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_else(|| "file".into());
    outgoing.lock().unwrap().insert(id.clone(), path.to_path_buf());
    Ok(ServerMsg::FileOffer { id, name, size })
}

/// Reads `path` in chunks, sending frames from `offset` on; returns the whole file's SHA-256.
/// `send` returning false (phone gone) stops early.
pub fn stream(path: &Path, id: &[u8; 16], offset: u64, send: &mut dyn FnMut(Vec<u8>) -> bool) -> std::io::Result<String> {
    let mut f = File::open(path)?;
    let mut hash = Context::new(&SHA256);
    let mut buf = vec![0u8; CHUNK];
    let mut pos = 0u64;
    loop {
        let n = f.read(&mut buf)?;
        if n == 0 {
            return Ok(hex(hash.finish().as_ref()));
        }
        hash.update(&buf[..n]);
        let end = pos + n as u64;
        if end > offset {
            let skip = offset.saturating_sub(pos) as usize;
            if !send(chunk_frame(id, pos + skip as u64, &buf[skip..n])) {
                return Err(std::io::ErrorKind::BrokenPipe.into());
            }
        }
        pos = end;
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::proto::chunk_frame;

    fn tmp(name: &str) -> PathBuf {
        let d = std::env::temp_dir().join(format!("pcr-files-{name}-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&d);
        d
    }

    const ID: &str = "0123abcd-0000-4000-8000-00000000beef";

    fn sha(b: &[u8]) -> String {
        crate::tls::hex(&crate::tls::sha256(b))
    }

    #[test]
    fn sanitize_rejects_traversal_and_reserved() {
        assert_eq!(sanitize(r"..\..\Windows\evil.dll"), "evil.dll");
        assert_eq!(sanitize("../../etc/passwd"), "passwd");
        assert_eq!(sanitize("CON"), "_CON");
        assert_eq!(sanitize("con.txt"), "_con.txt");
        assert_eq!(sanitize("NUL.txt"), "_NUL.txt");
        assert_eq!(sanitize("com1.tar.gz"), "_com1.tar.gz");
        assert_eq!(sanitize("lpt9"), "_lpt9");
        assert_eq!(sanitize("console.txt"), "console.txt");
        assert_eq!(sanitize("a<b>c:d\"e|f?g*h"), "a_b_c_d_e_f_g_h");
        assert_eq!(sanitize("report.pdf. . "), "report.pdf");
        assert_eq!(sanitize("tab\there\u{0}"), "tab_here_");
        assert_eq!(sanitize(".."), "file");
        assert_eq!(sanitize(""), "file");
        assert_eq!(sanitize("photo.jpg"), "photo.jpg");
        assert_eq!(sanitize("héllo wörld.txt"), "héllo wörld.txt");
        assert!(sanitize(&"x".repeat(500)).chars().count() <= 200);
    }

    #[test]
    fn clash_adds_number() {
        let d = tmp("clash");
        std::fs::create_dir_all(&d).unwrap();
        assert_eq!(unique_path(&d, "a.txt"), d.join("a.txt"));
        std::fs::write(d.join("a.txt"), "1").unwrap();
        assert_eq!(unique_path(&d, "a.txt"), d.join("a (1).txt"));
        std::fs::write(d.join("a (1).txt"), "1").unwrap();
        assert_eq!(unique_path(&d, "a.txt"), d.join("a (2).txt"));
        std::fs::write(d.join("noext"), "1").unwrap();
        assert_eq!(unique_path(&d, "noext"), d.join("noext (1)"));
    }

    #[test]
    fn uuid_parses() {
        let id = parse_uuid(ID).unwrap();
        assert_eq!(id[0], 0x01);
        assert_eq!(id[15], 0xef);
        assert_eq!(parse_uuid("nope"), None);
        assert_eq!(format_uuid(&id), ID);
    }

    #[test]
    fn receives_whole_file() {
        let d = tmp("whole");
        let data = b"hello world, this is a file".to_vec();
        let mut rx = Incoming::new(d.clone());
        assert_eq!(rx.begin(ID, "../note.txt", data.len() as u64), ServerMsg::FileReady { id: ID.into(), offset: 0 });
        let id = parse_uuid(ID).unwrap();
        assert_eq!(rx.chunk(&chunk_frame(&id, 0, &data[..10])), None);
        assert_eq!(rx.chunk(&chunk_frame(&id, 10, &data[10..])), None);
        let done = rx.end(ID, &sha(&data));
        let path = d.join("note.txt");
        assert_eq!(done, ServerMsg::FileDone { id: ID.into(), path: path.to_string_lossy().into() });
        assert_eq!(std::fs::read(&path).unwrap(), data);
        assert_eq!(std::fs::read_dir(&d).unwrap().count(), 1, "no .part left");
    }

    #[test]
    fn resume_appends_to_part() {
        let d = tmp("resume");
        let data: Vec<u8> = (0..100_000u32).map(|i| (i % 251) as u8).collect();
        let id = parse_uuid(ID).unwrap();
        {
            let mut rx = Incoming::new(d.clone());
            rx.begin(ID, "big.bin", data.len() as u64);
            rx.chunk(&chunk_frame(&id, 0, &data[..40_000]));
        } // connection dropped
        let mut rx = Incoming::new(d.clone());
        assert_eq!(rx.begin(ID, "big.bin", data.len() as u64), ServerMsg::FileReady { id: ID.into(), offset: 40_000 });
        rx.chunk(&chunk_frame(&id, 40_000, &data[40_000..]));
        assert!(matches!(rx.end(ID, &sha(&data)), ServerMsg::FileDone { .. }));
        assert_eq!(std::fs::read(d.join("big.bin")).unwrap(), data);
    }

    #[test]
    fn bad_hash_deletes_part() {
        let d = tmp("badhash");
        let id = parse_uuid(ID).unwrap();
        let mut rx = Incoming::new(d.clone());
        rx.begin(ID, "x.bin", 3);
        rx.chunk(&chunk_frame(&id, 0, b"abc"));
        assert_eq!(rx.end(ID, &sha(b"abd")), ServerMsg::FileError { id: ID.into(), code: "bad_hash".into() });
        assert_eq!(std::fs::read_dir(&d).unwrap().count(), 0);
    }

    #[test]
    fn more_data_than_announced_is_too_large() {
        let d = tmp("toolarge");
        let id = parse_uuid(ID).unwrap();
        let mut rx = Incoming::new(d.clone());
        rx.begin(ID, "x.bin", 2);
        assert_eq!(rx.chunk(&chunk_frame(&id, 0, b"abc")), Some(ServerMsg::FileError { id: ID.into(), code: "too_large".into() }));
        assert_eq!(std::fs::read_dir(&d).unwrap().count(), 0);
    }

    #[test]
    fn unknown_or_stale_chunks_are_ignored() {
        let d = tmp("stale");
        let id = parse_uuid(ID).unwrap();
        let mut rx = Incoming::new(d.clone());
        assert_eq!(rx.chunk(&chunk_frame(&id, 0, b"abc")), None, "no begin yet");
        rx.begin(ID, "x.bin", 6);
        rx.chunk(&chunk_frame(&id, 0, b"abc"));
        rx.chunk(&chunk_frame(&id, 0, b"abc")); // duplicate
        rx.chunk(&chunk_frame(&id, 3, b"def"));
        assert!(matches!(rx.end(ID, &sha(b"abcdef")), ServerMsg::FileDone { .. }));
    }

    #[test]
    fn outgoing_chunks_from_offset_hash_whole_file() {
        let d = tmp("out");
        std::fs::create_dir_all(&d).unwrap();
        let data: Vec<u8> = (0..(CHUNK * 2 + 100)).map(|i| (i % 7) as u8).collect();
        let p = d.join("s.bin");
        std::fs::write(&p, &data).unwrap();
        let id = parse_uuid(ID).unwrap();
        let mut frames = vec![];
        let hash = stream(&p, &id, 1000, &mut |f| {
            frames.push(f);
            true
        })
        .unwrap();
        assert_eq!(hash, sha(&data));
        let mut got = vec![];
        let mut next = 1000u64;
        for f in &frames {
            let Some(crate::proto::Binary::FileChunk { id: fid, offset, data }) = crate::proto::parse_binary(f) else { panic!() };
            assert_eq!((fid, offset), (id, next));
            assert!(data.len() <= CHUNK);
            next += data.len() as u64;
            got.extend(data);
        }
        assert_eq!(got, &data[1000..]);
    }
}

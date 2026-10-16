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

/// A phone-supplied relative path ("Photos/2024/a.jpg") made safe: every component sanitised,
/// `.`/`..`/empty parts and drive or UNC prefixes dropped, so it can never leave the target folder.
pub fn safe_rel(name: &str) -> PathBuf {
    let parts: Vec<String> = name
        .split(['/', '\\'])
        .filter(|p| !p.is_empty() && *p != "." && *p != "..")
        .map(sanitize)
        .collect();
    let keep = parts.len().saturating_sub(16); // at most 16 levels deep
    let p: PathBuf = parts[keep..].iter().collect();
    if p.as_os_str().is_empty() { PathBuf::from("file") } else { p }
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
    /// Final folder (the received-files folder, or a subfolder of it) and file name.
    dir: PathBuf,
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
        let rel = safe_rel(name);
        let dir = rel.parent().map_or(self.dir.clone(), |p| self.dir.join(p));
        let name = rel.file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_else(|| "file".into());
        if std::fs::create_dir_all(&dir).is_err() {
            return Self::error(id, "denied");
        }
        let part = dir.join(format!("{name}.{}.part", &hex(&key)[..12]));
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
        self.active.insert(key, Rx { id: id.into(), dir, name, part, file, len, size, hash });
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
        let dest = unique_path(&rx.dir, &rx.name);
        match std::fs::rename(&rx.part, &dest) {
            Ok(()) => {
                crate::notify::file_received(&dest);
                ServerMsg::FileDone { id: id.into(), path: dest.to_string_lossy().into() }
            }
            Err(_) => Self::error(id, "denied"),
        }
    }

    /// `file.cancel`: stops the upload and deletes its partial file.
    pub fn cancel(&mut self, id: &str) {
        if let Some(rx) = parse_uuid(id).and_then(|k| self.active.remove(&k)) {
            drop(rx.file);
            let _ = std::fs::remove_file(&rx.part);
        }
    }
}

/// Files offered to phones, by transfer id, until accepted or rejected.
pub type Outgoing = std::sync::Arc<std::sync::Mutex<HashMap<String, PathBuf>>>;

/// Registers `path` and returns the `file.offer` to send.
pub fn offer(outgoing: &Outgoing, path: &Path) -> std::io::Result<ServerMsg> {
    let name = path.file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_else(|| "file".into());
    offer_as(outgoing, path, name)
}

fn offer_as(outgoing: &Outgoing, path: &Path, name: String) -> std::io::Result<ServerMsg> {
    let size = std::fs::metadata(path)?.len();
    let id = format_uuid(&crate::store::random::<16>());
    outgoing.lock().unwrap().insert(id.clone(), path.to_path_buf());
    Ok(ServerMsg::FileOffer { id, name, size })
}

/// Most files one folder download offers.
const TREE_MAX: usize = 5000;

/// `fs.get`: a file → one offer; a folder → one offer per file inside, named `folder/sub/file`.
pub fn offer_tree(outgoing: &Outgoing, path: &Path) -> std::io::Result<Vec<ServerMsg>> {
    if !std::fs::metadata(path)?.is_dir() {
        return Ok(vec![offer(outgoing, path)?]);
    }
    let root = path.parent().unwrap_or(path);
    let mut out = vec![];
    let mut stack = vec![path.to_path_buf()];
    while let Some(dir) = stack.pop() {
        let Ok(rd) = std::fs::read_dir(&dir) else { continue };
        for e in rd.flatten() {
            let p = e.path();
            match e.file_type() {
                Ok(t) if t.is_dir() => stack.push(p),
                Ok(t) if t.is_file() => {
                    let rel = p.strip_prefix(root).unwrap_or(&p).to_string_lossy().replace('\\', "/");
                    if let Ok(m) = offer_as(outgoing, &p, rel) {
                        out.push(m);
                    }
                    if out.len() >= TREE_MAX {
                        return Ok(out);
                    }
                }
                _ => {}
            }
        }
    }
    Ok(out)
}

/// `fs.list`: a folder's entries (folders first), or for "" the drives and the user's folders.
pub fn list(path: &str, home: &Path) -> std::io::Result<Vec<crate::proto::FsEntry>> {
    use crate::proto::FsEntry;
    let secs = |m: &std::fs::Metadata| m.modified().ok().and_then(|t| t.duration_since(std::time::UNIX_EPOCH).ok()).map_or(0, |d| d.as_secs());
    if path.is_empty() {
        let mut v: Vec<FsEntry> = ["Desktop", "Documents", "Downloads", "Pictures", "Videos", "Music"]
            .iter()
            .map(|n| home.join(n))
            .filter(|p| p.is_dir())
            .map(|p| FsEntry { name: p.file_name().unwrap().to_string_lossy().into(), path: p.to_string_lossy().into(), dir: true, size: 0, mtime: 0 })
            .collect();
        // SAFETY: no arguments.
        let mask = unsafe { windows::Win32::Storage::FileSystem::GetLogicalDrives() };
        for i in 0..26 {
            if mask & (1 << i) != 0 {
                let d = format!("{}:\\", (b'A' + i as u8) as char);
                v.push(FsEntry { name: d.clone(), path: d, dir: true, size: 0, mtime: 0 });
            }
        }
        return Ok(v);
    }
    let mut v: Vec<FsEntry> = std::fs::read_dir(path)?
        .flatten()
        .filter_map(|e| {
            let m = e.metadata().ok()?;
            use std::os::windows::fs::MetadataExt;
            if m.file_attributes() & 0x6 != 0 {
                return None; // hidden or system
            }
            Some(FsEntry { name: e.file_name().to_string_lossy().into(), path: e.path().to_string_lossy().into(), dir: m.is_dir(), size: if m.is_dir() { 0 } else { m.len() }, mtime: secs(&m) })
        })
        .collect();
    v.sort_by(|a, b| b.dir.cmp(&a.dir).then_with(|| a.name.to_lowercase().cmp(&b.name.to_lowercase())));
    Ok(v)
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

    #[test]
    fn relative_paths_cannot_escape() {
        assert_eq!(safe_rel("Photos/2024/a.jpg"), Path::new("Photos").join("2024").join("a.jpg"));
        assert_eq!(safe_rel("../../x.txt"), PathBuf::from("x.txt"));
        assert_eq!(safe_rel("a/../../b/x"), Path::new("a").join("b").join("x"));
        assert_eq!(safe_rel(r"C:\Windows\x.dll"), Path::new("C_").join("Windows").join("x.dll"));
        assert_eq!(safe_rel(r"\host\share\x"), Path::new("host").join("share").join("x"));
        assert_eq!(safe_rel("CON/aux.txt"), Path::new("_CON").join("_aux.txt"));
        assert_eq!(safe_rel(""), PathBuf::from("file"));
        assert_eq!(safe_rel("/../"), PathBuf::from("file"));
        for p in ["../x", r"..\..\x", "a/../../../x", r"C:\x", r"\h\s\x"] {
            assert!(safe_rel(p).components().all(|c| matches!(c, std::path::Component::Normal(_))), "{p}");
        }
    }

    #[test]
    fn upload_into_subfolder_and_cancel() {
        let d = tmp("sub");
        let mut rx = Incoming::new(d.clone());
        assert!(matches!(rx.begin(ID, "Trip/day 1/a.txt", 3), ServerMsg::FileReady { offset: 0, .. }));
        let id = parse_uuid(ID).unwrap();
        rx.chunk(&chunk_frame(&id, 0, b"abc"));
        let ServerMsg::FileDone { path, .. } = rx.end(ID, &sha(b"abc")) else { panic!() };
        assert_eq!(PathBuf::from(path), d.join("Trip").join("day 1").join("a.txt"));
        rx.begin(ID, "../b.txt", 3);
        rx.chunk(&chunk_frame(&id, 0, b"a"));
        rx.cancel(ID);
        assert!(std::fs::read_dir(&d).unwrap().flatten().all(|e| !e.file_name().to_string_lossy().ends_with(".part")));
        assert!(matches!(rx.end(ID, &sha(b"abc")), ServerMsg::FileError { .. }));
    }

    #[test]
    fn folder_offers_keep_relative_names() {
        let d = tmp("tree");
        std::fs::create_dir_all(d.join("Album").join("raw")).unwrap();
        std::fs::write(d.join("Album").join("a.jpg"), b"1").unwrap();
        std::fs::write(d.join("Album").join("raw").join("b.dng"), b"22").unwrap();
        let out = Outgoing::default();
        let mut names: Vec<(String, u64)> = offer_tree(&out, &d.join("Album"))
            .unwrap()
            .into_iter()
            .map(|m| match m { ServerMsg::FileOffer { name, size, .. } => (name, size), _ => panic!() })
            .collect();
        names.sort();
        assert_eq!(names, vec![("Album/a.jpg".into(), 1), ("Album/raw/b.dng".into(), 2)]);
        assert_eq!(out.lock().unwrap().len(), 2);
        let listed = list(d.join("Album").to_str().unwrap(), &d).unwrap();
        assert_eq!(listed.iter().map(|e| (e.name.as_str(), e.dir)).collect::<Vec<_>>(), vec![("raw", true), ("a.jpg", false)]);
    }
}

//! DPAPI for secrets at rest. New blobs are machine scope (the SYSTEM server reads them; the
//! data folder's ACL keeps other users out); `unprotect` reads either scope.

use windows::core::PCWSTR;
use windows::Win32::Foundation::{LocalFree, HLOCAL};
use windows::Win32::Security::Cryptography::{
    CryptProtectData, CryptUnprotectData, CRYPTPROTECT_LOCAL_MACHINE, CRYPTPROTECT_UI_FORBIDDEN, CRYPT_INTEGER_BLOB,
};

fn blob(b: &[u8]) -> CRYPT_INTEGER_BLOB {
    CRYPT_INTEGER_BLOB { cbData: b.len() as u32, pbData: b.as_ptr() as *mut u8 }
}

fn take(out: CRYPT_INTEGER_BLOB) -> Vec<u8> {
    // SAFETY: DPAPI allocated `out` with LocalAlloc; we copy it then free it once.
    unsafe {
        let v = std::slice::from_raw_parts(out.pbData, out.cbData as usize).to_vec();
        LocalFree(Some(HLOCAL(out.pbData as _)));
        v
    }
}

pub fn protect(data: &[u8]) -> windows::core::Result<Vec<u8>> {
    let mut out = CRYPT_INTEGER_BLOB::default();
    // SAFETY: input blob borrows `data` for the call; output is owned by `take`.
    unsafe { CryptProtectData(&blob(data), PCWSTR::null(), None, None, None, CRYPTPROTECT_UI_FORBIDDEN | CRYPTPROTECT_LOCAL_MACHINE, &mut out)? };
    Ok(take(out))
}

pub fn unprotect(data: &[u8]) -> windows::core::Result<Vec<u8>> {
    let mut out = CRYPT_INTEGER_BLOB::default();
    // SAFETY: as in `protect`.
    unsafe { CryptUnprotectData(&blob(data), None, None, None, None, CRYPTPROTECT_UI_FORBIDDEN, &mut out)? };
    Ok(take(out))
}

#[cfg(test)]
mod tests {
    #[test]
    fn round_trip() {
        let secret = b"top secret \x00\xff bytes";
        let blob = super::protect(secret).unwrap();
        assert_ne!(&blob[..], &secret[..]);
        assert_eq!(super::unprotect(&blob).unwrap(), secret);
    }

    #[test]
    fn tampered_blob_fails() {
        let mut blob = super::protect(b"x").unwrap();
        let n = blob.len();
        blob[n - 1] ^= 0xff;
        assert!(super::unprotect(&blob).is_err());
    }
}

//! Pointer shapes from DXGI → RGBA PNG for `cursor.shape`, and CURSOR packet payloads.

use super::capture::Shape;

const MONOCHROME: u32 = 1;
const COLOR: u32 = 2;
const MASKED_COLOR: u32 = 4;

/// Straight RGBA pixels (w × h) of a DXGI pointer shape.
pub fn rgba(s: &Shape) -> Option<(u32, u32, Vec<u8>)> {
    let (w, p) = (s.w as usize, s.pitch as usize);
    match s.typ {
        COLOR | MASKED_COLOR => {
            let h = s.h as usize;
            let mut out = Vec::with_capacity(w * h * 4);
            for y in 0..h {
                for px in s.data.get(y * p..y * p + w * 4)?.as_chunks::<4>().0 {
                    let (b, g, r, a) = (px[0], px[1], px[2], px[3]);
                    out.extend(match s.typ {
                        // alpha 0: opaque colour; 0xFF: XOR with the screen, shown as black where non-zero
                        MASKED_COLOR if a == 0 => [r, g, b, 255],
                        MASKED_COLOR => [0, 0, 0, if (r | g | b) == 0 { 0 } else { 255 }],
                        _ => [r, g, b, a],
                    });
                }
            }
            Some((s.w, s.h, out))
        }
        MONOCHROME => {
            // AND mask on top, XOR mask below, 1 bit per pixel each
            let h = s.h as usize / 2;
            let bit = |row: usize, x: usize| s.data.get(row * p + x / 8).map(|b| b >> (7 - x % 8) & 1);
            let mut out = Vec::with_capacity(w * h * 4);
            for y in 0..h {
                for x in 0..w {
                    out.extend(match (bit(y, x)?, bit(y + h, x)?) {
                        (0, 0) => [0, 0, 0, 255],
                        (0, _) => [255, 255, 255, 255],
                        (_, 0) => [0, 0, 0, 0],
                        _ => [0, 0, 0, 255], // inverted: black reads on most backgrounds
                    });
                }
            }
            Some((s.w, h as u32, out))
        }
        _ => None,
    }
}

pub fn png(s: &Shape) -> Option<Vec<u8>> {
    let (w, h, px) = rgba(s)?;
    let mut out = Vec::new();
    image::codecs::png::PngEncoder::new(&mut out).write_image(&px, w, h, image::ExtendedColorType::Rgba8).ok()?;
    Some(out)
}

use image::ImageEncoder as _;

/// CURSOR plaintext: `x` i16 ‖ `y` i16 (stream pixels) ‖ `visible` u8.
pub fn packet(x: i32, y: i32, visible: bool) -> [u8; 5] {
    let mut b = [0u8; 5];
    b[..2].copy_from_slice(&(x.clamp(i16::MIN as i32, i16::MAX as i32) as i16).to_be_bytes());
    b[2..4].copy_from_slice(&(y.clamp(i16::MIN as i32, i16::MAX as i32) as i16).to_be_bytes());
    b[4] = visible as u8;
    b
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn monochrome_masks() {
        // 8x1 pointer: AND row then XOR row
        let s = Shape { typ: MONOCHROME, w: 8, h: 2, pitch: 1, hx: 0, hy: 0, data: vec![0b0000_1111, 0b0101_0101] };
        let (w, h, px) = rgba(&s).unwrap();
        assert_eq!((w, h), (8, 1));
        let a: Vec<[u8; 4]> = px.chunks(4).map(|c| c.try_into().unwrap()).collect();
        assert_eq!(a[0], [0, 0, 0, 255]); // AND 0, XOR 0 → black
        assert_eq!(a[1], [255, 255, 255, 255]); // AND 0, XOR 1 → white
        assert_eq!(a[4], [0, 0, 0, 0]); // AND 1, XOR 0 → transparent
        assert_eq!(a[5], [0, 0, 0, 255]); // AND 1, XOR 1 → inverted
    }

    #[test]
    fn color_is_bgra_to_rgba_and_png() {
        let s = Shape { typ: COLOR, w: 1, h: 1, pitch: 4, hx: 0, hy: 0, data: vec![1, 2, 3, 200] };
        assert_eq!(rgba(&s).unwrap().2, vec![3, 2, 1, 200]);
        assert_eq!(&png(&s).unwrap()[1..4], b"PNG");
    }

    #[test]
    fn cursor_packet_layout() {
        assert_eq!(packet(-2, 300, true), [0xFF, 0xFE, 0x01, 0x2C, 1]);
        assert_eq!(packet(70000, 0, false)[..2], [0x7F, 0xFF]);
    }
}

//! M2 — PNG decode/encode for model textures.
//!
//! Reference: upstream `natives/image/Image.java` plus
//! `tmp/YesSteveModel-Native-dev/modules/codec/src/codec/image/png.cc`. Upstream's native decoder
//! normalises whatever a pack ships to RGBA and hands the pixels to a `NativeImage`; this port's host
//! (`com.fox.ysmu.client.texture.OuterFileTexture`) consumes an `int[]` of `0xAARRGGBB` instead, so
//! this module's JNI contract is:
//!
//! * `int[] decodePng(byte[] src)` — `[width, height, argb...]`, row-major `0xAARRGGBB`, or `null`.
//!   A malformed/unsupported PNG is a declined decode, not an exception: the Java facade returns
//!   `null` to `OuterFileTexture`, which then runs its unchanged `ImageIO` path and reports the
//!   failure there. Only panics/JNI failures throw (via `jni_util::guard`), and those disable the
//!   module on the Java side.
//! * `byte[] encodePng(int width, int height, int[] argb, int level)` — RGBA8 PNG, or `null`.
//!
//! # Pixel parity with ImageIO
//!
//! The Java call sites used `ImageIO.read` followed by a `TYPE_INT_ARGB` blit
//! (`OuterFileTexture.toIntArgb`), i.e. the pixels are what that blit produces, which is *not* always
//! the raw PNG sample. Java2D has fast paths for `TYPE_BYTE_GRAY` and `TYPE_USHORT_GRAY` without
//! alpha (samples are copied, 16-bit keeps its high byte) but converts any `ComponentColorModel` with
//! alpha through the `CS_GRAY` ICC grey profile, which is an sRGB transfer. The decoder reproduces
//! that path colour type by colour type:
//!
//! | PNG input | pixel written |
//! | --- | --- |
//! | truecolour / truecolour+alpha, 8 bit | raw samples, alpha unmultiplied |
//! | truecolour / truecolour+alpha, 16 bit | `round(sample / 257)` per channel |
//! | palette, any bit depth | palette RGB, tRNS alpha |
//! | grayscale 1/2/4 bit, no alpha | `sample * 255 / (2^depth - 1)` |
//! | grayscale 8 bit, no alpha | raw sample |
//! | grayscale 16 bit, no alpha | `sample >> 8` (the Java2D `TYPE_USHORT_GRAY` fast path) |
//! | grayscale with alpha or tRNS, 1..8 bit | `sRGB(sample / max)`, alpha raw / 0 / 255 |
//! | grayscale with alpha or tRNS, 16 bit | `sRGB(sample / 65535)`, alpha `round(alpha / 257)` |
//! | grayscale + tRNS / colour-key tRNS | transparent pixel keeps its colour channels |
//!
//! One ImageIO quirk is worth naming: for sub-8-bit grayscale with a colour key, ImageIO compares
//! the *scaled* 8-bit sample against the raw tRNS value (PNGImageReader:1193-1207 then :1343-1349),
//! so only a key of 0 actually selects a pixel. The decoder reproduces that instead of the PNG spec
//! intent, because the Java path being replaced is the reference (see [`read_trns`]).
//!
//! PNG `gAMA`/`iCCP`/`sRGB` chunks are deliberately ignored, exactly as `ImageIO.read` ignored them
//! for the images this port decodes: there is no gamma decoding (see the module note in the ExecPlan).
//! Interlaced (Adam7) images are supported because the `png` crate de-interlaces them.
//!
//! The conversion formulas were verified upset-by-value against Zulu 25 `ImageIO` for all 256 8-bit
//! and all 65 536 16-bit grayscale values and for every colour type/bit-depth combination (the M2
//! harness compares full ARGB arrays; the `cfg(test)` fixtures below keep the representative values).

use std::io::Cursor;
use std::sync::LazyLock;

use jni::objects::{JByteArray, JClass, JIntArray};
use jni::sys::{jbyteArray, jint, jintArray};
use jni::JNIEnv;
use png::{BitDepth, ColorType, Encoder, Limits, Transformations};

use crate::jni_util;

/// Mirrors `com.fox.ysmu.client.texture.OuterFileTexture#MAX_DIMENSION`; keep the two in sync.
///
/// An image above the limit is declined here (null) *before* its pixels are allocated, so the Java
/// caller keeps its own "YSM texture is too large" log line and `IOException`, which is the behaviour
/// that existed before the native layer.
const MAX_DIMENSION: u32 = 4096;

/// The decoder's auxiliary-allocation budget (`png`'s default). This only guards pathological input:
/// the pixel buffers are allocated separately and are bounded by `MAX_DIMENSION`.
const MAX_DECODE_ALLOCATION: usize = 64 * 1024 * 1024;

/// Deflate effort for the encoder.
///
/// `png` 0.17 exposes three compressor configurations rather than a 0..=9 level: 0..=2 selects its
/// fastest path (fdeflate), 3..=7 the flate2 default (~level 6) and 8..=9 flate2 best (~level 9).
/// Every bucket is lossless, so the parameter only trades encode time against file size.
fn compression_for(level: i32) -> png::Compression {
    match level.clamp(0, 9) {
        0..=2 => png::Compression::Fast,
        3..=7 => png::Compression::Default,
        _ => png::Compression::Best,
    }
}

/// Number of bytes of an RGBA8 image, checked against `null` (returned by `decodePng`).
fn pixel_count(width: u32, height: u32) -> Option<usize> {
    (width as usize).checked_mul(height as usize)
}

fn pack_opaque(red: u8, green: u8, blue: u8) -> i32 {
    (0xFF << 24) | ((red as i32) << 16) | ((green as i32) << 8) | blue as i32
}

fn pack(alpha: u8, red: u8, green: u8, blue: u8) -> i32 {
    ((alpha as i32) << 24) | ((red as i32) << 16) | ((green as i32) << 8) | blue as i32
}

fn gray(level: u8) -> i32 {
    pack_opaque(level, level, level)
}

fn big_endian16(bytes: &[u8]) -> u16 {
    ((bytes[0] as u16) << 8) | bytes[1] as u16
}

/// The sRGB transfer function for one linear-light channel, rounded to 8 bits.
///
/// This is the transfer `ImageIO` performs when it normalises a `CS_GRAY` component colour model to
/// 8-bit sRGB; it is not a PNG gamma correction (no `gAMA` is read).
fn srgb_channel(linear: f64) -> u8 {
    let encoded = if linear <= 0.003_130_8 {
        12.92 * linear
    } else {
        1.055 * linear.powf(1.0 / 2.4) - 0.055
    };
    (encoded * 255.0).round().clamp(0.0, 255.0) as u8
}

/// Lazy lookup tables for the gray-to-sRGB transfer.
///
/// A per-pixel `powf` would cost more than the decode of a large texture and this path is already
/// the rare one; the tables are built once, 16-bit only when a 16-bit gray image is actually decoded.
static SRGB_8: LazyLock<[u8; 256]> = LazyLock::new(|| {
    let mut table = [0u8; 256];
    for (value, entry) in table.iter_mut().enumerate() {
        *entry = srgb_channel(value as f64 / 255.0);
    }
    table
});

static SRGB_16: LazyLock<Box<[u8; 65536]>> = LazyLock::new(|| {
    let mut table = Box::new([0u8; 65536]);
    for (value, entry) in table.iter_mut().enumerate() {
        *entry = srgb_channel(value as f64 / 65535.0);
    }
    table
});

/// 16-bit sample to 8-bit, as `ComponentColorModel` scales 16-bit truecolour/alpha samples:
/// `round(value / 257)`. The plain 16-bit grayscale path is different (it keeps the high byte); see
/// [`decode_png`].
fn scale_16(value: u16) -> u8 {
    ((value as u32 * 255 + 32767) / 65535) as u8
}

/// Finds the colour-key `tRNS` chunk of a grayscale/truecolour PNG.
///
/// The `png` crate trims the chunk for sub-16-bit images (it keeps the low byte), but `ImageIO`
/// compares against the full 16-bit value as stored - and for sub-8-bit grayscale it compares the
/// *scaled* sample, so a spec-valid key like 1 selects nothing. Reproducing that needs the raw
/// chunk; palette tRNS does not need it (the crate's expansion already matches ImageIO).
fn read_trns(source: &[u8]) -> Option<&[u8]> {
    if source.len() < 8 || source[..8] != [137, 80, 78, 71, 13, 10, 26, 10] {
        return None;
    }
    let mut offset = 8;
    while offset + 8 <= source.len() {
        let length = u32::from_be_bytes([
            source[offset],
            source[offset + 1],
            source[offset + 2],
            source[offset + 3],
        ]) as usize;
        let start = offset + 8;
        let end = start.checked_add(length)?;
        if end.checked_add(4)? > source.len() {
            return None;
        }
        match &source[offset + 4..offset + 8] {
            b"tRNS" => return Some(&source[start..end]),
            // Metadata chunks are complete once image data starts; a tRNS chunk after IDAT is
            // invalid and ImageIO ignores it too.
            b"IDAT" | b"IEND" => return None,
            _ => {}
        }
        offset = end + 4;
    }
    None
}

/// Reads a big-endian 16-bit tRNS component without panicking on a truncated chunk.
fn trns_component(trns: &[u8], index: usize) -> Option<u16> {
    let bytes = trns.get(index * 2..index * 2 + 2)?;
    Some(big_endian16(bytes))
}

/// Decodes a PNG to `[width, height, argb...]`, or `null` when the input cannot be decoded.
///
/// Returns `None` for anything that is not a PNG this module handles: not a PNG, malformed/truncated
/// data, dimensions above the Java texture limit, or a colour-type/bit-depth combination the `png`
/// crate did not expand (it declines rather than guessing pixels).
pub fn decode_png(source: &[u8]) -> Option<Vec<i32>> {
    if source.is_empty() {
        return None;
    }

    let mut decoder = png::Decoder::new(Cursor::new(source));
    decoder.set_limits(Limits { bytes: MAX_DECODE_ALLOCATION });
    // Everything the Java path could give us is normalised to 8-bit RGBA or gray by this one
    // transformation: palettes are expanded to RGB(A), sub-8-bit gray to 8-bit, and tRNS to alpha.
    // 16-bit samples stay big-endian, because the Java2D normalisation is not a plain `>> 8`.
    decoder.set_transformations(Transformations::EXPAND);

    let mut reader = decoder.read_info().ok()?;
    let (width, height) = reader.info().size();
    if width == 0 || height == 0 || width > MAX_DIMENSION || height > MAX_DIMENSION {
        return None;
    }
    let pixels = pixel_count(width, height)?;

    // The `png` crate's EXPAND already resolves tRNS to alpha, but its comparison follows the raw
    // PNG sample while ImageIO compares the (possibly 8-bit scaled) sample against the full 16-bit
    // colour key. Read the key ourselves for the two branches where that changes the alpha byte.
    let source_color = reader.info().color_type;
    let color_key = match source_color {
        ColorType::Grayscale | ColorType::Rgb => read_trns(source),
        _ => None,
    };
    let gray_key = match (source_color, color_key) {
        (ColorType::Grayscale, Some(key)) => Some(trns_component(key, 0)?),
        _ => None,
    };
    let rgb_key = match (source_color, color_key) {
        (ColorType::Rgb, Some(key)) => Some((
            trns_component(key, 0)?,
            trns_component(key, 1)?,
            trns_component(key, 2)?,
        )),
        _ => None,
    };

    let mut samples = zeroed_bytes(reader.output_buffer_size())?;
    let frame = reader.next_frame(&mut samples).ok()?;
    let samples = &samples[..frame.buffer_size()];

    // The two leading slots are the size header the Java facade documents.
    let mut decoded = zeroed_ints(pixels.checked_add(2)?)?;
    decoded[0] = width as i32;
    decoded[1] = height as i32;
    let destination = &mut decoded[2..];

    match (frame.color_type, frame.bit_depth) {
        (ColorType::Rgb, BitDepth::Eight) => {
            if samples.len() < pixels.checked_mul(3)? {
                return None;
            }
            for (pixel, rgb) in destination.iter_mut().zip(samples.chunks_exact(3)) {
                *pixel = pack_opaque(rgb[0], rgb[1], rgb[2]);
            }
        }
        (ColorType::Rgba, BitDepth::Eight) => {
            if samples.len() < pixels.checked_mul(4)? {
                return None;
            }
            for (pixel, rgba) in destination.iter_mut().zip(samples.chunks_exact(4)) {
                let alpha = match rgb_key {
                    // Truecolour + tRNS expanded by the crate: ImageIO compares against the full
                    // 16-bit key, so a key whose high byte is set can never match an 8-bit sample.
                    Some((red, green, blue))
                        if rgba[0] as u16 == red && rgba[1] as u16 == green && rgba[2] as u16 == blue =>
                    {
                        0
                    }
                    Some(_) => 255,
                    None => rgba[3],
                };
                *pixel = pack(alpha, rgba[0], rgba[1], rgba[2]);
            }
        }
        (ColorType::Grayscale, BitDepth::Eight) => {
            if samples.len() < pixels {
                return None;
            }
            for (pixel, &level) in destination.iter_mut().zip(samples.iter()) {
                *pixel = gray(level);
            }
        }
        (ColorType::GrayscaleAlpha, BitDepth::Eight) => {
            if samples.len() < pixels.checked_mul(2)? {
                return None;
            }
            for (pixel, samples) in destination.iter_mut().zip(samples.chunks_exact(2)) {
                let level = SRGB_8[samples[0] as usize];
                let alpha = match gray_key {
                    // Grayscale + tRNS: `ImageIO` compares the scaled 8-bit sample (the crate has
                    // already scaled it for sub-8-bit depths) with the full 16-bit key, so a
                    // spec-valid key other than 0 does not select any pixel there.
                    Some(key) => {
                        if samples[0] as u16 == key {
                            0
                        } else {
                            255
                        }
                    }
                    None => samples[1],
                };
                *pixel = pack(alpha, level, level, level);
            }
        }
        (ColorType::Rgb, BitDepth::Sixteen) => {
            if samples.len() < pixels.checked_mul(6)? {
                return None;
            }
            for (pixel, rgb) in destination.iter_mut().zip(samples.chunks_exact(6)) {
                *pixel = pack_opaque(
                    scale_16(big_endian16(&rgb[0..2])),
                    scale_16(big_endian16(&rgb[2..4])),
                    scale_16(big_endian16(&rgb[4..6])),
                );
            }
        }
        (ColorType::Rgba, BitDepth::Sixteen) => {
            if samples.len() < pixels.checked_mul(8)? {
                return None;
            }
            for (pixel, rgba) in destination.iter_mut().zip(samples.chunks_exact(8)) {
                *pixel = pack(
                    scale_16(big_endian16(&rgba[6..8])),
                    scale_16(big_endian16(&rgba[0..2])),
                    scale_16(big_endian16(&rgba[2..4])),
                    scale_16(big_endian16(&rgba[4..6])),
                );
            }
        }
        (ColorType::Grayscale, BitDepth::Sixteen) => {
            // No alpha/tRNS: Java2D's `TYPE_USHORT_GRAY` fast path keeps the high byte, without the
            // sRGB conversion it applies once the image has an alpha channel.
            if samples.len() < pixels.checked_mul(2)? {
                return None;
            }
            for (pixel, samples) in destination.iter_mut().zip(samples.chunks_exact(2)) {
                *pixel = gray((big_endian16(samples) >> 8) as u8);
            }
        }
        (ColorType::GrayscaleAlpha, BitDepth::Sixteen) => {
            if samples.len() < pixels.checked_mul(4)? {
                return None;
            }
            for (pixel, samples) in destination.iter_mut().zip(samples.chunks_exact(4)) {
                let level = SRGB_16[big_endian16(&samples[0..2]) as usize];
                *pixel = pack(scale_16(big_endian16(&samples[2..4])), level, level, level);
            }
        }
        // `Transformations::EXPAND` should have removed palettes and sub-8-bit depths; anything left
        // is a combination this module does not model. Decline it instead of inventing pixels.
        _ => return None,
    }

    Some(decoded)
}

/// Encodes `argb` as an RGBA8 PNG, or `None` when the input is invalid or encoding failed.
///
/// `level` is the deflate effort hint, see [`compression_for`]. Alpha is preserved unmultiplied, so
/// decoding the result gives back exactly the same `0xAARRGGBB` values.
pub fn encode_png(width: i32, height: i32, argb: &[i32], level: i32) -> Option<Vec<u8>> {
    if width <= 0 || height <= 0 {
        return None;
    }
    let width = width as u32;
    let height = height as u32;
    if width > MAX_DIMENSION || height > MAX_DIMENSION {
        return None;
    }
    let pixels = pixel_count(width, height)?;
    if argb.len() < pixels {
        return None;
    }

    let mut rgba = Vec::new();
    rgba.try_reserve_exact(pixels.checked_mul(4)?).ok()?;
    for &pixel in &argb[..pixels] {
        rgba.push((pixel >> 16) as u8);
        rgba.push((pixel >> 8) as u8);
        rgba.push(pixel as u8);
        rgba.push((pixel >> 24) as u8);
    }

    let mut encoded = Vec::new();
    let mut encoder = Encoder::new(&mut encoded, width, height);
    encoder.set_color(ColorType::Rgba);
    encoder.set_depth(BitDepth::Eight);
    encoder.set_compression(compression_for(level));
    let mut writer = encoder.write_header().ok()?;
    writer.write_image_data(&rgba).ok()?;
    writer.finish().ok()?;
    Some(encoded)
}

/// Allocates a zeroed byte buffer, returning `None` instead of aborting the JVM on allocation
/// failure (the process must not die because one texture is huge; the Java fallback reports it).
fn zeroed_bytes(length: usize) -> Option<Vec<u8>> {
    let mut buffer = Vec::new();
    buffer.try_reserve_exact(length).ok()?;
    buffer.resize(length, 0);
    Some(buffer)
}

/// Same as [`zeroed_bytes`], for the returned pixel array.
fn zeroed_ints(length: usize) -> Option<Vec<i32>> {
    let mut buffer = Vec::new();
    buffer.try_reserve_exact(length).ok()?;
    buffer.resize(length, 0);
    Some(buffer)
}

#[no_mangle]
pub extern "system" fn Java_com_fox_ysmu_nativebridge_NativeImages_decodePngNative<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    source: JByteArray<'local>,
) -> jintArray {
    let decoded = jni_util::guard(&mut env, None::<JIntArray<'local>>, |env| {
        let input = jni_util::read_bytes(env, &source)?;
        // Undecodable content is a declined decode (null, no exception). Only JNI failures and panics
        // throw here, which is what `NativeLoader.onModuleFailure` is meant to act on.
        let Some(pixels) = decode_png(&input) else {
            return Ok(None);
        };
        let length = i32::try_from(pixels.len())
            .map_err(|_| "decoded pixel array is too large for an int[]".to_string())?;
        let array = env
            .new_int_array(length)
            .map_err(|error| format!("failed to create int[]: {error}"))?;
        env.set_int_array_region(&array, 0, &pixels)
            .map_err(|error| format!("failed to fill int[]: {error}"))?;
        Ok(Some(array))
    });
    decoded.map(|array| array.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_com_fox_ysmu_nativebridge_NativeImages_encodePngNative<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    width: jint,
    height: jint,
    argb: JIntArray<'local>,
    level: jint,
) -> jbyteArray {
    let encoded = jni_util::guard(&mut env, None::<JByteArray<'local>>, |env| {
        let pixels = jni_util::read_ints(env, &argb)?;
        match encode_png(width, height, &pixels, level) {
            Some(bytes) => Ok(Some(jni_util::new_bytes(env, &bytes)?)),
            None => Ok(None),
        }
    });
    encoded.map(|array| array.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

#[cfg(test)]
mod tests {
    //! Pixel expectations in these tests were produced by the Java/ImageIO oracle used for the M2
    //! parity harness (Zulu 25, `ImageIO.read` + the `OuterFileTexture.toIntArgb` blit), so they pin
    //! the Java behaviour the native path replaces.

    use std::path::{Path, PathBuf};

    use png::{BitDepth, ColorType, Encoder};

    use super::{decode_png, encode_png};

    const PNG_SIGNATURE: [u8; 8] = [137, 80, 78, 71, 13, 10, 26, 10];

    /// Builds a PNG fixture with the `png` crate's encoder (the inverse direction of the module).
    fn fixture(
        width: u32,
        height: u32,
        color: ColorType,
        depth: BitDepth,
        samples: &[u8],
        palette: Option<Vec<u8>>,
        trns: Option<Vec<u8>>,
    ) -> Vec<u8> {
        let mut encoded = Vec::new();
        let mut encoder = Encoder::new(&mut encoded, width, height);
        encoder.set_color(color);
        encoder.set_depth(depth);
        if let Some(palette) = palette {
            encoder.set_palette(palette);
        }
        if let Some(trns) = trns {
            encoder.set_trns(trns);
        }
        let mut writer = encoder.write_header().expect("test PNG header");
        writer.write_image_data(samples).expect("test PNG data");
        writer.finish().expect("test PNG trailer");
        encoded
    }

    fn pixels(png: &[u8]) -> Vec<u32> {
        let decoded = decode_png(png).expect("fixture must decode");
        assert_eq!(decoded.len(), 2 + (decoded[0] * decoded[1]) as usize);
        decoded[2..].iter().map(|&pixel| pixel as u32).collect()
    }

    fn big_endian(values: &[u16]) -> Vec<u8> {
        values.iter().flat_map(|value| value.to_be_bytes()).collect()
    }

    #[test]
    fn round_trips_every_compression_bucket() {
        let (width, height) = (67u32, 43u32);
        let mut argb = Vec::with_capacity((width * height) as usize);
        for y in 0..height {
            for x in 0..width {
                let alpha = ((x * 31 + y * 17) & 0xFF) as u8;
                let red = ((x * 7 + y * 3) & 0xFF) as u8;
                let green = ((x * 13) & 0xFF) as u8;
                let blue = ((y * 29) & 0xFF) as u8;
                argb.push(super::pack(alpha, red, green, blue));
            }
        }

        for level in [0, 1, 3, 6, 7, 9] {
            let encoded = encode_png(width as i32, height as i32, &argb, level)
                .unwrap_or_else(|| panic!("encode failed for level {level}"));
            let decoded = decode_png(&encoded).unwrap_or_else(|| panic!("decode failed for level {level}"));
            assert_eq!(decoded[0], width as i32);
            assert_eq!(decoded[1], height as i32);
            assert_eq!(&decoded[2..], argb.as_slice(), "pixel mismatch for level {level}");
        }
    }

    #[test]
    fn decodes_the_built_in_png_corpus() {
        let root = Path::new(env!("CARGO_MANIFEST_DIR")).join("../../src/main/resources/assets/ysmu/builtin");
        let mut files = Vec::new();
        collect_pngs(&root, &mut files);
        files.sort();
        // The corpus is 154 files today; a lower bound keeps the test meaningful if it changes while
        // still failing loudly when the resources are not where the manifest expects them.
        assert!(files.len() >= 150, "expected the built-in PNG corpus under {}, found {} files", root.display(), files.len());

        let mut decoded_pixels = 0usize;
        for path in &files {
            let bytes = std::fs::read(path).unwrap_or_else(|error| panic!("cannot read {}: {error}", path.display()));
            let decoded = decode_png(&bytes).unwrap_or_else(|| panic!("built-in PNG failed to decode: {}", path.display()));
            let (width, height) = ihdr_dimensions(&bytes);
            assert_eq!(decoded[0], width as i32, "width mismatch: {}", path.display());
            assert_eq!(decoded[1], height as i32, "height mismatch: {}", path.display());
            assert_eq!(decoded.len(), 2 + width as usize * height as usize, "pixel count mismatch: {}", path.display());
            decoded_pixels += width as usize * height as usize;
        }
        assert!(decoded_pixels > 0, "the corpus must contain pixels, not just empty images");
    }

    #[test]
    fn declines_malformed_input_without_panicking() {
        assert!(decode_png(&[]).is_none(), "empty input");
        assert!(decode_png(b"not a png at all").is_none(), "garbage");
        assert!(decode_png(&[PNG_SIGNATURE, [0; 8]].concat()).is_none(), "signature only");

        let valid = encode_png(4, 4, &[0xFF00_0000u32 as i32; 16], 6).expect("encode");
        for cut in [8usize, 20, valid.len() / 2] {
            assert!(decode_png(&valid[..cut]).is_none(), "truncated at {cut} bytes");
        }

        // A PNG whose IHDR exceeds the Java texture limit is declined before any pixel allocation, so
        // the Java caller keeps its own rejection path.
        let oversized = fixture(
            5000,
            1,
            ColorType::Rgb,
            BitDepth::Eight,
            &[0u8; 5000 * 3],
            None,
            None,
        );
        assert!(decode_png(&oversized).is_none(), "oversized image");
    }

    #[test]
    fn normalises_every_colour_type_like_imageio() {
        // 8-bit truecolour: raw samples, opaque alpha.
        let rgb = fixture(2, 1, ColorType::Rgb, BitDepth::Eight, &[1, 2, 3, 255, 128, 0], None, None);
        assert_eq!(pixels(&rgb), vec![0xFF01_0203, 0xFFFF_8000]);

        // 8-bit RGBA: raw samples with their alpha, unmultiplied (0x80 alpha keeps colour 0x11).
        let rgba = fixture(1, 1, ColorType::Rgba, BitDepth::Eight, &[0x11, 0x22, 0x33, 0x80], None, None);
        assert_eq!(pixels(&rgba), vec![0x8011_2233]);

        // 8-bit grayscale: raw sample (Java2D's TYPE_BYTE_GRAY fast path).
        let gray = fixture(4, 1, ColorType::Grayscale, BitDepth::Eight, &[0, 1, 128, 255], None, None);
        assert_eq!(pixels(&gray), vec![0xFF00_0000, 0xFF01_0101, 0xFF80_8080, 0xFFFF_FFFF]);

        // 8-bit grayscale + alpha: the RGB channels go through the CS_GRAY -> sRGB transfer (1 -> 13,
        // 128 -> 188), the alpha channel is raw.
        let gray_alpha = fixture(
            4,
            1,
            ColorType::GrayscaleAlpha,
            BitDepth::Eight,
            &[0, 255, 1, 255, 128, 128, 255, 0],
            None,
            None,
        );
        assert_eq!(
            pixels(&gray_alpha),
            vec![0xFF00_0000, 0xFF0D_0D0D, 0x80BC_BCBC, 0x00FF_FFFF]
        );

        // 16-bit grayscale without alpha: high byte (the TYPE_USHORT_GRAY fast path).
        let gray16 = fixture(
            4,
            1,
            ColorType::Grayscale,
            BitDepth::Sixteen,
            &big_endian(&[0x00FF, 0x0100, 0x8000, 0xFFFF]),
            None,
            None,
        );
        assert_eq!(pixels(&gray16), vec![0xFF00_0000, 0xFF01_0101, 0xFF80_8080, 0xFFFF_FFFF]);

        // 16-bit grayscale + alpha: sRGB transfer for the colour, round(sample / 257) for the alpha.
        let gray_alpha16 = fixture(
            4,
            1,
            ColorType::GrayscaleAlpha,
            BitDepth::Sixteen,
            &big_endian(&[0x0001, 0x00FF, 0x00FF, 0x00FF, 0x8000, 0x8000, 0xFFFF, 0xFFFF]),
            None,
            None,
        );
        assert_eq!(
            pixels(&gray_alpha16),
            vec![0x0100_0000, 0x010D_0D0D, 0x80BC_BCBC, 0xFFFF_FFFF]
        );

        // 16-bit truecolour: round(sample / 257) per channel.
        let rgb16 = fixture(
            1,
            1,
            ColorType::Rgb,
            BitDepth::Sixteen,
            &big_endian(&[0x00FF, 0x7F7F, 0xFFFF]),
            None,
            None,
        );
        assert_eq!(pixels(&rgb16), vec![0xFF01_7FFF]);

        // 16-bit RGBA: same scaling for all four channels.
        let rgba16 = fixture(
            2,
            1,
            ColorType::Rgba,
            BitDepth::Sixteen,
            &big_endian(&[
                0x0102, 0x0304, 0x0506, 0x0708, 0xFFFF, 0x00FF, 0x8000, 0x0100,
            ]),
            None,
            None,
        );
        assert_eq!(pixels(&rgba16), vec![0x0701_0305, 0x01FF_0180]);

        // Palette with tRNS: palette RGB, tRNS alpha.
        let palette = fixture(
            4,
            1,
            ColorType::Indexed,
            BitDepth::Eight,
            &[0, 1, 2, 0],
            Some(vec![255, 0, 0, 0, 255, 0, 0, 0, 255]),
            Some(vec![255, 128, 0]),
        );
        assert_eq!(pixels(&palette), vec![0xFFFF_0000, 0x8000_FF00, 0x0000_00FF, 0xFFFF_0000]);

        // Truecolour with a tRNS colour key: transparent pixels keep their colour channels.
        let rgb_key = fixture(
            2,
            1,
            ColorType::Rgb,
            BitDepth::Eight,
            &[1, 2, 3, 255, 255, 255],
            None,
            Some(vec![0, 1, 0, 2, 0, 3]),
        );
        assert_eq!(pixels(&rgb_key), vec![0x0001_0203, 0xFFFF_FFFF]);

        // Grayscale with a tRNS colour key: the keyed sample is transparent, the rest go through the
        // CS_GRAY -> sRGB transfer (42 -> 113, 7 -> 46).
        let gray_key = fixture(2, 1, ColorType::Grayscale, BitDepth::Eight, &[42, 7], None, Some(vec![0, 42]));
        assert_eq!(pixels(&gray_key), vec![0x0071_7171, 0xFF2E_2E2E]);

        // Sub-8-bit grayscale without alpha is scaled to 0..255 (2 bit: 0, 85, 170, 255).
        let gray2 = fixture(4, 1, ColorType::Grayscale, BitDepth::Two, &[0b00_01_10_11], None, None);
        assert_eq!(pixels(&gray2), vec![0xFF00_0000, 0xFF55_5555, 0xFFAA_AAAA, 0xFFFF_FFFF]);

        // ... and with a tRNS key it additionally goes through the sRGB transfer. ImageIO compares
        // the scaled 8-bit sample with the full 16-bit key, so the spec-valid key 0 selects sample
        // 0 while key 1 matches nothing.
        let gray2_key0 = fixture(4, 1, ColorType::Grayscale, BitDepth::Two, &[0b00_01_10_11], None, Some(vec![0, 0]));
        assert_eq!(pixels(&gray2_key0), vec![0x0000_0000, 0xFF9C_9C9C, 0xFFD5_D5D5, 0xFFFF_FFFF]);
        let gray2_key1 = fixture(4, 1, ColorType::Grayscale, BitDepth::Two, &[0b00_01_10_11], None, Some(vec![0, 1]));
        assert_eq!(pixels(&gray2_key1), vec![0xFF00_0000, 0xFF9C_9C9C, 0xFFD5_D5D5, 0xFFFF_FFFF]);
        // The same full-key comparison applies to an 8-bit truecolour colour key.
        let rgb_key_high = fixture(
            1,
            1,
            ColorType::Rgb,
            BitDepth::Eight,
            &[1, 2, 3],
            None,
            Some(vec![1, 1, 0, 2, 0, 3]),
        );
        assert_eq!(pixels(&rgb_key_high), vec![0xFF01_0203]);
    }

    #[test]
    fn encode_rejects_invalid_buffers_instead_of_panicking() {
        assert!(encode_png(0, 1, &[0], 6).is_none(), "zero width");
        assert!(encode_png(1, -1, &[0], 6).is_none(), "negative height");
        assert!(encode_png(2, 2, &[0xFF00_0000u32 as i32; 3], 6).is_none(), "short pixel array");
        assert!(encode_png(5000, 1, &[0xFF00_0000u32 as i32; 5000], 6).is_none(), "oversized image");
    }

    fn collect_pngs(directory: &Path, files: &mut Vec<PathBuf>) {
        let entries = match std::fs::read_dir(directory) {
            Ok(entries) => entries,
            Err(_) => return,
        };
        for entry in entries.flatten() {
            let path = entry.path();
            if path.is_dir() {
                collect_pngs(&path, files);
            } else if path
                .extension()
                .is_some_and(|extension| extension.eq_ignore_ascii_case("png"))
            {
                files.push(path);
            }
        }
    }

    fn ihdr_dimensions(bytes: &[u8]) -> (u32, u32) {
        assert!(bytes.len() >= 24, "PNG shorter than its IHDR");
        assert_eq!(&bytes[..8], &PNG_SIGNATURE, "not a PNG");
        let width = u32::from_be_bytes([bytes[16], bytes[17], bytes[18], bytes[19]]);
        let height = u32::from_be_bytes([bytes[20], bytes[21], bytes[22], bytes[23]]);
        (width, height)
    }
}

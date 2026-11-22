//! M1 — standard zstd frames.
//!
//! Reference: upstream's `natives/Zstd.java` (a thin JNI wrapper) and
//! `tmp/YesSteveModel-Native-dev/modules/codec/src/codec/zstd.cc`. The Java side keeps everything that
//! is *protocol* — `rip/ysm/algorithms/YsmZstd.wash`/`obfuscate` rewrite the frame header/footer that
//! YSM expects — so this module only has to be a correct, fast standard-zstd codec.
//!
//! The bounded output contract matters for security: `YsmCrypt` decompresses network payloads, so the
//! caller always supplies an upper bound and a frame that would exceed it fails instead of allocating.

use jni::objects::{JByteArray, JClass};
use jni::sys::{jbyteArray, jint};
use jni::JNIEnv;

use crate::jni_util;

/// Decompresses one standard zstd frame, refusing to produce more than `max_output` bytes.
///
/// Two frame shapes exist in practice: frames with a declared content size (`zstd::bulk` can allocate
/// exactly and hand the data back in one call) and frames without one. The vendored pure-Java compressor
/// this port used before produces the latter, and existing caches on disk were written that way, so the
/// streaming path below is the one that actually serves old payloads: it decodes with a running byte
/// count and fails as soon as the bound would be crossed, exactly like `ZstdUtil`'s streaming branch.
pub fn decompress(data: &[u8], max_output: usize) -> Result<Vec<u8>, String> {
    if data.is_empty() {
        return Ok(Vec::new());
    }
    if max_output == 0 {
        return Err("zstd output limit is 0".to_string());
    }
    let declared = match zstd::zstd_safe::get_frame_content_size(data) {
        Ok(Some(size)) => Some(size),
        Ok(None) => None,
        Err(error) => return Err(format!("zstd frame header rejected: {error}")),
    };
    if let Some(size) = declared {
        if size > max_output as u64 {
            return Err(format!("zstd frame declares {size} bytes, above the {max_output} byte limit"));
        }
        if size > i32::MAX as u64 {
            return Err("zstd frame declares more than Integer.MAX_VALUE bytes".to_string());
        }
        return zstd::bulk::decompress(data, size as usize)
            .map_err(|error| format!("zstd decompress failed: {error}"));
    }
    stream_decompress(data, max_output)
}

/// Streaming decode for frames whose header declares no content size.
fn stream_decompress(data: &[u8], max_output: usize) -> Result<Vec<u8>, String> {
    use std::io::Read;

    let mut decoder = zstd::stream::read::Decoder::new(data)
        .map_err(|error| format!("zstd stream decoder rejected the frame: {error}"))?;
    // `max_output` may be Integer.MAX_VALUE (the Java facade's "no explicit bound" for the trusted
    // cache path); the running check below still keeps a corrupt frame from growing without limit.
    let limit = max_output.min(i32::MAX as usize);
    let mut output = Vec::new();
    let mut buffer = [0u8; 64 * 1024];
    loop {
        let read = decoder
            .read(&mut buffer)
            .map_err(|error| format!("zstd stream decode failed: {error}"))?;
        if read == 0 {
            return Ok(output);
        }
        if output.len() + read > limit {
            return Err(format!("zstd stream exceeds the {limit} byte limit"));
        }
        output.extend_from_slice(&buffer[..read]);
    }
}

/// Compresses `data` into one standard zstd frame. Levels outside 1..=19 are clamped (the vendored
/// Java zstd only implements the normal zstd range, and the Java callers pass 3).
pub fn compress(data: &[u8], level: i32) -> Result<Vec<u8>, String> {
    let level = level.clamp(1, 19) as i32;
    zstd::bulk::compress(data, level).map_err(|error| format!("zstd compress failed: {error}"))
}

#[no_mangle]
pub extern "system" fn Java_com_fox_ysmu_nativebridge_NativeCodecs_zstdDecompressNative<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    source: JByteArray<'local>,
    max_output_bytes: jint,
) -> jbyteArray {
    let result = jni_util::guard(&mut env, None::<JByteArray<'local>>, |env| {
        let limit = max_output_bytes.max(0) as usize;
        // The input is read through a JNI critical-array view instead of a copy: payloads are up to a few
        // MB and the copy was a measurable share of the whole call in the parity benchmark. A codec-level
        // failure is "this module declines the input", not a native layer failure: the Java caller then
        // runs its own implementation, which reports the real decode error to the user. Only panics and
        // JNI-level errors (which `guard` turns into exceptions) disable the module.
        let mut output: Option<Vec<u8>> = None;
        jni_util::with_bytes(env, &source, |input| output = decompress(input, limit).ok())?;
        match output {
            Some(bytes) => Ok(Some(jni_util::new_bytes(env, &bytes)?)),
            None => Ok(None),
        }
    });
    result.map(|array| array.into_raw()).unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_com_fox_ysmu_nativebridge_NativeCodecs_zstdCompressNative<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    source: JByteArray<'local>,
    level: jint,
) -> jbyteArray {
    let result = jni_util::guard(&mut env, None::<JByteArray<'local>>, |env| {
        let mut output: Option<Vec<u8>> = None;
        jni_util::with_bytes(env, &source, |input| output = compress(input, level).ok())?;
        match output {
            Some(bytes) => Ok(Some(jni_util::new_bytes(env, &bytes)?)),
            None => Ok(None),
        }
    });
    result.map(|array| array.into_raw()).unwrap_or(std::ptr::null_mut())
}

#[cfg(test)]
mod tests {
    use super::{compress, decompress};

    fn payload(length: usize) -> Vec<u8> {
        // Compressible but not trivial: a repeating pattern with a per-block counter, which is closer to
        // the JSON/PNG payloads than all-zeros and would catch a codec that only handles one block.
        let mut data = Vec::with_capacity(length);
        let mut counter = 0u32;
        while data.len() < length {
            data.extend_from_slice(b"{\"bone\":\"body\",\"values\":[");
            data.extend_from_slice(&counter.to_le_bytes());
            data.extend_from_slice(b"],\"uv\":[0.0,0.0]}");
            counter = counter.wrapping_mul(1664525).wrapping_add(1013904223);
        }
        data.truncate(length);
        data
    }

    #[test]
    fn round_trips_every_compression_level() {
        let source = payload(64 * 1024);
        for level in 1..=19 {
            let compressed = compress(&source, level).expect("compress");
            let restored = decompress(&compressed, source.len()).expect("decompress");
            assert_eq!(restored, source, "level {level}");
        }
    }

    #[test]
    fn honours_the_output_bound() {
        let source = payload(16 * 1024);
        let compressed = compress(&source, 3).expect("compress");
        let too_small = source.len() - 1;
        assert!(
            decompress(&compressed, too_small).is_err(),
            "the bound must be enforced, not ignored"
        );
    }

    #[test]
    fn streams_frames_without_a_declared_content_size() {
        // The vendored Java compressor (and therefore every cache file written before this layer
        // existed) produces frames whose header declares no content size. `encode_all` goes through the
        // streaming encoder, which likewise cannot declare one, so this exercises the same branch.
        let source = payload(48 * 1024);
        let frame = zstd::stream::encode_all(source.as_slice(), 3).expect("stream compress");
        assert_eq!(
            zstd::zstd_safe::get_frame_content_size(&frame).expect("header").is_none(),
            true,
            "this test must exercise the no-content-size branch"
        );
        assert_eq!(decompress(&frame, source.len()).expect("stream decode"), source);
        assert!(decompress(&frame, source.len() - 1).is_err(), "the bound must apply to streams too");
    }

    #[test]
    fn empty_input_is_an_empty_result() {
        assert_eq!(decompress(&[], 1024).expect("empty"), Vec::<u8>::new());
    }

    #[test]
    fn garbage_fails_instead_of_panicking() {
        // Malformed input is declined (the Java path then reports the real decode error), not thrown.
        assert!(decompress(b"not a zstd frame", 1024).is_err());
    }
}

//! YSMU Rust acceleration layer.
//!
//! This crate is loaded by `com.fox.ysmu.nativebridge.NativeLoader` as a JNI `cdylib` and is a pure
//! CPU helper: it never touches OpenGL and never allocates Java objects beyond the ones it returns.
//! The reference implementation of the same split is upstream's native renderer
//! (`tmp/YesSteveModel-Native-dev/modules/gfx`), which bakes geometry once and then writes the whole
//! per-frame vertex stream from native code into the host renderer's vertex buffer; the host keeps
//! every GL decision. This crate follows that shape with the 1.7.10 `Tessellator` as the host buffer.
//!
//! Module map:
//!
//! | module | what it replaces on the Java side | reference |
//! | --- | --- | --- |
//! | [`codec`] | `rip/ysm/algorithms/YsmZstd` + `rip/ysm/zstd/ZstdUtil` | `natives/Zstd.java`, `codec/zstd.cc` |
//! | [`image`] | `javax.imageio.ImageIO` PNG decode/encode | `natives/image/Image.java`, `codec/image/png.cc` |
//! | [`geometry`] | `Converter` + `RawGeometryTree` + `GeoBuilder` | `natives/render/NativeBakedModel.java`, `gfx/src/bake/baked_model.cc` |
//! | [`render`] | `IGeoRenderer.renderCube` vertex loop | `natives/render/NativeRenderer.java`, `gfx/src/renderer/render_impl.inc` |
//!
//! Every JNI entry follows the same contract: inputs are copied out of the Java heap into Rust
//! memory, work happens without any JNI calls, outputs are copied back once, and a panic is caught and
//! turned into a Java `IllegalStateException`. The Java facade classes catch that exception, turn the
//! whole native layer off for the session and continue on the Java implementation, so a bug here can
//! cost performance but never correctness or a crash.

mod codec;
mod geometry;
mod image;
mod jni_util;
mod render;

use jni::objects::JClass;
use jni::sys::jint;
use jni::JNIEnv;

/// Must match `com.fox.ysmu.nativebridge.NativeLoader#ABI_VERSION`.
///
/// Bumped whenever a JNI signature or a native/host memory layout changes; `NativeLoader` refuses a
/// library whose value differs, which turns a stale extracted copy left by an older mod version into a
/// clean fallback instead of an `UnsatisfiedLinkError` at the first model load.
///
/// History: 1 = zstd + PNG only; 2 = geometry bake/blob/info entries and the render-ready layout
/// (`BakedGeometry`/`BakedBone`/`BakedCube`/`BakedQuad`) plus the geometry handle registry, and the two
/// draw-group render entries (`emitNative` = single-group parity contract, `emitAtNative` = production
/// append path that keeps one Tessellator draw per model).
pub const ABI_VERSION: i32 = 2;

#[no_mangle]
pub extern "system" fn Java_com_fox_ysmu_nativebridge_NativeLoader_nVersion<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> jint {
    ABI_VERSION
}

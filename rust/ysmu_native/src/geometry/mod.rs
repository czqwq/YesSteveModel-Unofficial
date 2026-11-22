//! M3 — geometry parse + bake.
//!
//! Replaces, when available, the Java path `ClientModelManager.buildGeo(id, byte[])`:
//! `Converter.fromJsonString` (Jackson) -> `RawGeometryTree.parseHierarchy` -> `GeoBuilder.constructGeoModel`
//! (`src/main/java/software/bernie/geckolib3/geo/render/GeoBuilder.java`) which materialises
//! `GeoModel -> GeoBone -> GeoCube -> GeoQuad -> GeoVertex`.
//!
//! Reference for the shape: upstream bakes the model in native code
//! (`tmp/YesSteveModel-Native-dev/modules/gfx/src/bake/baked_model.cc`) from a serialised geometry message
//! and returns an immutable native model plus a compact serialised cache
//! (`natives/render/NativeBakedModel.java` `bake`/`serialize`/`read`). This port has no protobuf layer, so
//! the input is the same geometry JSON the pack ships and the output is a compact little-endian blob that
//! the Java side materialises into the engine's existing object graph. The same native handle keeps a
//! render-ready copy of the geometry for [`crate::render`], so a model is parsed exactly once.
//!
//! Module map: [`schema`] mirrors the engine's Jackson POJOs, [`jmap`] reproduces the Java `HashMap`
//! iteration order the hierarchy is built with, [`bake`] is the `RawGeometryTree` + `GeoBuilder` + `GeoCube`
//! port, [`blob`] is the materialisation format and [`model`] holds the shared per-model data types.
//!
//! JNI contract (see also `com.fox.ysmu.nativebridge.NativeGeometry`):
//!
//! * `long bakeNative(byte[] geometryJson, int formatVersion)` — handle, or `0` on a rejected model. The
//!   bake declines (never throws) for malformed JSON, an unsupported layout and every shape the Java
//!   builder would reject, so the caller can run the Java path and report the real error.
//! * `int blobSizeNative(long handle)` — blob length in bytes, `0` for an invalid handle.
//! * `int readBlobNative(long handle, byte[] dst)` — copies the blob into `dst`, returning the byte count,
//!   `-1` when `dst` is too small and `0` for an invalid handle.
//! * `int[] infoNative(long handle)` — `[boneCount, cubeCount, quadCount, flags]`, flags bit0 = flat cubes,
//!   bit1 = negative-size cubes, bit2 = mesh cubes, or `null` for an invalid handle.
//! * `void freeNative(long handle)`.
//!
//! The blob layout is defined next to the code that writes it; the Java reader
//! (`com.fox.ysmu.nativebridge.NativeGeometryReader`) must produce objects equal, field by field, to what
//! `GeoBuilder` produces. What "equal" means here is the contract `rust/parity` enforces: this module must
//! reproduce `GeoBuilder.constructGeoModel`/`constructBone`
//! (`src/main/java/software/bernie/geckolib3/geo/render/GeoBuilder.java`) and
//! `GeoCube.createFromPojoCube`/`createFromPolyMesh`
//! (`src/main/java/software/bernie/geckolib3/geo/render/built/GeoCube.java`) exactly, including that class'
//! two different mirrored vertex layouts (the box-UV branch swaps only west/east, `GeoCube.java:476-531`,
//! while the per-face-UV branch also swaps up/down, `:329-396` - a real bug the harness caught). The
//! harness compares the materialised graph field by field, order-sensitively and with raw f32 bits, so
//! `-0.0` vs `0.0`, Java `HashMap` iteration order and the exact UV arithmetic all count; a change here is
//! correct only when `rust/parity`'s geometry corpus (every geometry JSON of
//! `src/main/resources/assets/ysmu/builtin`) is green.

pub mod bake;
pub mod blob;
pub mod jmap;
pub mod model;
pub mod schema;

use jni::objects::{JByteArray, JClass, JIntArray};
use jni::sys::{jint, jintArray, jlong};
use jni::JNIEnv;

use crate::geometry::model::registry;
use crate::jni_util;

/// `NativeGeometry.bakeNative`: bakes one geometry JSON document, returning a registry handle or `0`.
///
/// A decline is the normal "use the Java path" answer and is returned, not thrown; only a panic or a JNI
/// failure reaches [`jni_util::guard`]'s exception path (which disables the module for the session).
#[no_mangle]
pub extern "system" fn Java_com_fox_ysmu_nativebridge_NativeGeometry_bakeNative<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    json: JByteArray<'local>,
    format_version: jint,
) -> jlong {
    jni_util::guard(&mut env, 0, |env| {
        let bytes = jni_util::read_bytes(env, &json)?;
        match bake::bake_json(&bytes, format_version) {
            Ok(baked) => Ok(registry().insert(baked) as jlong),
            Err(_) => Ok(0),
        }
    })
}

/// `NativeGeometry.blobSizeNative`: the materialisation blob length, `0` for an invalid handle.
#[no_mangle]
pub extern "system" fn Java_com_fox_ysmu_nativebridge_NativeGeometry_blobSizeNative<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
) -> jint {
    jni_util::guard(&mut env, 0, |_env| {
        Ok(registry()
            .get(handle as u64)
            .map(|geometry| geometry.blob.len() as jint)
            .unwrap_or(0))
    })
}

/// `NativeGeometry.readBlobNative`: copies the blob into the caller's `byte[]`.
///
/// The blob can be a megabyte or two for a large pack, so it is written straight into the Java array
/// through the JNI array-elements API instead of allocating an intermediate copy.
#[no_mangle]
pub extern "system" fn Java_com_fox_ysmu_nativebridge_NativeGeometry_readBlobNative<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    destination: JByteArray<'local>,
) -> jint {
    jni_util::guard(&mut env, -1, |env| {
        let geometry = registry().get(handle as u64);
        let Some(geometry) = geometry else {
            return Ok(0);
        };
        let blob = &geometry.blob;
        let length = env
            .get_array_length(&destination)
            .map_err(|error| format!("failed to read byte[] length: {error}"))? as usize;
        if blob.len() > length {
            return Ok(-1);
        }
        let mut elements = unsafe { env.get_array_elements(&destination, jni::objects::ReleaseMode::CopyBack) }
            .map_err(|error| format!("failed to access byte[]: {error}"))?;
        for (target, source) in elements.iter_mut().zip(blob.iter()) {
            *target = *source as i8;
        }
        Ok(blob.len() as jint)
    })
}

/// `NativeGeometry.infoNative`: `[boneCount, cubeCount, quadCount, flags]`, or `null` for an invalid handle.
#[no_mangle]
pub extern "system" fn Java_com_fox_ysmu_nativebridge_NativeGeometry_infoNative<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
) -> jintArray {
    jni_util::guard(&mut env, std::ptr::null_mut(), |env| {
        let Some(geometry) = registry().get(handle as u64) else {
            return Ok(std::ptr::null_mut());
        };
        let values = [
            geometry.bone_count as jint,
            geometry.cube_count as jint,
            geometry.quad_count as jint,
            geometry.flags as jint,
        ];
        let array: JIntArray<'local> = env
            .new_int_array(values.len() as i32)
            .map_err(|error| format!("failed to create int[]: {error}"))?;
        env.set_int_array_region(&array, 0, &values)
            .map_err(|error| format!("failed to write int[]: {error}"))?;
        Ok(array.into_raw())
    })
}

/// `NativeGeometry.freeNative`: drops one handle. Removing an unknown handle is not an error.
#[no_mangle]
pub extern "system" fn Java_com_fox_ysmu_nativebridge_NativeGeometry_freeNative<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
) {
    let _ = jni_util::guard(&mut env, (), |_env| {
        registry().remove(handle as u64);
        Ok(())
    });
}

#[cfg(test)]
mod tests {
    use std::path::{Path, PathBuf};

    use crate::geometry::blob::test_support::read_summary;
    use crate::geometry::bake::bake_json;
    use crate::geometry::model::{registry, FLAG_FLAT_CUBES, FLAG_MESH_CUBES, FLAG_NEGATIVE_CUBES};

    const CORPUS_ROOT: &str = concat!(env!("CARGO_MANIFEST_DIR"), "/../../src/main/resources/assets/ysmu/builtin");

    fn collect_json_files(directory: &Path, files: &mut Vec<PathBuf>) {
        let Ok(entries) = std::fs::read_dir(directory) else {
            return;
        };
        for entry in entries.flatten() {
            let path = entry.path();
            if path.is_dir() {
                collect_json_files(&path, files);
            } else if path.extension().is_some_and(|extension| extension == "json") {
                files.push(path);
            }
        }
    }

    fn geometry_files() -> Vec<PathBuf> {
        let mut files = Vec::new();
        collect_json_files(Path::new(CORPUS_ROOT), &mut files);
        files.sort();
        files
            .into_iter()
            .filter(|path| {
                std::fs::read(path)
                    .map(|bytes| contains_geometry_marker(&bytes))
                    .unwrap_or(false)
            })
            .collect()
    }

    fn contains_geometry_marker(bytes: &[u8]) -> bool {
        const MARKER: &[u8] = b"minecraft:geometry";
        bytes.windows(MARKER.len()).any(|window| window == MARKER)
    }

    fn relative(path: &Path) -> String {
        path.strip_prefix(CORPUS_ROOT).unwrap_or(path).display().to_string()
    }

    /// Acceptance for M3: every geometry file of the built-in corpus bakes without panicking, produces
    /// plausible counts and geometry bounds, declares itself consistently in `info`, and its
    /// materialisation blob round-trips (structure + counts + exact length).
    ///
    /// The field-level comparison against Java `Converter` + `GeoBuilder` is the verifier's parity
    /// harness; this test is the fast, dependency-free half that catches layout, count and overflow bugs
    /// in the bake itself.
    #[test]
    fn every_builtin_geometry_bakes_and_round_trips() {
        let files = geometry_files();
        assert!(files.len() >= 80, "expected the full built-in geometry corpus, found {}", files.len());
        let mut healthy = 0usize;
        let mut declined: Vec<String> = Vec::new();
        let mut total_bones = 0usize;
        let mut total_cubes = 0usize;
        let mut total_quads = 0usize;
        let mut max_vertices_per_cube = 0usize;
        for path in &files {
            let bytes = std::fs::read(path).expect("read corpus file");
            let baked = match bake_json(&bytes, 0) {
                Ok(baked) => baked,
                Err(reason) => {
                    declined.push(format!("{}: {reason}", relative(path)));
                    continue;
                }
            };
            healthy += 1;
            total_bones += baked.bone_count as usize;
            total_cubes += baked.cube_count as usize;
            total_quads += baked.quad_count as usize;

            // Plausible counts: at least one bone, the declared counts equal the vectors, and every
            // bone's cube range is inside the cube list.
            assert!(baked.bone_count > 0, "{}: no bones", relative(path));
            assert_eq!(baked.bone_count as usize, baked.bones.len(), "{}", relative(path));
            assert_eq!(baked.cube_count as usize, baked.cubes.len(), "{}", relative(path));
            for (index, bone) in baked.bones.iter().enumerate() {
                let start = bone.cube_start as usize;
                let end = start + bone.cube_count as usize;
                assert!(end <= baked.cubes.len(), "{}: bone {index} cube range", relative(path));
                assert!(bone.parent < index as i32, "{}: bone {index} parent order", relative(path));
            }
            assert!(
                baked
                    .bones
                    .iter()
                    .map(|bone| bone.cube_count as usize)
                    .sum::<usize>()
                    == baked.cube_count as usize,
                "{}: bone cube ranges do not cover every cube",
                relative(path)
            );

            // Plausible bounds: positions are block units and every built-in model fits in a generous box.
            for cube in &baked.cubes {
                let positions: Vec<[f32; 3]> = if cube.mesh {
                    cube.mesh_positions.clone()
                } else {
                    cube.positions.to_vec()
                };
                max_vertices_per_cube = max_vertices_per_cube.max(positions.len());
                for position in &positions {
                    for axis in position {
                        assert!(axis.is_finite(), "{}: non-finite vertex position", relative(path));
                        assert!(axis.abs() < 4096.0, "{}: vertex position {axis} out of range", relative(path));
                    }
                }
                for quad in &cube.quads {
                    for coordinate in quad.uvs.iter().flat_map(|uv| uv.iter()) {
                        assert!(coordinate.is_finite(), "{}: non-finite uv", relative(path));
                    }
                    for axis in quad.normal {
                        assert!(axis.is_finite(), "{}: non-finite normal", relative(path));
                    }
                }
            }

            // The model flags describe the cubes that are really there.
            let has_flat = baked.cubes.iter().any(|cube| !cube.mesh && cube.flat);
            let has_negative = baked.cubes.iter().any(|cube| cube.negative);
            let has_mesh = baked.cubes.iter().any(|cube| cube.mesh);
            assert_eq!(baked.flags & FLAG_FLAT_CUBES != 0, has_flat, "{}: flat flag", relative(path));
            assert_eq!(
                baked.flags & FLAG_NEGATIVE_CUBES != 0,
                has_negative,
                "{}: negative flag",
                relative(path)
            );
            assert_eq!(baked.flags & FLAG_MESH_CUBES != 0, has_mesh, "{}: mesh flag", relative(path));

            // Blob round-trip: the header counts match, every field is consumed and the size is exact.
            let summary = read_summary(&baked.blob);
            assert_eq!(summary.bones, baked.bone_count as usize, "{}: blob bones", relative(path));
            assert_eq!(summary.cubes, baked.cube_count as usize, "{}: blob cubes", relative(path));
            assert_eq!(summary.quads, baked.quad_count as usize, "{}: blob quads", relative(path));
            let rendered_quads: usize = baked.cubes.iter().map(|cube| cube.quads.len()).sum();
            assert_eq!(rendered_quads, baked.quad_count as usize, "{}: rendered quads", relative(path));

            // Registry lifecycle: insert -> inspect -> remove, and a freed handle is gone.
            let handle = registry().insert(baked);
            assert_ne!(handle, 0);
            let (blob_length, info) = registry()
                .get(handle)
                .map(|geometry| {
                    (
                        geometry.blob.len(),
                        [geometry.bone_count, geometry.cube_count, geometry.quad_count, geometry.flags],
                    )
                })
                .expect("handle is live");
            assert!(blob_length > 16, "{}: blob too small", relative(path));
            assert!(info[0] > 0 && info[1] > 0 && info[2] > 0, "{}: info counts", relative(path));
            assert!(registry().remove(handle));
            assert!(registry().get(handle).is_none());
        }
        assert!(declined.is_empty(), "native bake declined built-in geometry:\n{}", declined.join("\n"));
        assert_eq!(healthy, files.len());
        assert!(total_bones > 0 && total_cubes > 0 && total_quads > 0);
        // Informational output for `cargo test --release -- --nocapture`.
        println!(
            "native geometry corpus: {healthy}/{} files, {total_bones} bones, {total_cubes} cubes, \
             {total_quads} quads, max {max_vertices_per_cube} vertices per cube",
            files.len()
        );
    }

    #[test]
    fn unknown_format_versions_are_declined() {
        let document = br#"{"format_version": "0.0.1",
            "minecraft:geometry": [{"description": {}, "bones": [{"name": "a"}]}]}"#;
        assert!(bake_json(document, 0).is_err());
    }

    #[test]
    fn a_validated_hint_skips_the_version_gate() {
        // The hint exists for a caller that already parsed the document; with hint 1 the version string
        // itself is not re-checked (but the document still has to parse and bake).
        let document = br#"{"format_version": "1.12.0",
            "minecraft:geometry": [{"description": {}, "bones": [{"name": "a"}]}]}"#;
        assert!(bake_json(document, 1).is_ok());
        assert!(bake_json(document, 9).is_err(), "out-of-range hint is a programming error");
    }
}

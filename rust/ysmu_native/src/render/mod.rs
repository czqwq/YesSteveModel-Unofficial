//! M4 — per-frame vertex emission.
//!
//! Reference: upstream `natives/render/NativeRenderer.java` together with
//! `tmp/YesSteveModel-Native-dev/modules/gfx/src/renderer/render_impl.inc` and `cube/transform_*.h`: the
//! native renderer receives the bone attribute array plus a packed host matrix buffer, writes the whole
//! model's vertices into the host's own vertex buffer and the host (`VertexConsumer`) takes over upload
//! and GL state. This port does the same against 1.7.10's `Tessellator`:
//!
//! * the Java side keeps every GL/state decision (`YsmRenderType` setup, material session, polygon
//!   offset, cull-face for negative-size cubes, per-bone texture overrides) and every bone-matrix
//!   computation (`software/bernie/geckolib3/util/MatrixStack`);
//! * this module receives one *draw group* — the cubes of one bone under one cube filter — with the
//!   bone's final model matrix, normal matrix and tint, and writes the exact 8-int vertex records that
//!   `Tessellator.addVertexWithUV`/`setNormal`/`setColorRGBA_F` would have written:
//!   `[0..2]` position float bits, `[3..4]` uv float bits, `[5]` packed colour
//!   (`alpha<<24 | blue<<16 | green<<8 | red`, little endian), `[6]` packed normal (4 signed bytes),
//!   `[7]` brightness — never written, because this engine's model draw does not set brightness;
//! * the Java side then commits the group by writing `rawBufferIndex`/`vertexCount` and the
//!   `hasTexture`/`hasColor`/`hasNormals`/`isColorDisabled` flags and calling `Tessellator.draw()`
//!   through the existing `TessellatorBufferKeep.draw` seam.
//!
//! Why per draw group instead of one call per model: `Tessellator.draw()` always uploads from buffer
//! offset 0, so a group's vertices have to be at the start of `rawBuffer` when it is drawn. The Java
//! fast path accumulates one call per bone group into the same pending batch — [`emit_group_at`] takes an
//! explicit destination offset for exactly that — and only draws at the boundaries the Java path draws
//! at (capacity, flat-cube polygon-offset changes, the negative-size pass, texture overrides), so a
//! normal opaque model is still one `Tessellator.draw()`.
//!
//! JNI contract (see also `com.fox.ysmu.nativebridge.NativeDrawGroups`). There are two entries on
//! purpose: the first is the documented single-group contract the parity harness drives, the second is
//! the production append path.
//!
//! * `int emitNative(long geometry, int boneIndex, float[] modelMatrix16, float[] normalMatrix9,
//!   int colorArgb, int cubeFilter, int[] rawBuffer)` — number of vertices written at the start of
//!   `rawBuffer`, or `-1` when the group does not fit / the handle is invalid (the caller then runs the
//!   Java loop for that group, so the fallback is per draw group rather than per model).
//! * `int emitAtNative(..., int[] rawBuffer, int intOffset)` — the same group written at
//!   `rawBuffer[intOffset]`. This exists because `Tessellator.draw()` uploads `rawBuffer[0..vertexCount*8)`,
//!   i.e. always from offset 0: the Java fast path keeps one pending batch per Java draw, so each bone
//!   group must be appended after the groups already in it, not written over them. Appending keeps the
//!   draw count equal to the Java path's (one `Tessellator.draw()` for a whole opaque model instead of
//!   one per bone); the caller commits the preceding groups by advancing the tessellator cursor.
//!
//! `colorArgb` is Java's ARGB convention (`alpha<<24 | red<<16 | green<<8 | blue`), already quantised by
//! the caller with `Tessellator.setColorRGBA_F`'s `(int)(f * 255.0F)` truncation and 0..255 clamp; this
//! module only rewrites it into the `Tessellator` memory order, like upstream's `NativeRenderer.packColor`.
//!
//! `cubeFilter`: 0 = all cubes, 1 = positive-size only, 2 = negative-size only; it mirrors
//! `IGeoRenderer.renderCubeGroup`'s two-pass outline handling. The Java fast path only calls this with a
//! filter whose selected cubes all share one flatness, so no polygon-offset transition can happen inside
//! a group; a bone that mixes flat and non-flat cubes keeps the Java loop.
//!
//! Bit-for-bit contract. Every float operation below is ordered exactly like the Java loop it replaces,
//! including the matrix products (`javax.vecmath` 1.5.2 `Matrix4f.mul`/`Matrix3f.mul`,
//! `MatrixStack.beginCube`'s `T(p)*Rz*Ry*Rx*T(-p)`), the `Matrix3f.transform` normal product and the
//! `Tessellator.setNormal` packing. Rust does not reassociate floating point, so the emitted records are
//! byte-identical to the Java path for the same pose; `tools/native-parity` asserts that.

use jni::objects::{JClass, JFloatArray, JIntArray};
use jni::sys::{jint, jlong};
use jni::JNIEnv;

use crate::geometry::model::{registry, BakedCube, BakedGeometry, BakedQuad};
use crate::jni_util::{guard, read_floats, with_ints_mut};

/// Emit every cube of the bone.
pub const CUBE_FILTER_ALL: i32 = 0;
/// Emit only positive-size cubes (the second pass of `IGeoRenderer.renderBoneCubes`).
pub const CUBE_FILTER_POSITIVE: i32 = 1;
/// Emit only negative-size cubes (the cull-front first pass of `IGeoRenderer.renderBoneCubes`).
pub const CUBE_FILTER_NEGATIVE: i32 = 2;

/// One `Tessellator` vertex record, in `int`s.
const VERTEX_INTS: usize = 8;

const IDENTITY_4: [f32; 16] = [
    1.0, 0.0, 0.0, 0.0, //
    0.0, 1.0, 0.0, 0.0, //
    0.0, 0.0, 1.0, 0.0, //
    0.0, 0.0, 0.0, 1.0,
];

const IDENTITY_3: [f32; 9] = [
    1.0, 0.0, 0.0, //
    0.0, 1.0, 0.0, //
    0.0, 0.0, 1.0,
];

/// Writes one draw group's vertices at the start of `out`.
///
/// @param geometry      the render-ready geometry the bake kept for this module
/// @param bone_index    depth-first bone index (the Java `GeoBone#nativeBoneIndex`)
/// @param model_matrix  the bone's final model matrix, `javax.vecmath.Matrix4f` field order
///                      (`m00, m01, m02, m03, m10, ...`; translation at indices 3/7/11)
/// @param normal_matrix the bone's final normal matrix, `Matrix3f` field order (`m00..m22`)
/// @param color         Java ARGB (`alpha<<24 | red<<16 | green<<8 | blue`), already quantised
/// @param cube_filter   [`CUBE_FILTER_ALL`], [`CUBE_FILTER_POSITIVE`] or [`CUBE_FILTER_NEGATIVE`]
/// @param out           destination ints; every record is 8 ints and starts at `out[0]`
/// @return the number of vertices written (`out.len()` must be at least `vertices * 8`), or `-1` when
///         the group does not fit, the bone index is unknown or the filter is invalid
#[allow(dead_code)] // 文档化的纯 Rust 单组入口；JNI 生产路径走 emit_group_at，本函数供单测与后
                    // 续 integration/parity 测试使用。
pub fn emit_group(
    geometry: &BakedGeometry,
    bone_index: usize,
    model_matrix: &[f32; 16],
    normal_matrix: &[f32; 9],
    color: u32,
    cube_filter: i32,
    out: &mut [i32],
) -> i32 {
    emit_group_at(geometry, bone_index, model_matrix, normal_matrix, color, cube_filter, out, 0)
}

/// [`emit_group`] with an explicit destination offset, so the Java fast path can append one bone group
/// after the groups already pending in the same `Tessellator` batch.
pub fn emit_group_at(
    geometry: &BakedGeometry,
    bone_index: usize,
    model_matrix: &[f32; 16],
    normal_matrix: &[f32; 9],
    color: u32,
    cube_filter: i32,
    out: &mut [i32],
    int_offset: usize,
) -> i32 {
    if !matches!(
        cube_filter,
        CUBE_FILTER_ALL | CUBE_FILTER_POSITIVE | CUBE_FILTER_NEGATIVE
    ) {
        return -1;
    }
    let Some(cubes) = geometry.bone_cubes(bone_index) else {
        // A stale bone index (or a Java-side model that was not materialised from this handle) is a job
        // for the Java loop, not a native-layer failure.
        return -1;
    };

    // Count first: the capacity contract is "write everything or nothing", because a partially written
    // group must never be committed (the caller's buffer cursor was not advanced).
    let mut vertex_count: usize = 0;
    for cube in cubes {
        if !cube_selected(cube, cube_filter) {
            continue;
        }
        let cube_vertices = match cube.quads.len().checked_mul(4) {
            Some(value) => value,
            None => return -1,
        };
        vertex_count = match vertex_count.checked_add(cube_vertices) {
            Some(value) => value,
            None => return -1,
        };
    }
    let needed = match vertex_count.checked_mul(VERTEX_INTS) {
        Some(value) => value,
        None => return -1,
    };
    if int_offset > out.len() || out.len() - int_offset < needed {
        return -1;
    }
    if vertex_count == 0 {
        return 0;
    }

    // Tessellator.setColorRGBA's packing on a little-endian host (the platforms this library ships for).
    // The Java caller already did the float -> int truncation and the 0..255 clamp, then packed ARGB; only
    // the byte order of the packed int is ours to produce: alpha<<24 | blue<<16 | green<<8 | red. This is
    // upstream's `NativeRenderer.packColor`, which converts the same host ARGB into the vertex stream's
    // memory order.
    let packed_color = (((color >> 24) & 0xFF) as i32) << 24
        | ((color & 0xFF) as i32) << 16
        | (((color >> 8) & 0xFF) as i32) << 8
        | ((color >> 16) & 0xFF) as i32;

    let mut cursor = int_offset;
    for cube in cubes {
        if !cube_selected(cube, cube_filter) {
            continue;
        }
        // One cube matrix per cube, exactly MatrixStack.beginCube: a cube without its own rotation
        // reuses the bone matrix (beginCube hands back the stack top unchanged), a rotated one gets
        // `bone * T(p) * Rz * Ry * Rx * T(-p)`.
        let (cube_model, cube_normal) = cube_matrices(cube, model_matrix, normal_matrix);
        let positions: &[[f32; 3]] = if cube.mesh {
            &cube.mesh_positions
        } else {
            &cube.positions
        };
        for quad in &cube.quads {
            let normal = pack_quad_normal(cube, quad, &cube_normal);
            for lane in 0..4 {
                let Some(position) = positions.get(quad.vertex_indices[lane] as usize) else {
                    // The geometry and the quad index space disagree; decline so the Java loop draws.
                    return -1;
                };
                // IGeoRenderer.renderCube's unrolled `Matrix4f * (x, y, z, 1)`.
                let vx = position[0];
                let vy = position[1];
                let vz = position[2];
                let x = cube_model[0] * vx + cube_model[1] * vy + cube_model[2] * vz + cube_model[3];
                let y = cube_model[4] * vx + cube_model[5] * vy + cube_model[6] * vz + cube_model[7];
                let z = cube_model[8] * vx + cube_model[9] * vy + cube_model[10] * vz + cube_model[11];
                // Tessellator.addVertex narrows `(float)((double) value + xOffset)`; the offset is gated
                // to +0.0 by the caller, and the double round trip only changes the sign of a -0.0
                // result, which `+ 0.0` reproduces in one f32 operation.
                let x = x + 0.0;
                let y = y + 0.0;
                let z = z + 0.0;
                out[cursor] = x.to_bits() as i32;
                out[cursor + 1] = y.to_bits() as i32;
                out[cursor + 2] = z.to_bits() as i32;
                out[cursor + 3] = quad.uvs[lane][0].to_bits() as i32;
                out[cursor + 4] = quad.uvs[lane][1].to_bits() as i32;
                out[cursor + 5] = packed_color;
                out[cursor + 6] = normal;
                // `out[cursor + 7]` (brightness) is deliberately left untouched: `Tessellator.addVertex`
                // writes it only when `hasBrightness` is set, and this engine's model draw never calls
                // `setBrightness`. Writing anything here would break byte parity with the Java path.
                cursor += VERTEX_INTS;
            }
        }
    }
    vertex_count as i32
}

/// `IGeoRenderer.renderCubeGroup`'s cube selection, shared by both passes.
fn cube_selected(cube: &BakedCube, cube_filter: i32) -> bool {
    match cube_filter {
        CUBE_FILTER_POSITIVE => !cube.negative,
        CUBE_FILTER_NEGATIVE => cube.negative,
        _ => true,
    }
}

/// `IGeoRenderer.renderCubeGroup`'s flat test: a non-mesh cube with a zero-size axis is drawn with a
/// polygon offset. The Java fast path uses the same expression to decide draw-group boundaries.
#[allow(dead_code)] // 语义由 Java 侧的 flat 分组共用；单测直接调用它。
pub fn cube_is_flat(cube: &BakedCube) -> bool {
    !cube.mesh && (cube.size[0] == 0.0 || cube.size[1] == 0.0 || cube.size[2] == 0.0)
}

/// `MatrixStack.beginCube`: the cube's final model matrix and normal matrix.
fn cube_matrices(
    cube: &BakedCube,
    bone_model: &[f32; 16],
    bone_normal: &[f32; 9],
) -> ([f32; 16], [f32; 9]) {
    let rx = cube.rotation[0];
    let ry = cube.rotation[1];
    let rz = cube.rotation[2];
    if rx == 0.0 && ry == 0.0 && rz == 0.0 {
        // beginCube: T(p) * T(-p) is the identity, so the cube *is* the stack top - no copy, no product.
        return (*bone_model, *bone_normal);
    }
    let px = cube.pivot[0];
    let py = cube.pivot[1];
    let pz = cube.pivot[2];

    // MatrixStack.beginCube's order: translate to the pivot, Rz, Ry, Rx, translate back.
    let mut local = translation_4(px, py, pz);
    local = mul_4(&local, &rotation_z_4(rz));
    local = mul_4(&local, &rotation_y_4(ry));
    local = mul_4(&local, &rotation_x_4(rx));
    local = mul_4(&local, &translation_4(-px, -py, -pz));
    let model = mul_4(bone_model, &local);

    let mut normal = IDENTITY_3;
    normal = mul_3(&normal, &rotation_z_3(rz));
    normal = mul_3(&normal, &rotation_y_3(ry));
    normal = mul_3(&normal, &rotation_x_3(rx));
    let normal = mul_3(bone_normal, &normal);
    (model, normal)
}

/// `IGeoRenderer.renderCube`'s per-face normal: the cube normal matrix product, the flat-cube sign
/// workaround for dark shading, then `Tessellator.setNormal`'s byte packing.
fn pack_quad_normal(cube: &BakedCube, quad: &BakedQuad, cube_normal: &[f32; 9]) -> i32 {
    let nx = quad.normal[0];
    let ny = quad.normal[1];
    let nz = quad.normal[2];
    // javax.vecmath Matrix3f.transform(Vector3f): one sum per component, left to right.
    let mut x = cube_normal[0] * nx + cube_normal[1] * ny + cube_normal[2] * nz;
    let mut y = cube_normal[3] * nx + cube_normal[4] * ny + cube_normal[5] * nz;
    let mut z = cube_normal[6] * nx + cube_normal[7] * ny + cube_normal[8] * nz;

    // Fix shading for flat cubes, kept character-for-character from IGeoRenderer.renderCube (the same
    // conditions Optifine's shader compatibility expects).
    if !cube.mesh && (cube.size[1] == 0.0 || cube.size[2] == 0.0) && x < 0.0 {
        x *= -1.0;
    }
    if !cube.mesh && (cube.size[0] == 0.0 || cube.size[2] == 0.0) && y < 0.0 {
        y *= -1.0;
    }
    if !cube.mesh && (cube.size[0] == 0.0 || cube.size[1] == 0.0) && z < 0.0 {
        z *= -1.0;
    }

    // Tessellator.setNormal: `(byte)((int)(component * 127.0F))` packed as three little-endian bytes.
    let b0 = ((x * 127.0) as i32) & 0xFF;
    let b1 = ((y * 127.0) as i32) & 0xFF;
    let b2 = ((z * 127.0) as i32) & 0xFF;
    b0 | (b1 << 8) | (b2 << 16)
}

fn translation_4(x: f32, y: f32, z: f32) -> [f32; 16] {
    let mut matrix = IDENTITY_4;
    matrix[3] = x;
    matrix[7] = y;
    matrix[11] = z;
    matrix
}

/// `Matrix4f.mul`: `this = this * m1`, the same expression order vecmath uses (one rounding per
/// multiply and per add, left to right).
fn mul_4(left: &[f32; 16], right: &[f32; 16]) -> [f32; 16] {
    let mut result = [0f32; 16];
    for row in 0..4 {
        for column in 0..4 {
            result[row * 4 + column] = left[row * 4] * right[column]
                + left[row * 4 + 1] * right[4 + column]
                + left[row * 4 + 2] * right[8 + column]
                + left[row * 4 + 3] * right[12 + column];
        }
    }
    result
}

/// `Matrix3f.mul`: `this = this * m1`, same expression order as vecmath.
fn mul_3(left: &[f32; 9], right: &[f32; 9]) -> [f32; 9] {
    let mut result = [0f32; 9];
    for row in 0..3 {
        for column in 0..3 {
            result[row * 3 + column] = left[row * 3] * right[column]
                + left[row * 3 + 1] * right[3 + column]
                + left[row * 3 + 2] * right[6 + column];
        }
    }
    result
}

// vecmath's `Matrix4f.rotX/Y/Z(float)` take a float angle, widen it to double for `Math.sin/cos` and
// narrow the result back to float. Using f64 here is therefore not an optimisation choice: Java's trig
// is double precision and `(float) Math.sin((double) angle)` is what the Java loop computes.

fn rotation_x_4(angle: f32) -> [f32; 16] {
    let sin = (angle as f64).sin() as f32;
    let cos = (angle as f64).cos() as f32;
    [1.0, 0.0, 0.0, 0.0, 0.0, cos, -sin, 0.0, 0.0, sin, cos, 0.0, 0.0, 0.0, 0.0, 1.0]
}

fn rotation_y_4(angle: f32) -> [f32; 16] {
    let sin = (angle as f64).sin() as f32;
    let cos = (angle as f64).cos() as f32;
    [cos, 0.0, sin, 0.0, 0.0, 1.0, 0.0, 0.0, -sin, 0.0, cos, 0.0, 0.0, 0.0, 0.0, 1.0]
}

fn rotation_z_4(angle: f32) -> [f32; 16] {
    let sin = (angle as f64).sin() as f32;
    let cos = (angle as f64).cos() as f32;
    [cos, -sin, 0.0, 0.0, sin, cos, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0]
}

fn rotation_x_3(angle: f32) -> [f32; 9] {
    let sin = (angle as f64).sin() as f32;
    let cos = (angle as f64).cos() as f32;
    [1.0, 0.0, 0.0, 0.0, cos, -sin, 0.0, sin, cos]
}

fn rotation_y_3(angle: f32) -> [f32; 9] {
    let sin = (angle as f64).sin() as f32;
    let cos = (angle as f64).cos() as f32;
    [cos, 0.0, sin, 0.0, 1.0, 0.0, -sin, 0.0, cos]
}

fn rotation_z_3(angle: f32) -> [f32; 9] {
    let sin = (angle as f64).sin() as f32;
    let cos = (angle as f64).cos() as f32;
    [cos, -sin, 0.0, sin, cos, 0.0, 0.0, 0.0, 1.0]
}

/// Shared body of the two JNI entries: copies the matrices out of the Java heap, runs the emitter
/// against the registry handle, and writes through the critical-array view of the caller's buffer.
fn emit_entry<'local>(
    env: &mut JNIEnv<'local>,
    handle: u64,
    bone_index: jint,
    model_matrix: &JFloatArray<'local>,
    normal_matrix: &JFloatArray<'local>,
    color_argb: jint,
    cube_filter: jint,
    raw_buffer: &JIntArray<'local>,
    int_offset: jint,
) -> Result<jint, String> {
    let model_floats = read_floats(env, model_matrix)?;
    if model_floats.len() < 16 {
        return Err(format!(
            "model matrix has {} floats, expected 16",
            model_floats.len()
        ));
    }
    let normal_floats = read_floats(env, normal_matrix)?;
    if normal_floats.len() < 9 {
        return Err(format!(
            "normal matrix has {} floats, expected 9",
            normal_floats.len()
        ));
    }
    let mut bone_model = [0f32; 16];
    bone_model.copy_from_slice(&model_floats[..16]);
    let mut bone_normal = [0f32; 9];
    bone_normal.copy_from_slice(&normal_floats[..9]);
    if int_offset < 0 || bone_index < 0 {
        // A Java-side bookkeeping error, not a native failure: decline this one group (the Java loop
        // draws it) instead of throwing and disabling the whole module.
        return Ok(-1);
    }
    let mut vertices: jint = -1;
    with_ints_mut(env, raw_buffer, |out| {
        if let Some(result) = registry().with(handle, |geometry| {
            emit_group_at(
                geometry,
                bone_index as usize,
                &bone_model,
                &bone_normal,
                color_argb as u32,
                cube_filter,
                out,
                int_offset as usize,
            )
        }) {
            vertices = result;
        }
    })?;
    Ok(vertices)
}

#[no_mangle]
pub extern "system" fn Java_com_fox_ysmu_nativebridge_NativeDrawGroups_emitNative<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    geometry: jlong,
    bone_index: jint,
    model_matrix: JFloatArray<'local>,
    normal_matrix: JFloatArray<'local>,
    color_argb: jint,
    cube_filter: jint,
    raw_buffer: JIntArray<'local>,
) -> jint {
    guard(&mut env, -1, |env| {
        emit_entry(
            env,
            geometry as u64,
            bone_index,
            &model_matrix,
            &normal_matrix,
            color_argb,
            cube_filter,
            &raw_buffer,
            0,
        )
    })
}

#[no_mangle]
pub extern "system" fn Java_com_fox_ysmu_nativebridge_NativeDrawGroups_emitAtNative<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    geometry: jlong,
    bone_index: jint,
    model_matrix: JFloatArray<'local>,
    normal_matrix: JFloatArray<'local>,
    color_argb: jint,
    cube_filter: jint,
    raw_buffer: JIntArray<'local>,
    int_offset: jint,
) -> jint {
    guard(&mut env, -1, |env| {
        emit_entry(
            env,
            geometry as u64,
            bone_index,
            &model_matrix,
            &normal_matrix,
            color_argb,
            cube_filter,
            &raw_buffer,
            int_offset,
        )
    })
}

#[cfg(test)]
mod tests {
    use super::{
        cube_is_flat, emit_group, emit_group_at, CUBE_FILTER_ALL, CUBE_FILTER_NEGATIVE,
        CUBE_FILTER_POSITIVE, IDENTITY_3, IDENTITY_4,
    };
    use crate::geometry::model::{BakedBone, BakedCube, BakedGeometry, BakedQuad};

    fn bone_matrix(x: f32, y: f32, z: f32) -> [f32; 16] {
        let mut matrix = IDENTITY_4;
        matrix[3] = x;
        matrix[7] = y;
        matrix[11] = z;
        matrix
    }

    /// One quad over the first four corners of a unit cube, textured 0..1, facing up.
    fn unit_quad() -> BakedQuad {
        BakedQuad {
            vertex_indices: [0, 1, 2, 3],
            uvs: [[0.0, 0.0], [1.0, 0.0], [1.0, 1.0], [0.0, 1.0]],
            normal: [0.0, 1.0, 0.0],
        }
    }

    /// A box cube with the engine's eight corner positions (`GeoCube`'s P1..P8).
    fn box_cube(quads: Vec<BakedQuad>, size: [f32; 3]) -> BakedCube {
        BakedCube {
            positions: [
                [0.0, 0.0, 0.0],
                [0.0, 0.0, 1.0],
                [0.0, 1.0, 0.0],
                [0.0, 1.0, 1.0],
                [1.0, 0.0, 0.0],
                [1.0, 0.0, 1.0],
                [1.0, 1.0, 0.0],
                [1.0, 1.0, 1.0],
            ],
            quads,
            size,
            ..Default::default()
        }
    }

    fn one_bone_geometry(cubes: Vec<BakedCube>) -> BakedGeometry {
        let cube_count = cubes.len() as u32;
        let quad_count = cubes.iter().map(|cube| cube.quads.len() as u32).sum();
        BakedGeometry {
            bones: vec![BakedBone {
                name: "bone".to_string(),
                parent: -1,
                cube_start: 0,
                cube_count,
                ..Default::default()
            }],
            cubes,
            bone_count: 1,
            cube_count,
            quad_count,
            ..Default::default()
        }
    }

    fn bits(value: f32) -> i32 {
        value.to_bits() as i32
    }

    #[test]
    fn writes_the_vanilla_eight_int_vertex_record() {
        let geometry = one_bone_geometry(vec![box_cube(vec![unit_quad()], [1.0, 1.0, 1.0])]);
        let model = bone_matrix(1.0, 2.0, 3.0);
        let normal = IDENTITY_3;
        // ARGB: opaque red 0x80, green 0x40, blue 0x20 -> Tessellator order alpha<<24|blue<<16|green<<8|red.
        let mut out = vec![0i32; 4 * 8];
        for vertex in 0..4 {
            // The brightness slot is never written by the Java path either; the test proves we leave it.
            out[vertex * 8 + 7] = 0x0BADF00D;
        }
        let written = emit_group(&geometry, 0, &model, &normal, 0xFF_80_40_20, CUBE_FILTER_ALL, &mut out);
        assert_eq!(written, 4);

        let expected_positions = [
            [1.0f32, 2.0, 3.0],
            [1.0, 2.0, 4.0],
            [1.0, 3.0, 3.0],
            [1.0, 3.0, 4.0],
        ];
        let expected_uvs = [[0.0f32, 0.0], [1.0, 0.0], [1.0, 1.0], [0.0, 1.0]];
        let expected_color = (0xFFu32 << 24 | 0x20 << 16 | 0x40 << 8 | 0x80) as i32;
        let expected_normal = (127 << 8) as i32; // (0, 1, 0) * 127 packed into the second byte
        for vertex in 0..4 {
            let base = vertex * 8;
            let position = expected_positions[vertex];
            assert_eq!(out[base], bits(position[0]), "x of vertex {vertex}");
            assert_eq!(out[base + 1], bits(position[1]), "y of vertex {vertex}");
            assert_eq!(out[base + 2], bits(position[2]), "z of vertex {vertex}");
            assert_eq!(out[base + 3], bits(expected_uvs[vertex][0]), "u of vertex {vertex}");
            assert_eq!(out[base + 4], bits(expected_uvs[vertex][1]), "v of vertex {vertex}");
            assert_eq!(out[base + 5], expected_color, "colour of vertex {vertex}");
            assert_eq!(out[base + 6], expected_normal, "normal of vertex {vertex}");
            assert_eq!(out[base + 7], 0x0BADF00D, "brightness must stay untouched");
        }
    }

    #[test]
    fn uses_the_column_major_matrix_field_order() {
        // Row-major field layout: m00..m03 at 0..3, m10..m13 at 4..7, ...; translation at 3/7/11.
        let geometry = one_bone_geometry(vec![box_cube(vec![unit_quad()], [1.0, 1.0, 1.0])]);
        let model = [
            2.0, 3.0, 4.0, 5.0, //
            6.0, 7.0, 8.0, 9.0, //
            10.0, 11.0, 12.0, 13.0, //
            14.0, 15.0, 16.0, 17.0,
        ];
        let mut out = vec![0i32; 4 * 8];
        let written = emit_group(&geometry, 0, &model, &IDENTITY_3, 0xFFFF_FFFF, CUBE_FILTER_ALL, &mut out);
        assert_eq!(written, 4);
        // vertex 0 is position (0, 0, 0): x = m03 = 5, y = m13 = 9, z = m23 = 13.
        assert_eq!(out[0], bits(5.0));
        assert_eq!(out[1], bits(9.0));
        assert_eq!(out[2], bits(13.0));
    }

    #[test]
    fn transforms_and_packs_the_quad_normal() {
        let geometry = one_bone_geometry(vec![box_cube(vec![unit_quad()], [1.0, 1.0, 1.0])]);
        // m11 = 2 stretches the y normal to 2.0 -> 2.0 * 127 = 254.
        let normal = [0.5, 0.0, 0.0, 0.0, 2.0, 0.0, 0.0, 0.0, 1.0];
        let mut out = vec![0i32; 4 * 8];
        assert_eq!(
            emit_group(&geometry, 0, &bone_matrix(0.0, 0.0, 0.0), &normal, 0xFFFF_FFFF, CUBE_FILTER_ALL, &mut out),
            4
        );
        assert_eq!(out[6], 254 << 8);
    }

    #[test]
    fn applies_the_flat_cube_normal_sign_fix_and_skips_it_for_mesh_cubes() {
        // A y-thin cube flips a negative x normal: !mesh && (size.y == 0 || size.z == 0) && x < 0.
        let flat = box_cube(
            vec![BakedQuad {
                normal: [-1.0, 0.0, 0.0],
                ..unit_quad()
            }],
            [1.0, 0.0, 1.0],
        );
        assert!(cube_is_flat(&flat));
        let geometry = one_bone_geometry(vec![flat]);
        let mut out = vec![0i32; 4 * 8];
        assert_eq!(
            emit_group(&geometry, 0, &IDENTITY_4, &IDENTITY_3, 0xFFFF_FFFF, CUBE_FILTER_ALL, &mut out),
            4
        );
        assert_eq!(out[6], 127, "flat cube: the negative x normal is flipped to +1 -> 127");

        // The same cube as a mesh cube keeps the declared normal: (-1 * 127) & 0xFF = 129.
        let mesh = BakedCube {
            mesh: true,
            size: [1.0, 0.0, 1.0],
            mesh_positions: vec![[0.0, 0.0, 0.0], [0.0, 0.0, 1.0], [0.0, 1.0, 0.0], [0.0, 1.0, 1.0]],
            quads: vec![BakedQuad {
                normal: [-1.0, 0.0, 0.0],
                ..unit_quad()
            }],
            ..Default::default()
        };
        let geometry = one_bone_geometry(vec![mesh]);
        let mut out = vec![0i32; 4 * 8];
        assert_eq!(
            emit_group(&geometry, 0, &IDENTITY_4, &IDENTITY_3, 0xFFFF_FFFF, CUBE_FILTER_ALL, &mut out),
            4
        );
        assert_eq!(out[6], 129, "mesh cube: the sign fix does not apply");
    }

    #[test]
    fn emits_mesh_cubes_from_the_mesh_vertex_list() {
        let quad = BakedQuad {
            vertex_indices: [2, 3, 0, 1],
            uvs: [[0.0, 0.0], [1.0, 0.0], [1.0, 1.0], [0.0, 1.0]],
            normal: [0.0, 1.0, 0.0],
        };
        let cube = BakedCube {
            mesh: true,
            mesh_positions: vec![
                [10.0, 0.0, 0.0],
                [11.0, 0.0, 0.0],
                [12.0, 0.0, 0.0],
                [13.0, 0.0, 0.0],
            ],
            quads: vec![quad],
            ..Default::default()
        };
        let geometry = one_bone_geometry(vec![cube]);
        let mut out = vec![0i32; 4 * 8];
        assert_eq!(
            emit_group(&geometry, 0, &IDENTITY_4, &IDENTITY_3, 0xFFFF_FFFF, CUBE_FILTER_ALL, &mut out),
            4
        );
        let expected = [12.0f32, 13.0, 10.0, 11.0];
        for (vertex, value) in expected.iter().enumerate() {
            assert_eq!(out[vertex * 8], bits(*value), "mesh vertex {vertex}");
        }
    }

    #[test]
    fn rotates_a_cube_about_its_pivot() {
        // 90 degrees about z around pivot (0.5, 0, 0): the local point (1, 0, 0) - (0.5, 0, 0) becomes
        // (0, 0.5, 0), so the world point is (0.5, 0.5, 0) plus the bone translation.
        let cube = BakedCube {
            positions: [
                [1.0, 0.0, 0.0],
                [1.0, 0.0, 0.0],
                [1.0, 0.0, 0.0],
                [1.0, 0.0, 0.0],
                [1.0, 0.0, 0.0],
                [1.0, 0.0, 0.0],
                [1.0, 0.0, 0.0],
                [1.0, 0.0, 0.0],
            ],
            quads: vec![BakedQuad {
                vertex_indices: [0, 0, 0, 0],
                ..unit_quad()
            }],
            pivot: [0.5, 0.0, 0.0],
            rotation: [0.0, 0.0, std::f64::consts::FRAC_PI_2 as f32],
            size: [1.0, 1.0, 1.0],
            flat: false,
            ..Default::default()
        };
        let geometry = one_bone_geometry(vec![cube]);
        let mut out = vec![0i32; 4 * 8];
        assert_eq!(
            emit_group(&geometry, 0, &IDENTITY_4, &IDENTITY_3, 0xFFFF_FFFF, CUBE_FILTER_ALL, &mut out),
            4
        );
        let x = f32::from_bits(out[0] as u32);
        let y = f32::from_bits(out[1] as u32);
        assert!((x - 0.5).abs() < 1e-6, "x was {x}");
        assert!((y - 0.5).abs() < 1e-6, "y was {y}");
    }

    #[test]
    fn filters_positive_and_negative_cubes() {
        let mut positive = box_cube(vec![unit_quad()], [1.0, 1.0, 1.0]);
        positive.positions[0] = [0.0, 0.0, 0.0];
        let mut negative = box_cube(vec![unit_quad()], [1.0, 1.0, 1.0]);
        negative.negative = true;
        negative.positions[0] = [10.0, 0.0, 0.0];
        let geometry = one_bone_geometry(vec![positive, negative]);

        let mut all = vec![0i32; 8 * 8];
        assert_eq!(
            emit_group(&geometry, 0, &IDENTITY_4, &IDENTITY_3, 0xFFFF_FFFF, CUBE_FILTER_ALL, &mut all),
            8
        );

        let mut positive_only = vec![0i32; 8 * 8];
        assert_eq!(
            emit_group(&geometry, 0, &IDENTITY_4, &IDENTITY_3, 0xFFFF_FFFF, CUBE_FILTER_POSITIVE, &mut positive_only),
            4
        );
        assert_eq!(positive_only[0], bits(0.0));

        let mut negative_only = vec![0i32; 8 * 8];
        assert_eq!(
            emit_group(&geometry, 0, &IDENTITY_4, &IDENTITY_3, 0xFFFF_FFFF, CUBE_FILTER_NEGATIVE, &mut negative_only),
            4
        );
        assert_eq!(negative_only[0], bits(10.0));
    }

    #[test]
    fn rejects_when_the_destination_is_too_small() {
        let geometry = one_bone_geometry(vec![box_cube(vec![unit_quad()], [1.0, 1.0, 1.0])]);
        let mut too_small = vec![0i32; 4 * 8 - 1];
        assert_eq!(
            emit_group(&geometry, 0, &IDENTITY_4, &IDENTITY_3, 0xFFFF_FFFF, CUBE_FILTER_ALL, &mut too_small),
            -1
        );
        let mut exact = vec![0i32; 4 * 8];
        assert_eq!(
            emit_group(&geometry, 0, &IDENTITY_4, &IDENTITY_3, 0xFFFF_FFFF, CUBE_FILTER_ALL, &mut exact),
            4
        );

        // The offset variant only needs the room after the offset, and it must write there.
        let mut buffered = vec![0i32; 4 * 8 + 3];
        assert_eq!(
            emit_group_at(&geometry, 0, &IDENTITY_4, &IDENTITY_3, 0xFFFF_FFFF, CUBE_FILTER_ALL, &mut buffered, 3),
            4
        );
        assert_eq!(buffered[3], bits(0.0), "the record starts at the requested offset");
        assert_eq!(buffered[3 + 6], 127 << 8);

        let mut offset_too_small = vec![0i32; 4 * 8 + 3 - 1];
        assert_eq!(
            emit_group_at(&geometry, 0, &IDENTITY_4, &IDENTITY_3, 0xFFFF_FFFF, CUBE_FILTER_ALL, &mut offset_too_small, 3),
            -1
        );
    }

    #[test]
    fn declines_unknown_bones_filters_and_empty_selections() {
        let geometry = one_bone_geometry(vec![box_cube(vec![unit_quad()], [1.0, 1.0, 1.0])]);
        let mut out = vec![0i32; 4 * 8];
        assert_eq!(emit_group(&geometry, 1, &IDENTITY_4, &IDENTITY_3, 0xFFFF_FFFF, CUBE_FILTER_ALL, &mut out), -1);
        assert_eq!(emit_group(&geometry, 0, &IDENTITY_4, &IDENTITY_3, 0xFFFF_FFFF, 7, &mut out), -1);

        // A cube filter that selects nothing is a successful empty group, not an error.
        let mut negative = box_cube(vec![unit_quad()], [1.0, 1.0, 1.0]);
        negative.negative = true;
        let geometry = one_bone_geometry(vec![negative]);
        assert_eq!(
            emit_group(&geometry, 0, &IDENTITY_4, &IDENTITY_3, 0xFFFF_FFFF, CUBE_FILTER_POSITIVE, &mut out),
            0
        );

        // A bone with no cubes emits nothing.
        let empty = one_bone_geometry(Vec::new());
        assert_eq!(emit_group(&empty, 0, &IDENTITY_4, &IDENTITY_3, 0xFFFF_FFFF, CUBE_FILTER_ALL, &mut out), 0);
    }

    #[test]
    fn colour_conversion_keeps_the_quantised_channel_values() {
        // The Java caller quantises each float channel with `(int)(value * 255.0F)` (truncation) and
        // clamps 0..255, then packs ARGB. This is what a tint of (red=0.5, green=0.25, blue=0.0,
        // alpha=1.0) becomes, and the emitter must only reorder the bytes.
        let geometry = one_bone_geometry(vec![box_cube(vec![unit_quad()], [1.0, 1.0, 1.0])]);
        let mut out = vec![0i32; 4 * 8];
        let color = (255 << 24) | (127 << 16) | (63 << 8) | 0;
        assert_eq!(
            emit_group(&geometry, 0, &IDENTITY_4, &IDENTITY_3, color as u32, CUBE_FILTER_ALL, &mut out),
            4
        );
        assert_eq!(out[5], (255 << 24) | (0 << 16) | (63 << 8) | 127);
    }
}

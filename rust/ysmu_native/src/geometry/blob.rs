//! The materialisation blob: a compact, little-endian, versioned encoding of exactly the object graph
//! `GeoBuilder.constructGeoModel` returns.
//!
//! The blob exists so the native bake can hand the Java engine the *same* `GeoModel -> GeoBone ->
//! GeoCube -> GeoQuad -> GeoVertex` graph `GeoBuilder` would have built, without Jackson and without a
//! second parse of the JSON: `com.fox.ysmu.nativebridge.NativeGeometryReader` is the only Java reader and
//! the only other place that knows this layout (the parity harness compares its output field-for-field
//! with the Java bake).
//!
//! Layout, version 1 (all integers little-endian, `string` = `u32` byte length + UTF-8, with
//! `0xFFFF_FFFF` for `null`; `tristate` = `u8` 0 null / 1 false / 2 true; `opt<T>` = `u8` present flag +
//! `T`):
//!
//! ```text
//! header:       magic "YSMG", u32 version, u32 bone_count, u32 cube_count, u32 quad_count
//! properties:   opt<ModelProperties>
//! model lists:  u32 top_level_count + indices
//!               u32 left_hand_count  + indices
//!               u32 right_hand_count + indices
//!               u32 elytra_count     + indices
//!               i32 first_person_head, i32 first_person_view_locator  (-1 = null)
//! bones:        bone_count records in depth-first order, each:
//!                 string name, i32 parent, u32 child_count + child indices,
//!                 tristate mirror, tristate dont_render, tristate reset, opt<f64> inflate,
//!                 f32 rotation_point[3], f32 rotation[3],
//!                 u32 cube_count + cube records
//! cube:         f32 size[3], f32 pivot[3], f32 rotation[3], f64 inflate,
//!               tristate mirror, u8 mesh, u8 has_neg_size,
//!               u32 quad_slot_count + quad slots (u8 present + quad record when present)
//! quad:         i8 direction (EnumFacing ordinal, -1 = null), i32 normal[3], f32 normal_vector[3],
//!               i32 uv_rotation, 4 * (f32 position[3], f32 u, f32 v)
//! ```
//!
//! `ModelProperties` block, in field order: 11 booleans (`animationArmsDown`, `animationArmsOutFront`,
//! `animationDontShowArmor`, `animationInvertedCrouch`, `animationNoHeadBob`,
//! `animationSingleArmAnimation`, `animationSingleLegAnimation`, `animationStationaryLegs`,
//! `animationStatueOfLibertyArms`, `animationUpsideDown`, `preserve_model_pose`), string `identifier`,
//! `opt<f64>` `texture_height`, `opt<f64>` `texture_width`, `opt<f64>` `visible_bounds_height`,
//! `opt<f64[]>` `visible_bounds_offset` (`u32` length + values), `opt<f64>` `visible_bounds_width`,
//! `f64` `height_scale`, `f64` `width_scale`, `opt<ExtraInfo>`, tristate `render_layers_first`,
//! string `default_texture`, tristate `force_culling`.
//!
//! `ExtraInfo` block: string `name`, string `tips`, `opt<string[]>` `extra_animation_names`,
//! `opt<string[]>` `authors`, string `license`.
//!
//! Notes on what the block has to carry for parity:
//!
//! * `GeoQuad.uvRotation` is always 0 in this channel (`FaceUv.getRotationEnum` answers `NONE`) but is
//!   written because the field is part of the graph.
//! * `GeoQuad.direction` is `null` for poly-mesh quads, hence the `-1` sentinel.
//! * a box cube writes six slots (null faces stay null); a poly-mesh cube writes the length of
//!   `GeoCube.quads` - either the six `null`s of the early-return paths or one entry per built quad.
//! * `GeoCube.size` is the declared size in model units (the engine never scales that field); the vertex
//!   positions are the already-baked block-unit values.

use crate::geometry::bake::{GeoBoneIr, GeoCubeIr, GeoModelIr, GeoQuadIr};
use crate::geometry::schema::{ExtraInfo, ModelProperties};
pub const BLOB_MAGIC: [u8; 4] = *b"YSMG";
pub const BLOB_VERSION: u32 = 1;

const NULL_STRING: u32 = u32::MAX;

/// Serialises one built model into its materialisation blob.
pub(crate) fn write_blob(model: &GeoModelIr) -> Result<Vec<u8>, String> {
    let mut writer = Writer::with_capacity(estimate_size(model));
    writer.bytes(&BLOB_MAGIC);
    writer.u32(BLOB_VERSION);
    writer.u32(checked_u32(model.bones.len(), "bone count")?);
    writer.u32(checked_u32(model.bones.iter().map(|bone| bone.cubes.len()).sum(), "cube count")?);
    writer.u32(checked_u32(model.bones.iter().flat_map(|bone| bone.cubes.iter()).flat_map(|cube| cube.quads.iter()).flatten().count(), "quad count")?);
    writer.u8(1); // properties present: bake_json rejects a document without `description`.
    write_properties(&mut writer, model.properties);
    write_indices(&mut writer, &model.top_level);
    write_indices(&mut writer, &model.left_hand_bones);
    write_indices(&mut writer, &model.right_hand_bones);
    write_indices(&mut writer, &model.elytra_bones);
    writer.i32(model.first_person_head.map(|index| index as i32).unwrap_or(-1));
    writer.i32(model.first_person_view_locator.map(|index| index as i32).unwrap_or(-1));
    for bone in &model.bones {
        write_bone(&mut writer, bone)?;
    }
    Ok(writer.finish())
}

fn estimate_size(model: &GeoModelIr) -> usize {
    let quads: usize = model
        .bones
        .iter()
        .flat_map(|bone| bone.cubes.iter())
        .flat_map(|cube| cube.quads.iter())
        .flatten()
        .count();
    // Bone records are tiny next to quads (each quad is 4 * 20 + 25 bytes); reserve generously but
    // bounded so a giant model does not over-reserve.
    64 + model.bones.len() * 64 + quads * 128
}

fn checked_u32(value: usize, what: &str) -> Result<u32, String> {
    u32::try_from(value).map_err(|_| format!("{what} does not fit in u32"))
}

fn write_indices(writer: &mut Writer, indices: &[usize]) {
    match checked_u32(indices.len(), "index list") {
        Ok(length) => writer.u32(length),
        Err(_) => writer.u32(u32::MAX),
    }
    for index in indices {
        writer.u32(*index as u32);
    }
}

fn write_properties(writer: &mut Writer, properties: &ModelProperties) {
    writer.tristate(properties.animation_arms_down);
    writer.tristate(properties.animation_arms_out_front);
    writer.tristate(properties.animation_dont_show_armor);
    writer.tristate(properties.animation_inverted_crouch);
    writer.tristate(properties.animation_no_head_bob);
    writer.tristate(properties.animation_single_arm_animation);
    writer.tristate(properties.animation_single_leg_animation);
    writer.tristate(properties.animation_stationary_legs);
    writer.tristate(properties.animation_statue_of_liberty_arms);
    writer.tristate(properties.animation_upside_down);
    writer.tristate(properties.preserve_model_pose);
    writer.string(properties.identifier.as_deref());
    writer.optional_f64(properties.texture_height);
    writer.optional_f64(properties.texture_width);
    writer.optional_f64(properties.visible_bounds_height);
    writer.optional_f64_slice(properties.visible_bounds_offset.as_deref());
    writer.optional_f64(properties.visible_bounds_width);
    writer.f64(properties.height_scale.unwrap_or(0.7));
    writer.f64(properties.width_scale.unwrap_or(0.7));
    match &properties.extra_info {
        Some(extra_info) => {
            writer.u8(1);
            write_extra_info(writer, extra_info);
        }
        None => writer.u8(0),
    }
    writer.tristate(properties.render_layers_first);
    writer.string(properties.default_texture.as_deref());
    writer.tristate(properties.force_culling);
}

fn write_extra_info(writer: &mut Writer, extra_info: &ExtraInfo) {
    writer.string(extra_info.name.as_deref());
    writer.string(extra_info.tips.as_deref());
    writer.optional_strings(extra_info.extra_animation_names.as_deref());
    writer.optional_strings(extra_info.authors.as_deref());
    writer.string(extra_info.license.as_deref());
}

fn write_bone(writer: &mut Writer, bone: &GeoBoneIr) -> Result<(), String> {
    writer.string(Some(&bone.name));
    writer.i32(bone.parent);
    write_indices(writer, &bone.children);
    writer.tristate(bone.mirror);
    writer.tristate(bone.dont_render);
    writer.tristate(bone.reset);
    writer.optional_f64(bone.inflate);
    writer.f32s(&bone.rotation_point);
    writer.f32s(&bone.rotation);
    writer.u32(checked_u32(bone.cubes.len(), "bone cube count")?);
    for cube in &bone.cubes {
        write_cube(writer, cube)?;
    }
    Ok(())
}

fn write_cube(writer: &mut Writer, cube: &GeoCubeIr) -> Result<(), String> {
    writer.f32s(&cube.size);
    writer.f32s(&cube.pivot);
    writer.f32s(&cube.rotation);
    writer.f64(cube.inflate);
    writer.tristate(cube.mirror);
    writer.u8(u8::from(cube.mesh));
    writer.u8(u8::from(cube.has_neg_size));
    writer.u32(checked_u32(cube.quads.len(), "cube quad slot count")?);
    for quad in &cube.quads {
        match quad {
            Some(quad) => {
                writer.u8(1);
                write_quad(writer, quad);
            }
            None => writer.u8(0),
        }
    }
    Ok(())
}

/// One serialised quad: direction `i8`, `i32` normal[3], `f32` normal_vector[3], `i32` uv_rotation and
/// four vertices of `f32` position[3] + `f32` u + `f32` v.
const QUAD_BYTES: usize = 1 + 3 * 4 + 3 * 4 + 4 + 4 * (3 * 4 + 2 * 4);

fn write_quad(writer: &mut Writer, quad: &GeoQuadIr) {
    // Written into one stack buffer and pushed with a single extend: a model can have tens of
    // thousands of quads, and one growable-buffer call per field showed up in the bake profile.
    let mut bytes = [0u8; QUAD_BYTES];
    bytes[0] = quad.direction as u8;
    let mut offset = 1;
    for value in quad.normal {
        bytes[offset..offset + 4].copy_from_slice(&value.to_le_bytes());
        offset += 4;
    }
    for value in quad.normal_vector {
        bytes[offset..offset + 4].copy_from_slice(&value.to_le_bytes());
        offset += 4;
    }
    bytes[offset..offset + 4].copy_from_slice(&quad.uv_rotation.to_le_bytes());
    offset += 4;
    for vertex in &quad.vertices {
        for value in vertex.position {
            bytes[offset..offset + 4].copy_from_slice(&value.to_le_bytes());
            offset += 4;
        }
        for value in [vertex.u, vertex.v] {
            bytes[offset..offset + 4].copy_from_slice(&value.to_le_bytes());
            offset += 4;
        }
    }
    debug_assert_eq!(offset, QUAD_BYTES);
    writer.bytes(&bytes);
}

struct Writer {
    data: Vec<u8>,
}

impl Writer {
    fn with_capacity(capacity: usize) -> Self {
        Self { data: Vec::with_capacity(capacity) }
    }

    fn finish(self) -> Vec<u8> {
        self.data
    }

    fn bytes(&mut self, bytes: &[u8]) {
        self.data.extend_from_slice(bytes);
    }

    fn u8(&mut self, value: u8) {
        self.data.push(value);
    }

    fn u32(&mut self, value: u32) {
        self.data.extend_from_slice(&value.to_le_bytes());
    }

    fn i32(&mut self, value: i32) {
        self.data.extend_from_slice(&value.to_le_bytes());
    }

    fn f32s(&mut self, values: &[f32; 3]) {
        for value in values {
            self.data.extend_from_slice(&value.to_le_bytes());
        }
    }

    fn f64(&mut self, value: f64) {
        self.data.extend_from_slice(&value.to_le_bytes());
    }

    fn string(&mut self, value: Option<&str>) {
        match value {
            Some(text) => {
                self.u32(text.len() as u32);
                self.bytes(text.as_bytes());
            }
            None => self.u32(NULL_STRING),
        }
    }

    fn optional_f64(&mut self, value: Option<f64>) {
        match value {
            Some(value) => {
                self.u8(1);
                self.f64(value);
            }
            None => self.u8(0),
        }
    }

    fn optional_f64_slice(&mut self, values: Option<&[f64]>) {
        match values {
            Some(values) => {
                self.u8(1);
                self.u32(values.len() as u32);
                for value in values {
                    self.f64(*value);
                }
            }
            None => self.u8(0),
        }
    }

    fn optional_strings(&mut self, values: Option<&[String]>) {
        match values {
            Some(values) => {
                self.u8(1);
                self.u32(values.len() as u32);
                for value in values {
                    self.string(Some(value));
                }
            }
            None => self.u8(0),
        }
    }

    fn tristate(&mut self, value: Option<bool>) {
        self.u8(match value {
            None => 0,
            Some(false) => 1,
            Some(true) => 2,
        });
    }
}

#[cfg(test)]
pub(crate) mod test_support {
    use super::{BLOB_MAGIC, BLOB_VERSION, NULL_STRING};

    /// A minimal cursor over the blob, used to prove the writer's layout round-trips: every field is
    /// consumed and the cursor ends exactly at the end of the buffer. (The authoritative reader is the
    /// Java `NativeGeometryReader`; this one only guards against layout/count bugs in the writer.)
    struct Reader<'a> {
        data: &'a [u8],
        offset: usize,
    }

    /// Counts a blob declares and the reader observed.
    #[derive(Debug, PartialEq, Eq)]
    pub(crate) struct Summary {
        pub bones: usize,
        pub cubes: usize,
        pub quads: usize,
        pub top_level: usize,
        pub left_hand: usize,
        pub right_hand: usize,
        pub elytra: usize,
    }

    impl<'a> Reader<'a> {
        fn new(data: &'a [u8]) -> Self {
            Self { data, offset: 0 }
        }

        fn take(&mut self, count: usize) -> &'a [u8] {
            assert!(self.offset + count <= self.data.len(), "blob truncated");
            let slice = &self.data[self.offset..self.offset + count];
            self.offset += count;
            slice
        }

        fn u8(&mut self) -> u8 {
            self.take(1)[0]
        }

        fn i8(&mut self) -> i8 {
            self.u8() as i8
        }

        fn u32(&mut self) -> u32 {
            u32::from_le_bytes(self.take(4).try_into().unwrap())
        }

        fn i32(&mut self) -> i32 {
            i32::from_le_bytes(self.take(4).try_into().unwrap())
        }

        fn f64(&mut self) -> f64 {
            f64::from_le_bytes(self.take(8).try_into().unwrap())
        }

        fn skip_f32(&mut self, count: usize) {
            self.take(4 * count);
        }

        fn string(&mut self) {
            let length = self.u32();
            if length != NULL_STRING {
                self.take(length as usize);
            }
        }

        fn optional_f64(&mut self) {
            if self.u8() == 1 {
                self.f64();
            }
        }

        fn optional_f64_slice(&mut self) {
            if self.u8() == 1 {
                let length = self.u32() as usize;
                for _ in 0..length {
                    self.f64();
                }
            }
        }

        fn optional_strings(&mut self) {
            if self.u8() == 1 {
                let length = self.u32() as usize;
                for _ in 0..length {
                    self.string();
                }
            }
        }

        fn tristate(&mut self) {
            let value = self.u8();
            assert!(value <= 2, "invalid tristate {value}");
        }

        fn properties(&mut self) {
            for _ in 0..11 {
                self.tristate();
            }
            self.string();
            self.optional_f64();
            self.optional_f64();
            self.optional_f64();
            self.optional_f64_slice();
            self.optional_f64();
            self.f64();
            self.f64();
            if self.u8() == 1 {
                self.string();
                self.string();
                self.optional_strings();
                self.optional_strings();
                self.string();
            }
            self.tristate();
            self.string();
            self.tristate();
        }

        fn indices(&mut self) -> usize {
            let length = self.u32() as usize;
            for _ in 0..length {
                self.u32();
            }
            length
        }

        fn quad(&mut self) {
            let direction = self.i8();
            assert!((-1..=5).contains(&direction), "invalid direction {direction}");
            for _ in 0..3 {
                self.i32();
            }
            self.skip_f32(3);
            self.i32();
            for _ in 0..4 {
                self.skip_f32(3);
                self.skip_f32(2);
            }
        }

        fn cube(&mut self) -> usize {
            self.skip_f32(9);
            self.f64();
            self.tristate();
            let mesh = self.u8();
            assert!(mesh <= 1);
            let negative = self.u8();
            assert!(negative <= 1);
            let slots = self.u32() as usize;
            let mut quads = 0;
            for _ in 0..slots {
                if self.u8() == 1 {
                    self.quad();
                    quads += 1;
                }
            }
            quads
        }

        fn summary(mut self) -> Summary {
            assert_eq!(self.take(4), BLOB_MAGIC);
            assert_eq!(self.u32(), BLOB_VERSION);
            let bones = self.u32() as usize;
            let cubes = self.u32() as usize;
            let quads = self.u32() as usize;
            assert_eq!(self.u8(), 1, "properties flag");
            self.properties();
            let top_level = self.indices();
            let left_hand = self.indices();
            let right_hand = self.indices();
            let elytra = self.indices();
            self.i32();
            self.i32();
            let mut seen_cubes = 0;
            let mut seen_quads = 0;
            for _ in 0..bones {
                self.string();
                self.i32();
                self.indices();
                self.tristate();
                self.tristate();
                self.tristate();
                self.optional_f64();
                self.skip_f32(6);
                let cube_count = self.u32() as usize;
                seen_cubes += cube_count;
                for _ in 0..cube_count {
                    seen_quads += self.cube();
                }
            }
            assert_eq!(seen_cubes, cubes, "cube count in header");
            assert_eq!(seen_quads, quads, "quad count in header");
            assert_eq!(self.offset, self.data.len(), "blob has trailing bytes");
            Summary { bones, cubes, quads, top_level, left_hand, right_hand, elytra }
        }
    }

    /// Parses a blob and returns the counts it declares (panicking on any structural inconsistency).
    pub(crate) fn read_summary(blob: &[u8]) -> Summary {
        Reader::new(blob).summary()
    }
}

#[cfg(test)]
mod tests {
    use super::test_support::read_summary;
    use crate::geometry::bake::bake_json;

    const DOCUMENT: &[u8] = br#"{
        "format_version": "1.12.0",
        "minecraft:geometry": [{
            "description": {"texture_width": 64, "texture_height": 64, "identifier": "geometry.test"},
            "bones": [
                {"name": "root", "pivot": [0, 0, 0]},
                {"name": "LeftHandLocator", "parent": "root", "pivot": [1, 2, 3]},
                {"name": "body", "parent": "root", "pivot": [0, 8, 0],
                 "cubes": [{"origin": [-4, 0, -2], "size": [8, 12, 4], "uv": [0, 0]}]}
            ]
        }]
    }"#;

    #[test]
    fn blob_round_trips_structurally() {
        let baked = bake_json(DOCUMENT, 0).expect("bakes");
        let summary = read_summary(&baked.blob);
        assert_eq!(summary.bones, baked.bone_count as usize);
        assert_eq!(summary.cubes, baked.cube_count as usize);
        assert_eq!(summary.quads, baked.quad_count as usize);
        assert_eq!(summary.top_level, 1);
        assert_eq!(summary.left_hand, 2, "root + LeftHandLocator chain");
        assert_eq!(summary.right_hand, 0);
        assert_eq!(summary.elytra, 0);
    }

    #[test]
    fn quad_header_count_matches_the_rendered_quads() {
        let baked = bake_json(DOCUMENT, 0).expect("bakes");
        let rendered: usize = baked.cubes.iter().map(|cube| cube.quads.len()).sum();
        assert_eq!(rendered, baked.quad_count as usize);
    }
}

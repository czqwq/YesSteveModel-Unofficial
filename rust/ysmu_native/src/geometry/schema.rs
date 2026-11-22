//! Serde mirror of the Bedrock geometry JSON the engine's Jackson POJOs describe.
//!
//! The reference is `software/bernie/geckolib3/geo/raw/pojo/**` (`RawGeoModel`, `MinecraftGeometry`,
//! `Bone`, `Cube`, `FaceUv`, `UvFaces`, `UvUnion`, `PolyMesh`, `PolysUnion`, `ModelProperties`,
//! `ExtraInfo`). Every field here exists because a consumer reads it: the bake
//! (`Converter` + `RawGeometryTree` + `GeoBuilder` + `GeoCube`) or the renderer/loader side tables the
//! materialisation blob feeds. Fields the POJOs parse but nothing reads (`bind_pose_rotation`,
//! `render_group_id`, `texture_meshes`, `cape`, `debug`, locator values, `material_instance`) are
//! deliberately absent: Jackson is configured with `FAIL_ON_UNKNOWN_PROPERTIES=false`
//! (`Converter.java:110`) and serde ignores unknown keys by default, so a pack carrying them parses
//! exactly the same.
//!
//! `Option<T>` mirrors a nullable Java field: `None` is JSON `null`, or an absent key **when the POJO
//! declares a non-null field initialiser** (those keys carry `#[serde(default = "...")]` returning
//! `Some(default)`). The bake then reproduces the engine's own null handling instead of guessing: for
//! example a missing `pivot` becomes `{0,0,0}` (the POJO initialiser) while an explicit `null` would
//! fail `VectorUtils.fromArray`, which is why the two cases stay distinguishable here.

use serde::de::IgnoredAny;
use serde::Deserialize;

pub fn default_zero3() -> Option<Vec<f64>> {
    Some(vec![0.0, 0.0, 0.0])
}

pub fn default_one3() -> Option<Vec<f64>> {
    Some(vec![1.0, 1.0, 1.0])
}

/// `ModelProperties.heightScale` field initialiser (`0.7D`).
pub fn default_height_scale() -> Option<f64> {
    Some(0.7)
}

/// `ModelProperties.widthScale` field initialiser (`0.7D`).
pub fn default_width_scale() -> Option<f64> {
    Some(0.7)
}

/// `ExtraInfo.tips` field initialiser (`""`).
pub fn default_tips() -> Option<String> {
    Some(String::new())
}

/// `ExtraInfo.license` field initialiser (`"All Rights Reserved"`).
pub fn default_license() -> Option<String> {
    Some("All Rights Reserved".to_string())
}

#[derive(Debug, Deserialize)]
pub struct RawGeoModel {
    /// Validated by the bake against the known `FormatVersion` strings (`FormatVersion.forValue`).
    #[serde(rename = "format_version")]
    pub format_version: Option<String>,
    #[serde(rename = "minecraft:geometry")]
    pub minecraft_geometry: Option<Vec<MinecraftGeometry>>,
}

#[derive(Debug, Deserialize)]
pub struct MinecraftGeometry {
    pub bones: Option<Vec<Bone>>,
    /// Parsed by the POJO but never read by the engine bake.
    pub cape: Option<IgnoredAny>,
    /// Jackson property name is `description` (`MinecraftGeometry.getProperties`).
    pub description: Option<ModelProperties>,
}

#[derive(Debug, Deserialize)]
pub struct Bone {
    /// Parsed by the POJO but never read by the engine bake.
    #[serde(rename = "bind_pose_rotation")]
    pub bind_pose_rotation: Option<IgnoredAny>,
    pub cubes: Option<Vec<Cube>>,
    pub debug: Option<IgnoredAny>,
    pub inflate: Option<f64>,
    /// Parsed by the POJO but never read by the engine bake (locator groups are derived from bone names).
    pub locators: Option<IgnoredAny>,
    pub mirror: Option<bool>,
    pub name: Option<String>,
    #[serde(rename = "neverRender")]
    pub never_render: Option<bool>,
    pub parent: Option<String>,
    #[serde(default = "default_zero3")]
    pub pivot: Option<Vec<f64>>,
    #[serde(rename = "poly_mesh")]
    pub poly_mesh: Option<PolyMesh>,
    /// YSMU extension: secondary poly mesh for the negative-volume outline cube (`__ysm_neg_mesh`).
    #[serde(rename = "__ysm_neg_mesh")]
    pub ysm_neg_mesh: Option<PolyMesh>,
    #[serde(rename = "render_group_id")]
    pub render_group_id: Option<IgnoredAny>,
    pub reset: Option<bool>,
    #[serde(default = "default_zero3")]
    pub rotation: Option<Vec<f64>>,
    /// Parsed by the POJO but never read by the engine bake.
    #[serde(rename = "texture_meshes")]
    pub texture_meshes: Option<IgnoredAny>,
}

#[derive(Debug, Deserialize)]
pub struct Cube {
    pub inflate: Option<f64>,
    pub mirror: Option<bool>,
    #[serde(default = "default_zero3")]
    pub origin: Option<Vec<f64>>,
    #[serde(default = "default_zero3")]
    pub pivot: Option<Vec<f64>>,
    #[serde(default = "default_zero3")]
    pub rotation: Option<Vec<f64>>,
    #[serde(default = "default_one3")]
    pub size: Option<Vec<f64>>,
    pub uv: Option<UvUnion>,
}

/// `UvUnion`'s custom Jackson deserialiser: an array is box UV, an object is per-face UV, `null`
/// becomes an all-`null` union (`UvUnion.Deserializer`, `UvUnion.java:23-45`).
///
/// Hand-written rather than `#[serde(untagged)]`: an untagged enum buffers the whole value into serde's
/// internal `Content` tree before trying the variants, which on the built-in corpus is tens of
/// thousands of small heap allocations per big model (a cube's `uv` object holds six faces). Peeking at
/// the first token instead parses the value exactly once.
#[derive(Debug)]
pub enum UvUnion {
    BoxUv(Vec<f64>),
    Faces(UvFaces),
}

impl<'de> serde::Deserialize<'de> for UvUnion {
    fn deserialize<D>(deserializer: D) -> Result<Self, D::Error>
    where
        D: serde::Deserializer<'de>,
    {
        struct UvUnionVisitor;

        impl<'de> serde::de::Visitor<'de> for UvUnionVisitor {
            type Value = UvUnion;

            fn expecting(&self, formatter: &mut std::fmt::Formatter) -> std::fmt::Result {
                formatter.write_str("an array of box uv coordinates or an object of per-face uv data")
            }

            fn visit_seq<A>(self, mut sequence: A) -> Result<Self::Value, A::Error>
            where
                A: serde::de::SeqAccess<'de>,
            {
                let mut values = Vec::new();
                while let Some(value) = sequence.next_element::<f64>()? {
                    values.push(value);
                }
                Ok(UvUnion::BoxUv(values))
            }

            fn visit_map<A>(self, map: A) -> Result<Self::Value, A::Error>
            where
                A: serde::de::MapAccess<'de>,
            {
                // `MapAccessDeserializer` lets the derive on `UvFaces` read the same map directly.
                let faces = UvFaces::deserialize(serde::de::value::MapAccessDeserializer::new(map))?;
                Ok(UvUnion::Faces(faces))
            }
        }

        deserializer.deserialize_any(UvUnionVisitor)
    }
}

#[derive(Debug, Default, Deserialize)]
pub struct UvFaces {
    pub down: Option<FaceUv>,
    pub east: Option<FaceUv>,
    pub north: Option<FaceUv>,
    pub south: Option<FaceUv>,
    pub up: Option<FaceUv>,
    pub west: Option<FaceUv>,
}

#[derive(Debug, Deserialize)]
pub struct FaceUv {
    /// Parsed by the POJO; the engine never reads it.
    #[allow(dead_code)]
    pub material_instance: Option<IgnoredAny>,
    pub uv: Option<Vec<f64>>,
    #[serde(rename = "uv_size")]
    pub uv_size: Option<Vec<f64>>,
    /// Parsed and readable but deliberately not applied (`FaceUv.getRotationEnum` always answers
    /// `NONE`; upstream leaves `uv_rotation` unimplemented and the native bake has no such concept), so
    /// it never reaches the bake. Kept untyped so a pack carrying it parses anyway.
    #[serde(rename = "uv_rotation")]
    #[allow(dead_code)]
    pub uv_rotation: Option<IgnoredAny>,
}

#[derive(Debug, Default, Deserialize)]
pub struct PolyMesh {
    #[serde(rename = "normalized_uvs")]
    pub normalized_uvs: Option<bool>,
    pub normals: Option<Vec<f64>>,
    pub polys: Option<PolysUnion>,
    pub positions: Option<Vec<f64>>,
    pub uvs: Option<Vec<f64>>,
}

/// `PolysUnion`'s custom Jackson deserialiser: a string is the `quad_list`/`tri_list` enum, an array
/// is the indexed form (which `GeoCube.createFromPolyMesh` ignores). An unknown string leaves a null
/// enum value, which the cube builder treats exactly like a missing one.
///
/// Hand-written for the same reason as [`UvUnion`], and the indexed form is consumed without building
/// its nested arrays: nothing reads it.
#[derive(Debug)]
pub enum PolysUnion {
    Kind(String),
    Indexed,
}

impl<'de> serde::Deserialize<'de> for PolysUnion {
    fn deserialize<D>(deserializer: D) -> Result<Self, D::Error>
    where
        D: serde::Deserializer<'de>,
    {
        struct PolysUnionVisitor;

        impl<'de> serde::de::Visitor<'de> for PolysUnionVisitor {
            type Value = PolysUnion;

            fn expecting(&self, formatter: &mut std::fmt::Formatter) -> std::fmt::Result {
                formatter.write_str("a polys kind string or an indexed polys array")
            }

            fn visit_str<E>(self, value: &str) -> Result<Self::Value, E>
            where
                E: serde::de::Error,
            {
                Ok(PolysUnion::Kind(value.to_string()))
            }

            fn visit_seq<A>(self, mut sequence: A) -> Result<Self::Value, A::Error>
            where
                A: serde::de::SeqAccess<'de>,
            {
                // Consume the indexed form without materialising it (the engine ignores it too).
                while sequence.next_element::<IgnoredAny>()?.is_some() {}
                Ok(PolysUnion::Indexed)
            }
        }

        deserializer.deserialize_any(PolysUnionVisitor)
    }
}

#[derive(Debug, Deserialize)]
pub struct ModelProperties {
    #[serde(rename = "animationArmsDown")]
    pub animation_arms_down: Option<bool>,
    #[serde(rename = "animationArmsOutFront")]
    pub animation_arms_out_front: Option<bool>,
    #[serde(rename = "animationDontShowArmor")]
    pub animation_dont_show_armor: Option<bool>,
    #[serde(rename = "animationInvertedCrouch")]
    pub animation_inverted_crouch: Option<bool>,
    #[serde(rename = "animationNoHeadBob")]
    pub animation_no_head_bob: Option<bool>,
    #[serde(rename = "animationSingleArmAnimation")]
    pub animation_single_arm_animation: Option<bool>,
    #[serde(rename = "animationSingleLegAnimation")]
    pub animation_single_leg_animation: Option<bool>,
    #[serde(rename = "animationStationaryLegs")]
    pub animation_stationary_legs: Option<bool>,
    #[serde(rename = "animationStatueOfLibertyArms")]
    pub animation_statue_of_liberty_arms: Option<bool>,
    #[serde(rename = "animationUpsideDown")]
    pub animation_upside_down: Option<bool>,
    pub identifier: Option<String>,
    #[serde(rename = "preserve_model_pose")]
    pub preserve_model_pose: Option<bool>,
    #[serde(rename = "texture_height")]
    pub texture_height: Option<f64>,
    #[serde(rename = "texture_width")]
    pub texture_width: Option<f64>,
    #[serde(rename = "visible_bounds_height")]
    pub visible_bounds_height: Option<f64>,
    #[serde(rename = "visible_bounds_offset")]
    pub visible_bounds_offset: Option<Vec<f64>>,
    #[serde(rename = "visible_bounds_width")]
    pub visible_bounds_width: Option<f64>,
    /// Host scale, declared under the `ysm_` prefix; POJO initialiser `0.7D`. `BuiltGeo` unboxes it, so
    /// `None` (explicit `null`) fails the Java build exactly like a missing `description`.
    #[serde(rename = "ysm_height_scale", default = "default_height_scale")]
    pub height_scale: Option<f64>,
    #[serde(rename = "ysm_width_scale", default = "default_width_scale")]
    pub width_scale: Option<f64>,
    #[serde(rename = "ysm_extra_info")]
    pub extra_info: Option<ExtraInfo>,
    #[serde(rename = "ysm_render_layers_first")]
    pub render_layers_first: Option<bool>,
    #[serde(rename = "ysm_default_texture")]
    pub default_texture: Option<String>,
    #[serde(rename = "ysm_all_cutout")]
    pub force_culling: Option<bool>,
}

#[derive(Debug, Deserialize)]
pub struct ExtraInfo {
    pub name: Option<String>,
    #[serde(default = "default_tips")]
    pub tips: Option<String>,
    #[serde(rename = "extra_animation_names")]
    pub extra_animation_names: Option<Vec<String>>,
    pub authors: Option<Vec<String>>,
    #[serde(default = "default_license")]
    pub license: Option<String>,
}

/// The `format_version` strings `FormatVersion.forValue` accepts, compared after `String.trim()`.
pub const SUPPORTED_FORMAT_VERSIONS: [&str; 8] =
    ["1.8.0", "1.10.0", "1.12.0", "1.14.0", "1.16.0", "1.19.0", "1.20.0", "1.21.0"];

/// Port of `FormatVersion.forValue` + `isSupportedLayout` (which answers `true` for every known
/// version, `FormatVersion.java:69-88`).
pub fn is_supported_format_version(declared: Option<&str>) -> bool {
    match declared {
        Some(value) => {
            let normalized = value.trim();
            SUPPORTED_FORMAT_VERSIONS.contains(&normalized)
        }
        None => false,
    }
}


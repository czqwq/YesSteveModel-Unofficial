//! The bake: `RawGeometryTree.parseHierarchy` + `GeoBuilder.constructGeoModel` + `GeoCube` ported to
//! Rust, producing both the render-ready [`BakedGeometry`] and the materialisation blob.
//!
//! Faithfulness notes (each one is a quirk a shipped pack depends on):
//!
//! * **Coordinate conventions.** The engine negates X on every bone/cube rotation and pivot
//!   (`GeoBuilder.java:77-78`, `GeoCube.java:165-173`) and builds the eight corners from
//!   `origin = (-(ox + sx) / 16, oy / 16, oz / 16)` (`GeoCube.java:157-158`). The positions stored in
//!   the engine are already in block units, so the bake stores them that way: the renderer multiplies
//!   them by the cube matrix directly.
//! * **Cube rotations.** `MatrixStack.beginCube` composes `T(pivot) * Rz * Ry * Rx * T(-pivot)`
//!   (`MatrixStack.java:324-369`), i.e. the vertices are rotated X first; `.agent/phase57` records the
//!   regression that came from baking that order backwards.
//! * **UV mapping.** `GeoQuad`'s constructor normalises the face uv rect, swaps u1/u2 when the cube is
//!   not mirrored, negates the normal X when it is, and applies the (always `NONE`) uv rotation
//!   (`GeoQuad.java:32-75`, `FaceUv.java:79-95`). Mirroring a bone swaps the west/east and up/down
//!   vertex orders (`GeoCube.java:329-396`), the cube's own mirror flag does the same for box UV
//!   (`:476-531`).
//! * **Inflate.** A cube's own `inflate` is divided by 16 (`GeoCube.java:123`), a bone's is divided by
//!   16 by `GeoBuilder` (`GeoBuilder.java:101`); a zero-thickness cube with zero inflate gets the
//!   port-specific `0.001` (`GeoCube.java:125-127`, see AGENTS.md: the engine keeps that inflation
//!   because removing it makes flat decals z-fight).
//! * **Negative sizes.** A cube with any negative size component is normalised to `abs(size)` with its
//!   origin shifted to the true minimum corner and the `hasNegSize` flag set (`GeoCube.java:108-155`),
//!   which `IGeoRenderer.renderBoneCubes` draws first with `CULL_FRONT`.
//! * **poly_mesh.** `GeoCube.createFromPolyMesh` is the legacy channel's free-form geometry: its
//!   positions are used as-is (no `/16`), quads come from the flat `quad_list` form only, quads are
//!   `min(positions/12, uvs/8)`, and the normal per quad is either `(0,1,0)` or the flat normal list
//!   (`GeoCube.java:37-104`).
//! * **Hierarchy.** `RawGeometryTree.parseHierarchy` scans the bone array backwards, wrapping, and only
//!   attaches a bone once its parent is already in the tree; bones whose parent never resolves are
//!   dropped after 10000 unchanged loops. Children and top-level bones live in Java `HashMap`s, so the
//!   bake reproduces their iteration order (see [`crate::geometry::jmap`]).

use std::collections::HashMap;

use crate::geometry::jmap::JavaHashMap;
use crate::geometry::model::{
    BakedBone, BakedCube, BakedGeometry, BakedQuad, CUBE_VERTEX_COUNT, FLAG_FLAT_CUBES, FLAG_MESH_CUBES,
    FLAG_NEGATIVE_CUBES,
};
use crate::geometry::schema::{
    is_supported_format_version, Bone, Cube, FaceUv, ModelProperties, PolyMesh, PolysUnion, RawGeoModel,
    UvUnion,
};

// ---------------------------------------------------------------------------------------------------------------------
// Engine-shaped intermediate graph (the object graph box `GeoBuilder` produces, before materialisation)
// ---------------------------------------------------------------------------------------------------------------------

/// One built `GeoVertex`: a position (already in the engine's units) and the normalised texture coords.
#[derive(Clone, Copy, Debug, Default)]
pub(crate) struct GeoVertexIr {
    pub position: [f32; 3],
    pub u: f32,
    pub v: f32,
}

/// One built `GeoQuad`. `direction` is the `EnumFacing` ordinal, or `-1` for the poly-mesh constructor
/// which leaves `direction` null; `normal`/`normal_vector` are `GeoQuad.normal` (`Vec3i`) and
/// `GeoQuad.normalVector` after the constructor's mirror handling.
#[derive(Clone, Debug)]
pub(crate) struct GeoQuadIr {
    pub direction: i8,
    pub normal: [i32; 3],
    pub normal_vector: [f32; 3],
    pub uv_rotation: i32,
    pub vertices: [GeoVertexIr; 4],
    /// Indices into [`GeoCubeIr::positions`]; for mesh cubes the writer stores quad-local `0..4` instead
    /// and the render-ready copy makes them absolute (see [`GeoCubeIr::mesh`]).
    pub vertex_indices: [u8; 4],
}

/// One built `GeoCube`.
#[derive(Clone, Debug)]
pub(crate) struct GeoCubeIr {
    /// `GeoCube.size`: the declared size in model units, `(0,0,0)` for a mesh cube.
    pub size: [f32; 3],
    /// `GeoCube.pivot` (model units, X already negated).
    pub pivot: [f32; 3],
    /// `GeoCube.rotation` (radians, X/Y already negated).
    pub rotation: [f32; 3],
    /// `GeoCube.inflate` (block units).
    pub inflate: f64,
    /// `GeoCube.mirror` (the cube's own flag; `None` is the POJO's `null`).
    pub mirror: Option<bool>,
    pub mesh: bool,
    pub has_neg_size: bool,
    /// Box cube: `[Some/None; 6]` in west, east, north, south, up, down order. Mesh cube: one entry per
    /// built quad (or the six `None`s of `createFromPolyMesh`'s early returns, whose `GeoQuad[]` is the
    /// default 6-slot array).
    pub quads: Vec<Option<GeoQuadIr>>,
    /// The eight corner positions (`P1..P8`) of a box cube; all zero for a mesh cube.
    pub positions: [[f32; 3]; CUBE_VERTEX_COUNT],
}

/// One built `GeoBone`; `children` hold indices into [`GeoModelIr::bones`] in `GeoBone.childBones` order.
#[derive(Clone, Debug)]
pub(crate) struct GeoBoneIr {
    pub name: String,
    pub parent: i32,
    pub children: Vec<usize>,
    pub mirror: Option<bool>,
    pub dont_render: Option<bool>,
    pub reset: Option<bool>,
    pub inflate: Option<f64>,
    pub rotation_point: [f32; 3],
    pub rotation: [f32; 3],
    pub cubes: Vec<GeoCubeIr>,
}

/// One built `GeoModel`. Bone index order is the depth-first bake order the renderer's native fast path
/// uses (`GeoBone.nativeBoneIndex`); the top-level and locator lists carry the engine's list order.
#[derive(Debug)]
pub(crate) struct GeoModelIr<'a> {
    pub bones: Vec<GeoBoneIr>,
    pub properties: &'a ModelProperties,
    pub top_level: Vec<usize>,
    pub left_hand_bones: Vec<usize>,
    pub right_hand_bones: Vec<usize>,
    pub elytra_bones: Vec<usize>,
    pub first_person_head: Option<usize>,
    pub first_person_view_locator: Option<usize>,
}

// ---------------------------------------------------------------------------------------------------------------------
// Entry point
// ---------------------------------------------------------------------------------------------------------------------

/// Parses and bakes one geometry JSON document.
///
/// `format_version_hint` is `0` when the caller has not parsed the document (the normal case: the Java
/// loader only has the raw bytes, and parsing JSON just to read `format_version` would defeat the point
/// of the native bake). In that case the declared version is validated here against the engine's own
/// accepted strings (`FormatVersion.forValue`). A non-zero hint is the already-validated declared version
/// as `FormatVersion.ordinal() + 1`, which skips that check; no current caller uses it.
///
/// `Err` means "the native bake declines this document" - malformed JSON, an unsupported layout, or a
/// shape `GeoBuilder` itself would reject. The Java caller then runs the Jackson + `GeoBuilder` path,
/// which reports the real error. Declining never throws.
pub fn bake_json(json: &[u8], format_version_hint: i32) -> Result<BakedGeometry, String> {
    if !(0..=8).contains(&format_version_hint) {
        return Err(format!("invalid format_version hint {format_version_hint}"));
    }
    let cleaned = strip_json_comments(json);
    let raw: RawGeoModel =
        serde_json::from_slice(cleaned.as_ref()).map_err(|error| format!("geometry JSON: {error}"))?;
    if format_version_hint == 0 && !is_supported_format_version(raw.format_version.as_deref()) {
        // Unsupported/missing `format_version`: `RawGeometryTree` is only reached for a version
        // `FormatVersion` accepted, and `ClientModelManager` rejects the rest with a warning.
        return Err(format!(
            "unsupported format_version {:?}",
            raw.format_version.as_deref().unwrap_or("<missing>")
        ));
    }
    let geometry = raw
        .minecraft_geometry
        .as_ref()
        .and_then(|list| list.first())
        .ok_or_else(|| "geometry document has no 'minecraft:geometry' array".to_string())?;
    // `RawGeometryTree.parseHierarchy` opens by rejecting an empty/missing bones array
    // (RawGeometryTree.java:23-31).
    let bones = geometry
        .bones
        .as_ref()
        .ok_or_else(|| "geometry document has no 'bones' array".to_string())?;
    if bones.is_empty() {
        return Err("geometry document has an empty 'bones' array".to_string());
    }
    // `BuiltGeo`'s constructor unboxes `properties.heightScale`/`widthScale`, so a geometry without a
    // `description` (or with an explicit `null` scale) fails the Java build path; decline so the Java
    // path reproduces that outcome and logs its own warning.
    let properties = geometry
        .description
        .as_ref()
        .ok_or_else(|| "geometry document has no 'description'".to_string())?;
    if properties.height_scale.is_none() || properties.width_scale.is_none() {
        return Err("geometry description declares a null ysm_height_scale/ysm_width_scale".to_string());
    }
    let tree = parse_hierarchy(bones)?;
    let model = construct_model(&tree, bones, properties)?;
    let blob = crate::geometry::blob::write_blob(&model)?;
    Ok(into_baked(&model, blob))
}

// ---------------------------------------------------------------------------------------------------------------------
// JSON comment stripping (Jackson's ALLOW_COMMENTS)
// ---------------------------------------------------------------------------------------------------------------------

/// Removes `//` line comments and `/* */` block comments the way Jackson's
/// `JsonParser.Feature.ALLOW_COMMENTS` does (`Converter.createMapper`, `Converter.java:101-105`).
///
/// Comments inside string literals are content, not comments, so the scanner tracks string state and
/// escapes. A block comment is replaced by one space so `1/*c*/2` cannot fuse into the number `12`;
/// everything else is copied verbatim. A document without any `/` (the overwhelming majority - the
/// built-in corpus has no comments at all) is returned borrowed, which keeps the parse from paying a
/// full copy of a multi-megabyte JSON document.
fn strip_json_comments(input: &[u8]) -> std::borrow::Cow<'_, [u8]> {
    if !input.contains(&b'/') {
        return std::borrow::Cow::Borrowed(input);
    }
    let mut output = Vec::with_capacity(input.len());
    let mut index = 0;
    let mut in_string = false;
    while index < input.len() {
        let byte = input[index];
        if in_string {
            output.push(byte);
            if byte == b'\\' && index + 1 < input.len() {
                output.push(input[index + 1]);
                index += 2;
                continue;
            }
            if byte == b'"' {
                in_string = false;
            }
            index += 1;
            continue;
        }
        match byte {
            b'"' => {
                in_string = true;
                output.push(byte);
                index += 1;
            }
            b'/' if input.get(index + 1) == Some(&b'/') => {
                index += 2;
                while index < input.len() && input[index] != b'\n' && input[index] != b'\r' {
                    index += 1;
                }
            }
            b'/' if input.get(index + 1) == Some(&b'*') => {
                output.push(b' ');
                index += 2;
                while index + 1 < input.len() && !(input[index] == b'*' && input[index + 1] == b'/') {
                    index += 1;
                }
                index = (index + 2).min(input.len());
            }
            _ => {
                output.push(byte);
                index += 1;
            }
        }
    }
    std::borrow::Cow::Owned(output)
}

// ---------------------------------------------------------------------------------------------------------------------
// RawGeometryTree.parseHierarchy (RawGeometryTree.java:19-87)
// ---------------------------------------------------------------------------------------------------------------------

struct TreeNode {
    /// Index into the document's bone array.
    bone: usize,
    children: JavaHashMap,
}

struct HierarchyTree {
    top_level: JavaHashMap,
    nodes: Vec<TreeNode>,
}

/// Port of `RawGeometryTree.parseHierarchy`.
///
/// The scan starts at the last bone and walks backwards, wrapping to the end of the remaining list
/// whenever it reaches index 0. A bone is attached to the tree when it has no `parent` field (it becomes
/// top-level) or when its parent is already in the tree; the loop gives up after 10000 iterations in
/// which nothing was attached (`loopsWithoutChange > 10000`). The `HashMap` nature of the top-level and
/// children maps is preserved by [`JavaHashMap`] because the builder walks them later.
///
/// Java's `getGroupFromHierarchy` rebuilds a flattened `name -> group` map on every call. That is
/// quadratic in the bone count, and it is only observable through duplicate names - a lookup can never
/// resolve a name that occurs once to anything but that bone. So the common case (every bone name
/// unique) gets an incrementally maintained lookup table here, and only a hierarchy with duplicate
/// names takes the faithful rebuild-on-every-attach path.
fn parse_hierarchy(bones: &[Bone]) -> Result<HierarchyTree, String> {
    let mut tree = HierarchyTree { top_level: JavaHashMap::new(), nodes: Vec::new() };
    let mut remaining: Vec<usize> = (0..bones.len()).collect();
    let mut index: isize = remaining.len() as isize - 1;
    let mut loops_without_change = 0u32;
    let mut unique_names = unique_name_index(bones);
    let mut flattened: Option<HashMap<&str, usize>> = None;
    loop {
        loops_without_change += 1;
        if loops_without_change > 10_000 {
            // Java only breaks out of the loop; the unattached bones are silently dropped.
            break;
        }
        let bone_id = remaining.get(index as usize).copied().ok_or_else(|| {
            format!("bone index {index} out of range while parsing the hierarchy")
        })?;
        let raw_bone = &bones[bone_id];
        let attached = match raw_bone.parent.as_deref() {
            None => Some(None),
            Some(parent_name) => {
                if let Some(names) = unique_names.as_ref() {
                    // All names are unique, so the attached node with this name is the only answer.
                    names.get(parent_name).copied().map(Some)
                } else {
                    // Java's `getGroupFromHierarchy` only changes when a bone is attached; cache the
                    // flattened map between attaches (identical lookup results, far fewer rebuilds).
                    if flattened.is_none() {
                        flattened = Some(flatten(&tree, bones));
                    }
                    flattened.as_ref().expect("flattened map is built").get(parent_name).copied().map(Some)
                }
            }
        };
        match attached {
            Some(parent_node) => {
                let node = tree.nodes.len();
                tree.nodes.push(TreeNode { bone: bone_id, children: JavaHashMap::new() });
                match parent_node {
                    None => tree.top_level.put(raw_bone.name.clone(), node),
                    Some(parent) => tree.nodes[parent].children.put(raw_bone.name.clone(), node),
                }
                .map_err(|_| "bone hierarchy HashMap would treeify".to_string())?;
                if let Some(names) = unique_names.as_mut() {
                    if let Some(name) = raw_bone.name.as_deref() {
                        names.insert(name, node);
                    }
                }
                remaining.remove(index as usize);
                loops_without_change = 0;
                flattened = None;
            }
            None => {}
        }
        if index == 0 {
            index = remaining.len() as isize - 1;
            if index == -1 {
                break;
            }
        } else {
            index -= 1;
        }
    }
    Ok(tree)
}

/// `Some(map)` when every bone declares a distinct name (and none is `null`), i.e. when a name lookup
/// always resolves to the one bone carrying it, exactly like Java's flattened map for unique names.
fn unique_name_index(bones: &[Bone]) -> Option<HashMap<&str, usize>> {
    let mut seen = std::collections::HashSet::with_capacity(bones.len());
    for bone in bones {
        let name = bone.name.as_deref()?;
        if !seen.insert(name) {
            return None;
        }
    }
    Some(HashMap::with_capacity(bones.len()))
}

/// `RawGeometryTree.getGroupFromHierarchy` + `traverse`: a flattened name -> node index map built in
/// traversal order (top-level iteration order, then depth-first children iteration order), so duplicate
/// names resolve exactly like Java's repeated `put`s (the later traversal wins). The map borrows the
/// names - Java's `put` does not copy the key either, and cloning them here is quadratic work.
fn flatten<'a>(tree: &HierarchyTree, bones: &'a [Bone]) -> HashMap<&'a str, usize> {
    let mut flat = HashMap::new();
    for node in tree.top_level.values() {
        if let Some(name) = bones[tree.nodes[node].bone].name.as_deref() {
            flat.insert(name, node);
        }
        flatten_children(&mut flat, tree, bones, node);
    }
    flat
}

fn flatten_children<'a>(
    flat: &mut HashMap<&'a str, usize>,
    tree: &HierarchyTree,
    bones: &'a [Bone],
    node: usize,
) {
    for child in tree.nodes[node].children.values() {
        if let Some(name) = bones[tree.nodes[child].bone].name.as_deref() {
            flat.insert(name, child);
        }
        flatten_children(flat, tree, bones, child);
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// GeoBuilder.constructGeoModel / constructBone (GeoBuilder.java:41-129)
// ---------------------------------------------------------------------------------------------------------------------

const LEFT_HAND_LOCATOR: &str = "LeftHandLocator";
const RIGHT_HAND_LOCATOR: &str = "RightHandLocator";
const ELYTRA_LOCATOR_NAME: &str = "ElytraLocator";
const FIRST_PERSON_HEAD_NAME: &str = "AllHead";
const FIRST_PERSON_VIEW_LOCATOR_NAME: &str = "ViewLocator";

fn construct_model<'a>(
    tree: &HierarchyTree,
    raw_bones: &[Bone],
    properties: &'a ModelProperties,
) -> Result<GeoModelIr<'a>, String> {
    let mut model = GeoModelIr {
        bones: Vec::with_capacity(tree.nodes.len()),
        properties,
        top_level: Vec::with_capacity(tree.top_level.len()),
        left_hand_bones: Vec::new(),
        right_hand_bones: Vec::new(),
        elytra_bones: Vec::new(),
        first_person_head: None,
        first_person_view_locator: None,
    };
    for root in tree.top_level.values() {
        let index = construct_bone(root, -1, tree, raw_bones, properties, &mut model.bones)?;
        model.top_level.push(index);
    }
    // `GeoBuilder.constructGeoModel`'s locator wiring: `getBone(name)` then that bone's parent chain,
    // reversed (root first, locator last) (`GeoBuilder.java:48-66`, `:124-129`).
    model.left_hand_bones = locator_chain(&model.bones, LEFT_HAND_LOCATOR);
    model.right_hand_bones = locator_chain(&model.bones, RIGHT_HAND_LOCATOR);
    model.elytra_bones = locator_chain(&model.bones, ELYTRA_LOCATOR_NAME);
    model.first_person_head = find_bone(&model.bones, FIRST_PERSON_HEAD_NAME);
    model.first_person_view_locator = find_bone(&model.bones, FIRST_PERSON_VIEW_LOCATOR_NAME);
    Ok(model)
}

/// `GeoModel.getBone` in the bake's depth-first order: the first bone with that name wins
/// (`GeoModel.getBone` walks `topLevelBones` and recurses; that is a depth-first pre-order walk).
fn find_bone(bones: &[GeoBoneIr], name: &str) -> Option<usize> {
    bones.iter().position(|bone| bone.name == name)
}

/// `getBoneParent` + `Collections.reverse`: the chain from the root to the locator bone, inclusive.
fn locator_chain(bones: &[GeoBoneIr], name: &str) -> Vec<usize> {
    let Some(mut current) = find_bone(bones, name) else {
        return Vec::new();
    };
    let mut chain = Vec::new();
    loop {
        chain.push(current);
        if bones[current].parent < 0 {
            break;
        }
        current = bones[current].parent as usize;
    }
    chain.reverse();
    chain
}

/// Port of `GeoBuilder.constructBone` (`GeoBuilder.java:71-122`).
///
/// The bone index order is depth-first pre-order, which is both the renderer's draw order and the
/// `GeoBone.nativeBoneIndex` the materialiser assigns.
fn construct_bone(
    node: usize,
    parent: i32,
    tree: &HierarchyTree,
    raw_bones: &[Bone],
    properties: &ModelProperties,
    out: &mut Vec<GeoBoneIr>,
) -> Result<usize, String> {
    let raw_bone = &raw_bones[tree.nodes[node].bone];
    // A null bone name reaches `GeoModel.getBone`'s `bone.name.equals(...)` and fails the whole Java
    // build, so it is declined rather than materialised.
    let name = raw_bone
        .name
        .clone()
        .ok_or_else(|| "bone has no name (GeoModel#getBone would throw)".to_string())?;
    let rotation = bone_rotation(raw_bone)?;
    let rotation_point = bone_rotation_point(raw_bone)?;
    let bone_inflate = raw_bone.inflate;
    let bone_inflate_blocks = bone_inflate.map(|inflate| inflate / 16.0);

    let mut cubes = Vec::with_capacity(raw_bone.cubes.as_ref().map_or(0, Vec::len) + 2);
    if let Some(raw_cubes) = &raw_bone.cubes {
        for cube in raw_cubes {
            cubes.push(build_box_cube(cube, properties, bone_inflate_blocks, raw_bone.mirror)?);
        }
    }
    if let Some(mesh) = &raw_bone.poly_mesh {
        cubes.push(build_poly_mesh_cube(mesh, properties, false)?);
    }
    // YSMU extension: the secondary poly mesh is a separate GeoCube with `hasNegSize = true`, drawn as the
    // `CULL_FRONT` outline pass (`GeoBuilder.java:108-115`).
    if let Some(mesh) = &raw_bone.ysm_neg_mesh {
        cubes.push(build_poly_mesh_cube(mesh, properties, true)?);
    }

    let index = out.len();
    out.push(GeoBoneIr {
        name,
        parent,
        children: Vec::new(),
        mirror: raw_bone.mirror,
        dont_render: raw_bone.never_render,
        reset: raw_bone.reset,
        inflate: bone_inflate,
        rotation_point,
        rotation,
        cubes,
    });
    let mut children = Vec::new();
    for child in tree.nodes[node].children.values() {
        children.push(construct_bone(child, index as i32, tree, raw_bones, properties, out)?);
    }
    out[index].children = children;
    Ok(index)
}

/// `VectorUtils.fromArray` requires three components; the rotation is cast to float, X and Y are negated
/// and the result converted to radians (`GeoBuilder.java:75-89`).
fn bone_rotation(raw_bone: &Bone) -> Result<[f32; 3], String> {
    let values = raw_bone.rotation.as_deref().ok_or_else(|| "bone.rotation is null".to_string())?;
    if values.len() < 3 {
        return Err("bone.rotation needs three components".to_string());
    }
    let mut rotation = [values[0] as f32, values[1] as f32, values[2] as f32];
    rotation[0] *= -1.0;
    rotation[1] *= -1.0;
    Ok([to_radians(rotation[0]), to_radians(rotation[1]), to_radians(rotation[2])])
}

/// `GeoBone.rotationPoint = (-pivot.x, pivot.y, pivot.z)` after the double -> float cast
/// (`GeoBuilder.java:76`, `:91-93`).
fn bone_rotation_point(raw_bone: &Bone) -> Result<[f32; 3], String> {
    let values = raw_bone.pivot.as_deref().ok_or_else(|| "bone.pivot is null".to_string())?;
    if values.len() < 3 {
        return Err("bone.pivot needs three components".to_string());
    }
    let pivot = [values[0] as f32, values[1] as f32, values[2] as f32];
    Ok([-pivot[0], pivot[1], pivot[2]])
}

/// `Math.toRadians(double)` evaluated on the float value and cast back to float, exactly like
/// `(float) Math.toRadians(rotation.x)` where `rotation.x` is a `Vector3f` component.
fn to_radians(degrees: f32) -> f32 {
    (degrees as f64 / 180.0 * std::f64::consts::PI) as f32
}

/// `Math.round(float)` (floor(x + 0.5)), used by `GeoQuad`'s poly-mesh constructor for `Vec3i`.
fn java_round(value: f32) -> i32 {
    (value + 0.5).floor() as i32
}

// ---------------------------------------------------------------------------------------------------------------------
// GeoCube.createFromPojoCube (GeoCube.java:106-568)
// ---------------------------------------------------------------------------------------------------------------------

/// Box-UV face slot order: west, east, north, south, up, down (`GeoCube.quads[0..6]`).
const FACE_ORDINALS: [i8; 6] = [5, 4, 2, 3, 1, 0];
/// 1.7.10 `EnumFacing` front offsets in the same slot order. Note that 1.7.10's EAST offset is `-X` and
/// WEST is `+X`, the opposite of modern versions
/// (`build/rfg/minecraft-src/java/net/minecraft/util/EnumFacing.java:3-8`).
const FACE_NORMALS: [[i32; 3]; 6] = [[1, 0, 0], [-1, 0, 0], [0, 0, -1], [0, 0, 1], [0, 1, 0], [0, -1, 0]];
/// Unmirrored vertex order per face (indices into `P1..P8`, i.e. 0-based) (`GeoCube.java:262-327`).
const FACE_VERTICES: [[u8; 4]; 6] = [[3, 2, 0, 1], [6, 7, 5, 4], [2, 6, 4, 0], [7, 3, 1, 5], [3, 7, 6, 2], [0, 4, 5, 1]];
/// Vertex order under a mirrored bone/cube on the **per-face-UV** channel (`GeoCube.java:329-396`):
/// west/east, up and down all swap.
const FACE_VERTICES_MIRRORED: [[u8; 4]; 6] =
    [[6, 7, 5, 4], [3, 2, 0, 1], [2, 6, 4, 0], [7, 3, 1, 5], [0, 4, 5, 1], [3, 7, 6, 2]];
/// Vertex order under a mirrored bone/cube on the **box-UV** channel (`GeoCube.java:476-531`): only
/// west/east swap, up and down keep the unmirrored order - the box-UV mirrored branch does not rebuild
/// those two faces with the swapped corners the per-face branch uses.
const FACE_VERTICES_BOX_MIRRORED: [[u8; 4]; 6] =
    [[6, 7, 5, 4], [3, 2, 0, 1], [2, 6, 4, 0], [7, 3, 1, 5], [3, 7, 6, 2], [0, 4, 5, 1]];

/// The engine's texture size fallback: a missing or `0` declaration means 64
/// (`GeoCube.java:129-140`; upstream's own rule is `textureWidth != 0 ? textureWidth : 64f`).
fn texture_dimensions(properties: &ModelProperties) -> (f32, f32) {
    let width = match properties.texture_width {
        None => 64.0,
        Some(value) if value == 0.0 => 64.0,
        Some(value) => value as f32,
    };
    let height = match properties.texture_height {
        None => 64.0,
        Some(value) if value == 0.0 => 64.0,
        Some(value) => value as f32,
    };
    (width, height)
}

/// Port of `GeoCube.createFromPojoCube` (`GeoCube.java:106-544`).
fn build_box_cube(
    cube: &Cube,
    properties: &ModelProperties,
    bone_inflate: Option<f64>,
    bone_mirror: Option<bool>,
) -> Result<GeoCubeIr, String> {
    // `GeoCube(cubeIn.getSize())` dereferences the array, so a null size fails the Java build.
    let raw_size = cube.size.as_deref().ok_or_else(|| "cube.size is null".to_string())?;
    // Negative sizes are detected before any transformation; the origin is shifted to the true minimum
    // corner and the size normalised to `abs` (`GeoCube.java:108-155`).
    let has_neg_size =
        raw_size.len() >= 3 && (raw_size[0] < 0.0 || raw_size[1] < 0.0 || raw_size[2] < 0.0);
    let size = if raw_size.len() >= 3 {
        [raw_size[0] as f32, raw_size[1] as f32, raw_size[2] as f32]
    } else {
        [0.0, 0.0, 0.0]
    };

    let (is_box_uv, faces, box_uv) = match &cube.uv {
        None => (false, None, None),
        Some(UvUnion::BoxUv(values)) => (true, None, Some(values.as_slice())),
        Some(UvUnion::Faces(faces)) => (false, Some(faces), None),
    };

    let mut inflate = match cube.inflate {
        Some(value) => value / 16.0,
        None => bone_inflate.unwrap_or(0.0),
    };
    // The port-specific zero-thickness inflation that keeps flat decals from z-fighting (`GeoCube.java:125-127`).
    if inflate == 0.0 && (size[0] == 0.0 || size[1] == 0.0 || size[2] == 0.0) {
        inflate = 0.001;
    }

    let (texture_width, texture_height) = texture_dimensions(properties);

    let raw_origin = cube.origin.as_deref();
    let mut ox = component_or(raw_origin, 0, 0.0);
    let mut oy = component_or(raw_origin, 1, 0.0);
    let mut oz = component_or(raw_origin, 2, 0.0);
    let sx = raw_size.first().map(|value| value.abs()).unwrap_or(1.0);
    let sy = raw_size.get(1).map(|value| value.abs()).unwrap_or(1.0);
    let sz = raw_size.get(2).map(|value| value.abs()).unwrap_or(1.0);
    if raw_size.first().is_some_and(|value| *value < 0.0) {
        ox += raw_size[0];
    }
    if raw_size.get(1).is_some_and(|value| *value < 0.0) {
        oy += raw_size[1];
    }
    if raw_size.get(2).is_some_and(|value| *value < 0.0) {
        oz += raw_size[2];
    }

    // `origin = (-(ox + sx) / 16, oy / 16, oz / 16)` and `size *= 0.0625` are double arithmetic; the
    // corner positions are cast to float only when the `GeoVertex` is created (`GeoCube.java:157-237`).
    let origin_x = -(ox + sx) / 16.0;
    let origin_y = oy / 16.0;
    let origin_z = oz / 16.0;
    let size_x = sx * 0.0625;
    let size_y = sy * 0.0625;
    let size_z = sz * 0.0625;
    let inflate_f = inflate;
    let corner = |x: f64, y: f64, z: f64| [x as f32, y as f32, z as f32];
    let positions: [[f32; 3]; CUBE_VERTEX_COUNT] = [
        corner(origin_x - inflate_f, origin_y - inflate_f, origin_z - inflate_f),
        corner(origin_x - inflate_f, origin_y - inflate_f, origin_z + size_z + inflate_f),
        corner(origin_x - inflate_f, origin_y + size_y + inflate_f, origin_z - inflate_f),
        corner(origin_x - inflate_f, origin_y + size_y + inflate_f, origin_z + size_z + inflate_f),
        corner(origin_x + size_x + inflate_f, origin_y - inflate_f, origin_z - inflate_f),
        corner(origin_x + size_x + inflate_f, origin_y - inflate_f, origin_z + size_z + inflate_f),
        corner(origin_x + size_x + inflate_f, origin_y + size_y + inflate_f, origin_z - inflate_f),
        corner(origin_x + size_x + inflate_f, origin_y + size_y + inflate_f, origin_z + size_z + inflate_f),
    ];

    let raw_rotation = cube.rotation.as_deref().ok_or_else(|| "cube.rotation is null".to_string())?;
    if raw_rotation.len() < 3 {
        return Err("cube.rotation needs three components".to_string());
    }
    let mut rotation = [raw_rotation[0] as f32, raw_rotation[1] as f32, raw_rotation[2] as f32];
    rotation[0] *= -1.0;
    rotation[1] *= -1.0;
    let rotation = [to_radians(rotation[0]), to_radians(rotation[1]), to_radians(rotation[2])];

    let raw_pivot = cube.pivot.as_deref().ok_or_else(|| "cube.pivot is null".to_string())?;
    if raw_pivot.len() < 3 {
        return Err("cube.pivot needs three components".to_string());
    }
    let mut pivot = [raw_pivot[0] as f32, raw_pivot[1] as f32, raw_pivot[2] as f32];
    pivot[0] *= -1.0;

    let mut quads: [Option<GeoQuadIr>; 6] = std::array::from_fn(|_| None);
    if !is_box_uv {
        if let Some(faces) = faces {
            let face_refs: [Option<&FaceUv>; 6] = [
                usable_face(faces.west.as_ref()),
                usable_face(faces.east.as_ref()),
                usable_face(faces.north.as_ref()),
                usable_face(faces.south.as_ref()),
                usable_face(faces.up.as_ref()),
                usable_face(faces.down.as_ref()),
            ];
            // `cubeIn.getMirror() == TRUE || bone mirror == TRUE` selects the mirrored *layout*
            // (vertex order); the cube's own flag is what `GeoQuad` receives as `mirrorIn`.
            let mirrored_layout = cube.mirror == Some(true) || bone_mirror == Some(true);
            let vertex_orders = if mirrored_layout { &FACE_VERTICES_MIRRORED } else { &FACE_VERTICES };
            for slot in 0..6 {
                let Some(face) = face_refs[slot] else {
                    continue;
                };
                let Some(uv) = face.uv.as_deref() else { continue };
                let Some(uv_size) = face.uv_size.as_deref() else { continue };
                quads[slot] = Some(build_quad(
                    vertex_orders[slot],
                    uv,
                    uv_size,
                    slot,
                    cube.mirror,
                    texture_width,
                    texture_height,
                    &positions,
                ));
            }
        }
    } else if let Some(box_uv) = box_uv {
        // `if (uvUnion == null || uvUnion.boxUVCoords == null)` leaves all quads null; otherwise the
        // box uv origin/size is used with the floored cube size (`GeoCube.java:397-531`).
        if box_uv.len() < 2 {
            return Err("box uv needs two components".to_string());
        }
        if raw_size.len() < 3 {
            // `VectorUtils.fromArray(cubeIn.getSize())` validates three components.
            return Err("box uv needs a cube.size with three components".to_string());
        }
        let u = box_uv[0];
        let v = box_uv[1];
        let uv_size = [raw_size[0].floor(), raw_size[1].floor(), raw_size[2].floor()];
        let west_uv = [u + uv_size[2] + uv_size[0], v + uv_size[2]];
        let west_size = [uv_size[2], uv_size[1]];
        let east_uv = [u, v + uv_size[2]];
        let east_size = [uv_size[2], uv_size[1]];
        let north_uv = [u + uv_size[2], v + uv_size[2]];
        let north_size = [uv_size[0], uv_size[1]];
        let south_uv = [u + uv_size[2] + uv_size[0] + uv_size[2], v + uv_size[2]];
        let south_size = [uv_size[0], uv_size[1]];
        let up_uv = [u + uv_size[2], v];
        let up_size = [uv_size[0], uv_size[2]];
        // Upstream's down face keeps the negative v extent in every layout (`GeoCube.java:456-469`).
        let down_uv = [u + uv_size[2] + uv_size[0], v + uv_size[2]];
        let down_size = [uv_size[0], -uv_size[2]];
        let box_uvs: [([f64; 2], [f64; 2]); 6] = [
            (west_uv, west_size),
            (east_uv, east_size),
            (north_uv, north_size),
            (south_uv, south_size),
            (up_uv, up_size),
            (down_uv, down_size),
        ];
        let mirrored_layout = cube.mirror == Some(true) || bone_mirror == Some(true);
        // Box UV: the mirrored layout swaps only west/east (`GeoCube.java:476-531`).
        let vertex_orders = if mirrored_layout { &FACE_VERTICES_BOX_MIRRORED } else { &FACE_VERTICES };
        for (slot, (uv, uv_size)) in box_uvs.iter().enumerate() {
            quads[slot] = Some(build_quad(
                vertex_orders[slot],
                uv,
                uv_size,
                slot,
                cube.mirror,
                texture_width,
                texture_height,
                &positions,
            ));
        }
    }

    Ok(GeoCubeIr {
        size,
        pivot,
        rotation,
        inflate,
        mirror: cube.mirror,
        mesh: false,
        has_neg_size,
        quads: quads.to_vec(),
        positions,
    })
}

fn component_or(values: Option<&[f64]>, index: usize, fallback: f64) -> f64 {
    values.and_then(|values| values.get(index).copied()).unwrap_or(fallback)
}

/// `GeoCube.usableFace` (`GeoCube.java:550-567`): a face without usable uv/uv_size data builds no quad.
fn usable_face(face: Option<&FaceUv>) -> Option<&FaceUv> {
    let face = face?;
    let uv = face.uv.as_ref()?;
    let uv_size = face.uv_size.as_ref()?;
    if uv.len() < 2 || uv_size.len() < 2 {
        return None;
    }
    Some(face)
}

/// `GeoQuad`'s uv computation (`GeoQuad.java:32-75`) for one already-selected face + vertex order.
#[allow(clippy::too_many_arguments)]
fn build_quad(
    vertex_order: [u8; 4],
    uv_coords: &[f64],
    uv_size: &[f64],
    slot: usize,
    mirror_in: Option<bool>,
    texture_width: f32,
    texture_height: f32,
    positions: &[[f32; 3]; CUBE_VERTEX_COUNT],
) -> GeoQuadIr {
    // The constructor takes floats and casts the declared uv values first.
    let u1 = uv_coords[0] as f32;
    let v1 = uv_coords[1] as f32;
    let u_size = uv_size[0] as f32;
    let v_size = uv_size[1] as f32;

    let u2 = u1 + u_size;
    let v2 = v1 + v_size;
    let mut u_end = u2 / texture_width;
    let v_end = v2 / texture_height;
    let mut u_start = u1 / texture_width;
    let v_start = v1 / texture_height;

    let [mut nx, ny, nz] = FACE_NORMALS[slot];
    let mirror = mirror_in == Some(true);
    if !mirror {
        std::mem::swap(&mut u_end, &mut u_start);
    } else {
        nx = -nx;
    }
    // `FaceUv.getRotationEnum()` is always `NONE`, so `rotateUvs` returns the unrotated corner order:
    // (u1,v1), (u2,v1), (u2,v2), (u1,v2) (`FaceUv.java:126-137`).
    let uvs = [[u_start, v_start], [u_end, v_start], [u_end, v_end], [u_start, v_end]];

    let mut vertices = [GeoVertexIr::default(); 4];
    for (index, position_index) in vertex_order.iter().enumerate() {
        vertices[index] = GeoVertexIr {
            position: positions[*position_index as usize],
            u: uvs[index][0],
            v: uvs[index][1],
        };
    }
    GeoQuadIr {
        direction: FACE_ORDINALS[slot],
        normal: [nx, ny, nz],
        normal_vector: [nx as f32, ny as f32, nz as f32],
        uv_rotation: 0,
        vertices,
        vertex_indices: vertex_order,
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// GeoCube.createFromPolyMesh (GeoCube.java:37-104)
// ---------------------------------------------------------------------------------------------------------------------

/// Port of `GeoCube.createFromPolyMesh`. `has_neg_size` is the `__ysm_neg_mesh` flag `GeoBuilder` sets
/// after construction.
fn build_poly_mesh_cube(
    mesh: &PolyMesh,
    properties: &ModelProperties,
    has_neg_size: bool,
) -> Result<GeoCubeIr, String> {
    // `createFromPolyMesh` opens with a blank cube whose `GeoQuad[]` is the class default six-null array
    // and returns it unchanged for every unusable mesh (`GeoCube.java:37-50`).
    let mut quads: Vec<Option<GeoQuadIr>> = std::iter::repeat_with(|| None).take(6).collect();
    if let (Some(positions), Some(uvs)) = (&mesh.positions, &mesh.uvs) {
        // Only the flat `quad_list` form is built; the indexed form is ignored by the engine too.
        let quad_list = matches!(&mesh.polys, Some(PolysUnion::Kind(kind)) if kind == "quad_list");
        if quad_list {
            let normals = mesh.normals.as_deref();
            let (texture_width, texture_height) = texture_dimensions(properties);
            let normalized_uvs = mesh.normalized_uvs.unwrap_or(false);
            let quad_count = (positions.len() / 12).min(uvs.len() / 8);
            // `BakedQuad.vertex_indices` is `[u8;4]`, so the render-ready copy addresses at most 256 mesh
            // vertices; a mesh cube above that falls back to the Java path (the blob/materialisation path
            // itself has no such limit, but this bake produces both in one pass).
            if quad_count * 4 > u8::MAX as usize + 1 {
                return Err("poly_mesh cube has more than 64 quads".to_string());
            }
            let mut built = Vec::with_capacity(quad_count);
            for quad_index in 0..quad_count {
                let mut vertices = [GeoVertexIr::default(); 4];
                for vertex_index in 0..4 {
                    let position_index = quad_index * 12 + vertex_index * 3;
                    let uv_index = quad_index * 8 + vertex_index * 2;
                    let mut u = uvs[uv_index] as f32;
                    let mut v = uvs[uv_index + 1] as f32;
                    if !normalized_uvs {
                        u /= texture_width;
                        v /= texture_height;
                    }
                    vertices[vertex_index] = GeoVertexIr {
                        position: [
                            positions[position_index] as f32,
                            positions[position_index + 1] as f32,
                            positions[position_index + 2] as f32,
                        ],
                        u,
                        v,
                    };
                }
                let normal = poly_mesh_normal(normals, quad_index);
                built.push(GeoQuadIr {
                    // The poly-mesh `GeoQuad` constructor leaves `direction` null and `uvRotation` 0.
                    direction: -1,
                    normal: [java_round(normal[0]), java_round(normal[1]), java_round(normal[2])],
                    normal_vector: normal,
                    uv_rotation: 0,
                    vertices,
                    vertex_indices: [0, 1, 2, 3],
                });
            }
            quads = built.into_iter().map(Some).collect();
        }
    }
    Ok(GeoCubeIr {
        size: [0.0, 0.0, 0.0],
        pivot: [0.0, 0.0, 0.0],
        rotation: [0.0, 0.0, 0.0],
        inflate: 0.0,
        mirror: Some(false),
        mesh: true,
        has_neg_size,
        quads,
        positions: [[0.0; 3]; CUBE_VERTEX_COUNT],
    })
}

/// `GeoCube.getPolyMeshNormal` (`GeoCube.java:93-104`).
fn poly_mesh_normal(normals: Option<&[f64]>, quad_index: usize) -> [f32; 3] {
    let Some(normals) = normals else {
        return [0.0, 1.0, 0.0];
    };
    if normals.len() < 3 {
        return [0.0, 1.0, 0.0];
    }
    let mut index = 0;
    if normals.len() >= (quad_index + 1) * 12 {
        index = quad_index * 12;
    } else if normals.len() >= (quad_index + 1) * 3 {
        index = quad_index * 3;
    }
    [normals[index] as f32, normals[index + 1] as f32, normals[index + 2] as f32]
}

// ---------------------------------------------------------------------------------------------------------------------
// Render-ready conversion
// ---------------------------------------------------------------------------------------------------------------------

fn into_baked(model: &GeoModelIr, blob: Vec<u8>) -> BakedGeometry {
    let total_cubes: usize = model.bones.iter().map(|bone| bone.cubes.len()).sum();
    let mut bones = Vec::with_capacity(model.bones.len());
    let mut cubes = Vec::with_capacity(total_cubes);
    let mut flags = 0u32;
    let mut quad_count = 0u32;
    for bone in model.bones.iter() {
        let cube_start = cubes.len() as u32;
        for cube in &bone.cubes {
            let baked = baked_cube(cube);
            if baked.flat {
                flags |= FLAG_FLAT_CUBES;
            }
            if baked.negative {
                flags |= FLAG_NEGATIVE_CUBES;
            }
            if baked.mesh {
                flags |= FLAG_MESH_CUBES;
            }
            quad_count += baked.quads.len() as u32;
            cubes.push(baked);
        }
        bones.push(BakedBone {
            name: bone.name.clone(),
            parent: bone.parent,
            cube_start,
            cube_count: cubes.len() as u32 - cube_start,
            // `GeoBone.rotationPoint*` is in model units; `MatrixStack` divides by 16, so the render-ready
            // copy keeps it in the block units the emitter's matrix expects.
            pivot: [bone.rotation_point[0] / 16.0, bone.rotation_point[1] / 16.0, bone.rotation_point[2] / 16.0],
            rotation: bone.rotation,
        });
    }
    BakedGeometry {
        bone_count: bones.len() as u32,
        cube_count: cubes.len() as u32,
        quad_count,
        bones,
        cubes,
        blob,
        flags,
    }
}

fn baked_cube(cube: &GeoCubeIr) -> BakedCube {
    let rotation = cube.rotation;
    let rotated = !(rotation[0] == 0.0 && rotation[1] == 0.0 && rotation[2] == 0.0);
    let mut baked = BakedCube {
        size: cube.size,
        mesh: cube.mesh,
        negative: cube.has_neg_size,
        pivot: [cube.pivot[0] / 16.0, cube.pivot[1] / 16.0, cube.pivot[2] / 16.0],
        rotation,
        rotated,
        // `IGeoRenderer.renderCubeGroup`'s flat test is `!cube.mesh && one axis of GeoCube.size is 0`.
        flat: !cube.mesh && (cube.size[0] == 0.0 || cube.size[1] == 0.0 || cube.size[2] == 0.0),
        quads: Vec::with_capacity(cube.quads.len()),
        ..BakedCube::default()
    };
    if cube.mesh {
        let mut mesh_positions = Vec::with_capacity(cube.quads.len() * 4);
        for quad in cube.quads.iter().flatten() {
            let base = mesh_positions.len() as u32;
            mesh_positions.extend(quad.vertices.iter().map(|vertex| vertex.position));
            baked.quads.push(BakedQuad {
                vertex_indices: [base as u8, (base + 1) as u8, (base + 2) as u8, (base + 3) as u8],
                uvs: [
                    [quad.vertices[0].u, quad.vertices[0].v],
                    [quad.vertices[1].u, quad.vertices[1].v],
                    [quad.vertices[2].u, quad.vertices[2].v],
                    [quad.vertices[3].u, quad.vertices[3].v],
                ],
                normal: quad.normal_vector,
            });
        }
        baked.mesh_positions = mesh_positions;
    } else {
        baked.positions = cube.positions;
        for quad in cube.quads.iter().flatten() {
            baked.quads.push(BakedQuad {
                vertex_indices: quad.vertex_indices,
                uvs: [
                    [quad.vertices[0].u, quad.vertices[0].v],
                    [quad.vertices[1].u, quad.vertices[1].v],
                    [quad.vertices[2].u, quad.vertices[2].v],
                    [quad.vertices[3].u, quad.vertices[3].v],
                ],
                normal: quad.normal_vector,
            });
        }
    }
    baked
}

#[cfg(test)]
mod tests {
    use std::path::{Path, PathBuf};
    use std::time::Instant;

    use super::{bake_json, construct_model, into_baked, parse_hierarchy, strip_json_comments};
    use crate::geometry::schema::RawGeoModel;

    #[test]
    fn comments_are_stripped_outside_strings_only() {
        let input = br#"{"a": "http://x/*y*/", // trailing
            "b": /* inline */ 2}"#;
        let cleaned = String::from_utf8(strip_json_comments(input).into_owned()).expect("utf8");
        assert_eq!(cleaned, "{\"a\": \"http://x/*y*/\", \n            \"b\":   2}");
        // A block comment between two tokens must not fuse them into a different token.
        assert_eq!(
            String::from_utf8(strip_json_comments(b"1/*c*/2").into_owned()).unwrap(),
            "1 2"
        );
        // A comment-free document is returned borrowed, i.e. not copied.
        assert!(matches!(strip_json_comments(br#"{"a":1}"#), std::borrow::Cow::Borrowed(_)));
    }

    #[test]
    fn malformed_and_unsupported_documents_are_declined_not_panicked() {
        assert!(bake_json(b"not json", 0).is_err());
        assert!(bake_json(b"{}", 0).is_err());
        assert!(bake_json(br#"{"format_version":"9.9.9","minecraft:geometry":[{"bones":[]}]}"#, 0).is_err());
    }

    #[test]
    fn box_uv_mirror_layout_only_swaps_west_and_east() {
        // Regression for the geometry parity run: the box-UV mirrored branch of `createFromPojoCube`
        // rebuilds only west/east with the swapped corners (`GeoCube.java:476-531`); up/down keep the
        // unmirrored vertex order. The per-face-UV mirrored branch does swap up/down (`:329-396`), and
        // applying that rule to box-UV cubes made the UP quad of every mirrored box-UV cube wrong.
        let document = br#"{
            "format_version": "1.12.0",
            "minecraft:geometry": [{
                "description": {"texture_width": 64, "texture_height": 64},
                "bones": [{"name": "arm", "cubes": [{
                    "origin": [0, 0, 0], "size": [1, 1, 1], "uv": [0, 0], "mirror": true
                }]}]
            }]
        }"#;
        let baked = bake_json(document, 0).expect("mirrored box-uv cube bakes");
        let cube = &baked.cubes[0];
        assert_eq!(cube.quads.len(), 6);
        // Slot order: west, east, north, south, up, down; vertex indices are the P1..P8 corner indices.
        assert_eq!(cube.quads[0].vertex_indices, [6, 7, 5, 4], "west swaps under the mirrored layout");
        assert_eq!(cube.quads[1].vertex_indices, [3, 2, 0, 1], "east swaps under the mirrored layout");
        assert_eq!(cube.quads[4].vertex_indices, [3, 7, 6, 2], "up keeps P4,P8,P7,P3");
        assert_eq!(cube.quads[5].vertex_indices, [0, 4, 5, 1], "down keeps P1,P5,P6,P2");
    }

    #[test]
    fn per_face_uv_mirror_layout_swaps_up_and_down() {
        let document = br#"{
            "format_version": "1.12.0",
            "minecraft:geometry": [{
                "description": {"texture_width": 64, "texture_height": 64},
                "bones": [{"name": "arm", "cubes": [{
                    "origin": [0, 0, 0], "size": [1, 1, 1], "mirror": true,
                    "uv": {
                        "west": {"uv": [0, 0], "uv_size": [1, 1]},
                        "east": {"uv": [0, 0], "uv_size": [1, 1]},
                        "north": {"uv": [0, 0], "uv_size": [1, 1]},
                        "south": {"uv": [0, 0], "uv_size": [1, 1]},
                        "up": {"uv": [0, 0], "uv_size": [1, 1]},
                        "down": {"uv": [0, 0], "uv_size": [1, 1]}
                    }
                }]}]
            }]
        }"#;
        let baked = bake_json(document, 0).expect("mirrored per-face cube bakes");
        let cube = &baked.cubes[0];
        assert_eq!(cube.quads[4].vertex_indices, [0, 4, 5, 1], "up swaps on the per-face channel");
        assert_eq!(cube.quads[5].vertex_indices, [3, 7, 6, 2], "down swaps on the per-face channel");
    }

    #[test]
    #[ignore = "measurement helper: run with --ignored --nocapture"]
    fn corpus_bake_phase_timing() {
        fn collect(directory: &Path, files: &mut Vec<PathBuf>) {
            let Ok(entries) = std::fs::read_dir(directory) else {
                return;
            };
            for entry in entries.flatten() {
                let path = entry.path();
                if path.is_dir() {
                    collect(&path, files);
                } else if path.extension().is_some_and(|extension| extension == "json") {
                    files.push(path);
                }
            }
        }
        const ROOT: &str = concat!(env!("CARGO_MANIFEST_DIR"), "/../../src/main/resources/assets/ysmu/builtin");
        let mut files = Vec::new();
        collect(Path::new(ROOT), &mut files);
        files.sort();
        struct Row {
            name: String,
            bytes: usize,
            strip: f64,
            parse: f64,
            tree: f64,
            construct: f64,
            blob: f64,
            baked: f64,
            total: f64,
            blob_bytes: usize,
        }
        let mut rows = Vec::new();
        for path in files {
            let bytes = std::fs::read(&path).expect("read corpus file");
            if !bytes.windows(18).any(|window| window == b"minecraft:geometry") {
                continue;
            }
            let start = Instant::now();
            let cleaned = strip_json_comments(&bytes);
            let strip = start.elapsed().as_secs_f64() * 1000.0;
            let start = Instant::now();
            let raw: RawGeoModel = serde_json::from_slice(cleaned.as_ref()).expect("corpus JSON parses");
            let parse = start.elapsed().as_secs_f64() * 1000.0;
            let geometry = raw.minecraft_geometry.as_ref().and_then(|list| list.first()).expect("geometry");
            let bones = geometry.bones.as_ref().expect("bones");
            let properties = geometry.description.as_ref().expect("description");
            let start = Instant::now();
            let tree = parse_hierarchy(bones).expect("hierarchy");
            let tree_ms = start.elapsed().as_secs_f64() * 1000.0;
            let start = Instant::now();
            let model = construct_model(&tree, bones, properties).expect("model");
            let construct = start.elapsed().as_secs_f64() * 1000.0;
            let start = Instant::now();
            let blob = crate::geometry::blob::write_blob(&model).expect("blob");
            let blob_ms = start.elapsed().as_secs_f64() * 1000.0;
            let blob_bytes = blob.len();
            let start = Instant::now();
            let baked = into_baked(&model, blob);
            let baked_ms = start.elapsed().as_secs_f64() * 1000.0;
            drop(baked);
            rows.push(Row {
                name: path.strip_prefix(ROOT).unwrap_or(&path).display().to_string(),
                bytes: bytes.len(),
                strip,
                parse,
                tree: tree_ms,
                construct,
                blob: blob_ms,
                baked: baked_ms,
                total: strip + parse + tree_ms + construct + blob_ms + baked_ms,
                blob_bytes,
            });
        }
        rows.sort_by(|left, right| right.total.partial_cmp(&left.total).unwrap());
        println!(
            "{:<44} {:>9} {:>7} {:>7} {:>7} {:>7} {:>7} {:>7} {:>10}",
            "model", "json B", "strip", "parse", "tree", "model", "blob", "baked", "blob B"
        );
        for row in rows.iter().take(8) {
            println!(
                "{:<44} {:>9} {:>7.3} {:>7.3} {:>7.3} {:>7.3} {:>7.3} {:>7.3} {:>10}",
                row.name,
                row.bytes,
                row.strip,
                row.parse,
                row.tree,
                row.construct,
                row.blob,
                row.baked,
                row.blob_bytes
            );
        }
        let count = rows.len() as f64;
        let sum = |selector: fn(&Row) -> f64| rows.iter().map(selector).sum::<f64>();
        let total = sum(|row| row.total);
        println!(
            "corpus: {} files, json {:.2} MB, total {:.1} ms ({:.3} ms/model); strip {:.1} parse {:.1} \
             tree {:.1} model {:.1} blob {:.1} baked {:.1} ms",
            rows.len(),
            rows.iter().map(|row| row.bytes).sum::<usize>() as f64 / (1024.0 * 1024.0),
            total,
            total / count,
            sum(|row| row.strip),
            sum(|row| row.parse),
            sum(|row| row.tree),
            sum(|row| row.construct),
            sum(|row| row.blob),
            sum(|row| row.baked)
        );
    }

    #[test]
    #[ignore = "measurement helper: run with --ignored --nocapture"]
    fn serde_parse_breakdown() {
        use serde::de::IgnoredAny;
        use serde::Deserialize;

        #[derive(Deserialize)]
        struct Minimal {
            #[serde(rename = "minecraft:geometry")]
            #[allow(dead_code)]
            geometry: Option<IgnoredAny>,
        }

        const BIG: &str = concat!(
            env!("CARGO_MANIFEST_DIR"),
            "/../../src/main/resources/assets/ysmu/builtin/wine_fox/08_sta/models/main.json"
        );
        let bytes = std::fs::read(BIG).expect("read big model");
        println!("big model: {} bytes", bytes.len());
        for round in 0..5 {
            let start = Instant::now();
            let raw: RawGeoModel = serde_json::from_slice(&bytes).expect("typed parse");
            let typed = start.elapsed().as_secs_f64() * 1000.0;
            drop(raw);
            let start = Instant::now();
            let _minimal: Minimal = serde_json::from_slice(&bytes).expect("minimal parse");
            let minimal = start.elapsed().as_secs_f64() * 1000.0;
            let start = Instant::now();
            let _dom: serde_json::Value = serde_json::from_slice(&bytes).expect("dom parse");
            let dom = start.elapsed().as_secs_f64() * 1000.0;
            println!("round {round}: typed {typed:.3} ms, ignored-geometry {minimal:.3} ms, dom {dom:.3} ms");
        }
    }

    #[test]
    fn a_minimal_document_bakes() {
        let document = br#"{
            "format_version": "1.12.0",
            "minecraft:geometry": [{
                "description": {"texture_width": 64, "texture_height": 64},
                "bones": [{
                    "name": "body",
                    "pivot": [0, 0, 0],
                    "cubes": [{"origin": [-4, 0, -2], "size": [8, 12, 4], "uv": [0, 0]}]
                }]
            }]
        }"#;
        let baked = bake_json(document, 0).expect("minimal model bakes");
        assert_eq!(baked.bone_count, 1);
        assert_eq!(baked.cube_count, 1);
        assert_eq!(baked.quad_count, 6);
        assert_eq!(baked.bones[0].cube_start, 0);
        assert_eq!(baked.bones[0].cube_count, 1);
        assert!(!baked.blob.is_empty());
        assert_eq!(baked.cubes[0].quads.len(), 6);
        // 8x12x4 model units at x = -4: `origin.x = -(-4 + 8) / 16 = -0.25`, and P5..P8 add the x size
        // (8 / 16 = 0.5), i.e. 0.25. Inflate is zero, so P1 sits exactly on the origin.
        assert_eq!(baked.cubes[0].positions[4][0], 0.25);
        assert_eq!(baked.cubes[0].positions[0][0], -0.25);
    }
}

//! Baked geometry data shared by the bake (M3) and the per-frame emitter (M4).
//!
//! One [`BakedGeometry`] holds three things:
//!
//! 1. `bones` / `cubes` — the render-ready copy: bones are numbered in depth-first bake order (the model
//!    author's hierarchy order, which is also the order the Java renderer walks them), and every cube
//!    carries its corner positions, quads (uvs, normal, vertex indices into the eight corners) and the
//!    flags the emitter needs (`flat`, `negative`) plus the cube-local rotation that
//!    `MatrixStack.beginCube` applies;
//! 2. `blob` — the little-endian materialisation blob the Java side reads to recreate the engine's
//!    object graph (`GeoModel`/`GeoBone`/`GeoCube`/`GeoQuad`/`GeoVertex`). Its layout is defined by the
//!    bake that writes it and by `NativeGeometryReader` that reads it; both live in M3;
//! 3. `info`/flags — counts and the model-level predicates both modules budget from.
//!
//! The registry is a process-wide handle table. A handle is opaque to Java; it is a `u64` created by
//! [`Registry::insert`] and only valid until [`Registry::remove`]. Java frees handles from the geometry
//! release path, so a leaked handle costs native memory until the next connection clear.

use std::collections::HashMap;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Mutex, OnceLock};

/// A cube has at most eight corner positions; mesh (poly) cubes still index into this array.
pub const CUBE_VERTEX_COUNT: usize = 8;

/// The model contains at least one zero-thickness (decal) cube: `IGeoRenderer.renderCubeGroup` switches
/// polygon offset for those, which changes GL state and therefore draw-group boundaries.
pub const FLAG_FLAT_CUBES: u32 = 1 << 0;
/// The model contains at least one negative-size outline cube: `renderBoneCubes` draws those first with
/// `GL_CULL_FACE`/`GL_FRONT`, which also splits the draw.
pub const FLAG_NEGATIVE_CUBES: u32 = 1 << 1;
/// The model contains at least one mesh cube (free-form poly geometry, no six-face assumption).
pub const FLAG_MESH_CUBES: u32 = 1 << 2;

/// One quad of a baked cube, in the same vertex order the engine emits (the engine's `GeoQuad`).
#[derive(Clone, Debug, Default)]
pub struct BakedQuad {
    /// Indices into [`BakedCube::positions`], in emission order.
    pub vertex_indices: [u8; 4],
    /// Per-vertex texture coordinates, matching `vertex_indices`.
    pub uvs: [[f32; 2]; 4],
    /// The quad's normal before the bone's normal matrix is applied.
    pub normal: [f32; 3],
}

/// One baked cube: eight corner positions in the bone's local space plus its quads.
#[derive(Clone, Debug, Default)]
pub struct BakedCube {
    /// The eight corner positions of a box cube, in the engine's P1..P8 order. All-zero for a mesh
    /// cube: its vertices live in [`Self::mesh_positions`] instead.
    pub positions: [[f32; 3]; CUBE_VERTEX_COUNT],
    pub quads: Vec<BakedQuad>,
    /// Cube-local rotation pivot (`MatrixStack.beginCube`), already divided by 16 into block units.
    pub pivot: [f32; 3],
    /// Cube-local rotation in radians, applied `Rz * Ry * Rx` about [`Self::pivot`].
    pub rotation: [f32; 3],
    /// `true` when [`Self::rotation`] is non-zero and the cube therefore needs its own matrix.
    pub rotated: bool,
    /// Zero-thickness cube: the Java path draws it with polygon offset.
    pub flat: bool,
    /// Negative-size outline cube: the Java path draws it first with front-face culling.
    pub negative: bool,
    /// `GeoCube.size` exactly as the engine stores it: the declared size **in model units** (not
    /// divided by 16; `GeoCube`'s constructor only casts it). The Java renderer reads it for the
    /// per-axis zero tests of the flat-shading normal fix (`IGeoRenderer.renderCube`), and the
    /// material draw session for its own flat test.
    pub size: [f32; 3],
    /// `true` for a free-form `poly_mesh` cube (`GeoCube.mesh`): no six-face assumption, arbitrary
    /// quad count, vertices in [`Self::mesh_positions`].
    pub mesh: bool,
    /// Vertices of a mesh cube, four per quad in quad order (quad `q` uses `4*q .. 4*q+4`); empty for
    /// a box cube. [`BakedQuad::vertex_indices`] index into this vector for mesh cubes.
    pub mesh_positions: Vec<[f32; 3]>,
}

/// One bone in depth-first bake order.
#[derive(Clone, Debug, Default)]
pub struct BakedBone {
    pub name: String,
    /// Index of the parent bone, or `-1` for a root bone.
    pub parent: i32,
    /// Range of [`BakedGeometry::cubes`] this bone owns.
    pub cube_start: u32,
    pub cube_count: u32,
    /// The bone's rotation point in block units (`GeoBone#rotationPoint* / 16`), kept for debugging and
    /// for the materialisation blob's consumers.
    pub pivot: [f32; 3],
    pub rotation: [f32; 3],
}

/// One baked model.
#[derive(Clone, Debug, Default)]
pub struct BakedGeometry {
    pub bones: Vec<BakedBone>,
    pub cubes: Vec<BakedCube>,
    /// Materialisation blob handed to the Java reader (see the module docs).
    pub blob: Vec<u8>,
    /// `FLAG_*` bits describing the whole model.
    pub flags: u32,
    pub bone_count: u32,
    pub cube_count: u32,
    pub quad_count: u32,
}

impl BakedGeometry {
    /// The cubes of one bone, or `None` when `bone_index` is out of range.
    pub fn bone_cubes(&self, bone_index: usize) -> Option<&[BakedCube]> {
        let bone = self.bones.get(bone_index)?;
        let start = bone.cube_start as usize;
        let end = start.checked_add(bone.cube_count as usize)?;
        self.cubes.get(start..end)
    }

    /// Total vertices the model would emit with no culling: quads * 4.
    pub fn vertex_count(&self) -> usize {
        self.cubes.iter().map(|cube| cube.quads.len() * 4).sum()
    }
}

/// Process-wide opaque handle table.
#[derive(Default)]
pub struct Registry {
    next: AtomicU64,
    entries: Mutex<HashMap<u64, std::sync::Arc<BakedGeometry>>>,
}

impl Registry {
    /// Stores one baked model and returns its handle (never zero).
    pub fn insert(&self, geometry: BakedGeometry) -> u64 {
        let handle = self.next.fetch_add(1, Ordering::Relaxed) + 1;
        self.entries
            .lock()
            .expect("geometry registry poisoned")
            .insert(handle, std::sync::Arc::new(geometry));
        handle
    }

    /// Runs `body` against one stored model. The registry lock is held for the duration, which is
    /// deliberate: bake and render are both called from threads that may race with the Java release
    /// path, and a bake/render body is short compared to a frame.
    pub fn with<R>(&self, handle: u64, body: impl FnOnce(&BakedGeometry) -> R) -> Option<R> {
        let entries = self.entries.lock().expect("geometry registry poisoned");
        entries.get(&handle).map(|geometry| body(geometry))
    }

    /// Borrows one stored model without holding the registry lock across the caller's work. Used by the
    /// blob read, which copies the (possibly megabyte-sized) blob into a Java array and must not keep the
    /// lock while doing JNI calls, and available to the per-frame emitter for the same reason.
    pub fn get(&self, handle: u64) -> Option<std::sync::Arc<BakedGeometry>> {
        self.entries
            .lock()
            .expect("geometry registry poisoned")
            .get(&handle)
            .cloned()
    }

    /// Drops one model; returns whether it existed.
    pub fn remove(&self, handle: u64) -> bool {
        self.entries.lock().expect("geometry registry poisoned").remove(&handle).is_some()
    }

    /// Number of live models (used by tests and the debug status line).
    pub fn len(&self) -> usize {
        self.entries.lock().expect("geometry registry poisoned").len()
    }

    pub fn is_empty(&self) -> bool {
        self.len() == 0
    }
}

static REGISTRY: OnceLock<Registry> = OnceLock::new();

/// The process-wide geometry registry.
pub fn registry() -> &'static Registry {
    REGISTRY.get_or_init(Registry::default)
}

#[cfg(test)]
mod tests {
    use super::{registry, BakedBone, BakedCube, BakedGeometry, BakedQuad};

    fn one_cube_model() -> BakedGeometry {
        BakedGeometry {
            bones: vec![BakedBone { name: "body".to_string(), parent: -1, cube_start: 0, cube_count: 1, ..Default::default() }],
            cubes: vec![BakedCube { quads: vec![BakedQuad::default()], ..Default::default() }],
            bone_count: 1,
            cube_count: 1,
            quad_count: 1,
            ..Default::default()
        }
    }

    #[test]
    fn handles_are_unique_and_removed_exactly_once() {
        let model = one_cube_model();
        let first = registry().insert(model.clone());
        let second = registry().insert(model);
        assert_ne!(first, second);
        assert_ne!(first, 0);
        assert_eq!(registry().with(first, |geometry| geometry.vertex_count()), Some(4));
        assert!(registry().remove(first));
        assert!(!registry().remove(first));
        assert_eq!(registry().with(first, |_| ()), None);
        assert!(registry().remove(second));
    }

    #[test]
    fn bone_cube_ranges_are_checked() {
        let geometry = one_cube_model();
        assert_eq!(geometry.bone_cubes(0).map(<[BakedCube]>::len), Some(1));
        assert!(geometry.bone_cubes(1).is_none());
    }
}

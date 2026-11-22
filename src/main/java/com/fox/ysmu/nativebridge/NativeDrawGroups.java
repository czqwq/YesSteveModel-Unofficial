package com.fox.ysmu.nativebridge;

/**
 * Rust per-frame vertex emission (module {@code render}).
 *
 * <p>
 * Replaces the innermost loop of the engine's draw path: {@code IGeoRenderer.renderCube}'s per-vertex
 * transform plus {@code Tessellator.addVertexWithUV}/{@code setNormal}/{@code setColorRGBA_F}. One call
 * writes one <b>draw group</b> — the cubes of one bone under one cube filter, transformed by that bone's
 * final model matrix and coloured with the bone tint — directly into the caller's
 * {@code Tessellator.rawBuffer}, in the exact 8-int record vanilla writes:
 * {@code [0..2]} position float bits, {@code [3..4]} uv float bits, {@code [5]} packed colour
 * ({@code alpha<<24 | blue<<16 | green<<8 | red}), {@code [6]} packed normal (4 signed bytes),
 * {@code [7]} brightness (never written: this engine's model draw does not set brightness).
 *
 * <p>
 * Why this shape: upstream's {@code natives/render/NativeRenderer.java} likewise generates the whole
 * vertex stream in native code and hands it to the host's vertex buffer; the host keeps every GL and
 * material decision. Here the host is the 1.7.10 {@code Tessellator}, so the Java side still decides
 * render type state, material session, polygon offset, cull-face for negative-size cubes and per-bone
 * texture overrides, and simply commits the group by writing {@code rawBufferIndex}/{@code vertexCount}
 * plus the {@code hasTexture}/{@code hasColor}/{@code hasNormals}/{@code isColorDisabled} flags and
 * calling {@code Tessellator.draw()} through the existing {@code TessellatorBufferKeep.draw} seam.
 * The result is therefore compatible with the vanilla tessellator and with Angelica's
 * {@code TessellatorStreamingDrawer} mixin without touching either.
 *
 * <p>
 * <b>Batching.</b> {@code Tessellator.draw()} uploads {@code rawBuffer[0..vertexCount*8)}, i.e. always
 * from buffer offset 0, so a group must be at the start of {@code rawBuffer} when it is drawn. The Java
 * fast path nevertheless wants the same draw count as the Java loops (a whole opaque model is one draw):
 * it emits each bone group with {@link #emitAt}, which appends the group after the groups already pending
 * in the batch, and only draws where the Java path draws (capacity, flat-cube polygon-offset changes, the
 * negative-size pass, texture overrides). One {@code emitAt} per bone keeps the JNI traffic proportional
 * to bones, not to vertices. {@link #emit} (offset 0) is kept as the single-group entry point: it is the
 * documented per-group parity contract and needs no pending batch.
 *
 * <p>
 * <b>Filters.</b> {@code cubeFilter} is evaluated by the native side over the bone's cubes, so the Java
 * caller only passes a filter whose selected cubes all share one flatness; a bone that mixes flat and
 * non-flat cubes keeps the Java loop for that bone (the polygon offset would have to split the batch
 * inside the group).
 */
public final class NativeDrawGroups {

    private static final String MODULE = "render";

    /** Emit all cubes of the bone. */
    public static final int CUBE_FILTER_ALL = 0;
    /** Emit only positive-size cubes (the second pass of {@code IGeoRenderer.renderBoneCubes}). */
    public static final int CUBE_FILTER_POSITIVE = 1;
    /** Emit only negative-size cubes (the cull-front first pass of {@code IGeoRenderer.renderBoneCubes}). */
    public static final int CUBE_FILTER_NEGATIVE = 2;

    private NativeDrawGroups() {}

    /** Whether native emission can be attempted at all. */
    public static boolean isUsable() {
        return NativeLoader.moduleUsable(MODULE);
    }

    /**
     * Writes one draw group's vertices at the start of {@code rawBuffer}: the single-group entry point
     * (offset 0) used by the parity harness. Production batching uses {@link #emitAt} so a whole model can
     * stay one draw; both entries write the identical 8-int records for the same inputs.
     *
     * @param geometry         native geometry handle from {@link NativeGeometry#bake}
     * @param boneIndex        depth-first bone index ({@code GeoBone#nativeBoneIndex})
     * @param modelMatrix      16 floats, the bone's final model matrix in the engine's
     *                         {@code javax.vecmath.Matrix4f} field order (m00..m33; translation at
     *                         indices 3/7/11)
     * @param normalMatrix     9 floats, the bone's final normal matrix ({@code Matrix3f}, m00..m22)
     * @param colorArgb        the per-bone tint in Java ARGB order
     *                         ({@code alpha<<24 | red<<16 | green<<8 | blue}), already quantised by the
     *                         caller with {@code Tessellator.setColorRGBA_F}'s
     *                         {@code (int)(value * 255.0F)} truncation and 0..255 clamp
     * @param cubeFilter       {@link #CUBE_FILTER_ALL}, {@link #CUBE_FILTER_POSITIVE} or
     *                         {@link #CUBE_FILTER_NEGATIVE}
     * @param rawBuffer        the tessellator's raw int buffer; the group is written from index 0
     * @return the number of vertices written, or {@code -1} when the group does not fit or the native
     *         module declines (the caller then runs the Java loop for that group)
     */
    public static int emit(long geometry, int boneIndex, float[] modelMatrix, float[] normalMatrix, int colorArgb,
        int cubeFilter, int[] rawBuffer) {
        return emitAt(geometry, boneIndex, modelMatrix, normalMatrix, colorArgb, cubeFilter, rawBuffer, 0);
    }

    /**
     * Writes one draw group's vertices at {@code rawBuffer[intOffset]}, so a bone group can be appended
     * after the groups already pending in the same tessellator batch (the production fast path).
     *
     * @param intOffset index of the first destination int; must be a multiple of 8 for a sane vertex
     *                  stream, and the caller has already committed everything before it
     * @return the number of vertices written, or {@code -1} when the remaining capacity is too small or
     *         the native module declines
     */
    public static int emitAt(long geometry, int boneIndex, float[] modelMatrix, float[] normalMatrix, int colorArgb,
        int cubeFilter, int[] rawBuffer, int intOffset) {
        if (geometry == 0L || boneIndex < 0
            || modelMatrix == null
            || modelMatrix.length < 16
            || normalMatrix == null
            || normalMatrix.length < 9
            || rawBuffer == null
            || rawBuffer.length < 8
            || intOffset < 0
            || intOffset > rawBuffer.length
            || !NativeLoader.moduleUsable(MODULE)) {
            return -1;
        }
        try {
            return emitAtNative(geometry, boneIndex, modelMatrix, normalMatrix, colorArgb, cubeFilter, rawBuffer,
                intOffset);
        } catch (Throwable failure) {
            NativeLoader.onModuleFailure(MODULE, failure);
            return -1;
        }
    }

    /**
     * Single-group JNI entry (writes at {@code rawBuffer[0]}); kept as the documented per-group parity
     * contract. Implemented by the M4 milestone of {@code .agent/phase78-rust-native-modules.md}.
     */
    private static native int emitNative(long geometry, int boneIndex, float[] modelMatrix16, float[] normalMatrix9,
        int colorArgb, int cubeFilter, int[] rawBuffer);

    /**
     * Production append JNI entry: same records as {@link #emitNative} but written at {@code intOffset},
     * which is what keeps a whole model's vertices in one pending tessellator batch (and therefore one
     * {@code Tessellator.draw()} where the Java path has one). Implemented by the M4 milestone.
     */
    private static native int emitAtNative(long geometry, int boneIndex, float[] modelMatrix16, float[] normalMatrix9,
        int colorArgb, int cubeFilter, int[] rawBuffer, int intOffset);
}

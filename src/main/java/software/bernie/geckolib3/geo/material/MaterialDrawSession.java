package software.bernie.geckolib3.geo.material;

import java.nio.FloatBuffer;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import javax.vecmath.Matrix3f;
import javax.vecmath.Matrix4f;

import net.geckominecraft.client.renderer.GlStateManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.texture.ITextureObject;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import software.bernie.geckolib3.geo.IGeoRenderer;
import software.bernie.geckolib3.geo.YsmRenderType;
import software.bernie.geckolib3.geo.render.built.GeoCube;

/**
 * One uninterrupted model draw segment. Never sorts across layers, callbacks or nested render invocations.
 *
 * <p><b>When this runs at all.</b> The per-face work below is the Java form of the native renderer's baked
 * partitions ({@code render_state_impl.inc}, {@code baked_model.cc:685-734}): it classifies each face by the
 * texels its UV region samples, culls back-facing faces in the cutout partitions and sorts the translucent ones
 * far-to-near. Upstream pays for that inside the native renderer, where the bake already partitioned the geometry,
 * so a whole model is one dense pass. This port pays for it in Java, per face and per frame - which is only worth
 * it when the result actually differs from plain fixed-function drawing:
 * <ul>
 * <li><b>translucent</b> vertices: blending plus back-to-front order is required, or overlapping translucent faces
 * stack in the wrong order. A translucent {@link YsmRenderType} is the caller's statement that the model has
 * them (upstream's {@code getRenderType} asks {@code hasTranslucentVertices()} for exactly this), so the segment
 * must classify and sort.</li>
 * <li><b>forceCulling</b> ({@code all_cutout}): the pack asked for back-face culling on every face. The plain
 * path draws the cutout render type with GL culling off (upstream's {@code entityCutoutNoCull}), so the CPU
 * partition is the only thing that can honour the flag.</li>
 * </ul>
 * Every other model - the common case, a fully opaque texture on the cutout render type - is drawn by the plain
 * vertex path instead. That is not a fidelity loss: for a closed opaque cube back-face culling and double-sided
 * drawing produce the same pixels (the depth test hides the far side), and the partition that is genuinely
 * double-sided (irregular geometry the bake classifies as {@code cutout_no_culling}) was already the render type's
 * own behaviour. The plain path is the behaviour every model had before this class existed.
 *
 * <p><b>Allocation discipline.</b> The segment is polled from a small pool and its per-face storage is reused,
 * because this runs once per model per frame. The previous revision allocated a {@link Matrix4f}, a
 * {@code float[4]} coefficient, one {@link Face} and one {@code float[12]} per cube of every drawn model, plus a
 * {@code glIsEnabled(GL_LIGHTING)} call per face. See .agent/perf-material-draw.md.
 */
public final class MaterialDrawSession {

    /** Maximum number of nested open segments kept for reuse; nesting is at most a preview inside a draw. */
    private static final int MAX_POOLED = 4;
    private static final ArrayDeque<MaterialDrawSession> POOL = new ArrayDeque<>();

    /** Reused GL matrix readback buffer; the render thread is the only caller. */
    private static final ThreadLocal<FloatBuffer> MATRIX_BUFFER = ThreadLocal
        .withInitial(() -> BufferUtils.createFloatBuffer(16));

    /** Translucent faces are drawn far-to-near, stable on insertion order, exactly like {@link MaterialSort}. */
    private static final Comparator<Face> TRANSLUCENT_ORDER = (a, b) -> MaterialSort.compare(a.depth, a.index, b.depth,
        b.index);

    private static MaterialDrawSession active;

    private MaterialDrawSession previous;

    /**
     * Whether this segment classifies faces at all; see the class javadoc. Set from the render type the caller chose,
     * which is the same input upstream's native partitioner works from.
     */
    private boolean enabled;
    private boolean forceCulling;
    private YsmRenderType fallback;

    private final Matrix4f projection = new Matrix4f();
    private final Matrix4f modelview = new Matrix4f();
    private final Matrix4f outerClip = new Matrix4f();
    private final Matrix4f clip = new Matrix4f();
    private final float[] coefficient = new float[4];
    private boolean outerMirrored;
    private int baseBinding = -1;
    private ITextureObject source;
    private int texture;
    /** Current fixed-function lighting state, maintained by the caller instead of a {@code glIsEnabled} per face. */
    private boolean lit = true;

    /** Reused per-face storage. Indices {@code [0, opaqueCount)} and {@code [0, translucentCount)} are live. */
    private Face[] opaque = new Face[64];
    private Face[] translucent = new Face[32];
    private int opaqueCount;
    private int translucentCount;
    /** Insertion counter; the stable tie-break for translucent depth sorting. */
    private int collected;

    private MaterialDrawSession() {}

    public static MaterialDrawSession begin(boolean forceCulling, YsmRenderType fallback) {
        if (active != null) active.drain(Tessellator.instance);
        MaterialDrawSession session = POOL.poll();
        if (session == null) {
            session = new MaterialDrawSession();
        }
        session.open(forceCulling, fallback);
        active = session;
        return session;
    }

    public static MaterialDrawSession active() {
        return active;
    }

    private void open(boolean forceCulling, YsmRenderType fallback) {
        this.previous = active;
        this.forceCulling = forceCulling;
        this.fallback = fallback;
        this.opaqueCount = 0;
        this.translucentCount = 0;
        this.collected = 0;
        this.source = null;
        this.texture = 0;
        this.baseBinding = -1;
        // Upstream's bake splits geometry into four partitions and its renderer consumes the partition tags for
        // every model. For the two opaque partitions this port's plain path is equivalent (see the class javadoc),
        // so only the two cases whose result the fixed-function state cannot express classify here.
        this.enabled = forceCulling || (fallback != null && fallback.isBlend());
        if (!enabled) {
            // Nothing below is needed: skip two glGetFloat, two glGetInteger and a texture lookup per model per
            // frame on the common path.
            return;
        }
        this.baseBinding = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        readMatrix(GL11.GL_PROJECTION_MATRIX, projection);
        readMatrix(GL11.GL_MODELVIEW_MATRIX, modelview);
        outerClip.mul(projection, modelview);
        outerMirrored = MaterialFacing.mirrored(modelview);
        this.lit = GL11.glIsEnabled(GL11.GL_LIGHTING);
        if (fallback != null) {
            setTexture(fallback.getTexture());
        }
    }

    /** Reads one 4x4 GL matrix into {@code target} in row-major order. */
    private static void readMatrix(int name, Matrix4f target) {
        FloatBuffer buffer = MATRIX_BUFFER.get();
        buffer.clear();
        GL11.glGetFloat(name, buffer);
        for (int row = 0; row < 4; row++) {
            for (int col = 0; col < 4; col++) {
                target.setElement(row, col, buffer.get(col * 4 + row));
            }
        }
    }

    public void setTexture(ResourceLocation id) {
        if (!enabled) {
            return;
        }
        source = id == null ? null
            : Minecraft.getMinecraft()
                .getTextureManager()
                .getTexture(id);
        texture = source == null ? GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D) : source.getGlTextureId();
    }

    public ITextureObject source() {
        return source;
    }

    public void restoreSource(ITextureObject source, int texture) {
        this.source = source;
        this.texture = texture;
    }

    public int texture() {
        return texture;
    }

    /** Whether this segment classifies faces; false means {@link #collect} is a no-op and the plain path draws. */
    public boolean classified() {
        return enabled && source instanceof MaterialSource material && material.materialAvailable();
    }

    /** Lighting state for faces collected from now on; maintained by the caller when it toggles emissive bones. */
    public void setLit(boolean lit) {
        this.lit = lit;
    }

    public boolean collect(GeoCube cube, Matrix4f model, Matrix3f normalMatrix,
        float r, float g, float b, float a) {
        if (!enabled) {
            return false;
        }
        if (!(source instanceof MaterialSource material) || !material.materialAvailable()) {
            return false;
        }
        // Reused matrices: this used to allocate a Matrix4f and a float[4] coefficient per cube.
        clip.mul(outerClip, model);
        MaterialFacing.coefficient(clip, coefficient);
        // The native pose stack's orientation sign for this bone (pose_stack_impl.inc:53-60) combined with the
        // handedness of the outer transform: the port mirrors in the outer one (the biped arm and the paper doll;
        // see MaterialFacing#mirrored), a pack can mirror a bone with a negative scale.
        boolean mirrored = outerMirrored != MaterialFacing.mirrored(model);
        boolean flat = !cube.mesh && (cube.size.x == 0 || cube.size.y == 0 || cube.size.z == 0);
        List<BakedFace> baked = material.materialFaces(cube, forceCulling);
        for (int i = 0, size = baked.size(); i < size; i++) {
            BakedFace face = baked.get(i);
            if (MaterialFacing.culled(face, coefficient, mirrored)) {
                continue;
            }
            // Which side of the face is being drawn, for its lighting: the same rule the culling uses, and it is
            // meaningless for a mirrored chain, where the native rule cannot say (see MaterialFacing#culled), so
            // the declared normal is kept there.
            boolean back = !mirrored && MaterialFacing.backFace(face, coefficient);
            boolean translucentFace = face.partition.translucent;
            Face slot = obtain(translucentFace);
            slot.baked = face;
            slot.texture = texture;
            slot.index = collected++;
            slot.r = r;
            slot.g = g;
            slot.b = b;
            slot.a = a;
            slot.lit = lit;
            slot.flat = flat;
            if (translucentFace) {
                slot.depth = MaterialFacing.depth(face, clip);
            }
            MaterialFacing.normal(face, normalMatrix, back, slot.normal);
            float x0 = face.position(0, 0), y0 = face.position(0, 1), z0 = face.position(0, 2);
            float x1 = face.position(1, 0), y1 = face.position(1, 1), z1 = face.position(1, 2);
            float x2 = face.position(2, 0), y2 = face.position(2, 1), z2 = face.position(2, 2);
            float x3 = face.position(3, 0), y3 = face.position(3, 1), z3 = face.position(3, 2);
            float[] position = slot.position;
            position[0] = model.m00 * x0 + model.m01 * y0 + model.m02 * z0 + model.m03;
            position[1] = model.m10 * x0 + model.m11 * y0 + model.m12 * z0 + model.m13;
            position[2] = model.m20 * x0 + model.m21 * y0 + model.m22 * z0 + model.m23;
            position[3] = model.m00 * x1 + model.m01 * y1 + model.m02 * z1 + model.m03;
            position[4] = model.m10 * x1 + model.m11 * y1 + model.m12 * z1 + model.m13;
            position[5] = model.m20 * x1 + model.m21 * y1 + model.m22 * z1 + model.m23;
            position[6] = model.m00 * x2 + model.m01 * y2 + model.m02 * z2 + model.m03;
            position[7] = model.m10 * x2 + model.m11 * y2 + model.m12 * z2 + model.m13;
            position[8] = model.m20 * x2 + model.m21 * y2 + model.m22 * z2 + model.m23;
            position[9] = model.m00 * x3 + model.m01 * y3 + model.m02 * z3 + model.m03;
            position[10] = model.m10 * x3 + model.m11 * y3 + model.m12 * z3 + model.m13;
            position[11] = model.m20 * x3 + model.m21 * y3 + model.m22 * z3 + model.m23;
        }
        return true;
    }

    /** A reusable face for one partition; grows that partition's array when the model has more faces. */
    private Face obtain(boolean translucentFace) {
        if (translucentFace) {
            if (translucentCount == translucent.length) {
                translucent = Arrays.copyOf(translucent, translucent.length * 2);
            }
            Face face = translucent[translucentCount];
            if (face == null) {
                face = translucent[translucentCount] = new Face();
            }
            translucentCount++;
            return face;
        }
        if (opaqueCount == opaque.length) {
            opaque = Arrays.copyOf(opaque, opaque.length * 2);
        }
        Face face = opaque[opaqueCount];
        if (face == null) {
            face = opaque[opaqueCount] = new Face();
        }
        opaqueCount++;
        return face;
    }

    /** Flush before arbitrary host geometry, preserving the old renderer's fallback state afterwards. */
    public void drain(Tessellator builder) {
        if (opaqueCount == 0 && translucentCount == 0) {
            return;
        }
        IGeoRenderer.flushBatch(builder);
        int savedTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        boolean savedLighting = GL11.glIsEnabled(GL11.GL_LIGHTING);
        // Every face was already culled on the CPU, including the mirrored draws this rule cannot answer for; the GL
        // culling state therefore stays off for the whole segment.
        GlStateManager.disableCull();
        try {
            if (opaqueCount > 0) {
                drawFaces(builder, opaque, opaqueCount, false);
            }
            if (translucentCount > 0) {
                if (translucentCount > 1) {
                    Arrays.sort(translucent, 0, translucentCount, TRANSLUCENT_ORDER);
                }
                drawFaces(builder, translucent, translucentCount, true);
            }
            IGeoRenderer.flushBatch(builder);
        } finally {
            opaqueCount = 0;
            translucentCount = 0;
            collected = 0;
            GlStateManager.bindTexture(savedTexture);
            if (savedLighting) {
                GlStateManager.enableLighting();
            } else {
                GlStateManager.disableLighting();
            }
            GlStateManager.disablePolygonOffset();
            if (fallback != null && fallback.isCull()) {
                GlStateManager.enableCull();
            } else {
                GlStateManager.disableCull();
            }
            if (fallback != null && fallback.isBlend()) {
                GlStateManager.enableBlend();
            } else {
                GlStateManager.disableBlend();
            }
            if (fallback != null && fallback.isAlphaTest()) {
                GlStateManager.enableAlpha();
                GlStateManager.alphaFunc(GL11.GL_GREATER, fallback.getAlphaRef());
            } else {
                GlStateManager.disableAlpha();
            }
        }
    }

    /** Draws one partition group in array order (the translucent group was sorted by the caller). */
    private void drawFaces(Tessellator builder, Face[] group, int count, boolean translucentGroup) {
        int currentTexture = Integer.MIN_VALUE;
        boolean currentLit = false;
        boolean currentFlat = false;
        boolean currentTranslucent = false;
        for (int i = 0; i < count; i++) {
            Face face = group[i];
            // GL state is sampled when the batch is drawn, so anything that affects the draw must be flushed first.
            if (i == 0 || currentTexture != face.texture || currentLit != face.lit || currentFlat != face.flat
                || currentTranslucent != translucentGroup) {
                IGeoRenderer.flushBatch(builder);
                if (currentTexture != face.texture) {
                    GlStateManager.bindTexture(face.texture);
                }
                if (currentTranslucent != translucentGroup) {
                    if (translucentGroup) {
                        GlStateManager.enableBlend();
                        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
                        GlStateManager.disableAlpha();
                    } else {
                        GlStateManager.disableBlend();
                        GlStateManager.enableAlpha();
                        GlStateManager.alphaFunc(GL11.GL_GREATER, 0.1F);
                    }
                }
                if (currentLit != face.lit) {
                    if (face.lit) {
                        GlStateManager.enableLighting();
                    } else {
                        GlStateManager.disableLighting();
                    }
                }
                if (currentFlat != face.flat) {
                    if (face.flat) {
                        GlStateManager.enablePolygonOffset();
                        GlStateManager.doPolygonOffset(-1.0F, -10.0F);
                    } else {
                        GlStateManager.disablePolygonOffset();
                    }
                }
                currentTexture = face.texture;
                currentLit = face.lit;
                currentFlat = face.flat;
                currentTranslucent = translucentGroup;
            }
            IGeoRenderer.flushBatchIfNearlyFull(builder);
            builder.setColorRGBA_F(face.r, face.g, face.b, face.a);
            builder.setNormal(face.normal[0], face.normal[1], face.normal[2]);
            float[] position = face.position;
            builder.addVertexWithUV(position[0], position[1], position[2], face.baked.uv(0, 0), face.baked.uv(0, 1));
            builder.addVertexWithUV(position[3], position[4], position[5], face.baked.uv(1, 0), face.baked.uv(1, 1));
            builder.addVertexWithUV(position[6], position[7], position[8], face.baked.uv(2, 0), face.baked.uv(2, 1));
            builder.addVertexWithUV(position[9], position[10], position[11], face.baked.uv(3, 0), face.baked.uv(3, 1));
        }
    }

    public void close(Tessellator builder) {
        try {
            drain(builder);
        } finally {
            active = previous;
            disable();
            previous = null;
            if (POOL.size() < MAX_POOLED) {
                POOL.push(this);
            }
        }
    }

    /** Clears the state that must not outlive one segment; the arrays stay hot for the next one. */
    private void disable() {
        enabled = false;
        forceCulling = false;
        fallback = null;
        source = null;
        texture = 0;
        baseBinding = -1;
        opaqueCount = 0;
        translucentCount = 0;
        collected = 0;
    }

    private static final class Face {

        BakedFace baked;
        final float[] position = new float[12];
        final float[] normal = new float[3];
        int texture;
        int index;
        float depth;
        float r;
        float g;
        float b;
        float a;
        boolean lit;
        boolean flat;
    }
}

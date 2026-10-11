package software.bernie.geckolib3.geo.material;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;

import javax.vecmath.Matrix3f;
import javax.vecmath.Matrix4f;
import javax.vecmath.Vector3f;

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

/** One uninterrupted model draw segment. Never sorts across layers, callbacks or nested render invocations. */
public final class MaterialDrawSession {
    private static MaterialDrawSession active;
    private final MaterialDrawSession previous;
    private final Matrix4f outerClip = new Matrix4f();
    private final List<Face> faces = new ArrayList<>();
    private final boolean forceCulling;
    private final YsmRenderType fallback;
    private final int baseBinding;
    private ITextureObject source;
    private int texture;

    private MaterialDrawSession(boolean forceCulling, YsmRenderType fallback) {
        this.previous = active;
        this.forceCulling = forceCulling;
        this.fallback = fallback;
        baseBinding = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        Matrix4f projection = matrix(GL11.GL_PROJECTION_MATRIX);
        outerClip.mul(projection, matrix(GL11.GL_MODELVIEW_MATRIX));
        setTexture(fallback.getTexture());
        active = this;
    }

    public static MaterialDrawSession begin(boolean forceCulling, YsmRenderType fallback) {
        if (active != null) active.drain(Tessellator.instance);
        return new MaterialDrawSession(forceCulling, fallback);
    }
    public static MaterialDrawSession active() { return active; }

    private static Matrix4f matrix(int name) {
        FloatBuffer buffer = BufferUtils.createFloatBuffer(16);
        GL11.glGetFloat(name, buffer);
        Matrix4f matrix = new Matrix4f();
        for (int row = 0; row < 4; row++) for (int col = 0; col < 4; col++) matrix.setElement(row, col, buffer.get(col * 4 + row));
        return matrix;
    }

    public void setTexture(ResourceLocation id) {
        source = id == null ? null : Minecraft.getMinecraft().getTextureManager().getTexture(id);
        texture = source == null ? GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D) : source.getGlTextureId();
    }
    public ITextureObject source() { return source; }
    public void restoreSource(ITextureObject source, int texture) { this.source = source; this.texture = texture; }
    public int texture() { return texture; }
    public boolean classified() { return source instanceof MaterialSource material && material.materialAvailable(); }

    public boolean collect(GeoCube cube, Matrix4f model, Matrix3f normalMatrix,
        float r, float g, float b, float a) {
        if (!(source instanceof MaterialSource material) || !material.materialAvailable()) return false;
        Matrix4f clip = new Matrix4f(); clip.mul(outerClip, model);
        float[] coefficient = MaterialFacing.coefficient(clip);
        for (BakedFace baked : material.materialFaces(cube, forceCulling)) {
            boolean back = MaterialFacing.backFace(baked, coefficient);
            if (baked.partition.culling && back) continue;
            Face face = new Face();
            face.baked = baked; face.texture = texture;
            face.depth = MaterialFacing.depth(baked, clip); face.index = faces.size();
            face.r = r; face.g = g; face.b = b; face.a = a;
            face.lit = GL11.glIsEnabled(GL11.GL_LIGHTING);
            face.flat = !cube.mesh && (cube.size.x == 0 || cube.size.y == 0 || cube.size.z == 0);
            Vector3f normal = MaterialFacing.normal(baked, normalMatrix, false, back);
            face.nx = normal.x; face.ny = normal.y; face.nz = normal.z;
            for (int v = 0; v < 4; v++) {
                float x = baked.position(v, 0), y = baked.position(v, 1), z = baked.position(v, 2);
                face.position[v * 3] = model.m00 * x + model.m01 * y + model.m02 * z + model.m03;
                face.position[v * 3 + 1] = model.m10 * x + model.m11 * y + model.m12 * z + model.m13;
                face.position[v * 3 + 2] = model.m20 * x + model.m21 * y + model.m22 * z + model.m23;
            }
            faces.add(face);
        }
        return true;
    }

    /** Flush before arbitrary host geometry, preserving the old renderer's fallback state afterwards. */
    public void drain(Tessellator builder) {
        if (faces.isEmpty()) return;
        IGeoRenderer.flushBatch(builder);
        int savedTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        boolean savedLighting = GL11.glIsEnabled(GL11.GL_LIGHTING);
        faces.sort((a, b) -> {
            if (a.baked.partition.translucent != b.baked.partition.translucent) return a.baked.partition.translucent ? 1 : -1;
            return a.baked.partition.translucent ? MaterialSort.compare(a.depth, a.index, b.depth, b.index) : Integer.compare(a.index, b.index);
        });
        try {
            Face previousFace = null;
            for (Face face : faces) {
                boolean stateChange = previousFace == null || previousFace.texture != face.texture
                    || previousFace.baked.partition.translucent != face.baked.partition.translucent
                    || previousFace.lit != face.lit || previousFace.flat != face.flat;
                if (stateChange) IGeoRenderer.flushBatch(builder);
                IGeoRenderer.flushBatchIfNearlyFull(builder);
                GlStateManager.bindTexture(face.texture);
                GlStateManager.disableCull(); // CPU plane/winding culling already performed, including mirrors.
                if (face.baked.partition.translucent) {
                    GlStateManager.enableBlend(); GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
                    GlStateManager.disableAlpha();
                } else { GlStateManager.disableBlend(); GlStateManager.enableAlpha(); GlStateManager.alphaFunc(GL11.GL_GREATER, .1f); }
                if (face.lit) GlStateManager.enableLighting(); else GlStateManager.disableLighting();
                if (face.flat) { GlStateManager.enablePolygonOffset(); GlStateManager.doPolygonOffset(-1, -10); }
                else GlStateManager.disablePolygonOffset();
                builder.setColorRGBA_F(face.r, face.g, face.b, face.a);
                builder.setNormal(face.nx, face.ny, face.nz);
                for (int v = 0; v < 4; v++) builder.addVertexWithUV(face.position[v * 3], face.position[v * 3 + 1],
                    face.position[v * 3 + 2], face.baked.uv(v, 0), face.baked.uv(v, 1));
                previousFace = face;
            }
            IGeoRenderer.flushBatch(builder);
        } finally {
            faces.clear();
            GlStateManager.bindTexture(savedTexture);
            if (savedLighting) GlStateManager.enableLighting(); else GlStateManager.disableLighting();
            GlStateManager.disablePolygonOffset();
            if (fallback.isCull()) GlStateManager.enableCull(); else GlStateManager.disableCull();
            if (fallback.isBlend()) GlStateManager.enableBlend(); else GlStateManager.disableBlend();
            if (fallback.isAlphaTest()) { GlStateManager.enableAlpha(); GlStateManager.alphaFunc(GL11.GL_GREATER, fallback.getAlphaRef()); }
            else GlStateManager.disableAlpha();
        }
    }

    public void close(Tessellator builder) {
        try { drain(builder); }
        finally { active = previous; GlStateManager.bindTexture(baseBinding); }
    }

    private static final class Face {
        BakedFace baked;
        final float[] position = new float[12];
        int texture, index;
        float depth, r, g, b, a, nx, ny, nz;
        boolean lit, flat;
    }
}

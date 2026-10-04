package software.bernie.geckolib3.geo;

import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;
import javax.vecmath.Vector3f;
import javax.vecmath.Vector4f;

import net.geckominecraft.client.renderer.GlStateManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.texture.ITextureObject;
import net.minecraft.util.ResourceLocation;

import org.lwjgl.opengl.GL11;

import software.bernie.example.config.ConfigHandler;
import software.bernie.geckolib3.GeckoLib;
import software.bernie.geckolib3.core.util.Color;
import software.bernie.geckolib3.geo.render.built.GeoBone;
import software.bernie.geckolib3.geo.render.built.GeoCube;
import software.bernie.geckolib3.geo.render.built.GeoModel;
import software.bernie.geckolib3.geo.render.built.GeoQuad;
import software.bernie.geckolib3.geo.render.built.GeoVertex;
import software.bernie.geckolib3.core.processor.IBone;
import software.bernie.geckolib3.model.provider.GeoModelProvider;
import software.bernie.geckolib3.util.MatrixStack;

public interface IGeoRenderer<T> {

    public static MatrixStack MATRIX_STACK = new MatrixStack();
    /** Reusable per-quad normal vector — avoids allocating one per cube/quad. */
    Vector3f RENDER_TEMP_NORMAL = new Vector3f();
    /** Reusable per-vertex position vector — avoids allocating one per vertex. */
    Vector4f RENDER_TEMP_VEC = new Vector4f();

    /**
     * Render failures already reported, so one broken model cannot flood the log.
     */
    Set<String> REPORTED_RENDER_FAILURES = ConcurrentHashMap.newKeySet();

    int MAX_REPORTED_RENDER_FAILURES = 24;

    /**
     * Reports geometry that could not be drawn.
     * <p>
     * A cube that threw used to be skipped in complete silence unless {@code debugStacktraces} was on, which makes
     * "the model is not on screen" indistinguishable from "the model drew nothing at all". Announcing the cause once
     * per distinct failure is what turns it back into a diagnosable event; the cap keeps a broken pack from filling
     * the log. The full stack trace still needs the config flag.
     *
     * @param where what was being drawn, for example {@code "cube #3 of bone LeftLeg"}
     */
    default void reportRenderFailure(String where, Throwable failure) {
        String key = where + " | " + failure.getClass()
            .getName() + " | " + failure.getMessage();
        if (REPORTED_RENDER_FAILURES.size() >= MAX_REPORTED_RENDER_FAILURES
            || !REPORTED_RENDER_FAILURES.add(key)) {
            return;
        }
        GeckoLib.LOG.warn(
            "GeckoLib: could not draw {} ({}); that geometry is skipped. Set general.debugStacktraces=true in the"
                + " GeckoLib config for the full stack trace, and report which model pack shows this.",
            where,
            failure.toString());
        if (ConfigHandler.debugPrintStacktraces) {
            failure.printStackTrace();
        }
    }

    default void render(GeoModel model, T animatable, YsmRenderType type, float partialTicks, float red, float green,
        float blue, float alpha) {
        // The GL state comes from the type the caller chose, exactly as upstream's draw applies the RenderType it was
        // handed (com/elfmcys/ysm/geckolib3/geo/IGeoRenderer.java:18-26). Nothing is decided in here.
        if (type.isCull()) {
            GlStateManager.enableCull();
        } else {
            GlStateManager.disableCull();
        }
        if (type.isAlphaTest()) {
            GlStateManager.enableAlpha();
            GlStateManager.alphaFunc(GL11.GL_GREATER, type.getAlphaRef());
        } else {
            GlStateManager.disableAlpha();
        }
        GlStateManager.enableRescaleNormal();
        if (type.isBlend()) {
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GlStateManager.enableBlend();
        } else {
            GlStateManager.disableBlend();
        }
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        renderEarly(model, animatable, partialTicks, red, green, blue, alpha);

        renderLate(model, animatable, partialTicks, red, green, blue, alpha);
        Tessellator tess = Tessellator.instance;
        // BufferBuilder builder = Tessellator.instance.getBuffer();

        // builder.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX_COLOR_NORMAL);
        tess.startDrawing(GL11.GL_QUADS);// , DefaultVertexFormats.POSITION_TEX_COLOR_NORMAL);
        // Render all top level bones
        for (GeoBone group : model.topLevelBones) {
            renderRecursively(tess, animatable, group, red, green, blue, alpha);
        }

        software.bernie.geckolib3.util.GeoStats.noteFlush();
        software.bernie.geckolib3.util.TessellatorBufferKeep.draw(Tessellator.instance);

        renderAfter(model, animatable, partialTicks, red, green, blue, alpha);
        // GlStateManager.disableRescaleNormal();
        // Restores what this method has always restored. The alpha test is left disabled because the engine never
        // turned it on before the render type existed, so a cutout draw nets out to no change for anything after it.
        GlStateManager.disableBlend();
        GlStateManager.disableAlpha();
        GlStateManager.enableCull();
    }

    /**
     * 骨骼是否因缩放退化而整体不渲染：**任意一轴为 0** 即为隐藏。
     *
     * <p>三轴全 0 是最常见的显式隐藏；单轴 0 同样不可见（现代管线法线矩阵 invert 退化，
     * 且 0 厚度的面片不是作者想要的外观）。抽出来是为了能单测这条判定。</p>
     */
    static boolean isScaleInvisible(IBone bone) {
        return bone == null
            || bone.getScaleX() == 0f || bone.getScaleY() == 0f || bone.getScaleZ() == 0f;
    }

    default boolean isBoneRenderOverriden(T animatable, GeoBone bone) {
        return false;
    }

    default void drawOverridenBone(T animatable, GeoBone bone) {

    }

    default void renderRecursively(Tessellator builder, T animatable, GeoBone bone, float red, float green, float blue,
        float alpha) {
        MATRIX_STACK.push();

        // The body runs in a try/finally so the shared matrix stack is popped on every path. It used to be popped
        // only on the normal path, so one escaping exception leaked a frame for the rest of the session and every
        // later model could then fail its own pop (silently, through the callers' catch blocks).
        try {
            // Single combined bone transform — replaces 5 separate mul() calls
            MATRIX_STACK.transformBone(bone);

            if (isBoneRenderOverriden(animatable, bone)) {
                drawOverridenBone(animatable, bone);
                return;
            }

            if (!bone.isHidden()) {
                // 任意一轴缩放为 0 = 该骨骼及其子树不渲染。两种写法都是"隐藏"：
                //   * 三轴全 0：模型里最常见的显式隐藏（护甲动画 wiki：并行动画把护甲组缩放到 0，
                //     护甲动画再缩放回 1）；
                //   * 单轴 0：现代渲染管线的法线矩阵由 invert() 得到，缩放矩阵奇异时退化 → 整块
                //     画不出来；而且"压成 0 厚度的面片"也从来不是作者想要的外观。
                // 实测：某弹射物命中后把箭身写成 [0,1,1]（贴图 alpha 全不透明），官方客户端看不到
                // 箭身，我们却画出一块很显眼的面片 —— 作者的本意是"收掉箭身"。
                if (isScaleInvisible(bone)) {
                    return;
                }

                // Per-bone render state, upstream's bone_color / bone_transparency / bone_glow. A bone that never sets
                // them carries white, opaque and "not emissive", so every multiplication below is by one and the
                // lighting branch is not taken: a model that uses none of these draws exactly as it did before.
                float boneRed = red * bone.getRenderRed();
                float boneGreen = green * bone.getRenderGreen();
                float boneBlue = blue * bone.getRenderBlue();
                float boneAlpha = alpha * bone.getRenderAlpha();
                boolean emissive = bone.getRenderGlow() >= 0;
                if (emissive) {
                    // The fixed-function pipeline has no per-bone lightmap, so an emissive bone is simply drawn
                    // unlit. Upstream's level selects a light value there; what a pack asks for by calling
                    // bone_glow at all is that the bone glows, and the level is kept on the bone for a future
                    // brightness mapping rather than being invented here.
                    GlStateManager.disableLighting();
                }

                // Per-bone texture override: if this bone specifies a different texture,
                // bind it before rendering cubes and restore afterwards.
                int savedTextureId = -1;
                if (bone.textureOverride != null) {
                    savedTextureId = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
                    ITextureObject overrideTex = Minecraft.getMinecraft()
                        .getTextureManager().getTexture(bone.textureOverride);
                    if (overrideTex != null) {
                        flushBatch(builder);
                        GlStateManager.bindTexture(overrideTex.getGlTextureId());
                    }
                }

                try {
                    renderBoneCubes(builder, bone, boneRed, boneGreen, boneBlue, boneAlpha);
                } finally {
                    if (emissive) {
                        GlStateManager.enableLighting();
                    }
                }

                // Restore the original texture binding after rendering this bone's cubes.
                if (savedTextureId >= 0) {
                    flushBatch(builder);
                    GlStateManager.bindTexture(savedTextureId);
                }
            }
            if (!bone.childBonesAreHiddenToo()) {
                for (GeoBone childBone : bone.childBones) {
                    renderRecursively(builder, animatable, childBone, red, green, blue, alpha);
                }
            }
        } catch (Exception e) {
            reportRenderFailure("bone " + bone.getName(), e);
        } finally {
            MATRIX_STACK.pop();
        }
    }

    /**
     * 一批顶点快塞满共享 Tessellator 的 raw 缓冲时先 flush 再重新开一批（调用点在
     * {@link #renderCube} 的**每个面**之前 —— 一个 mesh cube 可能自己就超过预留量）。
     *
     * <p>见 {@link software.bernie.geckolib3.util.TessellatorBufferKeep}：只要容量不超过 0x20000
     * （512 KiB），原版补丁与 Angelica 的 {@code TessellatorStreamingDrawer} 里那段
     * "容量大就缩回 256 KiB"的分支就永远不执行，{@code func_78377_a} 也就不会每帧
     * {@code Arrays.copyOf} 把缓冲翻倍长回来。代价是超大模型会多几次 draw（每批
     * ≈ 0x20000 个 int ≈ 1.5 万顶点 ≈ 3.8 千个 quad），比起每帧数 MB 的拷贝划算。</p>
     */
    static void flushBatchIfNearlyFull(Tessellator builder) {
        if (software.bernie.geckolib3.util.TessellatorBufferKeep.nearlyFull(builder)) {
            flushBatch(builder);
        }
    }

    /** Submit before a GL state change; a restarted batch has no vertex attributes set. */
    static void flushBatch(Tessellator builder) {
        software.bernie.geckolib3.util.GeoStats.noteFlush();
        software.bernie.geckolib3.util.TessellatorBufferKeep.draw(builder);
        builder.startDrawing(GL11.GL_QUADS);
    }

    /** State boundary separated from vertex emission so consecutive flat cubes share one batch. */
    default void setCubePolygonOffset(boolean flat) {
        if (flat) {
            GlStateManager.enablePolygonOffset();
            GlStateManager.doPolygonOffset(-1.0F, -10.0F);
        } else {
            GlStateManager.disablePolygonOffset();
            GlStateManager.doPolygonOffset(0.0F, 0.0F);
        }
    }

    /** sign: 0 = all cubes, -1 = negative-size, +1 = positive-size; preserve source order. */
    default void renderCubeGroup(Tessellator builder, GeoBone bone, int sign,
        float red, float green, float blue, float alpha) {
        boolean offset = false;
        try {
            for (int cubeIndex = 0; cubeIndex < bone.childCubes.size(); cubeIndex++) {
                GeoCube cube = bone.childCubes.get(cubeIndex);
                if (sign < 0 && !cube.hasNegSize || sign > 0 && cube.hasNegSize) continue;
                boolean flat = !cube.mesh && (cube.size.x == 0 || cube.size.y == 0 || cube.size.z == 0);
                if (flat != offset) {
                    flushBatch(builder);
                    setCubePolygonOffset(flat);
                    offset = flat;
                }
                try {
                    renderCube(builder, cube, red, green, blue, alpha);
                } catch (Exception e) {
                    reportRenderFailure("cube #" + cubeIndex + " of bone " + bone.getName(), e);
                }
            }
        } finally {
            if (offset) {
                try {
                    flushBatch(builder);
                } finally {
                    setCubePolygonOffset(false);
                }
            }
        }
    }

    /**
     * Renders a bone's child cubes, handling negative-size outline geometry.
     * <p>
     * Some models use a negative-size cube as a slightly larger outline wrapper
     * that encloses the main positive-size textured cube. Both cubes' front
     * faces end up at the same depth after origin adjustment. With GL_LEQUAL as
     * the default depth function, rendering the negative cube second would
     * overwrite the main textured geometry.
     * <p>
     * Strategy — render negatives FIRST using CULL_FRONT (only back faces,
     * i.e. faces facing away from the camera), which are at the FARTHEST
     * depth. Then render positives SECOND with normal depth testing — the
     * positive cube's front faces (closer to camera) pass LEQUAL and render
     * on top. At the silhouette edges no positive geometry exists, so the
     * negative's back-face ring remains visible as the intended outline.
     * <p>
     * CULL_FRONT on a standard CCW-wound cube culls front faces (facing
     * camera) and renders back faces (facing away). This gives us the
     * far-side depth without vertex reversal trickery.
     * <p>
     * If the bone has only positive-size cubes, a single pass is used.
     */
    default void renderBoneCubes(Tessellator builder, GeoBone bone,
        float red, float green, float blue, float alpha) {
        // Single scan: detect whether we have neg/pos cubes at all.
        boolean anyNeg = false, anyPos = false;
        for (GeoCube c : bone.childCubes) {
            if (c.hasNegSize) anyNeg = true;
            else anyPos = true;
            if (anyNeg && anyPos) break;
        }

        if (!anyNeg) {
            // Single pass: all cubes are positive-size.
            // renderCube 不再改动矩阵栈（见 MatrixStack#beginCube），所以这里不需要 push/pop。
            renderCubeGroup(builder, bone, 0, red, green, blue, alpha);
            return;
        }

        software.bernie.geckolib3.util.GeoStats.noteFlush();
        software.bernie.geckolib3.util.TessellatorBufferKeep.draw(Tessellator.instance);

        // ── Pass 1: negative-size cubes (CULL_FRONT → back faces only) ──
        Tessellator.instance.startDrawing(GL11.GL_QUADS);
        GL11.glEnable(GL11.GL_CULL_FACE);
        GL11.glCullFace(GL11.GL_FRONT);
        renderCubeGroup(builder, bone, -1, red, green, blue, alpha);
        software.bernie.geckolib3.util.GeoStats.noteFlush();
        software.bernie.geckolib3.util.TessellatorBufferKeep.draw(Tessellator.instance);
        GL11.glCullFace(GL11.GL_BACK);
        GL11.glDisable(GL11.GL_CULL_FACE);

        // ── Pass 2: positive-size cubes (normal depth testing) ──
        Tessellator.instance.startDrawing(GL11.GL_QUADS);
        renderCubeGroup(builder, bone, 1, red, green, blue, alpha);
        software.bernie.geckolib3.util.GeoStats.noteFlush();
        software.bernie.geckolib3.util.TessellatorBufferKeep.draw(Tessellator.instance);

        Tessellator.instance.startDrawing(GL11.GL_QUADS);
    }

    default void renderCube(Tessellator builder, GeoCube cube, float red, float green, float blue, float alpha) {
        // 原来这里是 push + moveToPivot + rotate + moveBackFromPivot + pop（入栈拷贝 25 个浮点、
        // 5 次模型矩阵乘法 + 6 次法线矩阵乘法）。现在算出 cube 的最终矩阵但**不入栈**：
        // 没有自身旋转的 cube（绝大多数）直接引用栈顶，零拷贝零乘法。
        MATRIX_STACK.beginCube(cube);
        // 诊断计数：只有引擎开关 GeckoLib.geoStatsEnabled 打开时才真的自增（宿主按自己的调试开关设置它，
        // 见 GeoStats 与 AGENTS.md「Host Contract」）。
        software.bernie.geckolib3.util.GeoStats.noteCube(cube.quads.length * 4);

        final javax.vecmath.Matrix4f model = MATRIX_STACK.getCubeModelMatrix();
        final javax.vecmath.Matrix3f normalMatrix = MATRIX_STACK.getCubeNormalMatrix();

        for (GeoQuad quad : cube.quads) {
            if (quad == null) continue;
            // 切批只能在"面"边界：法线是 setNormal 按面设置的，切在面中间会让部分顶点丢掉法线。
            // mesh cube 的面数和顶点数都可能很大，一个 cube 就可能超过预留量，所以粒度取到面。
            flushBatchIfNearlyFull(builder);
            // startDrawing clears hasColor. Set all face attributes AFTER any capacity flush.
            builder.setColorRGBA_F(red, green, blue, alpha);
            // Fresh copy for normal transforms + flat shading workaround
            if (quad.normalVector == null) {
                RENDER_TEMP_NORMAL.set(quad.normal.getX(), quad.normal.getY(), quad.normal.getZ());
            } else {
                RENDER_TEMP_NORMAL.set(quad.normalVector);
            }

            normalMatrix.transform(RENDER_TEMP_NORMAL);

            /*
             * Fix shading dark shading for flat cubes + compatibility wish Optifine shaders
             */
            if (!cube.mesh && (cube.size.y == 0 || cube.size.z == 0) && RENDER_TEMP_NORMAL.x < 0) {
                RENDER_TEMP_NORMAL.x *= -1;
            }
            if (!cube.mesh && (cube.size.x == 0 || cube.size.z == 0) && RENDER_TEMP_NORMAL.y < 0) {
                RENDER_TEMP_NORMAL.y *= -1;
            }
            if (!cube.mesh && (cube.size.x == 0 || cube.size.y == 0) && RENDER_TEMP_NORMAL.z < 0) {
                RENDER_TEMP_NORMAL.z *= -1;
            }

            // 法线是"每个面"的量：原来每个顶点调一次 setNormal（4 倍冗余），提到面一级。
            builder.setNormal(RENDER_TEMP_NORMAL.x, RENDER_TEMP_NORMAL.y, RENDER_TEMP_NORMAL.z);

            for (GeoVertex vertex : quad.vertices) {
                // 展开 4x4 × vec4：省掉 RENDER_TEMP_VEC 的 set/读回和 vecmath 的通用实现。
                // 结果与 `model.transform(vec4(x, y, z, 1))` 逐位相同（只取了 xyz）。
                final float vx = vertex.position.x;
                final float vy = vertex.position.y;
                final float vz = vertex.position.z;
                builder.addVertexWithUV(
                    model.m00 * vx + model.m01 * vy + model.m02 * vz + model.m03,
                    model.m10 * vx + model.m11 * vy + model.m12 * vz + model.m13,
                    model.m20 * vx + model.m21 * vy + model.m22 * vz + model.m23,
                    vertex.textureU, vertex.textureV);
            }
        }

    }

    /*
     * (-0.4095761, 0.5882118, 0.70710677, 1.0)
     * (0.409576, 1.1617882, 0.70710677, 1.0)
     * (0.0039961934, 1.7410161, -5.9604645E-8, 1.0)
     * (-0.81515586, 1.1674397, -5.9604645E-8, 1.0)
     * (-0.003996223, 0.008983791, -5.9604645E-8, 1.0)
     * (0.81515586, 0.5825603, -5.9604645E-8, 1.0)
     * (0.40957603, 1.1617882, -0.7071068, 1.0)
     * (-0.40957603, 0.5882118, -0.7071068, 1.0)
     */
    @SuppressWarnings("rawtypes")
    GeoModelProvider getGeoModelProvider();

    ResourceLocation getTextureLocation(T instance);

    @Nullable
    default GeoModel getGeoModel() {
        return null;
    }

    default void renderEarly(GeoModel model, T animatable, float ticks, float red, float green, float blue,
        float alpha) {
        float width = getWidthScale(animatable);
        float height = getHeightScale(animatable);
        MATRIX_STACK.push();
        MATRIX_STACK.scale(width, height, width);
    }

    default void renderLate(GeoModel model, T animatable, float ticks, float red, float green, float blue,
        float alpha) {}

    default void renderAfter(GeoModel model, T animatable, float ticks, float red, float green, float blue,
        float alpha) {
        MATRIX_STACK.pop();
    }

    default Color getRenderColor(T animatable, float partialTicks) {
        return Color.ofRGBA(255, 255, 255, 255);
    }

    default Integer getUniqueID(T animatable) {
        return animatable.hashCode();
    }

    default GeoBone[] getPathFromRoot(GeoBone bone) {
        ArrayList<GeoBone> bones = new ArrayList<>();
        while (bone != null) {
            bones.add(0, bone);
            bone = bone.parent;
        }
        return bones.toArray(new GeoBone[0]);
    }

    default float getWidthScale(T animatable) {
        return 1F;
    }

    default float getHeightScale(T entity) {
        return 1F;
    }

    /**
     * Whether this frame's layers (held item, back attachments, armor) are drawn before the model instead of after it.
     * <p>
     * A pack can declare this per model - a held item or a wing the model geometry is drawn over has to go first,
     * otherwise the model covers it. The setting belongs to the host that owns the model data, so the renderer asks
     * here rather than the engine keeping a table of its own.
     *
     * @return {@code false} to keep the default order, which draws the model first
     */
    default boolean shouldRenderLayersFirst(T animatable) {
        return false;
    }

    /**
     * The state this model is drawn with, decided here and handed to {@link #render} as a parameter - the port of
     * upstream's {@code com/elfmcys/ysm/geckolib3/geo/IGeoRenderer.java:33-39}, same name, same parameters, same
     * branches. {@link YsmRenderType} is only the carrier, because 1.7.10 has no {@code RenderType} to return.
     * <p>
     * Upstream's body, kept verbatim in shape: a visible model is drawn translucent - which blends and culls, and is
     * what a flat decal needs, because the pack zeroes the uvs of the face it does not want and those faces are built
     * anyway - or as cutout with no culling. An invisible model is drawn only when it glows, as an outline; 1.7.10 has
     * no outline pass, so that branch draws nothing, which is this engine's behaviour today.
     *
     * @param translucent upstream reads this from {@code data.modelState.hasTranslucentVertices()}; the host answers
     *                    here through {@link #hasTranslucentVertices}
     */
    @Nullable
    default YsmRenderType getRenderType(ResourceLocation texture, boolean visible, boolean glowing,
        boolean translucent) {
        if (visible) {
            return translucent ? YsmRenderType.translucent(texture) : YsmRenderType.cutoutNoCull(texture);
        }
        return null;
    }

    /**
     * Whether the model has a vertex that is drawn translucently, i.e. whether {@link #getRenderType} takes the
     * translucent branch for it.
     * <p>
     * Upstream asks its baked model state: {@code GeoModelState#hasTranslucentVertices} returns
     * {@code nativeState.getTranslucentVertexCount() != 0}
     * ({@code com/elfmcys/ysm/geckolib3/geo/animated/GeoModelState.java:73-74}), a count its bake produces with the
     * pack's texture pixels as an input. That count is native and cannot be read here, so the host answers instead,
     * with the same two inputs the bake has: the per-bone transparency the engine already carries (see
     * {@code GeoBone#getRenderTransparency}, the port of upstream's {@code AnimatedGeoBone} slot 13) and the texels
     * the model's faces actually sample.
     *
     * @return {@code false} to take the cutout branch, which is what every model did before this existed
     */
    default boolean hasTranslucentVertices(T animatable) {
        return false;
    }
}

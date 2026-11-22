package software.bernie.geckolib3.geo;

import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;
import javax.vecmath.Matrix3f;
import javax.vecmath.Matrix4f;
import javax.vecmath.Vector3f;
import javax.vecmath.Vector4f;

import com.fox.ysmu.nativebridge.NativeDrawGroups;

import net.geckominecraft.client.renderer.GlStateManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.texture.ITextureObject;
import net.minecraft.util.ResourceLocation;

import org.lwjgl.opengl.GL11;

import software.bernie.example.config.ConfigHandler;
import software.bernie.geckolib3.GeckoLib;
import software.bernie.geckolib3.core.util.Color;
import software.bernie.geckolib3.geo.material.MaterialDrawSession;
import software.bernie.geckolib3.geo.render.built.GeoBone;
import software.bernie.geckolib3.geo.render.built.GeoCube;
import software.bernie.geckolib3.geo.render.built.GeoModel;
import software.bernie.geckolib3.geo.render.built.GeoQuad;
import software.bernie.geckolib3.geo.render.built.GeoVertex;
import software.bernie.geckolib3.core.processor.IBone;
import software.bernie.geckolib3.model.provider.GeoModelProvider;
import software.bernie.geckolib3.util.GeoStats;
import software.bernie.geckolib3.util.MatrixStack;
import software.bernie.geckolib3.util.TessellatorBufferKeep;
import software.bernie.geckolib3.util.TessellatorRawAccess;

public interface IGeoRenderer<T> {

    public static MatrixStack MATRIX_STACK = new MatrixStack();
    /** Reusable per-quad normal vector — avoids allocating one per cube/quad. */
    Vector3f RENDER_TEMP_NORMAL = new Vector3f();
    /** Reusable per-vertex position vector — avoids allocating one per vertex. */
    Vector4f RENDER_TEMP_VEC = new Vector4f();
    /**
     * 原生发射通道的矩阵搬运缓冲：每次 JNI 调用前把当前骨骼的最终模型/法线矩阵拷进去，调用返回后即不再被
     * 原生侧引用。它们只在本类自己的原生路径里使用，且由同一渲染线程顺序使用（见
     * {@link NativeDrawGroups#emitAt}）。
     */
    float[] NATIVE_MODEL_MATRIX = new float[16];
    float[] NATIVE_NORMAL_MATRIX = new float[9];

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
        // renderEarly pushes onto the shared static MATRIX_STACK and renderAfter pops it, so the pair has to be
        // balanced on every path. The draw body used to sit outside this try/finally, and a throw from it (the
        // tessellator's own flush, a material drain, an Error from a bone) skipped the pop: one leaked frame per
        // failure, and because the stack is process-static every later model rendered with a wrong transform until
        // the stack grew into its MAX_DEPTH guard. renderRecursively already brackets each bone this way for the
        // same reason; this is the same bracket one level up.
        try {
            renderLate(model, animatable, partialTicks, red, green, blue, alpha);
            Tessellator tess = Tessellator.instance;
            // BufferBuilder builder = Tessellator.instance.getBuffer();

            // builder.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX_COLOR_NORMAL);
            tess.startDrawing(GL11.GL_QUADS);// , DefaultVertexFormats.POSITION_TEX_COLOR_NORMAL);
            MaterialDrawSession session =
                MaterialDrawSession.begin(
                    model.properties != null && Boolean.TRUE.equals(model.properties.getForceCulling()), type);
            try {
                // M4: the Rust emitter replaces the per-vertex Java loops only when every model-level
                // condition holds (native geometry handle, no face-classifying material session, no
                // texture override in this pose, no tessellator translation). The walk itself still
                // re-checks per bone and falls back to the untouched Java methods on any doubt; with the
                // gate off nothing below is reached and the frame is exactly the pre-M4 one.
                if (canRenderNative(model, session, tess)) {
                    long geometry = model.nativeHandle;
                    for (GeoBone group : model.topLevelBones) {
                        renderRecursivelyNative(tess, animatable, group, red, green, blue, alpha, geometry);
                    }
                } else {
                    for (GeoBone group : model.topLevelBones) {
                        renderRecursively(tess, animatable, group, red, green, blue, alpha);
                    }
                }
            } finally {
                session.close(tess);
            }

            software.bernie.geckolib3.util.GeoStats.noteFlush();
            software.bernie.geckolib3.util.TessellatorBufferKeep.draw(Tessellator.instance);
        } finally {
            renderAfter(model, animatable, partialTicks, red, green, blue, alpha);
        }
        // GlStateManager.disableRescaleNormal();
        // Restores the 1.7.10 baseline the vanilla draws after this one expect: no blend, alpha test on, culling on.
        // The alpha test is part of that baseline rather than of this draw: Minecraft enables it once at startup
        // (Minecraft.java:575-576, GL_GREATER/0.1F) and vanilla rendering code never turns it on per draw - it relies
        // on it (RendererLivingEntity.java:163, ItemRenderer.renderItem's 2D path). This method, however, applies the
        // render type's own state for the draw, so it must put the baseline back afterwards; leaving the test disabled
        // (as this used to) breaks every vanilla draw that follows a YSM model inside the same frame. The concrete
        // case is the held-item layer: it is rendered after this method returns
        // (GeoReplacedEntityRenderer#renderLayers), so the 2D item's alpha-0 texels were not discarded - an opaque
        // "flat sheet" in the world, and a transparent hole showing the sky when the HUD paper doll bakes the model
        // into its alpha-only FBO. See .agent/phase56-held-item-alpha-state.md.
        GlStateManager.disableBlend();
        GlStateManager.enableAlpha();
        GlStateManager.alphaFunc(GL11.GL_GREATER, 0.1F);
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
                if (MaterialDrawSession.active() != null) {
                    MaterialDrawSession.active().drain(builder);
                }
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
                // The material segment classifies faces for a translucent or force-culled model. Its per-face draw
                // needs the fixed-function lighting state that applies to this bone, and the material path is the one
                // place that used to ask GL per face (`glIsEnabled`); carrying it in the segment instead keeps that
                // state decision where it is already made and removes the per-face query.
                MaterialDrawSession materialSession =
                    MaterialDrawSession.active();
                boolean emissive = bone.getRenderGlow() >= 0;
                if (emissive) {
                    // The fixed-function pipeline has no per-bone lightmap, so an emissive bone is simply drawn
                    // unlit. Upstream's level selects a light value there; what a pack asks for by calling
                    // bone_glow at all is that the bone glows, and the level is kept on the bone for a future
                    // brightness mapping rather than being invented here.
                    GlStateManager.disableLighting();
                    if (materialSession != null) {
                        materialSession.setLit(false);
                    }
                }

                // Per-bone texture override: if this bone specifies a different texture,
                // bind it before rendering cubes and restore afterwards.
                ITextureObject savedSource = materialSession == null ? null : materialSession.source();
                int savedMaterialTexture = materialSession == null ? -1 : materialSession.texture();
                int savedTextureId = -1;
                if (bone.textureOverride != null) {
                    savedTextureId = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
                    ITextureObject overrideTex = Minecraft.getMinecraft()
                        .getTextureManager().getTexture(bone.textureOverride);
                    if (overrideTex != null) {
                        flushBatch(builder);
                        GlStateManager.bindTexture(overrideTex.getGlTextureId());
                        if (materialSession != null) materialSession.setTexture(bone.textureOverride);
                    }
                }

                try {
                    renderBoneCubes(builder, bone, boneRed, boneGreen, boneBlue, boneAlpha);
                } finally {
                    if (emissive) {
                        GlStateManager.enableLighting();
                        if (materialSession != null) {
                            materialSession.setLit(true);
                        }
                    }
                    if (materialSession != null) materialSession.restoreSource(savedSource, savedMaterialTexture);
                    // Restore binding on failures too; otherwise the next bone can inherit an override.
                    if (savedTextureId >= 0) {
                        flushBatch(builder);
                        GlStateManager.bindTexture(savedTextureId);
                    }
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
     * {@link #renderRecursively} 的原生发射版本：逐骨骼算出与 Java 路径完全相同的矩阵，再把每根骨骼的
     * cube 组交给 Rust 模块（{@link NativeDrawGroups}）一次写完。
     *
     * <p>形状与 Java 路径逐条对齐：push/transformBone、hidden/零缩放跳过、逐骨骼颜色与发光（emissive 骨骼
     * 关光照）、子骨骼递归、每骨骼 try/catch 报错、finally pop。区别只在最内层：{@link #renderBoneCubes} 换成
     * {@link #renderBoneCubesNative}。</p>
     *
     * <p><b>回退粒度。</b>宿主覆盖骨骼（{@link #isBoneRenderOverriden}）、逐骨骼贴图覆盖、以及没有原生
     * {@code nativeBoneIndex} 的骨骼会让整棵子树交给原 Java 方法——它们的判定都在 push/transform 之前，
     * 原方法自己会重新 push/pop，所以不会出现"矩阵已变换一次、又交给 Java 再变换一次"。骨骼内部的分组在
     * {@link #renderBoneCubesNative} 里先验证后发射，验证失败同样整根骨骼回退。任何路径都不会留下
     * "一半顶点已提交、另一半走 Java"的批次。</p>
     *
     * @param geometry 模型的原生几何 handle（{@code GeoModel#nativeHandle}），调用方保证非 0
     */
    default void renderRecursivelyNative(Tessellator builder, T animatable, GeoBone bone, float red, float green,
        float blue, float alpha, long geometry) {
        if (bone == null) {
            return;
        }
        // 这些条件会让 Java 路径改变 GL 状态或走宿主回调，而且都在 push/transform 之前判定，所以整棵子树
        // 交给原方法。与 Java 路径的唯一区别是 isBoneRenderOverriden 的调用时机（这里在 transformBone 之前
        // 问；当前仓库没有任何覆盖实现，宿主回调也不依赖矩阵状态）。
        if (bone.textureOverride != null || bone.nativeBoneIndex < 0 || isBoneRenderOverriden(animatable, bone)) {
            renderRecursively(builder, animatable, bone, red, green, blue, alpha);
            return;
        }
        MATRIX_STACK.push();
        try {
            MATRIX_STACK.transformBone(bone);

            if (!bone.isHidden()) {
                if (isScaleInvisible(bone)) {
                    return;
                }

                float boneRed = red * bone.getRenderRed();
                float boneGreen = green * bone.getRenderGreen();
                float boneBlue = blue * bone.getRenderBlue();
                float boneAlpha = alpha * bone.getRenderAlpha();
                MaterialDrawSession materialSession = MaterialDrawSession.active();
                boolean emissive = bone.getRenderGlow() >= 0;
                if (emissive) {
                    // 与 renderRecursively 一致：固定管线没有逐骨骼光照图，发光骨骼就是不参与光照。
                    GlStateManager.disableLighting();
                    if (materialSession != null) {
                        materialSession.setLit(false);
                    }
                }

                try {
                    copyModelMatrix(MATRIX_STACK.getModelMatrix(), NATIVE_MODEL_MATRIX);
                    copyNormalMatrix(MATRIX_STACK.getNormalMatrix(), NATIVE_NORMAL_MATRIX);
                    int colorArgb = packNativeColor(boneRed, boneGreen, boneBlue, boneAlpha);
                    if (!renderBoneCubesNative(builder, bone, geometry, bone.nativeBoneIndex, colorArgb, boneRed,
                        boneGreen, boneBlue, boneAlpha)) {
                        renderBoneCubes(builder, bone, boneRed, boneGreen, boneBlue, boneAlpha);
                    }
                } finally {
                    if (emissive) {
                        GlStateManager.enableLighting();
                        if (materialSession != null) {
                            materialSession.setLit(true);
                        }
                    }
                }
            }

            if (!bone.childBonesAreHiddenToo()) {
                for (GeoBone childBone : bone.childBones) {
                    renderRecursivelyNative(builder, animatable, childBone, red, green, blue, alpha, geometry);
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
        MaterialDrawSession session =
            MaterialDrawSession.active();
        if (session != null && session.classified()) {
            for (GeoCube cube : bone.childCubes) renderCube(builder, cube, red, green, blue, alpha);
            return;
        }
        if (session != null) session.drain(builder);
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

    /**
     * {@link #renderBoneCubes} 的原生版本，GL 状态切换与 Java 方法逐条一致（负尺寸先 CULL_FRONT 一趟、
     * 正尺寸后一趟、两趟之间 flush）。
     *
     * <p>返回 {@code false} 表示这根骨骼一个顶点都没发射（或负尺寸一趟已画出、正尺寸一趟由本方法内部的
     * Java 组方法补齐，此时返回 {@code true}）；调用方随后对整根骨骼运行原 Java 方法。两趟轮廓在改 GL
     * 状态之前就把两组都验证一遍，所以"验证失败"不会留下半趟顶点。</p>
     */
    default boolean renderBoneCubesNative(Tessellator builder, GeoBone bone, long geometry, int boneIndex,
        int colorArgb, float red, float green, float blue, float alpha) {
        MaterialDrawSession session = MaterialDrawSession.active();
        if (session != null && session.classified()) {
            // 模型级门槛已经排除这一支；这里再挡一次，避免嵌套绘制悄悄绕过分类/排序路径。
            return false;
        }
        boolean anyNeg = false, anyPos = false;
        for (GeoCube cube : bone.childCubes) {
            if (cube.hasNegSize) anyNeg = true;
            else anyPos = true;
            if (anyNeg && anyPos) break;
        }
        if (!anyNeg) {
            // Single pass: all cubes are positive-size（与 renderBoneCubes 第一支相同）。
            return renderCubeGroupNative(builder, bone, 0, geometry, boneIndex, colorArgb);
        }

        int negativeInts = nativeCubeGroupInts(bone, -1);
        int positiveInts = nativeCubeGroupInts(bone, 1);
        if (negativeInts < 0 || positiveInts < 0) {
            return false;
        }

        // ── Pass 1: negative-size cubes (CULL_FRONT → back faces only) ──
        GeoStats.noteFlush();
        TessellatorBufferKeep.draw(builder);
        builder.startDrawing(GL11.GL_QUADS);
        if (!reserveNativeCapacity(builder, Math.max(negativeInts, positiveInts))) {
            return false;
        }
        GL11.glEnable(GL11.GL_CULL_FACE);
        GL11.glCullFace(GL11.GL_FRONT);
        boolean negativeOk = renderCubeGroupNative(builder, bone, -1, geometry, boneIndex, colorArgb);
        GeoStats.noteFlush();
        TessellatorBufferKeep.draw(builder);
        GL11.glCullFace(GL11.GL_BACK);
        GL11.glDisable(GL11.GL_CULL_FACE);
        builder.startDrawing(GL11.GL_QUADS);
        if (!negativeOk) {
            // 负尺寸一趟什么都没发射；原方法会完整重跑两趟，不会重复几何。
            return false;
        }

        // ── Pass 2: positive-size cubes (normal depth testing) ──
        boolean positiveOk = renderCubeGroupNative(builder, bone, 1, geometry, boneIndex, colorArgb);
        GeoStats.noteFlush();
        TessellatorBufferKeep.draw(builder);
        builder.startDrawing(GL11.GL_QUADS);
        if (!positiveOk) {
            // 负尺寸一趟已经画出去了，正尺寸这一趟用 Java 的组方法补齐：顺序与 renderBoneCubes 完全一致。
            renderCubeGroup(builder, bone, 1, red, green, blue, alpha);
            GeoStats.noteFlush();
            TessellatorBufferKeep.draw(builder);
            builder.startDrawing(GL11.GL_QUADS);
        }
        return true;
    }

    /**
     * {@link #renderCubeGroup} 的原生版本：一组同 flatness 的 cube 一次 JNI 调用写完，polygon offset 的
     * 切换点与 Java 相同（组首切、组末还原）。返回 {@code false} 表示这组一个顶点都没写。
     */
    default boolean renderCubeGroupNative(Tessellator builder, GeoBone bone, int sign, long geometry, int boneIndex,
        int colorArgb) {
        int ints = nativeCubeGroupInts(bone, sign);
        if (ints < 0) {
            return false;
        }
        if (ints == 0) {
            // 这个 sign 下没有 cube：Java 的循环也什么都不做。
            return true;
        }
        boolean flat = nativeCubeGroupFlat(bone, sign);
        boolean offsetSet = false;
        try {
            if (flat) {
                // Java renderCubeGroup：flatness 变化先 flush 再切 polygon offset。整组同一 flatness，所以
                // 切一次；组末尾在 finally 里 flush + 还原（与 Java 的局部 offset 变量同形）。
                flushBatch(builder);
                setCubePolygonOffset(true);
                offsetSet = true;
            }
            int offset = TessellatorRawAccess.rawBufferIndex(builder);
            if (offset < 0) {
                return false;
            }
            int[] buffer = TessellatorRawAccess.rawBuffer(builder);
            if (buffer == null || offset > buffer.length) {
                // 共享 Tessellator 的 rawBuffer 在本帧还没被任何绘制分配过：按原版初值分配，而不是回退。
                offset = 0;
                if (!reserveNativeCapacity(builder, ints)) {
                    return false;
                }
                buffer = TessellatorRawAccess.rawBuffer(builder);
                if (buffer == null) {
                    return false;
                }
            }
            // 与 Java 的 flushBatchIfNearlyFull 同一容量策略：先 flush，再让容量最多长到 0x20000
            // （TessellatorBufferKeep 的缩回门槛），避免每帧 copyOf 扩容与缩回。
            if (offset + ints > TessellatorBufferKeep.SHRINK_THRESHOLD - TessellatorBufferKeep.BATCH_RESERVE) {
                flushBatch(builder);
                offset = 0;
                buffer = TessellatorRawAccess.rawBuffer(builder);
                if (buffer == null) {
                    return false;
                }
            }
            if (ints > buffer.length - offset) {
                // 与 Java 的 addVertex 一样先扩容（扩容保留已写入的顶点）；只有不可扩容时才 flush 后重来。
                if (TessellatorRawAccess.ensureCapacity(builder, offset + ints)) {
                    buffer = TessellatorRawAccess.rawBuffer(builder);
                    if (buffer == null) {
                        return false;
                    }
                } else {
                    flushBatch(builder);
                    offset = 0;
                    if (!reserveNativeCapacity(builder, ints)) {
                        return false;
                    }
                    buffer = TessellatorRawAccess.rawBuffer(builder);
                    if (buffer == null) {
                        return false;
                    }
                }
            }
            int vertices = NativeDrawGroups.emitAt(geometry, boneIndex, NATIVE_MODEL_MATRIX, NATIVE_NORMAL_MATRIX,
                colorArgb, cubeFilterForSign(sign), buffer, offset);
            if (vertices < 0) {
                return false;
            }
            TessellatorRawAccess.advance(builder, vertices);
            // Java 在 renderCube 里按 cube.quads.length * 4 记诊断数（含空 face 槽位），原生路径保持同一个数。
            for (GeoCube cube : bone.childCubes) {
                if (sign < 0 && !cube.hasNegSize || sign > 0 && cube.hasNegSize) continue;
                GeoStats.noteCube(cube.quads.length * 4);
            }
            return true;
        } finally {
            if (offsetSet) {
                flushBatch(builder);
                setCubePolygonOffset(false);
            }
        }
    }

    /**
     * 一组 cube 交给原生发射会写多少个 int，或 {@code -1} 表示这组必须走 Java 循环。
     *
     * <p>返回 -1 的唯一情况是"这组选中的 cube 里既有平面（polygon offset）又有非平面"——此时 Java 会在组
     * 中间切状态、flush 并 draw，一次 JNI 调用表达不了，整组交回 Java。这个判定与
     * {@code renderCubeGroup} 的 {@code flat != offset} 分支同源。</p>
     */
    static int nativeCubeGroupInts(GeoBone bone, int sign) {
        int ints = 0;
        boolean flat = false;
        boolean first = true;
        for (GeoCube cube : bone.childCubes) {
            if (sign < 0 && !cube.hasNegSize || sign > 0 && cube.hasNegSize) continue;
            boolean cubeFlat = isFlatCube(cube);
            if (first) {
                flat = cubeFlat;
                first = false;
            } else if (flat != cubeFlat) {
                return -1;
            }
            for (GeoQuad quad : cube.quads) {
                if (quad == null) continue;
                ints += quad.vertices.length * 8;
                if (ints < 0) {
                    return -1;
                }
            }
        }
        return ints;
    }

    /** 一组 cube 的统一 flatness（调用前 {@link #nativeCubeGroupInts} 必须已返回正数）。 */
    static boolean nativeCubeGroupFlat(GeoBone bone, int sign) {
        for (GeoCube cube : bone.childCubes) {
            if (sign < 0 && !cube.hasNegSize || sign > 0 && cube.hasNegSize) continue;
            return isFlatCube(cube);
        }
        return false;
    }

    /** {@code renderCubeGroup} 的 sign（0 / -1 / +1）到原生 filter（0 / 1 / 2）的映射。 */
    static int cubeFilterForSign(int sign) {
        if (sign < 0) {
            return NativeDrawGroups.CUBE_FILTER_NEGATIVE;
        }
        if (sign > 0) {
            return NativeDrawGroups.CUBE_FILTER_POSITIVE;
        }
        return NativeDrawGroups.CUBE_FILTER_ALL;
    }

    /** {@code renderCubeGroup} 里的 flat 判定，原生端用同一个条件分 flat 方法。 */
    static boolean isFlatCube(GeoCube cube) {
        return !cube.mesh && (cube.size.x == 0f || cube.size.y == 0f || cube.size.z == 0f);
    }

    /**
     * 模型级门槛：有任一条不满足就整帧走原 Java 循环（连窗口都不会开）。通过之后每根骨骼/每个分组在发射
     * 前还有各自的保守判定。
     *
     * <p><b>nativeHandle 的含义。</b>{@code GeoModel#nativeHandle} 是 {@code transient} 的，只有真正由
     * {@code NativeGeometry.bake} + {@code NativeGeometryReader.read} 物化出来的模型才非 0；从烘焙几何缓存
     * 反序列化回来的模型该字段是 0（没有 native 几何可用），因此一律留在 Java 路径。handle 的生命周期由
     * 几何释放路径管理，本帧渲染期间非 0 即有效。</p>
     *
     * <p><b>刻意不接的入口。</b>只有 {@link #render} 走这条快速路径。{@link #renderMaterialRoot}（第一人称
     * 手臂）、{@code RenderHurtColor.render}，以及直接调用 {@link #renderRecursively} 的
     * {@code ArrowProjectileRenderer}/{@code VehicleRenderer} 都保持原 Java 路径：它们各自叠加了额外的
     * 外层矩阵与 GL 状态（手臂镜像、受伤红叠色、投射物/载具的自定义 transform），本里程碑不把"gate 之外
     * 的调用方"也接进来。</p>
     */
    default boolean canRenderNative(GeoModel model, MaterialDrawSession session, Tessellator tessellator) {
        if (model == null || model.nativeHandle == 0L) {
            // 只有原生烘焙物化的 GeoModel 才携带 handle；缓存反序列化、Java 烘焙的模型都是 0。
            return false;
        }
        if (!NativeDrawGroups.isUsable() || !TessellatorRawAccess.available()) {
            return false;
        }
        if (session != null && session.classified()) {
            // 半透明 / forceCulling 模型要逐面分类、排序与 CPU 剔除，仍由 Java 路径负责。
            return false;
        }
        if (!TessellatorRawAccess.offsetsZero(tessellator)) {
            // 原版 setTranslation 的非零偏移做不到逐位复现（(float)((double)v + offset)）。
            return false;
        }
        return !hasTextureOverrideInPose(model);
    }

    /** 当前姿态里任何骨骼带贴图覆盖都关闭原生路径：覆盖会切纹理并 flush，属于 Java 的 GL 决策。 */
    static boolean hasTextureOverrideInPose(GeoModel model) {
        for (GeoBone bone : model.topLevelBones) {
            if (hasTextureOverrideInPose(bone)) {
                return true;
            }
        }
        return false;
    }

    static boolean hasTextureOverrideInPose(GeoBone bone) {
        if (bone == null) {
            return false;
        }
        if (bone.textureOverride != null) {
            return true;
        }
        for (GeoBone childBone : bone.childBones) {
            if (hasTextureOverrideInPose(childBone)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 保证当前 raw 缓冲（在必要的一次 flush 之后）能装下 {@code ints} 个 int。扩容（以及"缓冲还没分配"
     * 时的分配）只走 {@link TessellatorRawAccess#ensureCapacity}（即 {@code rawBufferSize} 可解析时）；
     * 不可扩容时返回现有容量是否够用，不够就由调用方回退 Java。
     */
    static boolean reserveNativeCapacity(Tessellator builder, int ints) {
        if (ints <= 0) {
            return true;
        }
        int[] buffer = TessellatorRawAccess.rawBuffer(builder);
        if (buffer != null && ints <= buffer.length) {
            return true;
        }
        return TessellatorRawAccess.ensureCapacity(builder, ints) && TessellatorRawAccess.rawBuffer(builder) != null;
    }

    /** {@code MatrixStack} 的列主序 Matrix4f 拷成原生入口的 float[16]（m00..m33，平移在 3/7/11）。 */
    static void copyModelMatrix(Matrix4f matrix, float[] out) {
        out[0] = matrix.m00;
        out[1] = matrix.m01;
        out[2] = matrix.m02;
        out[3] = matrix.m03;
        out[4] = matrix.m10;
        out[5] = matrix.m11;
        out[6] = matrix.m12;
        out[7] = matrix.m13;
        out[8] = matrix.m20;
        out[9] = matrix.m21;
        out[10] = matrix.m22;
        out[11] = matrix.m23;
        out[12] = matrix.m30;
        out[13] = matrix.m31;
        out[14] = matrix.m32;
        out[15] = matrix.m33;
    }

    /** {@code Matrix3f} 拷成原生入口的 float[9]（m00..m22）。 */
    static void copyNormalMatrix(Matrix3f matrix, float[] out) {
        out[0] = matrix.m00;
        out[1] = matrix.m01;
        out[2] = matrix.m02;
        out[3] = matrix.m10;
        out[4] = matrix.m11;
        out[5] = matrix.m12;
        out[6] = matrix.m20;
        out[7] = matrix.m21;
        out[8] = matrix.m22;
    }

    /**
     * 原生通道的逐骨骼颜色：与 {@code Tessellator.setColorRGBA_F} 完全相同的量化和钳制，然后按 Java
     * ARGB 顺序打包；Rust 侧只做字节重排（对应上游 {@code NativeRenderer.packColor}）。
     */
    static int packNativeColor(float red, float green, float blue, float alpha) {
        return colorChannel(alpha) << 24 | colorChannel(red) << 16 | colorChannel(green) << 8 | colorChannel(blue);
    }

    /** {@code Tessellator.setColorRGBA_F} 的 {@code (int)(value * 255.0F)} 截断与 0..255 钳制。 */
    static int colorChannel(float value) {
        int channel = (int) (value * 255.0F);
        if (channel < 0) {
            return 0;
        }
        return channel > 255 ? 255 : channel;
    }

    /** Direct first-arm root uses the same material owner after its outer alignment/mirror is applied. */
    default void renderMaterialRoot(T animatable, GeoModel model, GeoBone root, ResourceLocation texture, YsmRenderType type) {
        Tessellator builder = Tessellator.instance;
        builder.startDrawing(GL11.GL_QUADS);
        MaterialDrawSession session =
            MaterialDrawSession.begin(
                model != null && model.properties != null && Boolean.TRUE.equals(model.properties.getForceCulling()), type);
        try { renderRecursively(builder, animatable, root, 1, 1, 1, 1); }
        finally { session.close(builder); }
        software.bernie.geckolib3.util.TessellatorBufferKeep.draw(builder);
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
        MaterialDrawSession session =
            MaterialDrawSession.active();
        if (session != null && session.collect(cube, model, normalMatrix, red, green, blue, alpha)) return;

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

package software.bernie.geckolib3.core.molang;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.vecmath.Matrix4f;

import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.ResourceLocation;

import software.bernie.geckolib3.core.processor.AnimationProcessor;
import software.bernie.geckolib3.core.processor.IBone;
import software.bernie.geckolib3.geo.render.built.GeoBone;
import software.bernie.geckolib3.util.MatrixStack;

/**
 * Per-frame MoLang physics/variable scope.
 * <p>
 * The host mod opens a scope with {@link #begin} before a model's animation is evaluated and closes it with
 * {@link #end}. While a scope is open the {@code ysm.first_order}/{@code ysm.second_order} functions, the
 * {@code ysm.bone_*} query functions and {@code v.*} variable reads resolve against it.
 */
public final class MolangPhysicsRuntime {

    private static final ThreadLocal<FrameContext> CURRENT = new ThreadLocal<>();
    private static final Map<ScopeKey, ScopeState> STATES = new ConcurrentHashMap<>();

    /**
     * 弹射物（子模型）骨骼作用域：只提供"名字 → 骨骼"这一件事，供
     * {@code ysm.bone_pivot_abs()} / {@code bone_position|rotation|scale()} 使用。
     *
     * <p>弹射物的 GeoModel 不经过玩家的 {@link AnimationProcessor} 注册，所以
     * {@link #bone(int)} 原来对它一律返回 null：某弹射物动画的时间轴里
     * {@code ysm.bone_pivot_abs('Arrow')} 恒等于 0，粒子只能落在实体原点（而不是模型上算出来的位置）。
     * 这里用一份临时的名字表补上——弹射物渲染期间由
     * {@code ArrowProjectileRenderer} 调用 {@link #beginProjectileBones} 建立。</p>
     *
     * <p>刻意不复用 {@link FrameContext}：弹射物没有玩家侧的物理状态与变量作用域，
     * 借用 FrameContext 会让 {@code first_order} 之类的函数去读空的 {@code context.state}。</p>
     *
     * <p>移植注: 参考分支把该名字表当"玩家上下文查不到骨骼"时的兜底，而 {@link #bone(int)} 在这里
     * **优先**查它；原因（骨骼重名会让玩家那根骨骼胜出）写在该方法处。</p>
     */
    private static Map<String, IBone> projectileBones;

    /** 渲染路径骨骼绝对位置追踪（bone_pivot_abs 与几何渲染走同一矩阵路径）。
     *  每帧在 MatrixStack.transformBone 里按 模型+骨名 记录骨链累计后的完整 4×4 矩阵
     *  （blocks，含模型缩放，预 yaw / 预玩家位移）。bone_pivot_abs 用该矩阵计算
     *  枢轴点的世界位置（M×pivot），避免骨骼自身缩放/旋转污染平移列（如一个火把 locator
     *  的 scale [1.25, 2.5, 1.5] 会让平移列 z 虚增约 2 倍）。 */
    private static final Map<String, float[]> CAPTURED_BONE_MATRIX = new java.util.HashMap<>();
    private static boolean trackingEnabled = false;
    private static ResourceLocation trackingModelId = null;
    private static float trackScaleX = 1.0F;
    private static float trackScaleY = 1.0F;
    private static float trackScaleZ = 1.0F;

    static {
        MatrixStack.boneTransformSink = MolangPhysicsRuntime::captureBoneTransform;
    }

    private MolangPhysicsRuntime() {}

    public static void begin(IMolangPhysicsScope animatable, double renderTicks, AnimationProcessor<?> processor) {
        if (animatable == null || processor == null) {
            CURRENT.remove();
            return;
        }
        EntityLivingBase entity = animatable.getMolangEntity();
        ScopeKey key = ScopeKey.from(entity, animatable.getMolangModelId(), animatable.getMolangAnimationId());
        ScopeState state = STATES.computeIfAbsent(key, ignored -> new ScopeState());
        state.physics.update(renderTicks);
        applyRemoteVariables(entity, state);
        applyScopeVariables(animatable, state);
        // Before this frame's expressions run, so the bone_color / bone_transparency / bone_glow calls that follow
        // start from white, opaque and not emissive rather than from whatever the previous frame left behind.
        resetBoneRenderState(processor);
        CURRENT.set(new FrameContext(animatable.getMolangModelId(), state, processor));
    }

    /**
     * Seeds the scope with the entity's remote animation variables before the animation is evaluated, so a
     * model can read {@code v.roaming.<name>} even when the animation file never assigns it.
     */
    private static void applyRemoteVariables(EntityLivingBase entity, ScopeState state) {
        Map<String, Double> remote = RemoteAnimationVariables.get(entity);
        if (remote == null || remote.isEmpty()) {
            return;
        }
        state.variables.putAll(remote);
    }

    /**
     * Seeds the scope with the variables the animatable supplies for itself, after the entity's remote variables so
     * a host can override its own values. See {@link IMolangPhysicsScope#getMolangVariables()}.
     */
    private static void applyScopeVariables(IMolangPhysicsScope animatable, ScopeState state) {
        Map<String, Double> own = animatable.getMolangVariables();
        if (own == null || own.isEmpty()) {
            return;
        }
        // Canonicalised on the way in, because the scope is read by the parser under the canonical name: a host that
        // supplies "v.roaming.C" would otherwise be unreachable from an expression that says "v.roaming.C", since the
        // parser lower-cases the expression before looking anything up.
        for (Map.Entry<String, Double> entry : own.entrySet()) {
            state.variables.put(MolangParser.canonicalVariableName(entry.getKey()), entry.getValue());
        }
    }

    /**
     * 建立弹射物（子模型）骨骼作用域：把几何里的骨骼按名字登记，供
     * {@code ysm.bone_pivot_abs(...)} 等骨骼函数在弹射物动画的时间轴里解析。
     *
     * <p>由 {@code ArrowProjectileRenderer} 在一次弹射物渲染 pass 内成对调用
     * （{@link #beginProjectileBones} / {@link #endProjectileBones}），
     * 递归收集所有层级的骨骼；结束时会恢复调用前的作用域（弹射物渲染可能发生在
     * 玩家渲染之外，但也可能是嵌套的，所以用局部变量保存而不是简单置 null）。</p>
     */
    public static Map<String, IBone> beginProjectileBones(
        java.util.List<GeoBone> topLevelBones) {
        Map<String, IBone> previous = projectileBones;
        Map<String, IBone> bones = new java.util.HashMap<>();
        collectBones(topLevelBones, bones);
        projectileBones = bones;
        return previous;
    }

    /** 结束弹射物骨骼作用域，恢复 {@link #beginProjectileBones} 之前的那一份。 */
    public static void endProjectileBones(Map<String, IBone> previous) {
        projectileBones = previous;
    }

    private static void collectBones(java.util.List<GeoBone> bones, Map<String, IBone> out) {
        if (bones == null) {
            return;
        }
        for (GeoBone bone : bones) {
            if (bone == null) {
                continue;
            }
            if (bone.name != null) {
                out.put(bone.name, bone);
            }
            collectBones(bone.childBones, out);
        }
    }

    public static void end() {
        CURRENT.remove();
    }

    public static void clear() {
        STATES.clear();
        // 追踪矩阵按"模型主 id + 骨名"缓存：资源重载 / 登出后旧模型的矩阵必须一起丢掉，
        // 否则刷新出来的模型首帧会先读到上一个模型的骨骼位置（SOURCE clear() 同样清它）。
        CAPTURED_BONE_MATRIX.clear();
        CURRENT.remove();
    }

    public static double firstOrder(int nameId, double input, double response) {
        if (nameId == MolangStringPool.EMPTY_ID) {
            return 0.0D;
        }
        FrameContext context = CURRENT.get();
        if (context == null) {
            return input;
        }
        return context.state.physics.firstOrder(nameId, input, response);
    }

    public static double secondOrder(int nameId, double input, double frequency, double coefficient, double response) {
        if (nameId == MolangStringPool.EMPTY_ID) {
            return 0.0D;
        }
        FrameContext context = CURRENT.get();
        if (context == null) {
            return input;
        }
        return context.state.physics.secondOrder(nameId, input, frequency, coefficient, response);
    }

    public static double getVariable(String name, double fallback) {
        FrameContext context = CURRENT.get();
        if (context == null) {
            return fallback;
        }
        Double value = context.state.variables.get(name);
        return value == null ? fallback : value;
    }

    public static boolean setVariable(String name, double value) {
        FrameContext context = CURRENT.get();
        if (context == null) {
            return false;
        }
        context.state.variables.put(name, value);
        return true;
    }

    public static double boneRotation(int nameId, char axis) {
        IBone bone = bone(nameId);
        if (bone == null) {
            return 0.0D;
        }
        if (axis == 'x') {
            return -Math.toDegrees(bone.getRotationX());
        }
        if (axis == 'y') {
            return -Math.toDegrees(bone.getRotationY());
        }
        return Math.toDegrees(bone.getRotationZ());
    }

    public static double bonePosition(int nameId, char axis) {
        IBone bone = bone(nameId);
        if (bone == null) {
            return 0.0D;
        }
        if (axis == 'x') {
            return bone.getPositionX();
        }
        if (axis == 'y') {
            return bone.getPositionY();
        }
        return bone.getPositionZ();
    }

    /** 开启/关闭渲染期骨骼变换追踪（CustomPlayerRenderer 每帧包裹渲染调用）。
     *  开启时 MatrixStack.transformBone 记录每个骨的完整累计矩阵，供 bone_pivot_abs 读取。
     *
     * @param scaleX/scaleY/scaleZ 模型渲染缩放（renderEarly 应用的 width/height/width）
     * @param modelId 当前渲染的模型主 id（用于隔离不同模型的骨骼） */
    public static void setBoneTracking(boolean enabled, float scaleX, float scaleY, float scaleZ,
        ResourceLocation modelId) {
        trackingEnabled = enabled;
        if (enabled) {
            trackScaleX = scaleX;
            trackScaleY = scaleY;
            trackScaleZ = scaleZ;
            trackingModelId = modelId;
        } else {
            trackingModelId = null;
        }
    }

    /** MatrixStack 每骨渲染后回调：记录骨链累计后的完整矩阵
     *  （blocks，含模型缩放，预 yaw/预玩家位移）。回调须同步复制（Matrix4f 会被复用）。 */
    private static void captureBoneTransform(GeoBone bone, javax.vecmath.Matrix4f mat,
        float pivotX, float pivotY, float pivotZ) {
        if (!trackingEnabled || trackingModelId == null || bone == null || bone.getName() == null) {
            return;
        }
        String key = trackingModelId + "::" + bone.getName();
        float[] v = CAPTURED_BONE_MATRIX.get(key);
        if (v == null) {
            v = new float[16];
            CAPTURED_BONE_MATRIX.put(key, v);
        }
        v[0] = mat.m00;
        v[1] = mat.m01;
        v[2] = mat.m02;
        v[3] = mat.m03;
        v[4] = mat.m10;
        v[5] = mat.m11;
        v[6] = mat.m12;
        v[7] = mat.m13;
        v[8] = mat.m20;
        v[9] = mat.m21;
        v[10] = mat.m22;
        v[11] = mat.m23;
        v[12] = mat.m30;
        v[13] = mat.m31;
        v[14] = mat.m32;
        v[15] = mat.m33;
    }

    /** 骨骼绝对枢轴（模型单位，16 单位 = 1 格；OpenYSM bone_pivot_abs 语义）。
     *  优先读取渲染路径追踪到的骨骼矩阵（与几何渲染同一矩阵路径）；
     *  未追踪到（首帧/预览等）时回退为沿父链矩阵重算。
     *  <p>三轴都用 M×pivot（枢轴点世界位置）——平移列会被目标骨骼自身 scale/rotation
     *  污染（如某个火把 locator 的 scale [1.25,2.5,1.5]：平移列 z 虚增~2 倍、
     *  x/y 抖动），M×pivot 稳定且正确。
     *  <p><b>X 轴取负</b>：GeoBuilder 对 pivot.x/cube.x 取负（GeckoLib 内部约定），
     *  捕获矩阵在 GeoBone 空间（左手 X 为负）；而模型粒子公式
     *  {@code particle(..., bone_pivot_abs(...).x, ...)} 期望的 X 是渲染帧
     *  （无 GL 镜像）的坐标（左手为正），因此 x 轴结果需取反。z 轴由模型公式自带的
     *  {@code -bone_pivot_abs(...).z} 处理，y 轴无需取反。 */
    public static double bonePivot(int nameId, char axis) {
        IBone bone = bone(nameId);
        if (bone == null) {
            return 0.0D;
        }
        double matrixResult = matrixBonePivot(bone, axis);
        if (projectileBones != null) {
            // 弹射物作用域没有渲染期矩阵捕获，直接用父链重算；同时**避开**捕获路径：
            // 捕获表是按"当前玩家模型 + 骨骼名"存的，弹射物骨骼名可能和玩家模型重名
            // （例如都叫 Arrow），走了捕获就会拿到玩家那条骨骼的位置。
            return matrixResult;
        }
        if (trackingEnabled && trackingModelId != null) {
            String boneName = MolangStringPool.get(nameId);
            float[] m = boneName == null ? null
                : CAPTURED_BONE_MATRIX.get(trackingModelId + "::" + boneName);
            if (m != null) {
                // 枢轴点（blocks，与捕获矩阵同单位）
                float px = bone.getPivotX() / 16f;
                float py = bone.getPivotY() / 16f;
                float pz = bone.getPivotZ() / 16f;
                // 枢轴点的世界位置 = M × pivot（blocks），不受骨骼自身缩放/旋转污染。
                double wx = m[3] + (double) m[0] * px + (double) m[1] * py + (double) m[2] * pz;
                double wy = m[7] + (double) m[4] * px + (double) m[5] * py + (double) m[6] * pz;
                double wz = m[11] + (double) m[8] * px + (double) m[9] * py + (double) m[10] * pz;
                // GeoBone 空间 X 取负（GeoBuilder 约定）→ 渲染帧 X；模型公式 x 不取负。
                double capturedResult;
                if (axis == 'x') {
                    capturedResult = -wx * (16.0D / trackScaleX);
                } else if (axis == 'y') {
                    capturedResult = wy * (16.0D / trackScaleY);
                } else {
                    capturedResult = wz * (16.0D / trackScaleZ);
                }
                // 移植注: SOURCE 在这里还有 Config.DEBUG_PARTICLE 诊断日志
                // （打印 MxPivot 与 matrixFallback 两个候选值）。TARGET 没有对应的
                // 粒子调试开关（引擎侧只有 GeckoLib.geoStatsEnabled，它是几何提交计数，
                // 语义不同），因此该块被删除，上面的推导注释全部保留。
                return capturedResult;
            }
        }
        return matrixResult;
    }

    /** 沿 GeoBone 父链矩阵重算绝对枢轴（无渲染追踪时的回退，语义同 bonePivot：
     *  三轴都用 M×pivot；x 轴取负（GeoBone 空间 → 渲染帧，见 bonePivot 注释）。 */
    private static double matrixBonePivot(IBone bone, char axis) {
        if (!(bone instanceof GeoBone)) {
            // VirtualBone 等无父链/几何：退化为本地枢轴（GeoBone 空间，x 取负）
            if (axis == 'x') {
                return -bone.getPivotX();
            }
            if (axis == 'y') {
                return bone.getPivotY();
            }
            return bone.getPivotZ();
        }
        GeoBone geo = (GeoBone) bone;
        // 收集 root→target 骨链
        java.util.ArrayList<GeoBone> chain = new java.util.ArrayList<>();
        for (GeoBone b = geo; b != null; b = b.parent) {
            chain.add(b);
        }
        java.util.Collections.reverse(chain);
        Matrix4f acc = new Matrix4f();
        acc.setIdentity();
        Matrix4f tmp = new Matrix4f();
        for (GeoBone b : chain) {
            appendBoneTransform(acc, tmp, b);
        }
        // 枢轴点的世界位置 = M × pivot（模型单位），不受骨骼自身缩放/旋转污染。
        float px = geo.getPivotX();
        float py = geo.getPivotY();
        float pz = geo.getPivotZ();
        double wx = acc.m03 + (double) acc.m00 * px + (double) acc.m01 * py + (double) acc.m02 * pz;
        double wy = acc.m13 + (double) acc.m10 * px + (double) acc.m11 * py + (double) acc.m12 * pz;
        double wz = acc.m23 + (double) acc.m20 * px + (double) acc.m21 * py + (double) acc.m22 * pz;
        // x 轴取负（GeoBone 空间 → 渲染帧）；z 由模型公式自带取负，y 不变。
        if (axis == 'x') {
            return -wx;
        }
        if (axis == 'y') {
            return wy;
        }
        return wz;
    }

    /** acc = acc × M_bone（模型单位，与渲染 transformBone 相同的组合顺序）。 */
    private static void appendBoneTransform(Matrix4f acc, Matrix4f tmp, GeoBone b) {
        float px = b.getPivotX();
        float py = b.getPivotY();
        float pz = b.getPivotZ();
        float tx = -b.getPositionX();
        float ty = b.getPositionY();
        float tz = b.getPositionZ();
        float sx = b.getScaleX();
        float sy = b.getScaleY();
        float sz = b.getScaleZ();
        float rx = b.getRotationX();
        float ry = b.getRotationY();
        float rz = b.getRotationZ();
        // T(pos)
        tmp.setIdentity();
        tmp.m03 = tx;
        tmp.m13 = ty;
        tmp.m23 = tz;
        acc.mul(tmp);
        // T(pivot)
        tmp.setIdentity();
        tmp.m03 = px;
        tmp.m13 = py;
        tmp.m23 = pz;
        acc.mul(tmp);
        // Rz × Ry × Rx
        if (rz != 0.0F) {
            tmp.setIdentity();
            tmp.rotZ(rz);
            acc.mul(tmp);
        }
        if (ry != 0.0F) {
            tmp.setIdentity();
            tmp.rotY(ry);
            acc.mul(tmp);
        }
        if (rx != 0.0F) {
            tmp.setIdentity();
            tmp.rotX(rx);
            acc.mul(tmp);
        }
        // S
        tmp.setIdentity();
        tmp.m00 = sx;
        tmp.m11 = sy;
        tmp.m22 = sz;
        acc.mul(tmp);
        // T(-pivot)
        tmp.setIdentity();
        tmp.m03 = -px;
        tmp.m13 = -py;
        tmp.m23 = -pz;
        acc.mul(tmp);
    }

    public static double boneScale(int nameId, char axis) {
        IBone bone = bone(nameId);
        if (bone == null) {
            return axis == 'x' || axis == 'y' || axis == 'z' ? 1.0D : 0.0D;
        }
        if (axis == 'x') {
            return bone.getScaleX();
        }
        if (axis == 'y') {
            return bone.getScaleY();
        }
        return bone.getScaleZ();
    }

    /**
     * {@code bone_color(bone, red, green, blue)} - upstream rounds and clamps each channel to 0..255 before storing
     * it ({@code client/animation/molang/functions/BoneRenderFunction.java:52-66}). A bone this model does not have
     * is ignored, which is upstream's behaviour too.
     */
    public static void boneColor(int nameId, double red, double green, double blue) {
        GeoBone bone = renderBone(nameId);
        if (bone == null) {
            return;
        }
        bone.setRenderColor(channel(red), channel(green), channel(blue));
    }

    /** {@code bone_transparency(bone, alpha)} - 0..255, clamped the way upstream clamps it. */
    public static void boneTransparency(int nameId, double alpha) {
        GeoBone bone = renderBone(nameId);
        if (bone != null) {
            bone.setRenderTransparency(channel(alpha));
        }
    }

    /** {@code bone_glow(bone, level)} - -1 for "not emissive" or 0..15, upstream's clamp range. */
    public static void boneGlow(int nameId, double level) {
        GeoBone bone = renderBone(nameId);
        if (bone != null) {
            bone.setRenderGlow((int) Math.max(-1.0D, Math.min(15.0D, Math.round(level))));
        }
    }

    /**
     * Clears the per-bone render state before a frame's expressions run, so a bone that stops calling
     * {@code bone_glow} (or whose model changed) goes back to lit, opaque and white. Upstream gets this for free by
     * rebuilding its attribute array every frame; the port's bones are cached per model, so it has to be explicit.
     */
    public static void resetBoneRenderState(AnimationProcessor<?> processor) {
        List<IBone> bones = processor.getBones();
        for (int i = 0; i < bones.size(); i++) {
            IBone bone = bones.get(i);
            if (bone instanceof GeoBone geoBone) {
                geoBone.resetRenderState();
            }
        }
    }

    private static int channel(double value) {
        return (int) Math.max(0.0D, Math.min(255.0D, Math.round(value)));
    }

    private static GeoBone renderBone(int nameId) {
        IBone bone = bone(nameId);
        return bone instanceof GeoBone geoBone ? geoBone : null;
    }

    private static IBone bone(int nameId) {
        if (nameId == MolangStringPool.EMPTY_ID) {
            return null;
        }
        String boneName = MolangStringPool.get(nameId);
        if (boneName == null) {
            return null;
        }
        // 弹射物（子模型）作用域**优先**：弹射物的 GeoModel 不注册进玩家 processor，只能查这份名字表。
        //
        // 这个顺序有意与参考分支不同（它先查 processor、该表只作兜底），理由取自参考分支自己的注释 ——
        // 其 bonePivot 写着：捕获表按"当前玩家模型 + 骨骼名"存，而弹射物骨骼名可能与玩家模型重名
        // （例如都叫 Arrow），走了玩家那条路径就会拿到**玩家那根骨骼**的位置。参考分支只在捕获路径上
        // 挡住了这一点，bone() 仍先查 processor，于是玩家帧上下文还开着时 bone_pivot_abs 会给出玩家的坐标。
        // 先查这张表把那个口子一并堵上：该表只在弹射物渲染 pass 内存在（begin/endProjectileBones 成对），
        // 所以玩家渲染不受影响。
        Map<String, IBone> bones = projectileBones;
        if (bones != null) {
            IBone projectileBone = bones.get(boneName);
            if (projectileBone != null) {
                return projectileBone;
            }
        }
        FrameContext context = CURRENT.get();
        return context == null ? null : context.processor.getBone(boneName);
    }

    private static final class FrameContext {
        private final ResourceLocation modelId;
        private final ScopeState state;
        private final AnimationProcessor<?> processor;

        private FrameContext(ResourceLocation modelId, ScopeState state, AnimationProcessor<?> processor) {
            this.modelId = modelId;
            this.state = state;
            this.processor = processor;
        }
    }

    /** 当前渲染帧所属的模型 id（null 表示无活动帧上下文）。供 ?? 运算符等
     *  需要按模型判断"用户显式设置"的场景使用。
     *  <p>移植注: TARGET 原来的 {@link FrameContext} 不保存模型 id，这里把 {@link #begin}
     *  本来就收到的 {@link IMolangPhysicsScope#getMolangModelId()} 一并存进去——它正是宿主
     *  用来查自己模型的 id（YSMU 侧 {@code CustomPlayerEntity.getMolangModelId()} 返回
     *  {@code getMainModel()}，与 SOURCE 的 {@code animatable.getMainModel()} 是同一个值），
     *  没有引入新的状态来源。 */
    public static ResourceLocation getCurrentModelId() {
        FrameContext context = CURRENT.get();
        return context == null ? null : context.modelId;
    }

    private static final class ScopeState {
        private final MolangPhysicsState physics = new MolangPhysicsState();
        private final Map<String, Double> variables = new ConcurrentHashMap<>();
    }

    /**
     * What one scope belongs to: the owning entity, plus the model and animation being evaluated.
     * <p>
     * Every component is compared <em>by value</em>. A detached preview has no entity and is handed freshly built id
     * objects, so any identity-based component would give it a different key - and therefore a different, empty scope -
     * from one frame to the next, discarding the variables the pack's own scripts assigned. YSMU's GUI tiles do exactly
     * that: {@code RenderUtil.renderModel} calls {@code setMainModel(ModelIdUtil.getMainId(modelId))} on every frame
     * and {@code getMainId} returns a {@code new ResourceLocation}. A world entity never saw the problem because its
     * key is the player's UUID.
     */
    private static final class ScopeKey {
        private final UUID entityId;
        private final ResourceLocation modelId;
        private final ResourceLocation animationId;

        private ScopeKey(UUID entityId, ResourceLocation modelId, ResourceLocation animationId) {
            this.entityId = entityId;
            this.modelId = modelId;
            this.animationId = animationId;
        }

        private static ScopeKey from(EntityLivingBase entity, ResourceLocation modelId,
            ResourceLocation animationId) {
            return new ScopeKey(entity == null ? null : entity.getUniqueID(), modelId, animationId);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof ScopeKey)) {
                return false;
            }
            ScopeKey other = (ScopeKey) obj;
            if (entityId == null ? other.entityId != null : !entityId.equals(other.entityId)) {
                return false;
            }
            if (modelId == null ? other.modelId != null : !modelId.equals(other.modelId)) {
                return false;
            }
            return animationId == null ? other.animationId == null : animationId.equals(other.animationId);
        }

        @Override
        public int hashCode() {
            int result = entityId == null ? 0 : entityId.hashCode();
            result = 31 * result + (modelId == null ? 0 : modelId.hashCode());
            result = 31 * result + (animationId == null ? 0 : animationId.hashCode());
            return result;
        }
    }
}

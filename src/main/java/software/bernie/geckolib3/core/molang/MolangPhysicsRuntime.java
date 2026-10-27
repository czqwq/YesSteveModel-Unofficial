package software.bernie.geckolib3.core.molang;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
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
    /** {@code "v.<name>"} -> {@code "<name>"}；带缓存，因为 {@link #syncToRuntimeState} 每帧都要取裸名。 */
    private static final Map<String, String> STRIPPED_NAMES = new ConcurrentHashMap<>();

    /**
     * Whether {@link #end()} should copy the frame's variable scope into {@link #lastFrameVariables}, and whether
     * {@link #noteGlobalVarOwner} keeps a writer for a {@code v.*} name. Injected by the host.
     * <p>
     * <b>Deliberate divergence from the reference branch.</b> Its {@code MolangPhysicsRuntime.end()}
     * ({@code tmp/YesSteveModel-Unofficial-perf-previewUI/.../client/animation/molang/MolangPhysicsRuntime.java:244-250})
     * copies the whole scope into a new {@code HashMap} on <em>every</em> model pass - a per-frame allocation on the
     * render hot path, paid in normal play, purely so that a debug overlay and a {@code /ysm query} command can read
     * the values afterwards. The port gates that copy behind this switch, which the host sets only while something
     * actually needs the snapshot (the debug overlay is open, or a query/reset is in flight). With the switch off no
     * copy, no owner bookkeeping and no allocation happen for this feature at all. The public read accessors are
     * exactly the reference's.
     */
    private static volatile boolean snapshotCaptureEnabled = false;

    /**
     * The variables of the last captured scope, keyed as the scope keys them ({@code v.*}). Empty whenever
     * {@link #snapshotCaptureEnabled} is off - see it for why this is not captured unconditionally the way the
     * reference branch does.
     */
    private static Map<String, Double> lastFrameVariables = java.util.Collections.emptyMap();

    /**
     * {@code v.*} name -> the model id that last wrote it, for the debug overlay's {@code @model} column. Filled by
     * {@link #noteGlobalVarOwner} from the open frame context; nothing writes it unless the host calls that method.
     * <p>
     * The engine stores the writer's <em>id</em> ({@code <domain>:<path>}), not a display name: resolving a display
     * name needs the host's model tables, and the engine must not reference the host at all
     * ({@code software/bernie/geckolib3/util/GeoStats.java:19}). The host formats it.
     */
    private static final Map<String, String> GLOBAL_VAR_OWNER = new java.util.HashMap<>();

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
    /**
     * Per-bone lookup key cache: {@code modelId + "::" + boneName}, the key shape
     * {@link #CAPTURED_BONE_MATRIX} uses.
     * <p>
     * The renderer's sink is called once per bone per frame, and building that string there was one
     * {@code StringBuilder} + one {@code String} per bone per frame for every drawn entity (R7 of
     * .agent/perf-audit-frame-cpu.md; 150-526 strings/frame). The key only depends on which model the bone
     * belongs to, so it is computed once per (bone, model) and reused. Weak keys: a bone belongs to a
     * {@code GeoModel} that the connection teardown releases, and this table must not keep it alive. GeoBone
     * does not override equals/hashCode, so a {@link java.util.WeakHashMap} keys on identity as needed.
     */
    private static final Map<Object, String> BONE_KEY_CACHE = new WeakHashMap<>();
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
        ScopeKey key = ScopeKey.from(entity, animatable.getMolangModelId());
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
        // Divergence from the reference branch: its end() unconditionally copies the scope into a new HashMap here,
        // on every model pass (tmp/YesSteveModel-Unofficial-perf-previewUI/.../client/animation/molang/
        // MolangPhysicsRuntime.java:244-250). That is a per-frame allocation on the render hot path, paid in normal
        // play, purely so a debug overlay and /ysm query can read the values afterwards - which is why this port
        // gates the copy behind snapshotCaptureEnabled (see it) instead of copying it.
        if (snapshotCaptureEnabled) {
            FrameContext context = CURRENT.get();
            lastFrameVariables = context == null ? java.util.Collections.<String, Double>emptyMap()
                : new java.util.HashMap<>(context.state.variables);
        }
        CURRENT.remove();
    }

    public static void clear() {
        STATES.clear();
        // 追踪矩阵按"模型主 id + 骨名"缓存：资源重载 / 登出后旧模型的矩阵必须一起丢掉，
        // 否则刷新出来的模型首帧会先读到上一个模型的骨骼位置（SOURCE clear() 同样清它）。
        CAPTURED_BONE_MATRIX.clear();
        BONE_KEY_CACHE.clear();
        // The snapshot and the writer table describe the models that were just dropped, so they go with them.
        lastFrameVariables = java.util.Collections.emptyMap();
        GLOBAL_VAR_OWNER.clear();
        CURRENT.remove();
    }

    /**
     * Turns the per-frame variable snapshot on or off. The host calls this with {@code true} exactly while the debug
     * overlay is open or a query/reset is in flight, so that a normal frame pays nothing for a debug-only feature;
     * see {@link #snapshotCaptureEnabled} for the reference branch it diverges from.
     */
    public static void setSnapshotCaptureEnabled(boolean enabled) {
        snapshotCaptureEnabled = enabled;
    }

    /** Whether {@link #end()} currently copies the frame scope; see {@link #snapshotCaptureEnabled}. */
    public static boolean isSnapshotCaptureEnabled() {
        return snapshotCaptureEnabled;
    }

    /**
     * The variables of the last captured scope ({@code v.*} keys, as the scope keys them); never {@code null},
     * possibly empty. This is the only place the values an animation wrote into its frame scope can be read from
     * after the frame ended: {@code MolangParser.VARIABLES} holds {@link ScopedMolangVariable}s, whose registered
     * value is the construction default 0 once no frame context is open.
     */
    public static Map<String, Double> getLastFrameVariables() {
        return lastFrameVariables;
    }

    /** One variable of the last captured scope, or {@code null} if that scope did not set it. */
    public static Double getLastFrameVariable(String name) {
        return lastFrameVariables.get(name);
    }

    /** Drops the last captured scope, so the debug overlay reflects a reset immediately instead of one frame late. */
    public static void clearLastFrameSnapshot() {
        lastFrameVariables = java.util.Collections.emptyMap();
    }

    /**
     * Drops every per-scope state belonging to one player, for {@code /ysm reset} and for a logout: without it a
     * {@code (player, model)} scope - and the variables its timeline assigned - outlives the player it belonged to.
     */
    public static void clearPlayer(UUID playerId) {
        if (playerId == null) {
            return;
        }
        java.util.Iterator<Map.Entry<ScopeKey, ScopeState>> iterator = STATES.entrySet()
            .iterator();
        while (iterator.hasNext()) {
            if (playerId.equals(iterator.next().getKey().entityId)) {
                iterator.remove();
            }
        }
    }

    /**
     * The model ({@code <domain>:<path>}) that last wrote {@code name}, or {@code null} when nothing recorded one.
     * The host formats it for display; see {@link #GLOBAL_VAR_OWNER} for why the engine does not.
     */
    public static String getGlobalVarSource(String name) {
        return name == null ? null : GLOBAL_VAR_OWNER.get(name);
    }

    /**
     * Records the open frame's model as the writer of the {@code v.*} entry {@code varName}; a no-op outside a frame
     * context. The host calls this for a timeline assignment. Like the snapshot itself it records only while the
     * debug feature is on, so a normal frame pays nothing.
     */
    public static void noteGlobalVarOwner(String varName) {
        if (varName == null || !snapshotCaptureEnabled) {
            return;
        }
        FrameContext context = CURRENT.get();
        if (context == null || context.modelId == null) {
            return;
        }
        GLOBAL_VAR_OWNER.put(varName, context.modelId.toString());
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

    /**
     * Reads a variable from the open frame scope, keyed by the canonical spelling of {@code name}.
     * <p>
     * 这张表就是"变量名 → 值"的唯一入口，所以名字在这里过一次
     * {@link MolangParser#canonicalVariableName}：凡是进作用域的调用方（解析器注册的
     * {@code v.*} 变量、{@code .molang} 脚本的赋值、控制器状态机的读写）都因此看到同一个键，
     * 不会出现一半写 {@code v.is_Idle}、一半读 {@code v.is_idle} 的分裂。
     * 规则本身是幂等的（{@code canonicalVariableName} 小写化并把 {@code variable.}/{@code q.}
     * 折成 {@code v.}/{@code query.}），已经规范的名字不会被改坏；上游 1.20 的
     * {@code api/molang/MolangNames#identifier:29-35} 是同一个规则。
     */
    public static double getVariable(String name, double fallback) {
        FrameContext context = CURRENT.get();
        if (context == null) {
            return fallback;
        }
        Double value = context.state.variables.get(MolangParser.canonicalVariableName(name));
        return value == null ? fallback : value;
    }

    /** Writes a variable into the open frame scope; keyed by the canonical spelling, see {@link #getVariable}. */
    public static boolean setVariable(String name, double value) {
        FrameContext context = CURRENT.get();
        if (context == null) {
            return false;
        }
        context.state.variables.put(MolangParser.canonicalVariableName(name), value);
        return true;
    }

    /**
     * Copies the open frame scope's {@code v.*} variables into a host-side map, stripped of the {@code v.} prefix.
     * This is SOURCE's {@code MolangPhysicsRuntime#syncToRuntimeState}
     * ({@code com/fox/ysmu/client/animation/molang/MolangPhysicsRuntime.java:415-461}), and its one caller is
     * {@code OpenYsmPlayerControllerRuntime#prepareFrameVariables} ({@code SOURCE:1537}).
     *
     * <p>
     * <b>移植注：在这个拓扑里它对行为是空操作，不是缺陷修复。</b> SOURCE 的 {@code RuntimeState#variables} 是
     * 控制器条件的读取源，而关键帧 Molang 写的是这张作用域表，所以它必须同步一次才能让条件看见关键帧写的值。
     * 本仓把读取源统一了：{@code RuntimeState#getVariable} 先问 {@link #getVariable}（本方法要拷贝的那张表）
     * 再回落到本地 map。也就是说目标值已经按同一个键在读取路径上被优先命中，拷贝进本地 map 不会改变任何
     * 一次求值的结果；保留它是为了让这条 SOURCE 成员在树上可见、并让本地 map 在作用域关闭后仍与作用域一致，
     * 而不是因为这里存在 SOURCE 修掉的那个两表分裂。
     *
     * <p>
     * 与 SOURCE 的一处实现差异：它只遍历 {@code ScopeState} 的"本帧/上一帧脏集"（那是它自己的一个性能
     * 优化，因为一帧要跑十来个控制器），本仓的 {@code ScopeState} 没有脏集，所以这里整表遍历。作用域表通常
     * 十几个键、每帧十来个控制器，代价可忽略；等这里也有了脏集，再改成增量即可。
     */
    public static void syncToRuntimeState(Map<String, Double> target) {
        if (target == null) {
            return;
        }
        FrameContext context = CURRENT.get();
        if (context == null) {
            return;
        }
        for (Map.Entry<String, Double> entry : context.state.variables.entrySet()) {
            String key = entry.getKey();
            if (!key.startsWith("v.")) {
                continue;
            }
            target.put(strippedName(key), entry.getValue());
        }
    }

    /** {@code "v.<name>"} -> {@code "<name>"}，命中缓存避免每次调用都 substring 分配。 */
    private static String strippedName(String key) {
        String bare = STRIPPED_NAMES.get(key);
        if (bare == null) {
            bare = key.substring(2);
            STRIPPED_NAMES.put(key, bare);
        }
        return bare;
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
        String key = trackingKey(bone);
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

    /**
     * The capture-table key for one bone, computed once per (bone, model). See {@link #BONE_KEY_CACHE}.
     */
    private static String trackingKey(GeoBone bone) {
        String key = BONE_KEY_CACHE.get(bone);
        if (key == null) {
            key = trackingModelId + "::" + bone.getName();
            BONE_KEY_CACHE.put(bone, key);
        }
        return key;
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
            // R7: the key is cached per bone instead of being rebuilt by string concatenation on every query
            // (and on every bone capture). The table is keyed by bone identity, so this needs a GeoBone - a
            // VirtualBone has no captured matrix and falls back to the parent-chain recomputation below.
            String boneName = MolangStringPool.get(nameId);
            float[] m = null;
            if (boneName != null && bone instanceof GeoBone geoBone) {
                m = CAPTURED_BONE_MATRIX.get(trackingKey(geoBone));
            } else if (boneName != null) {
                m = CAPTURED_BONE_MATRIX.get(trackingModelId + "::" + boneName);
            }
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
     * What one scope belongs to: the owning entity and the model being evaluated.
     * <p>
     * Every component is compared <em>by value</em>. A detached preview has no entity and is handed freshly built id
     * objects, so any identity-based component would give it a different key - and therefore a different, empty scope -
     * from one frame to the next, discarding the variables the pack's own scripts assigned. YSMU's GUI tiles do exactly
     * that: {@code RenderUtil.renderModel} calls {@code setMainModel(ModelIdUtil.getMainId(modelId))} on every frame
     * and {@code getMainId} returns a {@code new ResourceLocation}. A world entity never saw the problem because its
     * key is the player's UUID.
     * <p>
     * The animation is deliberately <em>not</em> part of the key. Upstream keeps one variable store per entity, and the
     * built-in models depend on that: {@code 4_default_controllers/animations/arm.animation.json}'s {@code swing:sword}
     * timeline writes {@code v.jump=q.is_jumping&&(q.vertical_speed<0)} from the arm domain, while the same model's
     * {@code player.post_swing} controller reads {@code v.swing_sword&&!v.jump&&!(q.vertical_speed>0)} from the body
     * domain. Keying by animation put those two writes in different scopes, so the controller read 0 and the sword combo
     * never left its first state - the cross-domain gap recorded in {@code tmp/audit/animation.md:66-68}, of which the
     * port's own {@code swing_sword} write was only a single-case patch.
     */
    private static final class ScopeKey {
        private final UUID entityId;
        private final ResourceLocation modelId;

        private ScopeKey(UUID entityId, ResourceLocation modelId) {
            this.entityId = entityId;
            this.modelId = modelId;
        }

        private static ScopeKey from(EntityLivingBase entity, ResourceLocation modelId) {
            return new ScopeKey(entity == null ? null : entity.getUniqueID(), modelId);
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
            return modelId == null ? other.modelId == null : modelId.equals(other.modelId);
        }

        @Override
        public int hashCode() {
            int result = entityId == null ? 0 : entityId.hashCode();
            result = 31 * result + (modelId == null ? 0 : modelId.hashCode());
            return result;
        }
    }
}

package software.bernie.geckolib3.util;

import java.lang.reflect.Field;
import java.util.Arrays;

import net.minecraft.client.renderer.Tessellator;

/**
 * 反射访问 {@link Tessellator} 的 raw 顶点缓冲与提交游标，供 M4 原生命中快速路径把整组顶点直接写进原生
 * {@code rawBuffer}。
 *
 * <p>为什么需要反射：这些字段在 1.7.10 里全是 {@code private}，而引擎随本 jar 发布、不挂访问转换器
 * （{@code META-INF/ysmu_at.cfg} 只包含引擎自己的两个访问）。{@link TessellatorBufferKeep} 已有同样的先例，
 * 这里沿用它的形状：类初始化时解析两个名字集（开发/MCP 名与生产/SRG 名），拿不到就整体不可用并只警告一次，
 * 之后快速路径自动退回 Java 循环；任何时候都不把异常抛进渲染路径。</p>
 *
 * <p>与 {@link TessellatorBufferKeep} 一样，{@code rawBufferSize} 是一个不参与 SRG 重命名的字段（它由
 * GTNH/Forge 补丁加入，开发与生产环境都叫这个名字），所以只按字面名解析；只有它可用时才允许扩容
 * （{@link #ensureCapacity(Tessellator, int)}）。</p>
 *
 * <p><b>每次发射都要重新读字段，不要跨一次 {@code draw()} 缓存 {@code rawBuffer} 数组引用。</b>原版
 * {@code Tessellator.draw()} 末尾有一段"容量大就缩回 {@code 0x10000} 并换新数组"的分支（Angelica 的实现
 * 同形），所以 {@code rawBuffer} 这个引用在一次绘制前后可能已经不是同一个对象；{@link #ensureCapacity} 自己
 * 也会换数组。因此 {@code IGeoRenderer} 的快速路径每次发组前都用本类重新读 {@code rawBuffer}/
 * {@code rawBufferIndex}，并且 {@link #advance} 只写三个计数字段、不保存数组——任何缓存的引用都可能写进
 * 已废弃的旧数组。</p>
 */
public final class TessellatorRawAccess {

    /** raw 顶点缓冲（每顶点 8 个 int）。 */
    private static final Field RAW_BUFFER = field("rawBuffer", "field_78405_h");
    private static final Field RAW_BUFFER_SIZE = field("rawBufferSize");
    private static final Field RAW_BUFFER_INDEX = field("rawBufferIndex", "field_147569_p");
    private static final Field VERTEX_COUNT = field("vertexCount", "field_78406_i");
    private static final Field ADDED_VERTICES = field("addedVertices", "field_78411_s");
    private static final Field HAS_TEXTURE = field("hasTexture", "field_78400_o");
    private static final Field HAS_COLOR = field("hasColor", "field_78399_n");
    private static final Field HAS_NORMALS = field("hasNormals", "field_78413_q");
    private static final Field IS_COLOR_DISABLED = field("isColorDisabled", "field_78410_t");
    private static final Field X_OFFSET = field("xOffset", "field_78408_v");
    private static final Field Y_OFFSET = field("yOffset", "field_78407_w");
    private static final Field Z_OFFSET = field("zOffset", "field_78417_x");

    /**
     * 提交一组顶点至少要能写这些字段。缺任何一个都说明这个环境不是预期的 1.7.10 类（或补丁改动过大），
     * 快速路径整体关闭。
     */
    private static final boolean REQUIRED_PRESENT = RAW_BUFFER != null && RAW_BUFFER_INDEX != null
        && VERTEX_COUNT != null
        && HAS_TEXTURE != null
        && HAS_COLOR != null
        && HAS_NORMALS != null
        && IS_COLOR_DISABLED != null;

    private static volatile boolean available = REQUIRED_PRESENT;
    private static volatile boolean warned;

    private TessellatorRawAccess() {}

    private static Field field(String... candidates) {
        for (String candidate : candidates) {
            try {
                Field field = Tessellator.class.getDeclaredField(candidate);
                field.setAccessible(true);
                return field;
            } catch (Throwable ignored) {
                // 两个名字集都试过才算失败。
            }
        }
        return null;
    }

    /** 反射面是否完整可用；不完整意味着引擎会一直走 Java 顶点循环。 */
    public static boolean available() {
        return available;
    }

    /**
     * 提交游标是否为零（原版 {@code setTranslation} 的 x/y/z 偏移）。原生发射把位置直接写成 float 位，
     * 复现不了非零偏移下的 {@code (float)((double)x + offset)} 舍入，所以非零偏移一律回退 Java。
     * 用原始 double 位判断，{@code -0.0} 也回退（它会把 {@code -0.0} 位置改写成 {@code +0.0}）。
     */
    public static boolean offsetsZero(Tessellator tessellator) {
        if (!available || tessellator == null || X_OFFSET == null || Y_OFFSET == null || Z_OFFSET == null) {
            return false;
        }
        try {
            return Double.doubleToRawLongBits(X_OFFSET.getDouble(tessellator)) == 0L
                && Double.doubleToRawLongBits(Y_OFFSET.getDouble(tessellator)) == 0L
                && Double.doubleToRawLongBits(Z_OFFSET.getDouble(tessellator)) == 0L;
        } catch (Throwable failure) {
            disable(failure);
            return false;
        }
    }

    /** 当前 raw 缓冲，不可用时为 {@code null}。 */
    public static int[] rawBuffer(Tessellator tessellator) {
        if (!available || tessellator == null) {
            return null;
        }
        try {
            return (int[]) RAW_BUFFER.get(tessellator);
        } catch (Throwable failure) {
            disable(failure);
            return null;
        }
    }

    /** 下一条顶点的起始 int 下标（{@code rawBufferIndex}），不可用时为 -1。 */
    public static int rawBufferIndex(Tessellator tessellator) {
        if (!available || tessellator == null) {
            return -1;
        }
        try {
            return RAW_BUFFER_INDEX.getInt(tessellator);
        } catch (Throwable failure) {
            disable(failure);
            return -1;
        }
    }

    /** 当前待提交的顶点数（{@code vertexCount}），不可用时为 -1。 */
    public static int vertexCount(Tessellator tessellator) {
        if (!available || tessellator == null) {
            return -1;
        }
        try {
            return VERTEX_COUNT.getInt(tessellator);
        } catch (Throwable failure) {
            disable(failure);
            return -1;
        }
    }

    /**
     * 保证 raw 缓冲至少能装下 {@code neededInts} 个 int，必要时按原版 {@code addVertex} 的方式翻倍扩容；
     * 缓冲还没分配（原版首次 {@code addVertex} 之前）时按原版同一个 {@code 0x10000} 初值分配。
     *
     * <p>只有 {@code rawBufferSize} 可解析时才扩容/分配；否则返回当前缓冲是否已经够用，不够就让调用方回退
     * Java（Java 的 {@code addVertex} 自己会把缓冲长起来）。扩容保留已写入的内容，因此可以在一个待提交
     * 批次中途调用——这和原版在 {@code addVertex} 里扩容的时机等价。</p>
     */
    public static boolean ensureCapacity(Tessellator tessellator, int neededInts) {
        if (!available || tessellator == null || neededInts <= 0) {
            return false;
        }
        try {
            int[] buffer = (int[]) RAW_BUFFER.get(tessellator);
            if (buffer == null) {
                // 原版首次 addVertex 才分配 rawBuffer（0x10000 个 int）；共享 Tessellator 在实际渲染里
                // 通常已经被前面的原版绘制分配过，但"本帧第一个用它的绘制是模型"这种顺序也可能出现，
                // 所以按原版同一个初值分配，而不是让整帧退化。
                if (RAW_BUFFER_SIZE == null) {
                    return false;
                }
                int newSize = 0x10000;
                while (newSize < neededInts) {
                    int doubled = newSize << 1;
                    if (doubled <= newSize) {
                        return false;
                    }
                    newSize = doubled;
                }
                RAW_BUFFER.set(tessellator, new int[newSize]);
                RAW_BUFFER_SIZE.setInt(tessellator, newSize);
                return true;
            }
            if (buffer.length >= neededInts) {
                return true;
            }
            if (RAW_BUFFER_SIZE == null) {
                return false;
            }
            int newSize = buffer.length;
            while (newSize < neededInts) {
                int doubled = newSize << 1;
                if (doubled <= newSize) {
                    return false;
                }
                newSize = doubled;
            }
            int[] grown = Arrays.copyOf(buffer, newSize);
            RAW_BUFFER.set(tessellator, grown);
            RAW_BUFFER_SIZE.setInt(tessellator, newSize);
            return true;
        } catch (Throwable failure) {
            disable(failure);
            return false;
        }
    }

    /**
     * 把原生已经写进 {@code rawBuffer} 的 {@code vertices} 个顶点记成一笔 Java 顶点提交：推进
     * {@code rawBufferIndex}/{@code vertexCount}/{@code addedVertices}，并按原版路径 {@code startDrawing} +
     * {@code addVertexWithUV}/{@code setColorRGBA_F}/{@code setNormal} 的结果置好属性标志。
     *
     * <p>提交后 {@code Tessellator.draw()} 的语义与 Java 循环逐个 {@code addVertex} 后完全一致：下一次
     * Java 顶点会从同一个游标继续追加。</p>
     */
    public static void advance(Tessellator tessellator, int vertices) {
        if (!available || tessellator == null || vertices <= 0) {
            return;
        }
        try {
            int index = RAW_BUFFER_INDEX.getInt(tessellator);
            int count = VERTEX_COUNT.getInt(tessellator);
            int nextIndex = index + (vertices << 3);
            RAW_BUFFER_INDEX.setInt(tessellator, nextIndex);
            VERTEX_COUNT.setInt(tessellator, count + vertices);
            if (ADDED_VERTICES != null) {
                ADDED_VERTICES.setInt(tessellator, count + vertices);
            }
            // 原生记录里颜色、法线、UV 全都写了，和 Java 循环走到这批顶点末尾时一样。
            HAS_TEXTURE.setBoolean(tessellator, true);
            HAS_COLOR.setBoolean(tessellator, true);
            HAS_NORMALS.setBoolean(tessellator, true);
            IS_COLOR_DISABLED.setBoolean(tessellator, false);
        } catch (Throwable failure) {
            disable(failure);
        }
    }

    private static void disable(Throwable failure) {
        if (!available) {
            return;
        }
        available = false;
        warnOnce(failure);
    }

    private static void warnOnce(Throwable failure) {
        if (warned) {
            return;
        }
        warned = true;
        try {
            software.bernie.geckolib3.GeckoLib.LOG.warn(
                "[YSMU-NATIVE] 拿不到 Tessellator 的 raw 顶点缓冲字段（{}），逐帧顶点发射快速路径关闭，"
                    + "继续使用原 Java 循环（不影响渲染结果）",
                failure == null ? "字段不存在" : failure.toString());
        } catch (Throwable ignored) {
            // 日志本身出问题也不能影响渲染。
        }
    }
}

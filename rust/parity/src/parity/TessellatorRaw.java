package parity;

import java.lang.reflect.Field;

import net.minecraft.client.renderer.Tessellator;

/**
 * Headless access to the 1.7.10 {@code Tessellator}'s raw vertex buffer.
 *
 * <p>
 * The Java emission path and the Rust draw-group emitter both write the same 8-int vertex records into
 * the tessellator's {@code rawBuffer}; this helper lets the harness allocate a large buffer without GL,
 * reset the write cursor, and read back exactly the integers that were emitted. It is the same reflection
 * seam {@code software.bernie.geckolib3.util.TessellatorBufferKeep} already uses in production (which is
 * also why {@link Tessellator#startDrawing(int)} and the vertex methods are GL-free: only {@code draw()}
 * touches OpenGL, and the harness never calls it).
 */
final class TessellatorRaw {

    /** {@code GL11.GL_QUADS}; inlined so loading this class never touches LWJGL. */
    static final int GL_QUADS = 7;

    private static final Field RAW_BUFFER = field("rawBuffer", "field_147567_b", "field_147569_p");
    private static final Field RAW_BUFFER_SIZE = field("rawBufferSize");
    private static final Field RAW_BUFFER_INDEX = field("rawBufferIndex", "field_147569_p");

    private TessellatorRaw() {}

    /** Whether all fields were found; when false the render module reports SKIP instead of failing. */
    static boolean available() {
        return RAW_BUFFER != null && RAW_BUFFER_SIZE != null && RAW_BUFFER_INDEX != null;
    }

    static String problem() {
        return "cannot reflect Tessellator fields (rawBuffer=" + (RAW_BUFFER != null)
            + ", rawBufferSize=" + (RAW_BUFFER_SIZE != null) + ", rawBufferIndex=" + (RAW_BUFFER_INDEX != null) + ")";
    }

    /** Creates a tessellator with a private raw buffer of {@code capacity} ints and starts a quad batch. */
    static Tessellator create(int capacity) {
        Tessellator tessellator = new Tessellator();
        try {
            RAW_BUFFER.set(tessellator, new int[capacity]);
            RAW_BUFFER_SIZE.setInt(tessellator, capacity);
            RAW_BUFFER_INDEX.setInt(tessellator, 0);
        } catch (IllegalAccessException failure) {
            throw new IllegalStateException(problem(), failure);
        }
        tessellator.startDrawing(GL_QUADS);
        return tessellator;
    }

    /** Rewinds the write cursor; used per draw group so the buffer never nears its flush threshold. */
    static void rewind(Tessellator tessellator) {
        try {
            RAW_BUFFER_INDEX.setInt(tessellator, 0);
        } catch (IllegalAccessException failure) {
            throw new IllegalStateException(problem(), failure);
        }
    }

    /** Number of ints written since the last rewind. */
    static int usedInts(Tessellator tessellator) {
        try {
            return RAW_BUFFER_INDEX.getInt(tessellator);
        } catch (IllegalAccessException failure) {
            throw new IllegalStateException(problem(), failure);
        }
    }

    /** Copy of the first {@code usedInts()} ints of the raw buffer. */
    static int[] copyUsed(Tessellator tessellator) {
        int used = usedInts(tessellator);
        int[] copy = new int[used];
        try {
            System.arraycopy(RAW_BUFFER.get(tessellator), 0, copy, 0, used);
        } catch (IllegalAccessException failure) {
            throw new IllegalStateException(problem(), failure);
        }
        return copy;
    }

    /** The live buffer (for the benchmark's zero-copy writes). */
    static int[] buffer(Tessellator tessellator) {
        try {
            return (int[]) RAW_BUFFER.get(tessellator);
        } catch (IllegalAccessException failure) {
            throw new IllegalStateException(problem(), failure);
        }
    }

    private static Field field(String... names) {
        for (String name : names) {
            try {
                Field field = Tessellator.class.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (Throwable ignored) {
                // Try the next name (MCP name first, then the SRG names used by a production runtime).
            }
        }
        return null;
    }
}

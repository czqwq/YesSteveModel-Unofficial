package parity;

/**
 * Timing helpers shared by the module benchmarks.
 *
 * <p>
 * Not a microbenchmark harness: each operation runs once as a warm-up (class loading, JIT, first-use
 * caches), then {@link #BATCHES} timed batches are run and the <b>fastest</b> one is reported. The
 * batches are sized from a probe call to take roughly {@link #TARGET_NANOS} each. Best-of-N is deliberate:
 * this machine also runs Gradle/cargo builds while the harness is used, and a single batch can be hit by
 * a compile job or a GC pause; the fastest batch is the least contaminated estimate of the same work.
 * Treat the numbers as a same-machine before/after comparison, not as absolute guarantees.
 */
public final class Bench {

    /** Target duration of one timed batch. */
    private static final long TARGET_NANOS = 400_000_000L;
    /** Number of timed batches; the fastest is reported. */
    private static final int BATCHES = 3;
    /** Never run fewer iterations than this once the operation is timed. */
    private static final int MIN_ITERATIONS = 1;
    /** Cap for very fast operations so the harness does not spin forever. */
    private static final int MAX_ITERATIONS = 100;

    private Bench() {}

    /** One repeatable operation. */
    public interface Op {

        void run();
    }

    /** Result of one timed batch. */
    public static final class Result {

        /** Nanoseconds for the reported (fastest) batch. */
        public final long nanos;
        /** Number of iterations in that batch. */
        public final int iterations;
        /** Payload bytes per iteration (0 for pure-compute operations). */
        public final long bytesPerIteration;

        Result(long nanos, int iterations, long bytesPerIteration) {
            this.nanos = nanos;
            this.iterations = iterations;
            this.bytesPerIteration = bytesPerIteration;
        }

        public long totalBytes() {
            return bytesPerIteration * iterations;
        }

        public double millisPerOperation() {
            return nanos / 1_000_000.0 / iterations;
        }

        public double mbPerSecond() {
            double seconds = nanos / 1_000_000_000.0;
            return seconds <= 0.0 ? 0.0 : totalBytes() / seconds / (1024.0 * 1024.0);
        }
    }

    /**
     * Times {@code op}: one warm-up call, one probe call to size the batch, then {@link #BATCHES} timed
     * batches of that size; the fastest batch is returned. {@code bytesPerIteration} is only used for the
     * throughput column.
     */
    public static Result time(long bytesPerIteration, Op op) {
        op.run();
        System.gc();
        long probeStart = System.nanoTime();
        op.run();
        long probe = System.nanoTime() - probeStart;
        int iterations;
        if (probe <= 0L) {
            iterations = MAX_ITERATIONS;
        } else {
            iterations = (int) Math.min(MAX_ITERATIONS, Math.max(MIN_ITERATIONS, TARGET_NANOS / probe));
        }
        Result best = null;
        for (int batch = 0; batch < BATCHES; batch++) {
            long start = System.nanoTime();
            for (int i = 0; i < iterations; i++) {
                op.run();
            }
            long elapsed = System.nanoTime() - start;
            Result current = new Result(elapsed, iterations, bytesPerIteration);
            if (best == null || current.nanos < best.nanos) {
                best = current;
            }
        }
        return best;
    }

    /** Formats a result as MiB/s for the report's Java/Rust columns. */
    public static String mb(Result result) {
        return String.format(java.util.Locale.ROOT, "%.1f MiB/s", result.mbPerSecond());
    }
}

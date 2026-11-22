package parity;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Collects the parity/benchmark results and renders them twice: as the ASCII summary table printed at
 * the end of a run, and as {@code out/report.md} for the ExecPlan.
 *
 * <p>
 * One row is one comparable case: a module id, a case name, the Java and Rust measurement (a time, a
 * throughput or just "n/a"), a speedup and a status. The detail column carries the first concrete
 * mismatch / decline reason, which is what the owning milestone needs to act on.
 */
public final class Report {

    public enum Status {
        PASS,
        FAIL,
        PENDING,
        SKIP
    }

    public static final class Row {

        public final String module;
        public final String test;
        public final String javaMetric;
        public final String rustMetric;
        public final String speedup;
        public final Status status;
        public final String detail;

        Row(String module, String test, String javaMetric, String rustMetric, String speedup, Status status,
            String detail) {
            this.module = module;
            this.test = test;
            this.javaMetric = javaMetric == null ? "" : javaMetric;
            this.rustMetric = rustMetric == null ? "" : rustMetric;
            this.speedup = speedup == null ? "" : speedup;
            this.status = status;
            this.detail = detail == null ? "" : detail;
        }
    }

    private final List<Row> rows = new ArrayList<>();
    private final Map<String, String> meta = new LinkedHashMap<>();

    public void meta(String key, String value) {
        meta.put(key, value);
    }

    /** Current value of one metadata entry (empty string when absent). */
    public String metaValue(String key) {
        String value = meta.get(key);
        return value == null ? "" : value;
    }

    public Row add(Status status, String module, String test, String javaMetric, String rustMetric, String speedup,
        String detail) {
        Row row = new Row(module, test, javaMetric, rustMetric, speedup, status, detail);
        rows.add(row);
        return row;
    }

    public void pass(String module, String test, String javaMetric, String rustMetric, String speedup, String detail) {
        add(Status.PASS, module, test, javaMetric, rustMetric, speedup, detail);
    }

    public void fail(String module, String test, String javaMetric, String rustMetric, String speedup, String detail) {
        add(Status.FAIL, module, test, javaMetric, rustMetric, speedup, detail);
    }

    public void pending(String module, String test, String detail) {
        add(Status.PENDING, module, test, "", "", "", detail);
    }

    public void skip(String module, String test, String detail) {
        add(Status.SKIP, module, test, "", "", "", detail);
    }

    public List<Row> rows() {
        return rows;
    }

    public long count(Status status) {
        return rows.stream().filter(row -> row.status == status).count();
    }

    public boolean hasFailures() {
        return count(Status.FAIL) > 0;
    }

    /**
     * Formats a speedup for throughput-style values (higher is better), e.g. {@code 1.83x} means Rust
     * moved the same bytes in 1/1.83 of the Java time. For time-style values (milliseconds, lower is
     * better) pass them swapped or compute the ratio directly.
     */
    public static String speedup(double javaValue, double rustValue) {
        if (!(javaValue > 0.0) || !(rustValue > 0.0)) {
            return "";
        }
        return String.format(Locale.ROOT, "%.2fx", rustValue / javaValue);
    }

    /**
     * Formats a speedup for time-style values (milliseconds, lower is better): {@code javaMillis /
     * rustMillis}, so {@code 2.00x} reads "Rust does the same work in half the Java time".
     */
    public static String timeSpeedup(double javaMillis, double rustMillis) {
        if (!(javaMillis > 0.0) || !(rustMillis > 0.0)) {
            return "";
        }
        return String.format(Locale.ROOT, "%.2fx", javaMillis / rustMillis);
    }

    /** Formats MiB/s from a byte count and a nanosecond duration. */
    public static String mbPerSecond(long bytes, long nanos) {
        if (nanos <= 0L) {
            return "";
        }
        double seconds = nanos / 1_000_000_000.0;
        return String.format(Locale.ROOT, "%.1f MiB/s", bytes / seconds / (1024.0 * 1024.0));
    }

    /** Formats milliseconds from a nanosecond duration. */
    public static String millis(long nanos) {
        return String.format(Locale.ROOT, "%.2f ms", nanos / 1_000_000.0);
    }

    /** Formats milliseconds per operation. */
    public static String millisPer(long nanos, int operations) {
        if (operations <= 0) {
            return "";
        }
        return String.format(Locale.ROOT, "%.3f ms/op", nanos / 1_000_000.0 / operations);
    }

    public void print(PrintStream out) {
        out.println();
        out.println(repeat('=', 118));
        out.println(" YSMU Rust acceleration layer - headless parity + benchmark");
        out.println(repeat('=', 118));
        for (Map.Entry<String, String> entry : meta.entrySet()) {
            out.printf(" %-12s: %s%n", entry.getKey(), entry.getValue());
        }
        out.println(repeat('-', 118));

        String[] headers = { "module", "case", "Java", "Rust", "speedup", "status" };
        int[] widths = new int[headers.length];
        for (int i = 0; i < headers.length; i++) {
            widths[i] = headers[i].length();
        }
        for (Row row : rows) {
            String[] cells = cells(row);
            for (int i = 0; i < cells.length; i++) {
                widths[i] = Math.max(widths[i], cells[i].length());
            }
        }
        widths[0] = Math.min(widths[0], 12);
        widths[1] = Math.min(widths[1], 46);
        widths[2] = Math.min(widths[2], 24);
        widths[3] = Math.min(widths[3], 24);
        widths[4] = Math.min(widths[4], 10);

        StringBuilder format = new StringBuilder(" ");
        for (int i = 0; i < widths.length; i++) {
            format.append("%-").append(widths[i]).append("s");
            if (i < widths.length - 1) {
                format.append(" | ");
            }
        }
        out.printf(format + "%n", (Object[]) headers);
        out.println(repeat('-', 118));
        for (Row row : rows) {
            out.printf(format + "%n", (Object[]) cells(row));
        }
        out.println(repeat('-', 118));
        out.printf(
            " summary: %d PASS, %d FAIL, %d PENDING, %d SKIP%n",
            count(Status.PASS),
            count(Status.FAIL),
            count(Status.PENDING),
            count(Status.SKIP));

        List<Row> problems = new ArrayList<>();
        for (Row row : rows) {
            if (row.status == Status.FAIL || row.status == Status.PENDING || row.status == Status.SKIP) {
                problems.add(row);
            }
        }
        if (!problems.isEmpty()) {
            out.println();
            out.println(" details:");
            for (Row row : problems) {
                out.printf("   [%s] %s / %s: %s%n", row.status, row.module, row.test, row.detail);
            }
        }
        out.println(repeat('=', 118));
    }

    public void writeMarkdown(Path target, String commandLine) throws IOException {
        StringBuilder markdown = new StringBuilder(8192);
        markdown.append("# YSMU Rust acceleration layer — parity + benchmark report\n\n");
        markdown.append(
            "Generated by `rust/parity` (headless `javac` + `java`, no Gradle test task). Reproduce with the\n")
            .append("command in the [Command](#command) section; the exact classpath is written to `out/classpath.txt`.\n\n");
        markdown.append("**Command**\n\n```powershell\n")
            .append(commandLine)
            .append("\n```\n\n");

        markdown.append("## Environment\n\n| item | value |\n| --- | --- |\n");
        for (Map.Entry<String, String> entry : meta.entrySet()) {
            markdown.append("| ")
                .append(escape(entry.getKey()))
                .append(" | ")
                .append(escape(entry.getValue()))
                .append(" |\n");
        }
        markdown.append('\n');

        markdown.append("## Results\n\n");
        markdown.append("| module | case | Java | Rust | speedup | status |\n");
        markdown.append("| --- | --- | --- | --- | --- | --- |\n");
        for (Row row : rows) {
            markdown.append("| ")
                .append(escape(row.module))
                .append(" | ")
                .append(escape(row.test))
                .append(" | ")
                .append(escape(row.javaMetric))
                .append(" | ")
                .append(escape(row.rustMetric))
                .append(" | ")
                .append(escape(row.speedup))
                .append(" | **")
                .append(row.status)
                .append("** |\n");
        }
        markdown.append('\n');

        markdown.append("## Details\n\n");
        boolean anyDetail = false;
        for (Row row : rows) {
            if (row.detail.isEmpty()) {
                continue;
            }
            anyDetail = true;
            markdown.append("- `")
                .append(row.status)
                .append("` ")
                .append(escape(row.module))
                .append(" / ")
                .append(escape(row.test))
                .append(": ")
                .append(row.detail)
                .append('\n');
        }
        if (!anyDetail) {
            markdown.append("(none)\n");
        }
        markdown.append('\n');

        markdown.append("## Notes\n\n");
        markdown.append("- Java is the shipped implementation; Rust is read through the facade class in `com.fox.ysmu.nativebridge`.\n");
        markdown.append("- `PASS` means bit-exact/byte-exact equality or a proven fallback (or a measurement row of a passing module); `FAIL` names the first mismatch; `PENDING` means the owning milestone's native side was not present or declined when this report was generated — rerun after it lands; `SKIP` means an optional measurement needed a Java facility missing in this environment.\n");
        markdown.append("- The `speedup` column is Rust relative to Java: `> 1x` means Rust is faster (time-style rows use Java ms / Rust ms through the same helper).\n");
        markdown.append("- No OpenGL context is created: the Java render reference only calls `Tessellator.startDrawing`/`addVertexWithUV`/`setNormal`/`setColorRGBA_F` and reads `rawBuffer`/`rawBufferIndex` by reflection; `draw()` is never called and oversized draw groups are excluded from the comparison.\n");
        markdown.append("- Throughput is MiB of payload per second after a warm-up call; timings are wall clock on the machine named above and not a JVM microbenchmark harness.\n");
        markdown.append("- The report file is generated output; `out/` may be left uncommitted.\n\n");
        markdown.append("_Report timestamp: ")
            .append(DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(Instant.now().atZone(ZoneId.systemDefault())))
            .append("_\n");

        Files.createDirectories(target.getParent());
        Files.write(target, markdown.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String[] cells(Row row) {
        return new String[] { clip(row.module, 12), clip(row.test, 46), clip(row.javaMetric, 24),
            clip(row.rustMetric, 24), clip(row.speedup, 10), row.status.name() };
    }

    private static String clip(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max - 1) + '…';
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("|", "\\|")
            .replace("\n", " ");
    }

    private static String repeat(char character, int count) {
        StringBuilder builder = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            builder.append(character);
        }
        return builder.toString();
    }
}

package parity;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Entry point of the headless parity + benchmark harness.
 *
 * <p>
 * Run through {@code rust/parity/run.ps1}, which compiles this source tree with {@code javac}, builds
 * the classpath (mod classes + Minecraft + the Gradle dependency jars) and starts the JVM from the
 * repository root. Running from the repository root matters: {@code NativeLoader} extracts the
 * library into {@code config/ysmu/native/...} relative to the working directory, exactly like in game.
 *
 * <p>
 * Arguments: {@code --direct} skips the {@code NativeLoader} path and loads the newest DLL directly;
 * {@code --modules=zstd,png,geometry,render} runs a subset.
 */
public final class Main {

    private Main() {}

    public static void main(String[] args) {
        boolean direct = false;
        String modulesArgument = "";
        for (String argument : args) {
            if (argument.equals("--direct")) {
                direct = true;
            } else if (argument.startsWith("--modules=")) {
                modulesArgument = argument.substring("--modules=".length());
            }
        }

        Path repo = Path.of("")
            .toAbsolutePath()
            .normalize();
        Path parityDir = repo.resolve("rust/parity");
        Path outDir = parityDir.resolve("out");
        Path reportPath = outDir.resolve("report.md");
        Report report = new Report();

        report.meta(
            "generated",
            DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(Instant.now()
                .atZone(ZoneId.systemDefault())));
        report.meta(
            "java",
            System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + ")");
        report.meta(
            "os",
            System.getProperty("os.name") + " " + System.getProperty("os.version") + " / "
                + System.getProperty("os.arch"));
        report.meta("repo", repo.toString());

        NativeBootstrap.initialize(repo, direct);
        report.meta(
            "native layer",
            NativeBootstrap.available() ? NativeBootstrap.mode() + " - " + NativeBootstrap.status()
                : "UNAVAILABLE - " + NativeBootstrap.status());
        report.meta("native library", NativeBootstrap.available() ? NativeBootstrap.loadedFrom() : "-");
        if (NativeBootstrap.packagedStale()) {
            report.meta(
                "warning",
                "build/resources/main carries an older DLL than " + NativeBootstrap.freshestLibrary()
                    + "; the run used the newest file directly. Run run.ps1 -Gradle for a packaged-artifact run.");
        }

        List<String> modules = selectedModules(modulesArgument);
        System.out.println("YSMU Rust acceleration - headless parity + benchmark");
        System.out.println("  java        : " + reportMeta(report, "java"));
        System.out.println("  os          : " + reportMeta(report, "os"));
        System.out.println("  native      : " + reportMeta(report, "native layer"));
        System.out.println("  native file : " + reportMeta(report, "native library"));
        if (NativeBootstrap.packagedStale()) {
            System.out.println("  WARNING     : " + reportMeta(report, "warning"));
        }
        System.out.println("  modules     : " + String.join(", ", modules));
        System.out.println();

        for (String module : modules) {
            try {
                switch (module) {
                    case "zstd":
                        ParityZstd.run(repo, report);
                        break;
                    case "png":
                        ParityPng.run(repo, report);
                        break;
                    case "geometry":
                        ParityGeometry.run(repo, report);
                        break;
                    case "render":
                        ParityRender.run(repo, report);
                        break;
                    default:
                        report.fail(module, "unknown module", "", "", "", "not a harness module");
                        break;
                }
            } catch (Throwable failure) {
                report.fail(module, "module crashed", "", "", "", failure.toString());
                failure.printStackTrace(System.out);
            }
        }

        report.print(System.out);

        String command = System.getenv("YSMU_PARITY_COMMAND");
        if (command == null || command.trim().isEmpty()) {
            command = buildCommand(repo, modules, direct);
        }
        try {
            report.writeMarkdown(reportPath, command);
            System.out.println(" report written: " + reportPath);
        } catch (Throwable failure) {
            System.out.println(" could not write " + reportPath + ": " + failure);
        }

        if (report.hasFailures()) {
            System.exit(1);
        }
        if (report.count(Report.Status.PASS) == 0) {
            System.exit(3);
        }
    }

    private static String buildCommand(Path repo, List<String> modules, boolean direct) {
        StringBuilder command = new StringBuilder("cd \"").append(repo)
            .append("\"\n.\\rust\\parity\\run.ps1");
        if (!modules.equals(Arrays.asList("zstd", "png", "geometry", "render"))) {
            command.append(" -Modules ")
                .append(String.join(",", modules));
        }
        if (direct) {
            command.append(" -DirectLoad");
        }
        return command.toString();
    }

    private static List<String> selectedModules(String argument) {
        List<String> requested;
        if (argument == null || argument.isEmpty()) {
            requested = Arrays.asList("zstd", "png", "geometry", "render");
        } else {
            requested = new ArrayList<>();
            for (String part : argument.split(",")) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty()) {
                    requested.add(trimmed);
                }
            }
        }
        return requested;
    }

    private static String reportMeta(Report report, String key) {
        return report.metaValue(key);
    }
}

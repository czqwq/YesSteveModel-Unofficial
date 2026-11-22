# Headless parity + benchmark harness (Rust acceleration layer)

This directory proves that the Rust acceleration layer (`rust/ysmu_native`, loaded through
`com.fox.ysmu.nativebridge.*`) produces **exactly the same result** as the shipped Java implementations,
and measures the speedup. It is a plain `javac` + `java` program because the mod has no Gradle test
source set/task that can load the engine headlessly.

Nothing here is part of the mod jar and nothing writes outside `rust/parity/out/` (the only other writes
are `NativeLoader`'s own `config/ysmu/native/...` extraction when the packaged-artifact path is used,
exactly as in game).

## What it checks

| module | Java reference | Rust module | assertion |
| --- | --- | --- | --- |
| `zstd` | `rip/ysm/zstd/ZstdUtil` + `rip/ysm/algorithms/YsmZstd` | `NativeCodecs` (M1) | both cross-decode directions byte-identical, the `YsmZstd` wire frame round-trips with each implementation forced, bounded decompression declines oversized frames, every facade declines when `Config.EnableRustNative=false` |
| `png` | `javax.imageio.ImageIO` | `NativeImages` (M2) | every built-in PNG decodes to the exact `ImageIO.getRGB` ARGB pixels; `encodePng` output decodes back to the same pixels |
| `geometry` | `Converter.fromJsonString` → `RawGeometryTree.parseHierarchy` → `GeoBuilder.constructGeoModel` | `NativeGeometry.bake` → `.blob` → `NativeGeometryReader.read` (M3) | order-sensitive, field-for-field, raw-f32-bit equality of the whole `GeoModel` graph for every built-in geometry document; `NativeGeometry.info` counts/flags |
| `render` | `IGeoRenderer.renderCube` loop driven from one `NativeGeometryReader` model | `NativeDrawGroups.emit` (M4) | raw `Tessellator` int arrays byte-identical per (bone, cube filter) draw group under a deterministic pose |

Benchmarks for every module are printed next to the parity result (MiB/s, ms/model, vertices/ms) and the
whole run is written to `out/report.md`.

## Prerequisites

- Windows with PowerShell 5.1+ (the script uses plain PowerShell, no modules).
- A JDK: `java` and `javac` on `PATH` (tested with Zulu OpenJDK 25).
- The project has been built once, so these exist:
  - `build/classes/java/main` (compiled mod + engine classes)
  - `build/resources/main` (resources; may or may not carry the native DLL)
  - `build/rfg/recompiled_minecraft-1.7.10.jar` (deobfuscated + patched Minecraft 1.7.10)
- The Gradle module cache at `D:\gradle_cache\caches\modules-2\files-2.1` (hard-coded at the top of
  `run.ps1`; edit if your cache lives elsewhere).
- Rust toolchain only if you want to rebuild the native layer (the harness can still run on the last
  built DLL in `build/resources/main`).

## Commands

From the repository root:

```powershell
# 1. First time / after Java changes: compile the mod classes and stage the native artifact.
#    (processResources copies the pre-distributed DLL from native/ into build/resources/main; add
#    -PysmuNative=build to rebuild the host DLL from rust/ with cargo first.)
.\gradlew.bat compileJava processResources --console=plain --offline

# 2. Run every module: prints the ASCII table and writes out/report.md.
.\rust\parity\run.ps1

# Subsets while a milestone is in progress:
.\rust\parity\run.ps1 -Modules zstd,png
.\rust\parity\run.ps1 -Modules geometry
.\rust\parity\run.ps1 -Modules render

# Rebuild the host Rust cdylib through Gradle first (packaged-artifact path, the one the jar ships):
.\rust\parity\run.ps1 -Gradle

# Load rust/target/release/ysmu_native.dll directly with System.load instead of NativeLoader:
.\rust\parity\run.ps1 -DirectLoad
```

Rust-side tests (unit + corpus tests of the crate) are separate:

```powershell
cd rust
cargo test --release
```

The build also exposes the harness as Gradle tasks (same sources, project runtime classpath instead of
the cache scan, `processResources` first so the packaged DLL is the one under test; add
`-PysmuNative=build` to test a freshly built one):

```powershell
.\gradlew.bat nativeParity --console=plain --offline
# subset / direct-load, forwarded to the harness switches:
.\gradlew.bat nativeParity -PparityArgs="--modules=zstd,png" --console=plain --offline
```

`run.ps1` is the lower-level entry point and is the one that writes `out/classpath.txt`; both write the
same `out/report.md`.

On Windows the zstd C dependency is built through the MSVC toolchain and uses `%TMP%`/`%TEMP%` for
intermediate files; if `LNK1104` appears, point those at `rust\target\tmp`:

```powershell
$env:TMP = "$PWD\target\tmp"; $env:TEMP = $env:TMP; $env:TMPDIR = $env:TMP
cargo build --release
```

## How the classpath is built

`run.ps1` assembles the classpath itself; `out/classpath.txt` records the exact result of the last run.

Fixed entries:

```
build/classes/java/main
build/resources/main
build/generated/rust-resources          (only if present; staged native resource dir)
build/rfg/recompiled_minecraft-1.7.10.jar
```

Dependency jars are discovered under `D:\gradle_cache\caches\modules-2\files-2.1` by group/artifact with
the versions the mod is built against or Minecraft 1.7.10 ships (see the `$dependencies` table in
`run.ps1`):

| group | artifact | version |
| --- | --- | --- |
| com.fasterxml.jackson.core | jackson-databind / jackson-core / jackson-annotations | 2.9.0 |
| com.fasterxml.jackson.datatype | jackson-datatype-jsr310 | 2.9.0 |
| com.google.code.gson | gson | 2.10.1 (falls back to 2.11.0 / 2.8.0 / 2.2.4) |
| com.google.guava | guava | 17.0 |
| org.apache.commons | commons-lang3 | 3.12.0 |
| commons-io | commons-io | 2.4 |
| javax.vecmath | vecmath | 1.5.2 |
| it.unimi.dsi | fastutil | 8.5.18 |
| org.apache.logging.log4j | log4j-api / log4j-core | 2.0-beta9-fixed |
| commons-codec | commons-codec | 1.9 |
| org.lwjgl.lwjgl | lwjgl / lwjgl_util | 2.9.x (classes only; no GL context is ever created) |

If a jar is missing the script fails with the exact `group:artifact` so the cache can be checked. To
change a version, edit the `Versions` array of that row.

## Which native library is loaded

The harness prefers the shipping path but never proves a stale artifact:

1. If the DLL packaged in `build/resources/main` is at least as new as every other candidate
   (`build/generated/rust-resources`, `rust/target/release`, `build/rust-target/release`), the harness
   calls `NativeLoader.ensureLoaded()`; that extracts the resource into `config/ysmu/native/...` relative
   to the repository root and `System.load`s it, exactly like the game.
2. Otherwise (the usual case while a milestone is being iterated on: `cargo build` produced a newer DLL
   than Gradle copied) it `System.load`s the newest DLL directly and flips `NativeLoader`'s private
   `attempted`/`available` fields so the public facades treat the layer as available. The report marks
   this as `direct-load (packaged artifact stale)` and warns to rerun with `-Gradle` for the packaged run.

Either way the ABI handshake is checked (`NativeLoader.nVersion()` vs `NativeLoader.ABI_VERSION`); an ABI
mismatch is reported as an environment error, not as a parity failure. For the final report run
`.\rust\parity\run.ps1 -Gradle` so the packaged-artifact path is the one being proven.

## Output

```
out/report.md                 the benchmark + parity table with environment, command and details
out/run.log                   console log of the last run
out/classpath.txt             exact classpath of the last run
out/geometry-mismatches.txt   per-model first difference when geometry parity fails (for the M3 owner)
out/classes/                  javac output (regenerated every run)
```

`out/` is generated; it can be left uncommitted. `run.ps1` deletes and recreates `out/classes` each run.

Status meanings:

- `PASS` — the assertion held (equality or the fallback contract) or the row is a measurement of a
  passing module.
- `FAIL` — a real mismatch; the `details:` section names the model/bone/pixel and both values.
- `PENDING` — the owning milestone's native side is not in the loaded artifact yet (missing facade method,
  `bake`/`blob`/`emit` returned 0/-1 and the module was disabled, reader class absent). Rerun after the
  milestone reports done; this is not a parity failure by itself.
- `SKIP` — an optional measurement needs a Java facility that is missing in this environment (for example
  the `Tessellator` fields cannot be reflected).

The `speedup` column is Rust relative to Java: `> 1x` means Rust is faster. Time-based rows (ms/model)
use the same convention through `Report.timeSpeedup` (Java ms / Rust ms).

## Headless caveats

- No OpenGL context is created. The Java emission reference uses `Tessellator.startDrawing(7)` and the
  vertex methods only; `Tessellator.draw()` is never called. `rawBuffer`/`rawBufferIndex` are read/written
  through reflection, the same seam production uses in `TessellatorBufferKeep`/`TessellatorRawAccess`.
  Groups are rewound per bone so the nearly-full flush (which would call `draw()`) never triggers; groups
  larger than `0x1B000` ints are excluded from the comparison and counted in the report.
- The log4j configuration bundled in the 1.7.10 runtime references a `ServerGuiConsole` appender that does
  not exist headlessly; the resulting `ERROR Unable to locate appender` lines on stderr are harmless. The
  Java zstd port also triggers JDK 25 deprecation warnings for `sun.misc.Unsafe`; also harmless.
- The run is intentionally single-threaded and not a JMH benchmark: every measurement is a warm-up call,
  then three timed batches sized to roughly 0.4 s each, and the fastest batch is reported (`Bench.time`).
  Best-of-three is deliberate because Gradle/cargo builds may run on the same machine while the harness is
  used. Treat the numbers as a same-machine before/after comparison, not as absolute guarantees.
- No Rust-side test file is added by this harness on purpose: the Java harness already drives the real
  Rust code through the production JNI facades on the full built-in corpus, which subsumes a corpus
  integration test. The crate keeps its own unit tests under `rust/ysmu_native/src/**` (`cargo test`).

## Adding a module

1. Add `src/parity/ParityXxx.java` with a `public static void run(Path repo, Report report)` entry point
   that always reports at least one row (PASS/FAIL/PENDING/SKIP) and never throws.
2. Call it from `Main`'s module switch and add its id to `selectedModules`' default list.
3. Use `Corpus` for the built-in payloads, `Bench` for timing and `Report` for rows so the ASCII table and
   `report.md` stay in one shape.

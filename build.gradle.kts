import org.gradle.api.tasks.SourceSetContainer
import org.gradle.language.jvm.tasks.ProcessResources

plugins {
    id("com.github.ElytraServers.elytra-conventions") version "v1.1.1"
    id("com.gtnewhorizons.gtnhconvention")
}

// Rust acceleration layer (see .agent/phase78-rust-native-modules.md): cargoBuild stages the host cdylib into the jar,
// and NativeLoader falls back to the Java path when it is absent, so a missing toolchain never fails a build.
val rustLibraryName: String = run {
    val os = System.getProperty("os.name").lowercase()
    when {
        os.contains("win") -> "ysmu_native.dll"
        os.contains("mac") || os.contains("darwin") -> "libysmu_native.dylib"
        else -> "libysmu_native.so"
    }
}
val rustPlatform: String = run {
    val os = System.getProperty("os.name").lowercase()
    val arch = System.getProperty("os.arch").lowercase()
    val archTag = if (arch.contains("aarch64") || arch.contains("arm64")) "aarch64" else "x86_64"
    when {
        os.contains("win") -> "windows-$archTag"
        os.contains("mac") || os.contains("darwin") -> "macos-$archTag"
        else -> "linux-$archTag"
    }
}
val rustResources = layout.buildDirectory.dir("generated/rust-resources")
val rustTargetDir = layout.buildDirectory.dir("rust-target")

fun cargoAvailable(): Boolean = try {
    val process = ProcessBuilder("cargo", "--version").redirectErrorStream(true).start()
    process.inputStream.readBytes()
    process.waitFor() == 0
} catch (ignored: Exception) {
    false
}

val cargoBuild = tasks.register("cargoBuild") {
    group = "build"
    description = "Builds the Rust acceleration cdylib for this host and stages it as a mod resource."
    inputs.files(
        fileTree("rust") {
            include("Cargo.toml", "ysmu_native/Cargo.toml", "ysmu_native/src/**")
        })
    outputs.dir(rustResources)
    // Always re-run: a cargo no-op is cheap, and a deleted toolchain must not leave a stale library in the jar.
    outputs.upToDateWhen { false }
    doLast {
        val resources = rustResources.get().asFile
        resources.deleteRecursively()
        if (!cargoAvailable()) {
            logger.lifecycle("[rust] cargo is not installed; building the jar without native acceleration")
            return@doLast
        }
        val target = rustTargetDir.get().asFile
        val temp = layout.buildDirectory.dir("rust-tmp").get().asFile
        temp.mkdirs()
        // cc-rs links the bundled zstd C sources through MSVC, which uses TMP/TEMP for intermediates; keeping them in
        // the build directory avoids temp-dir ACL failures (LNK1104).
        val process = ProcessBuilder("cargo", "build", "--release").directory(file("rust"))
            .inheritIO()
            .apply {
                environment()["CARGO_TARGET_DIR"] = target.absolutePath
                environment()["TMP"] = temp.absolutePath
                environment()["TEMP"] = temp.absolutePath
                environment()["TMPDIR"] = temp.absolutePath
            }
            .start()
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            throw GradleException("cargo build failed with exit code $exitCode; see the output above")
        }
        val artifact = target.resolve("release").resolve(rustLibraryName)
        if (!artifact.isFile) {
            throw GradleException("cargo build succeeded but $artifact is missing")
        }
        val destination = resources.resolve("assets/ysmu/native/$rustPlatform")
        destination.mkdirs()
        artifact.copyTo(destination.resolve(rustLibraryName), overwrite = true)
        logger.lifecycle("[rust] bundled $rustLibraryName for $rustPlatform (${artifact.length()} bytes)")
    }
}

val cargoTest = tasks.register("cargoTest") {
    group = "verification"
    description = "Runs the Rust unit/parity tests of the acceleration layer."
    doLast {
        if (!cargoAvailable()) {
            throw GradleException("cargo is not installed; cannot run the Rust tests")
        }
        val target = layout.buildDirectory.dir("rust-target").get().asFile
        val temp = layout.buildDirectory.dir("rust-tmp").get().asFile
        temp.mkdirs()
        val process = ProcessBuilder("cargo", "test", "--release").directory(file("rust"))
            .inheritIO()
            .apply {
                environment()["CARGO_TARGET_DIR"] = target.absolutePath
                environment()["TMP"] = temp.absolutePath
                environment()["TEMP"] = temp.absolutePath
                environment()["TMPDIR"] = temp.absolutePath
            }
            .start()
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            throw GradleException("cargo test failed with exit code $exitCode; see the output above")
        }
    }
}

// The staged DLL is copied into processResources' output instead of a generated source dir: a srcDir would drag the
// binary into sourcesJar and make that task depend on cargoBuild.
tasks.named<ProcessResources>("processResources") {
    dependsOn(cargoBuild)
    from(rustResources)
}

// Headless parity + benchmark harness for the Rust layer (rust/parity/src, see rust/parity/README.md): prints
// PASS/FAIL plus a benchmark table and rewrites rust/parity/out/report.md, using the project's runtime classpath.
val mainSourceSet = extensions.getByType(SourceSetContainer::class.java).getByName("main")
val parityClasses = layout.buildDirectory.dir("parity-classes")
val compileParity = tasks.register<JavaCompile>("compileParity") {
    group = "verification"
    description = "Compiles the headless parity/benchmark harness (rust/parity/src)."
    source = fileTree("rust/parity/src") { include("**/*.java") }
    classpath = files(mainSourceSet.output, mainSourceSet.runtimeClasspath)
    destinationDirectory.set(parityClasses)
    options.encoding = "UTF-8"
}
tasks.register<JavaExec>("nativeParity") {
    group = "verification"
    description = "Runs the headless Rust-layer parity + benchmark harness against the packaged artifact."
    dependsOn(compileParity, "processResources")
    classpath = files(parityClasses, mainSourceSet.output, mainSourceSet.runtimeClasspath)
    mainClass.set("parity.Main")
    workingDir = projectDir
    // Pass-through switches for the harness, e.g. -PparityArgs="--modules=zstd,png" or -PparityArgs="--direct".
    (project.findProperty("parityArgs") as String?)?.let { args = it.split(" ") }
}

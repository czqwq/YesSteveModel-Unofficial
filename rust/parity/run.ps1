#Requires -Version 5.1
<#
    YSMU Rust acceleration layer - headless parity + benchmark harness.

    Plain `javac` + `java`: the mod has no usable Gradle test task, so the harness compiles its own
    sources against the already-built mod classes and Minecraft, then runs from the repository root
    (so relative paths like `config/ysmu/native/...` resolve exactly as they do in game).

    Usage (from anywhere):
        ./rust/parity/run.ps1                  # use the already-built classpath and native artifact
        ./rust/parity/run.ps1 -Gradle          # first run `gradlew processResources -PysmuNative=build` (rebuilds the host DLL)
        ./rust/parity/run.ps1 -DirectLoad      # load the newest DLL with System.load instead of NativeLoader
        ./rust/parity/run.ps1 -Modules zstd,png

    The classpath is assembled here by scanning the Gradle module cache; see README.md for the
    rationale and the exact list.
#>
[CmdletBinding()]
param(
    # Re-run `.\gradlew.bat processResources -PysmuNative=build` first so build/resources/main carries the freshly built DLL.
    [switch]$Gradle,
    # Skip the NativeLoader path and System.load the newest library file directly.
    [switch]$DirectLoad,
    # Comma-separated subset of zstd,png,geometry,render (default: all).
    [string[]]$Modules = @()
)

$ErrorActionPreference = 'Stop'

$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$repo = (Resolve-Path (Join-Path $here '..\..')).Path
Set-Location $repo

$cache = 'D:\gradle_cache\caches\modules-2\files-2.1'
if (-not (Test-Path $cache)) {
    throw "Gradle module cache not found at '$cache'. Edit `$cache at the top of rust/parity/run.ps1."
}

function Find-Jar {
    param([string]$Group, [string]$Artifact, [string[]]$Versions)
    $dir = Join-Path (Join-Path $cache $Group) $Artifact
    if (-not (Test-Path $dir)) { return $null }
    foreach ($version in $Versions) {
        $versionDir = Join-Path $dir $version
        if (Test-Path $versionDir) {
            $jar = Get-ChildItem $versionDir -File -Filter "$Artifact-*.jar" |
                Where-Object { $_.Name -notmatch '-(sources|javadoc)\.jar$' } |
                Select-Object -First 1
            if ($jar) { return $jar.FullName }
        }
    }
    # Fallback: newest non-sources jar anywhere under the artifact directory.
    $jar = Get-ChildItem $dir -Recurse -File -Filter "$Artifact-*.jar" |
        Where-Object { $_.Name -notmatch '-(sources|javadoc)\.jar$' } |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
    if ($jar) { return $jar.FullName }
    return $null
}

if ($Gradle) {
    Write-Host '[parity] running gradlew processResources -PysmuNative=build (rebuilds the host DLL and packages it)...'
    & .\gradlew.bat processResources -PysmuNative=build --console=plain --offline
    if ($LASTEXITCODE -ne 0) { throw "gradlew processResources failed with exit code $LASTEXITCODE" }
}

$entries = New-Object System.Collections.Generic.List[string]

$required = @(
    'build\classes\java\main',
    'build\resources\main',
    'build\rfg\recompiled_minecraft-1.7.10.jar'
)
foreach ($relative in $required) {
    $path = Join-Path $repo $relative
    if (-not (Test-Path $path)) {
        throw "missing build output '$relative'. Run .\gradlew.bat compileJava processResources --offline (or -Gradle) first."
    }
    $entries.Add($path)
}
# Native build output (only present after -PysmuNative=build); useful on a fresh build.
$rustResources = Join-Path $repo 'build\generated\rust-resources'
if (Test-Path $rustResources) { $entries.Add($rustResources) }

# Third-party jars the engine reads at runtime. Versions are the ones the mod is built against
# (dependencies.gradle) or the 1.7.10-era versions Minecraft/Forge itself ships.
$dependencies = @(
    @{ Group = 'com.fasterxml.jackson.core'; Artifact = 'jackson-databind'; Versions = @('2.9.0') },
    @{ Group = 'com.fasterxml.jackson.core'; Artifact = 'jackson-core'; Versions = @('2.9.0') },
    @{ Group = 'com.fasterxml.jackson.core'; Artifact = 'jackson-annotations'; Versions = @('2.9.0') },
    @{ Group = 'com.fasterxml.jackson.datatype'; Artifact = 'jackson-datatype-jsr310'; Versions = @('2.9.0') },
    @{ Group = 'com.google.code.gson'; Artifact = 'gson'; Versions = @('2.10.1', '2.11.0', '2.8.0', '2.2.4') },
    @{ Group = 'com.google.guava'; Artifact = 'guava'; Versions = @('17.0', '31.1-jre', '32.1.3-jre', '33.5.0-jre') },
    @{ Group = 'org.apache.commons'; Artifact = 'commons-lang3'; Versions = @('3.12.0', '3.8.1', '3.3.2') },
    @{ Group = 'commons-io'; Artifact = 'commons-io'; Versions = @('2.4', '2.15.1') },
    @{ Group = 'javax.vecmath'; Artifact = 'vecmath'; Versions = @('1.5.2') },
    @{ Group = 'it.unimi.dsi'; Artifact = 'fastutil'; Versions = @('8.5.18') },
    @{ Group = 'org.apache.logging.log4j'; Artifact = 'log4j-api'; Versions = @('2.0-beta9-fixed') },
    @{ Group = 'org.apache.logging.log4j'; Artifact = 'log4j-core'; Versions = @('2.0-beta9-fixed') },
    @{ Group = 'commons-codec'; Artifact = 'commons-codec'; Versions = @('1.9', '1.16.0') },
    @{ Group = 'org.lwjgl.lwjgl'; Artifact = 'lwjgl'; Versions = @('2.9.4-nightly-20150209', '2.9.3', '2.9.2') },
    @{ Group = 'org.lwjgl.lwjgl'; Artifact = 'lwjgl_util'; Versions = @('2.9.4-nightly-20150209', '2.9.3', '2.9.2') }
)

$missing = New-Object System.Collections.Generic.List[string]
foreach ($dependency in $dependencies) {
    $jar = Find-Jar -Group $dependency.Group -Artifact $dependency.Artifact -Versions $dependency.Versions
    if ($jar) {
        $entries.Add($jar)
    } else {
        $missing.Add("$($dependency.Group):$($dependency.Artifact)")
    }
}
if ($missing.Count -gt 0) {
    throw "missing dependency jars in the Gradle cache: $($missing -join ', '). The harness needs them to load the engine's classes."
}

$classpath = $entries -join ';'

$out = Join-Path $here 'out'
$classes = Join-Path $out 'classes'
New-Item -ItemType Directory -Force -Path $out | Out-Null
if (Test-Path $classes) { Remove-Item -Recurse -Force $classes }
New-Item -ItemType Directory -Force -Path $classes | Out-Null
Set-Content -Path (Join-Path $out 'classpath.txt') -Value $classpath -Encoding UTF8

$sources = Get-ChildItem -Path (Join-Path $here 'src') -Recurse -File -Filter '*.java' |
    ForEach-Object { $_.FullName }
if (-not $sources) { throw 'no harness sources found under rust/parity/src' }

Write-Host "[parity] compiling $($sources.Count) harness sources..."
$javacArgs = @('-encoding', 'UTF-8', '-nowarn', '-d', $classes, '-cp', $classpath) + $sources
& javac @javacArgs
if ($LASTEXITCODE -ne 0) { throw "javac failed with exit code $LASTEXITCODE" }

$moduleArg = @()
if ($Modules.Count -gt 0) { $moduleArg = @("--modules=" + ($Modules -join ',')) }
if ($DirectLoad) { $moduleArg += '--direct' }

# Record the exact invocation in out/report.md (the harness reads YSMU_PARITY_COMMAND for its Command block).
$commandParts = @()
if ($Gradle) { $commandParts += '-Gradle' }
if ($Modules.Count -gt 0) { $commandParts += '-Modules ' + ($Modules -join ',') }
if ($DirectLoad) { $commandParts += '-DirectLoad' }
$env:YSMU_PARITY_COMMAND = "cd `"$repo`"`n.\rust\parity\run.ps1 " + ($commandParts -join ' ')

$log = Join-Path $out 'run.log'
Write-Host '[parity] running harness from the repository root...'
& java "-Dfile.encoding=UTF-8" "-Dsun.stdout.encoding=UTF-8" -cp "$classes;$classpath" parity.Main @moduleArg 2>&1 |
    Tee-Object -FilePath $log
$exitCode = $LASTEXITCODE
Write-Host "[parity] harness exit code: $exitCode"
exit $exitCode

# Provenance: the vendored GeckoLib engine

This file records where the GeckoLib 3 animation engine inside this repository came from, so that
"which engine is in this jar" stays answerable. It was written when the engine was vendored
(2026-02-20, alongside the change that deleted `libs/geckolib-5.09.52.417-dev.jar`).

## What is vendored

The engine's source is in this repository under three package roots:

- `src/main/java/software` - 275 files
- `src/main/java/com/eliotlash` - 48 files
- `src/main/java/net/geckominecraft` - 4 files

327 files in total, verified 327/327 byte-identical to the source repository at snapshot time.

The `@Mod` annotation and its `preInit` handler were removed from
`software/bernie/geckolib3/GeckoLib.java`, which stays as a plain class carrying `LOG` and the
`geoStatsEnabled` switch (17 read sites in 11 engine files, plus `Config.java:294`). That is the only
file in the three trees that differs from the source repository; the javadoc was rewritten at the
same time. The engine therefore ships inside the YSMU jar and `geckolib` is no longer a mod id this
build registers.

## Where it came from

- Source repository: `E:\IDEA\Geckolib` (symlinked into this workspace as `tmp/Geckolib`).
- Engine repository state when the snapshot was taken: branch `master`, HEAD
  `ef11b7a2ccccb6409106c7af08365b907225c237`, with one uncommitted working-tree modification
  (`src/main/java/software/bernie/geckolib3/core/builder/Animation.java`).
- The previous workflow recorded the engine commit `1154d090f337c5960a787c21571575681d5594bb`
  (`master`) as its fixed baseline, with `5ca328b` one `.gitignore` line later
  (`.agent/phase7-dev-rebaseline.md`).
- **No hash of a file manifest was recorded** for this snapshot, by the captain or by any earlier
  agent, and no manifest file exists in this repository. It is not reconstructed here, because a hash
  computed after the fact describes the current tree rather than the snapshot. The file counts above
  and the "only `GeckoLib.java` differs" statement are the recorded identity; if a manifest hash is
  wanted, the tree must be re-hashed against the source repository before any further engine edit.

## One-time snapshot, no upstream to sync from

This is a **one-time source snapshot**. There is no sync path from `E:\IDEA\Geckolib` into this
repository: no build step copies from it, no version is pinned, and no upstream merge is planned. The
externally-built `libs/geckolib-*.jar` it replaced is gone, and with it the "edit the engine, rebuild
its jar, reinstall it in `libs/`" workflow.

## Ownership

The vendored `software/bernie`, `com/eliotlash` and `net/geckominecraft` trees are third-party
compatibility code **owned by this repository**. An engine change is therefore made here, not in the
engine repository. Keep every such edit as narrow as the defect - do not refactor, re-style or
reformat vendored code around a fix - and record the deviation, with its reason and its upstream
counterpart, in the relevant `.agent/` ExecPlan and in this file.

## Licence

The engine is MIT-licensed. Its licence text was copied verbatim to `LICENSE-geckolib-engine.txt`
(byte-identical to `E:\IDEA\Geckolib\LICENSE`, SHA-256
`C4D38B71AEFA6FEB4CB26A12353698489B1E0951859FF6DA24A173B7005873CC`; copyright (c) 2026 czqwq).

# Phase 7: Consume GeckoLib as a separate mod dependency

This ExecPlan is a living document and follows `.agent/PLANS.md`.

## Purpose / Big Picture

YSMU used to vendor the whole GeckoLib 3 engine (156 files under `software/bernie`,
`com/eliotlash` and `net/geckominecraft`). The engine has since been extracted into a standalone
Forge 1.7.10 mod (mod id `geckolib`), and TouhouLittleMaid needs the same engine. FML 1.7.10 has a
single class loader, so two mods shipping `software.bernie.geckolib3` silently shadow each other.
This phase deletes the vendored tree and makes YSMU depend on the shared engine jar, so exactly one
copy of the engine exists at runtime.

## Progress

- [x] Diff the vendored tree against the standalone engine repository: 0 files exist only in YSMU, so
      nothing is lost by deleting it.
- [x] Copy the engine's `dev` jar into `libs/` and declare it on the compile, test and dev-runtime
      classpaths.
- [x] Delete `src/main/java/{software,com/eliotlash,net}` and YSMU's duplicate MoLang physics runtime.
- [x] Migrate YSMU onto the engine's host contract (`IMolangPhysicsScope`).
- [x] Drop YSMU's Jackson dependency and the duplicate `geckolib_at.cfg`.
- [x] Compile all main and test sources against the jar: 0 errors.
- [ ] Build/test verification by the user (`gradlew build`), which this environment does not run.

## Decision Log

- Decision: depend on the engine's `dev` jar from `libs/` rather than republishing it through Maven.
  Rationale: the artifact is already built and committed to the workspace, the sandbox cannot run
  Gradle to publish it, and `compileOnly` + `devOnlyNonPublishable` keeps it out of YSMU's POM.
  The published release jar is reobfuscated, so only the `dev` jar can be compiled against.
- Decision: delete YSMU's `com.fox.ysmu.client.animation.molang.{MolangPhysicsRuntime, MolangPhysicsState,
  RemoteAnimationVariables}` and use the engine's copies instead.
  Rationale: the engine's MoLang parser and functions resolve `MolangPhysicsRuntime` inside their own
  package. Keeping a host-side copy would compile but leave the engine's scope state unpopulated, so
  every `v.*` read, bone query and first/second order filter would silently return 0.
- Decision: merge the two `geckolib_at.cfg` lines into `ysmu_at.cfg` and delete the old file.
  Rationale: YSMU's own render code accesses `Minecraft.timer` and `RenderHelper`, and the separate
  GeckoLib mod already ships those same ATs for its own jar; shipping a second `geckolib_at.cfg` would
  duplicate them.
- Decision: keep `com.fox.ysmu.compat.Axis`/`Utils`.
  Rationale: YSMU's `RenderUtil` and `YsmCommand` still use them; the engine's `util.RenderUtils` was
  relocated onto its own `software.bernie.geckolib3.compat` copies.

## Plan of Work

`dependencies.gradle` puts `libs/geckolib-5.09.52.417-dev.jar` on `compileOnly`,
`testImplementation` and `devOnlyNonPublishable`. `ysmu.java` declares
`required-after:geckolib`. `CustomPlayerEntity` implements the engine's `IMolangPhysicsScope`
(`getMolangEntity`, `getMolangModelId`, `getMolangAnimationId`) and `CustomPlayerModel`,
`ClientModelManager` and `ClientEventHandler` import the engine's
`core.molang.MolangPhysicsRuntime` / `core.molang.RemoteAnimationVariables` instead of the deleted
host copies.

## Validation and Acceptance

- `.gradlew.bat build` succeeds (requested from the user; this environment does not run Gradle).
- No `software/bernie`, `com/eliotlash` or `net/geckominecraft` sources remain in the repository.
- Main and test sources compile against the jar with zero errors.
- At runtime the GeckoLib mod supplies the engine; YSMU's Molang variables, bone queries and
  first/second order physics still resolve.

## Interfaces and Dependencies

    libs/geckolib-5.09.52.417-dev.jar                        the shared engine (mod id `geckolib`)
    software.bernie.geckolib3.core.molang.IMolangPhysicsScope host contract YSMU implements
    software.bernie.geckolib3.core.molang.MolangPhysicsRuntime the engine's per-frame scope
    software.bernie.geckolib3.core.molang.RemoteAnimationVariables the engine's roaming-var store

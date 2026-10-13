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
`geoStatsEnabled` switch (17 read sites in 11 engine files, plus `Config.java:294`). That was the only
file the three trees differed in **at vendoring time**; the javadoc was rewritten at the same
time. The engine therefore ships inside the YSMU jar and `geckolib` is no longer a mod id this
build registers. Later port work has also edited engine files - an engine edit is made in this
repository now - and every such deviation is recorded in `.agent/divergences.md`; the list below is
the part of it that is written against this snapshot.

## Deviations recorded against this snapshot

- **The immutable render context, ported into six engine files.** A GUI preview must present one stable pose for a
  whole frame; upstream gets that from its `RenderContext`'s `immutable` bit
  (`tmp/YesSteveModel-dev-1.20 .../geckolib3/geo/RenderContext.java:9-21`), resolved through the animatable's
  whitelist (`.../model/AnimatableEntity.java:452-461`) and consumed by `tickAnimation`'s
  `mutableRender` (`:344`) with `shouldUpdate`/`shouldTick` (`:373-374`) and a single `mainRenderData` for an
  immutable context (`:265-277`). Recorded in full in `.agent/phase42-track-f-engine.md`.
  - **`software/bernie/geckolib3/geo/RenderContext.java` - new, upstream's own type.** A record carrying the four
    bits this port can answer (`firstPersonMod`, `inventory`, `paperDoll`, `immutable`), `withImmutable` (`:16-21`),
    a `NONE` constant (the mutable context an engine-built event carries) and one factory per pass. Upstream's
    `level`, `irisShadow` and `offScreen` fields and its `nativeType()` (`:9`, `:23-28`) are deliberately **not**
    modelled: this port has no native renderer layer and no Iris shadow pass, and upstream's only extraction point
    passes `offScreen` as `false` (`util/RenderUtil.java:57-67`).
  - **`software/bernie/geckolib3/geo/ImmutableFramePose.java` - new, the port's stand-in for upstream's extracted
    model state.** `AnimatableEntity:255-263` extracts the posed model into `GeoRenderData.modelState` and `:265-277`
    reuses it; this port's renderer draws the live `GeoBone`s and every preview draw restores a `ModelPoseSnapshot`
    (`com.fox.ysmu.client.render.ModelPoseSnapshot`), so the smallest equivalent is a nine-float-per-bone record
    written back onto the bones. It holds no bone reference, so it cannot keep released geometry alive.
  - **`software/bernie/geckolib3/core/IAnimatable.java` - `determineImmutableContext(RenderContext)`, default
    `false`.** Upstream asks its animatable the same question (`AnimatableEntity:456-461`, with
    `client/gui/CustomGuiPlayerEntity.java:43-46` answering true); its own default reads the three flags this port
    does not model, so the port's default is false and only an animatable that declares otherwise enters the path.
  - **`software/bernie/geckolib3/core/event/predicate/AnimationEvent.java` - the event carries the context.**
    `getRenderContext()` is never null (`RenderContext.NONE` until a caller attaches one) and `setRenderContext`
    normalises null to it, so every engine-built event (`AnimatedGeoModel:71`, `AnimationController:1084`,
    `GeoEntityRenderer:107`) keeps today's mutable behaviour. Upstream's event carries the same value
    (`.../core/event/predicate/AnimationEvent.java:15`, read at `AnimatableEntity:343`).
  - **`software/bernie/geckolib3/core/manager/AnimationData.java` - one `immutableFrame` field.** Upstream keeps this
    per-frame state on the animatable (`AnimatableEntity:52`, `:55-58`); this port's animatables are host classes
    implementing `IAnimatable` while `AnimatedGeoModel` is shared by all of them, so the per-animatable state lands
    on the object that already is per-animatable.
  - **`software/bernie/geckolib3/model/AnimatedGeoModel.java` - the gate.** `setLivingAnimations` resolves
    `immutable = context.immutable() || animatable.determineImmutableContext(context)` and, for an immutable context
    whose frame already produced a pose, writes that pose back and returns before
    `animationProcessor.tickAnimation` (`:376-386` is the branch it replaces). The frame is the render tick
    (`seekTime`, upstream's `renderTicks`, `AnimatableEntity:342`). The union of the two declaration routes is
    upstream's own effective semantics: `resolveRenderContext`'s context always comes from
    `RenderUtil.extractRenderContext()` with `immutable = false` (`:57-67`, so the whitelist never downgrades
    anything), while `client/entity/CustomEntity.java:247` hands an already-immutable context straight to
    `update(float, RenderContext)`. `AnimationProcessor` and `AnimationController` are untouched, so the HUD/world
    double-process invariant (`AGENTS.md` Runtime Flow, `.agent/phase7-geckolib-dependency.md`) is unaffected: that
    path is mutable by default and every engine-built event is `RenderContext.NONE`.

- **Authored concurrent Bedrock controller players (verification pending).**
  `AnimationController` owns one holder per state animation, each with independent processed-clock completion,
  events and sound token, and folds weighted position/rotation sums and scale products. Legacy sequential queues
  remain. State progression uses visited zero-player destinations, name-derived hierarchy (depth 5), segmented
  transition values and per-child blend weights. LOOP/HOLD completion uses `>`; PLAY_ONCE completion starts at `>=`
  when ending begins. The initial universal-strict-boundary finding was corrected from deeper source review.
  See `.agent/task30-animation-finished-deviation.md` for upstream sources, the port-specific same-tick HUD guard,
  and unverified runtime acceptance. No shortest-path consumer was invented.


- **`software/bernie/geckolib3/geo/raw/pojo/ModelProperties.java` — `forceCulling` documentation corrected.**
  The native consumer is available at `tmp/YesSteveModel-Native-dev/modules/gfx/src/bake/baked_model.cc:685-734`:
  it chooses culling partitions for full six-face cubes without turning translucent faces opaque. Rendering then
  uses transformed face planes and winding. The initial edit changed documentation only; task32 now adds texture-owned
  immutable per-face alpha partitions, clip-plane/winding CPU culling and per-segment translucent ordering to
  `IGeoRenderer`, including its direct-arm entry. Shared `GeoCube` geometry is unchanged. `MaterialSource` is the
  host interface for CPU texture pixels; unavailable pixels keep the legacy fallback. See
  `.agent/task32-material-execplan.md` for native bake/transform evidence and optional Angelica publication,
  holder invalidation and GPU-accounting ownership. Shader visual parity and arbitrary callback GL state remain
  unverified; continuous segments do not sort across layers or callbacks.

- **`software/bernie/geckolib3/geo/material/MaterialFacing.java` — a mirrored draw is not CPU-culled (phase45).**
  The port mirrors the model in two of its draws, both vanilla conventions it follows: the biped arm
  (`FirstPersonHandRenderer#alignGeckoArmToVanillaBipedArm`, `glScalef(1, -1, 1)`) and the inventory preview / paper
  doll (`RenderUtil#renderPlayerEntity`, `glScalef(-scale, scale, scale)`; vanilla's own
  `GuiInventory.func_147046_a:94` is the same shape). Live client report: the arms of every opaque-skinned built-in
  model showed holes and see-through cubes, the bodies did not. Cause: the native facing rule the material path
  ports (`render_state_impl.inc:35-50`) takes its sign from the determinant of the chain its cofactors come from, so
  a mirror turned "the side the viewer sees" into "the side the viewer is behind" - and only cubes whose six faces
  are opaque get the culling partition that drops faces (`MaterialBake:44-53`), which is why arms were affected and
  bodies were not. The native renderer is safe here because its orientation sign is accumulated **per bone**
  (`pose_stack_impl.inc:53-60`, consumed at `render_state_impl.inc:55-62`) and its outer matrix never mirrors
  (upstream's own arm mirror is the even-handed `scale(-1, -1, 1)`); the port's mirrors sit in the middle of the
  model-to-eye chain, where a segment cannot undo them from its own matrices. `MaterialFacing.mirrored(Matrix4f)`
  detects them (handedness of the model-to-eye matrix, never of a model-to-clip one) and
  `MaterialFacing.culled(face, coefficient, mirrored)` then keeps the faces - for a closed model that is the same
  picture, since the far faces stay behind the near ones for the depth test and the segment draws with GL culling off
  anyway (`MaterialDrawSession#drain`). Unmirrored draws are byte-for-byte the previous behaviour. Verified by
  `MaterialFacingSortTest` (11 tests in the package, including the real built-in arm asset baked cube by cube);
  `.agent/phase45-mirrored-draw-face-culling.md` records the evidence and the one remaining gap (the paper doll's own
  `glFrontFace` half, left for the owner).

- **`software/bernie/geckolib3/geo/GeoReplacedEntityRenderer.java` - the frame's animation event moved into a method,
  so a second animatable of the same entity can be ticked with it.** `doRender` built the `EntityModelData` and the
  `AnimationEvent` inline and kept four of its inputs in locals for the layer pass; the port's first-person arm is a
  second animatable for the same player and must be ticked with the same per-frame values (its bones had never entered
  an `AnimationProcessor` at all, which is why that arm drew at its bind pose). The computation moved verbatim into
  `buildAnimationEvent(EntityLivingBase, float, T)` plus `resolveBodyYaw(EntityLivingBase, float, boolean)`; `doRender`
  reads the four locals back off the event it already has (the two head values are stored negated in the entity data,
  so they are negated back) and calls the same two methods. No behaviour change for a world draw. **Upstream
  counterpart: yes, and this is its shape** - one event builder per entity, used by both the body and the arm
  (`tmp/YesSteveModel-dev-1.20` `geckolib3/model/AnimatableEntity.java:280-339` builds it, `:341-390` ticks with it,
  `client/entity/CustomFirstPersonArmEntity.java:105-108` is the arm's override). Recorded in
  `.agent/phase22-first-person-arm-pose.md`.
- **`com/eliotlash/mclib/math/MathBuilder.java:147-171` - the two-character operator merge deleted the assignment
  operator, so a ternary right after `=` failed to parse.** Two cleanup paths that the code comment already
  described as alternatives (the merged operator's first character is either in the token buffer or was pushed
  into `symbols`) were executed in sequence, and the second one removed the last `symbols` entry whenever its text
  equalled that character - which is also true of an assignment `=` that `MolangParser` pushes as its own
  single-character operator. `v.x = v.y==0?1:v.y` therefore lost its assignment, `MolangParser.parseOneLine:393-396`
  stopped recognising it, and `tryTernary` read `[v.x, v.y, ==, 0]` as the condition and threw
  (`IndexOutOfBoundsException` at `MathBuilder:283`). Fix: the `symbols` cleanup became an `else if` of the buffer
  cleanup. **Upstream counterpart: none** - `tmp/YesSteveModel-dev-1.20` vendors no mclib math parser at all
  (`NOTICE.md:20`), and its own lexer keeps `=` and `==` as different tokens
  (`com/elfmcys/ysm/molang/parser/MolangParserImpl.java:310-311`) with `?` at its own precedence (`:256-271`), so
  the ambiguity cannot arise there. Guarded by `MolangParserCompatibilityTest.ternaryImmediatelyAfterAssignmentParses`.
- **`com/eliotlash/mclib/math/MathBuilder.java` - a unary minus in front of a parenthesised group failed to parse.**
  `breakdownChars` folds a minus into the token buffer for every follower except `(`, and the `(` branch pushes the
  buffered `-` as its own symbol, so `-(1)/25` tokenises to `[-, [1], /, 25]`. `parseSymbols` split at the `/`,
  recursed into `[-, [1]]`, found no operator (`lastOp == -1`) and threw `IndexOutOfBoundsException` from
  `operationForOperator(symbols.get(-1))`, killing the whole expression. `-(1)` alone worked only because it has no
  binary operator, and `-(1)+25` / `-(1)-(2)` only because `+`/`-` rank below `*`/`/` in the split rule. The logged
  shape is the model particle argument `-(ysm.bone_pivot_abs('X').z)/25`. Fix: `parseLeadingUnaryMinus`, called first
  in `parseSymbols`, resolves a bare `-` that sits in operand position (first symbol, or after an operator or a comma)
  by replacing the operator to its left along with it - so `-(1)*25` stays `(-1)*25` and the binary `10-3` is
  untouched. **Upstream counterpart: none** - `tmp/YesSteveModel-dev-1.20` vendors no mclib math parser (`NOTICE.md:20`)
  and its own Pratt parser has a real `UnaryExpression` (`com/elfmcys/ysm/molang/parser/MolangParserImpl.java:156-158`).
  Guarded by `MolangParserCompatibilityTest.unaryMinusBeforeAGroupParses`.
- **`software/bernie/geckolib3/core/molang/MolangParser.java` - the vector bone queries are registered, not only
  rewritten.** The `).<axis>` rewrite (`rewriteVectorFunction`, reached from the `breakdown` override) matches only a
  call whose own `)` is immediately followed by `).x`, and a pack also writes the vector form bare
  (`math.max(ysm.bone_pivot_abs('Root'),ysm.bone_pivot_abs('AllBody'))`), which used to throw
  `Function 'ysm.bone_pivot_abs' couldn't be found!` and kill the whole timeline instruction. The four engine-owned
  names (`ysm.bone_rot`/`bone_pos`/`bone_position`/`bone_scale`) are now registered in `doCoreRemaps` beside their
  `_x`/`_y`/`_z` forms (the host-owned `ysm.bone_pivot_abs` in `AnimationRegister.registerFunctions`), the
  `axis()` of `BoneRotation`/`BonePosition`/`BoneScale` falls back to `x` when the registered name carries no axis
  character, and `MolangParser.VECTOR_FUNCTIONS` + `isVectorFunctionName` give hosts one table to ask instead of
  re-deriving which names the rewrite supports. **Upstream counterpart: yes, its shape** -
  `tmp/YesSteveModel-dev-1.20` binds the same names as real functions and answers a member-access struct
  (`YSMBinding.java:79-85`, `functions/BoneParamFunction.java:13-31`, `struct/Vec3fStruct.java:11-23`); mclib's
  `IValue` is a bare `double`, so the member access stays a rewrite and only the bare form is answered by a
  fallback axis, which upstream's unfinished `BoneAbsolutePivot` does not define either. Recorded in
  `.agent/phase24-vector-bone-queries.md`.
- **`software/bernie/geckolib3/core/molang/MolangParser.java` - a braced block is one statement, with upstream's
  execution-scope semantics.** `splitStatements` used to split on every `;` outside a string literal, so a pack's
  `cond?{stmt;stmt}:{stmt}` timeline block (LaPluma2.5.3's hair/camera physics, the `?{` / `}:{` fragments in the
  client log's "Failed to execute OpenYSM timeline Molang instruction" lines) was cut into fragments starting with
  `}` and parsed one by one, each failing; a self-contained `{stmt}` fared worse, because
  `replaceBracePlaceholders` turned it into `0`, so the block's assignments silently never ran. It now tracks brace
  depth and splits only at depth 0, and `parseBracedStatement` (first thing `parseOneLine` does, before mclib's
  tokenizer) builds the block as a `MolangMultiStatement` and a braced ternary as a `MolangValue` that evaluates only
  the arm it takes. `{data3}` stays the port's documented 0-valued placeholder. **Upstream counterpart: yes, its
  shape** - `tmp/YesSteveModel-dev-1.20` lexes `{` (`molang/lexer/MolangLexerImpl.java:269-270`), parses it as an
  execution scope (`molang/parser/MolangParserImpl.java:90-118`,
  `molang/parser/ast/ExecutionScopeExpression.java:42-56`), evaluates it as an ordered sequence whose value is the
  last statement (`molang/runtime/ExpressionEvaluatorImpl.java:241-257`) and takes only the selected ternary arm
  (`:393-399`, colon-less form at `MolangParserImpl.java:269`); `SOURCE` splits the same way as the old port did, so
  its only form of this shape is `PackFunctionScript.java:229-309` for `.molang` files, which this mirrors. Recorded
  in `.agent/phase25-braced-molang-scopes.md`.
- **`com/eliotlash/mclib/math/functions/utility/DieRoll.java` and `DieRollInteger.java` - the snapshot's roll loop
  never terminated.** Both classes had `while (i < getArg(0))` with no increment and a spread of
  `(getArg(2) - getArg(2))`, i.e. exactly 0, so `math.die_roll(...)` / `math.die_roll_integer(...)` with a roll count
  above 0 spun forever on the client thread instead of answering a value. The bodies are now upstream's
  (`tmp/YesSteveModel-dev-1.20` `.../geckolib3/core/molang/builtin/math/DieRoll.java:14-30` and
  `DieRollInteger.java:14-31`, which the vendored newer MoLang VM mirrors item-for-item at
  `software/bernie/geckolib3/molang/builtin/math/DieRoll.java`); upstream's two classes are genuinely different shapes
  (round vs truncate, bounds float vs int, a different swap and `nextFloat` vs `nextInt`), so both were ported
  separately. Two documented substitutions: `Math.random()` stands in for upstream's per-entity `RandomSource`, which
  this layer has no entity to reach, and a degenerate span answers `min` instead of upstream's `nextInt(0)`. Note this
  is a defect the **reference branch carries too** (`tmp/YesSteveModel-Unofficial-perf-previewUI/.../mclib/math/
  functions/utility/DieRoll.java:26-28`), so it is an example of what "copy the reference implementation verbatim"
  must not copy. Recorded in `.agent/phase27-mclib-math-defects.md`.
- **`com/eliotlash/mclib/math/functions/classic/Sin.java` and `Cos.java` are unreachable, and stay as they are.** They
  compute `Math.sin`/`Math.cos`, i.e. radians, which contradicts upstream's degrees
  (`tmp/YesSteveModel-dev-1.20` `.../molang/builtin/math/Sin.java:11` `Mth.sin(arg/180f*PI)`) - but they never run:
  `MolangParser.java:98-99` registers `SinDegrees`/`CosDegrees` under the short keys first and `:181`/`:153` +
  `remap` (`:217-222`) remove the originals afterwards, so the live `math.sin` is
  `software/bernie/geckolib3/core/molang/functions/SinDegrees.java:21` (`arg / 180 * PI`, degrees). Javadoc was added to
  both classes recording that, so nobody converts them to degrees on the assumption that they are live. Recorded in
  `.agent/phase27-mclib-math-defects.md` section 0.
- **`MolangParser.doCoreRemaps` registers four more upstream names, one per implementation.** `math.roll`,
  `math.rolli`, `math.hermite` and `math.randomi` (`:158-166`) are upstream's own spellings for the same four
  implementations the `math.die_roll`/`die_roll_integer`/`hermite_blend`/`random_integer` names already use
  (`tmp/YesSteveModel-dev-1.20` `.../molang/builtin/MathBinding.java:46-48` vs `:54-57`), and a pack written against
  upstream lost the whole channel on the missing name. The neighbouring classes were reused rather than copied, and
  `remap` was deliberately not used because it deletes the short keys existing packs call. Recorded in
  `.agent/phase27-mclib-math-defects.md`.
- **`software/bernie/geckolib3/core/molang/MolangPhysicsRuntime.java` - the debug-overlay accessors, with the
  per-frame snapshot made demand-driven.** The port's debug overlay needs the same view of the last frame's `v.*`
  scope that the reference branch's host-side `MolangPhysicsRuntime` provides, so the engine gained
  `getLastFrameVariables()`, `getLastFrameVariable(String)`, `clearLastFrameSnapshot()`, `clearPlayer(UUID)`,
  `getGlobalVarSource(String)`, `noteGlobalVarOwner(String)` (the last writes the `GLOBAL_VAR_OWNER` table its single
  producer fills - `MolangInstructionExecutor` on a `MolangAssignment`, the reference's own producer at
  `.../MolangInstructionExecutor.java:340`), plus `setSnapshotCaptureEnabled`/`isSnapshotCaptureEnabled`, and `clear()`
  now drops the snapshot and the owner table with the scope it already cleared. **The one deliberate divergence:**
  the reference copies the whole scope into a new `HashMap` in `end()` **unconditionally, every frame**
  (`tmp/YesSteveModel-Unofficial-perf-previewUI/.../client/animation/molang/MolangPhysicsRuntime.java:244-250`) -
  a per-frame allocation paid in normal play, purely for a debug overlay and a `/ysm query`. The port copies only
  while capture is demanded (`Config.DEBUG_MOLANG_OVERLAY && (queryCaptureRequested || DebugOverlay.isActive())`, one
  predicate in `MolangDebugSnapshot.refreshCaptureDemand()`), so a default install allocates nothing; a query issued
  between frames requests a capture and defers its read one hop, so it never reads a stale map. Three of the
  reference's members were deliberately **not** added: `getModelDisplayName` stays host-side
  (`client/debug/MolangDebugSnapshot.modelDisplayName` over `ModelIdUtil`) because the engine must not reference the
  host - the engine's own `GeoStats.java:19` records that rule - and `getGlobalScopedValue` /
  `isGlobalVarReadable` / `noteUnscopedGlobalVarWrite` are omitted because they need
  `ScopedMolangVariable.globalFallback` / `unscopedWriteSink`, which this tree never wires; adding them would be dead
  code. Recorded in `.agent/phase33-m4-debug-overlay-ported.md`.

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

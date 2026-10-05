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

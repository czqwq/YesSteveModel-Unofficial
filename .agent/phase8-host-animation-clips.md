# Phase 8: Host-pushed animation clips (Touhou Little Maid clips, `sit` first)

This ExecPlan is a living document and follows `.agent/PLANS.md`.

## Purpose / Big Picture

A maid handed to YSMU is rendered by YSMU but her animation is decided by Touhou Little Maid. Upstream
1.20.1 solves this by owning the whole thing: YSM's source tree contains a `compat/touhoulittlemaid`
package that reads `EntityMaid.isMaidInSittingPose()`, `renderState` and the vehicle joy types, and keeps
its own 19-row clip table. That shape is not available here - the two 1.7.10 mods deliberately share no
compile-time dependency, and TLM already owns the table.

This phase publishes a sink instead: TLM resolves the clip it wants and pushes the single name, YSMU plays
exactly that clip.

## Progress

- [x] Compare upstream's maid animation layer with what TLM 1.7.10 already has.
- [x] Add `data/EntityClips` (per-entity pushed clip, keyed by entity id).
- [x] Add `api/EntityAnimationApi` (`setEntityClip` / `clearEntityClip` / `getEntityClip` /
      `getEntityClipLoop` / `modelHasClip` / `parseLoop`).
- [x] Play the pushed clip in `AnimationManager.predicateEntityLocomotion`, ahead of the model's own
      seat/locomotion rules.
- [x] Clear pushed clips on leaving the world and on overworld unload.
- [ ] Build verification (`gradlew build`).

## Decision Log

- Decision: the host decides the clip; YSMU only plays it. Do **not** port upstream's
  `GeoMaidAnimatedRegister` into YSMU.
  This is *not* because TLM is off limits - all three repositories are first-party and may be edited (see
  AGENTS.md "Cross-Project Ownership"). It is because TLM already owns that table and corrected it, so a
  second copy here would be two sources of truth for the same clip names.
  Rationale: (a) TLM 1.7.10 already has the same 19-row table, row for row, plus a signal abstraction;
  a second copy would put the same clip names in two packages and make a rename half-applied, which is the
  failure TLM's own `MaidClips` javadoc warns about. (b) Reading the maid's state from YSMU would need
  either a compile-time dependency on TLM or another layer of reflection. (c) Upstream's `sit` row asks for
  `LOOP`; TLM's port corrected it to `HOLD_ON_LAST_FRAME`, so a faithful copy would reintroduce the
  "she sits down over and over" bug.
- Decision: the loop mode travels with the clip, as one of the engine's three names.
  Rationale: the engine overwrites a clip's own `"loop"` field with whatever the row asks for, so a pose
  clip (`sit` is a 0.6 s stand-to-seated transition) holds her seat only under `HOLD_ON_LAST_FRAME`;
  `LOOP` replays the transition forever and `PLAY_ONCE` stands her back up after one frame.
- Decision: key the store by tracked entity id, not UUID, and keep the whole API common-side safe.
  Rationale: 1.7.10 gives non-player entities a random per-side `entityUniqueID`, so a UUID key cannot be
  matched between host and renderer. The API must not reference client-only classes, because a host resolves
  it reflectively on either side and its own bridge treats a load failure as a permanent disable.

## Upstream parity

| upstream (`client/compat/touhoulittlemaid`) | 1.7.10 | state |
| --- | --- | --- |
| `GeoMaidAnimatedRegister` (19 rows) | TLM `MaidAnimationRegister` | TLM owns it; pushed through this API |
| `YsmMaidMainPredicate` | TLM `MaidAnimationWiring.predicate` (`EntityMaid`:2254) | TLM owns it |
| `MaidVehiclePredicate` (gomoku/bookshelf/computer/keyboard/picnic/chair/broom) | TLM rows `GOMOKU`..`CHAIR` | TLM owns it; `broom` to confirm |
| `MaidStatuePredicate`, `renderState != ENTITY` | no statue/garage-kit concept in TLM 1.7.10 | not applicable |
| `MaidRoulettePredicate` | TLM `EntityMaid.rouletteAnim` / `rouletteAnimPlaying` | covered: push that clip with `PLAY_ONCE` |
| `TLMBindingInner` (maid Molang vars) | `UpdateRemoteStructEvent` + `RemoteAnimationVariables` | mechanism already present (`v.roaming.*`) |
| `YsmMaidTickEvent`, `UpdateRemoteStruct`, capability sync | TLM `YsmMaidClientTickEvent`, YSMU `UpdateRemoteStructEvent` | already present |
| `MaidModelScreen` / `MaidTextureScreen` | YSMU `ModelGuiApi` | already present |
| `CopyYsmModelEvent`, `TlmConverterHelper` | - | to confirm against TLM 1.7.10 |

## The host contract

```
EntityAnimationApi.setEntityClip(EntityLivingBase entity, String clip, String loopMode) -> boolean
    loopMode: "LOOP" | "PLAY_ONCE" | "HOLD_ON_LAST_FRAME" (case-insensitive, null == LOOP)
EntityAnimationApi.clearEntityClip(EntityLivingBase entity) -> boolean
EntityAnimationApi.getEntityClip(EntityLivingBase entity) -> String or null
EntityAnimationApi.getEntityClipLoop(EntityLivingBase entity) -> String or null
EntityAnimationApi.modelHasClip(EntityLivingBase entity, String clip) -> boolean
```

A host pushes on its client tick or whenever its own clip changes; `modelHasClip` is the same question its
own "can this model play that clip" filter asks, so the two renderers agree before anything is drawn.
A clip the model does not define is not an error: YSMU falls back to the seat/locomotion rules.

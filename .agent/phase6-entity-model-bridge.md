# Phase 6b: Non-player entity model replacement (Touhou Little Maid compat)

This ExecPlan is a living document and follows `.agent/PLANS.md`.

## Purpose / Big Picture

YSM's render pipeline is player-only. The 1.7.10 port of Touhou Little Maid (TLM) already
publishes a model-replacement contract (Maid 8a: NBT keys, three Forge events, two packets and a
mod-presence probe), but its Milestone 8b - actually handing a maid to a model-replacement renderer -
is blocked because YSMU exposes no non-player entry point. This phase closes that gap on the YSMU
side so a maid (or any modded living entity) can be rendered through YSMU's model engine, can
receive synced animation variables, and can be selected in-game from YSMU's own model screen.

TLM's own `Port.md` lists the four missing YSMU capabilities this plan implements, plus a fifth
found while integrating: the in-game model selection screen could not target a maid at all, because
`OpenModelGuiMessage` only opened for an `EntityPlayer` and assigned `CURRENT_NPC_ID` in that branch.

1. a non-player render entry (or a widened `SpecialPlayerRenderEvent`);
2. an `Entity`-oriented model/texture registry instead of a raw `Map<UUID, Pair<RL,RL>>`;
3. a server-side send API for `SetNpcModelAndTexture` with login-time bulk sync and unload cleanup;
4. an `UpdateRemoteStructEvent` equivalent that pushes `roamingVars` into the animation controller;
5. an in-game model selection entry for a non-player entity.

## Progress

- [x] Registry: `EntityModelData` + entity-keyed `NPCData`.
- [x] Render entry: widened `SpecialPlayerRenderEvent`, entity-aware `CustomPlayerEntity`,
      `CustomPlayerRenderer`, and a public `EntityModelRenderApi`.
- [x] Server API: implemented `SetNpcModelAndTexture.Handler`, `EntityModelApi`, login sync and
      unload cleanup.
- [x] Animation vars: `UpdateRemoteStructEvent` + `RemoteAnimationVariables` injected into the
      Molang scope.
- [x] Selection entry: `ModelGuiApi` + `ModelSelectionTarget` generalization of the model screen,
      plus a per-entity selection grant so a normal player may re-skin the entity handed to them.
- [x] Upstream review follow-up (A1-A11 in TLM's UPSTREAM_API_REVIEW.md): main-thread deferral,
      render result reporting, volatile registry, tracker push, grant TTL/revoke, reachability,
      protocol handshake, model validation, registry prune and player-override precedence.
- [ ] Build/test verification by the user (`gradlew compileJava`), which this environment does not run.

## Decision Log

- Decision: key the registry by the tracked entity id and expose `Entity`-typed accessors.
  Rationale: 1.7.10 generates a random `entityUniqueID` per side for non-player entities and never
  syncs it, so a UUID-keyed registry can never match a maid on the client. The tracked entity id
  arrives with the spawn packet and is identical on both sides, and the existing NPC GUI packets
  already speak entity ids. Entity-id reuse is bounded by removing on death and clearing on world
  unload.
- Decision: widen `SpecialPlayerRenderEvent` to `EntityLivingBase` and add `getEntity()`, but
  keep `getPlayer()` returning the entity when it is a player and `null` otherwise.
  Rationale: `Port.md` requires the event to stay source-compatible for existing NPC/Bukkit
  listeners; new non-player listeners use `getEntity()`.
- Decision: model the edited object of the selection screen as `ModelSelectionTarget` rather than
  branching on player/NPC inside every widget.
  Rationale: `PlayerModelScreen`, `PlayerTextureScreen`, `ModelButton` and `TextureButton` all
  hard-wired `EntityPlayer`; one abstraction keeps the player path byte-for-byte equivalent while
  letting an NPC target read/write `NPCData`.
- Decision: `ModelGuiApi.openModelGui(viewer, target)` both opens the screen and records a
  one-entity selection grant; `SetNpcModelAndTexture` accepts a change for a granted entity without
  operator rights.
  Rationale: the alternative - requiring permission level 2 for every non-self entity - makes it
  impossible for an ordinary player to re-skin their own companion, which is the whole point of the
  feature.
- Decision: TLM is **not** placed on the compile classpath. YSMU publishes a plain Java/Forge
  API and TLM wires itself in during its own Milestone 8b.
  Rationale: both projects deliberately keep a zero compile-time dependency relationship.

## Plan of Work

The registry is the shared vocabulary. `NPCData` keeps its static surface but stores
`EntityModelData` values keyed by entity id and gains `Entity` overloads. The renderer asks the
registry for a model before falling back to the player EEP. The server API resolves an entity by id,
writes the registry and broadcasts an update; login sends the whole map. Animation variables live in
a client-side per-entity map that `MolangPhysicsRuntime.begin` merges into the current Molang scope,
so a model can read `v.roaming.<name>` without any assignment existing in the animation file.

Non-player renderers reach YSMU two ways: a mod whose renderer still routes through
`RendererLivingEntity#doRender` is intercepted by `RenderLivingEvent.Pre`; a mod that overrides
`doRender` without calling `super` (TLM's vendored GeckoLib does) calls
`EntityModelRenderApi.render` directly from its own render pass.

For selection, `OpenModelGuiMessage` now accepts any `EntityLivingBase`; the screen edits a
`ModelSelectionTarget` (player EEP or `NPCData`), and `ModelGuiApi` is the server entry that opens
it and grants the viewer permission for exactly that entity.

## Validation and Acceptance

- `.gradlew.bat compileJava` succeeds (requested from the user; this environment does not run Gradle).
- With only YSMU installed, player rendering is unchanged and `SpecialPlayerRenderEvent` still
  fires for players.
- `EntityModelApi.setEntityModel(...)` stores an entry, broadcasts it, and a joining player
  receives the full map through `SyncNpcDataMessage`.
- `new UpdateRemoteStructEvent(entity, vars)` posted on the Forge bus makes
  `v.roaming.<name>` visible to that entity's Molang evaluation.
- `ModelGuiApi.openModelGui(player, maid)` opens the screen on that player's client, and picking a
  model sends `SetNpcModelAndTexture` which applies on the server without operator rights.

## Interfaces and Dependencies

    com.fox.ysmu.data.EntityModelData          model + texture value type
    com.fox.ysmu.data.NPCData                  entity id -> EntityModelData registry
    com.fox.ysmu.api.EntityModelApi            server-side set/clear + broadcast
    com.fox.ysmu.api.ModelGuiApi               server-side open-GUI + selection grant
    com.fox.ysmu.client.renderer.EntityModelRenderApi  client-side non-player render entry
    com.fox.ysmu.client.gui.ModelSelectionTarget       what the model screen edits
    com.fox.ysmu.event.api.SpecialPlayerRenderEvent    widened to EntityLivingBase
    com.fox.ysmu.event.api.UpdateRemoteStructEvent    roamingVars -> client Molang scope
    com.fox.ysmu.client.animation.molang.RemoteAnimationVariables

## Upstream review follow-up (TLM UPSTREAM_API_REVIEW.md)

- **A1 / A1b (threading):** util/DeferredWork holds two queues, one per side, drained by
  event/DeferredWorkTicker (client tick / server tick). SetNpcModelAndTexture, SetModelAndTexture and
  HandshakeMessage queue their work on the server queue; OpenModelGuiMessage queues the GUI open on the
  client queue. Separate queues mean a client tick can never drain server work in single-player.
- **A2 (render result):** the "can this client draw it" question is now asked about the model the entity
  actually **requested**, not the substituted one. `applyEntityModel` returns the override’s own
  `ModelIdUtil.getMainId(...)` (or `null` when there is no override); `hasModelFor` tests that id against
  `GeckoLibCache`, so a client missing the entity’s model reports `false` instead of always `true`.
  `doRenderModel` - the `EntityModelRenderApi.render` path - never substitutes the default, so its `false`
  branch is reachable and the one-time warning names the model that is actually missing.
  `CustomPlayerRenderer.doRender` keeps the default substitution through a private `fallBackToDefault`
  flag, so a player whose selected model is absent still renders rather than vanishing.
  `ClientEventHandler.onRenderLiving` cancels `RenderLivingEvent.Pre` only when `hasModelFor` is true, so a
  missing model falls back to the entity's own renderer.
- **A3 (registry field):** NPCData.DATA is volatile and addAll updates the existing map in place.
- **A4 (late viewer):** CommonEventHandler.onStartTracking pushes the override for a non-player target
  that is in the registry when a client starts tracking it.
- **A5 (grant lifetime):** grants carry a 5-minute TTL and ModelGuiApi.revokeSelectionGrant lets a
  downstream mod drop one early.
- **A6 (reachability):** ModelGuiApi.openModelGui returns boolean and refuses dead, cross-dimension or
  out-of-range (128 blocks) targets before granting or sending.
- **A7 (static state):** OpenModelGuiMessage.CURRENT_NPC_ID is gone; the granted id travels on
  PlayerModelTarget / EntityModelTarget.
- **A8 (protocol):** NetworkHandler.NETWORK_PROTOCOL is exchanged by HandshakeMessage on join; a mismatch
  disconnects with a readable reason.
- **A9 (model validation):** EntityModelApi.setEntityModel refuses a model the server does not have
  (ServerModelManager.hasModel), and SetNpcModelAndTexture distinguishes an explicit empty clear from a
  malformed payload, rejecting the latter instead of clearing.
- **A10 (leak):** a server-tick sweep calls NPCData.retainAll, pruning entries whose entity is gone from
  every loaded world after a 10-minute grace.
- **A11 (precedence):** the player's own SetModelAndTexture drops any stale NPCData override for that
  player, so an explicit self selection is not masked by the renderer's NPC-first precedence.

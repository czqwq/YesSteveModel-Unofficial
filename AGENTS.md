# AGENTS.md

## Project Snapshot

YSMU is a Minecraft Forge 1.7.10 mod that ports Yes Steve Model/OpenYSM-style player models back to 1.7.10. The mod id is `ysmu`, the root package is `com.fox.ysmu`, and the Forge entry point is `src/main/java/com/fox/ysmu/ysmu.java`.

The build uses the GTNH Gradle convention plugin through `settings.gradle.kts` and `build.gradle.kts`. `gradle.properties` targets Minecraft `1.7.10`, Forge `10.13.4.1614`, MCP stable `12`, enables Mixins, and enables Jabel modern Java syntax while still targeting JVM 8. Runtime/development dependencies are declared in `dependencies.gradle`. The GeckoLib 3 animation engine is **vendored into this repository** under `src/main/java/{software,com/eliotlash,net/geckominecraft}` as a one-time source snapshot; it is not an external mod and not a jar in `libs/`. See `PROVENANCE.md`.

The current focus of work is to port OpenYSM. The OpenYSM code should be carefully analyzed and the implementation should be closely followed.

## Upstream Fidelity

**When upstream already implements a feature, implement it the way upstream does. Do not home-grow a mechanism for something upstream has already solved.**

The reference is `tmp/YesSteveModel-dev-1.20`. Read it before changing behaviour, and cite the upstream file and line in the commit, the ExecPlan entry, or the code comment that the change rests on. A change whose only justification is "it seemed reasonable" is not finished.

- **Find the upstream mechanism first.** For a behaviour change, locate what upstream does for the same problem - it is usually one class and a handful of lines - and port that shape, including the fallback it picks when the data is missing.
- **Do not invent where upstream has nothing.** If upstream has no equivalent (for example YSMU's host-facing `EntityModelRenderApi`, which upstream does not have), do not invent a new predicate or a new semantic. Record the difference as a finding and let the owner arbitrate; a locally invented mechanism is a defect even when it works.
- **A port-specific addition needs a port-specific reason** - a 1.7.10 constraint, or the port's own architecture (one shared renderer for the world, the HUD overlay and the GUI). Say which, and keep the addition as small as possible.
- **Never add state upstream does not keep** to work around a timing difference. If a value is not available yet, the upstream-shaped answers are to resolve it through the structure that owns it (upstream resolves a texture *name* inside the model that was loaded), or to carry it as an explicit override, or to wait. Tracking "what I have already done" in a side set and gating on it has already produced one shipped regression here: a texture id set that rejected the player's own Minecraft skin for the built-in `steve`/`alex` models, which upstream draws by handing the skin to the renderer as a render-event override.
- **Do not copy an upstream bug.** Cite the behaviour, not the typo - for example upstream reads a render event's texture override *before* posting the event, so its own field never sees what its listener set; the port reads it after.

### Known divergences from upstream

Every place where this port answers a question differently from `tmp/YesSteveModel-dev-1.20`. Each is either resolved
(say how) or waiting for the owner. Being listed here is not a licence to invent a third answer: pick upstream's
shape, or record why 1.7.10 or the port's own architecture cannot.

- **Molang variable names are case-insensitive.** Upstream canonicalises every identifier through
  `MolangNames.identifier` (`api/molang/MolangNames.java:29-35`, `toLowerCase(Locale.ROOT)`). The port now does the
  same in one rule, `MolangParser.canonicalVariableName`, used by the parser's own registry, by
  `RemoteAnimationVariables.normalize` (the wire path) and by the physics scope's variable table. Before it, a pack's
  `v.roaming.C` was stored as written and looked up lower-cased, so it read as 0 and every `v.roaming.C==0?...` took
  its first branch: the pack's own settings toggles silently did nothing. Resolved - see
  `.agent/phase18-glow-and-roaming-case.md`.
- **Per-bone glow, transparency and colour.** Upstream keeps them in a 14-float-per-bone attribute array
  (`geckolib3/model/AnimatedGeoBone.java:9-16`, packing at `:201-211`) and consumes it in its native renderer
  (`natives/render/NativeModelState.java:57-66`). The port carries the same values with the same packing on
  `GeoBone` and registers the same three names (`bone_color`, `bone_transparency`, `bone_glow`) in `MolangParser`,
  but draws them with the fixed-function pipeline: a glow level >= 0 renders the bone unlit, because 1.7.10 has no
  per-bone lightmap. The level stays on the bone so a brightness mapping can be added once it can be checked against
  upstream instead of guessed. Port-specific by a 1.7.10 constraint.
- **The client's texture ladder has no rung for the model's own declared default.** Upstream resolves the texture
  inside the model that was loaded - requested name, then `settings.defaultTexture` (which
  `RawModelAssembler:189-196` fills with the first texture when the pack declares none), then the first entry
  (`format/schema/model/ModelManifestLookup.java:10-29`). The port parses and syncs that same field
  (`YSMFolderDeserializer:170` and `:1031`, `RawYsmModel:210`, `YSMBinarySerializer:483` /
  `YSMBinaryDeserializer:611`) but the client ladder put the global `Config.DEFAULT_MODEL_TEXTURE` in that rung
  (`CustomPlayerModel.textureFor`), so it only differed for a pack that declares `default_texture`. Resolved: the
  engine's `ModelProperties` gained a `defaultTexture` (`ysm_default_texture`), `RawYsmModelAdapter` injects it beside
  `ysm_render_layers_first`, `ClientModelManager.DECLARED_DEFAULT_TEXTURES` carries it with the same hygiene as
  `RENDER_LAYERS_FIRST`, and `CustomPlayerModel.resolveTexture` uses it between the selection and the constant,
  validating it against the model's own texture list the way upstream's `containsTexture` does.
  `Config.DEFAULT_MODEL_TEXTURE` keeps its exact role. `ResolveTextureLadderTest` guards it.
- **Two sync channels can deliver the same folder model.** Upstream syncs one channel. The port has a legacy and an
  OpenYSM channel, and for a folder model they serialise differently - the baked side re-emits the JSON pretty
  printed with the pack's floats widened to doubles, which is 3.0 MB of geometry against 9.0 MB for
  `wine_fox/05_magical`, with the six textures byte-identical. The duplicate check hashes those bytes, so it never
  matched: every `wine_fox/*` model was registered six times per session against three for each `.ysm` pack, and each
  re-registration parked the geometry, dropped the ready marker and the installed animations, and left the model
  drawing the built-in default for about a second - the flicker. Resolved by giving each model one channel:
  `SyncModelFiles` no longer offers a model the OpenYSM index carries, and only while
  `Config.ENABLE_OPEN_YSM_SYNC_PROTOCOL` is on, so built-in models and models `OpenYsmFormat` cannot produce a payload
  for stay legacy-only. `CACHE_NAME_INFO` keeps its entry either way - it is also the registry that stops the legacy
  folder scanner rescanning a directory the OpenYSM scanner already registered.
- **Render state is chosen as a type before the draw, the way upstream chooses a `RenderType`.** Upstream has one
  entry point, `IGeoRenderer#getRenderType(texture, visible, glowing, translucent)`
  (`geckolib3/geo/IGeoRenderer.java:33-39`), returning `CustomTranslucentRenderType` - which wraps
  `RenderType.entityTranslucent`, i.e. blending and culling - when the model has a translucent vertex, and
  `RenderType.entityCutoutNoCull` (alpha test, no blending, no culling) when it does not, and it hands that type to
  the draw as a parameter (`:18-26`). The port has the same shape: `YsmRenderType` is 1.7.10's stand-in for
  `RenderType` (the platform has none), `IGeoRenderer#getRenderType` carries upstream's name, parameters, defaults and
  both branches, and `render(model, animatable, type, ...)` applies the type instead of deciding. The port adds no
  `getRenderType` override, because the engine's default body *is* upstream's rule. `hasTranslucentVertices` is
  upstream's name from `GeoModelState:73-74`; upstream reads a native vertex count, and the port substitutes the
  host's answer from the texture about to be bound - the same input upstream's bake gets - with that substitution
  stated in the javadoc. The first-person arm does not go through `render`, so `FirstPersonHandRenderer` asks
  `getRenderType` the same question the world path asks - visible, not glowing, `hasTranslucentVertices` on the arm's
  own animatable - and applies that type's state in `prepareCustomArmState`, with `render`'s own rules
  (`IGeoRenderer.java:77-95`). The roll applies that state around a mirrored arm matrix and resets
  `glFrontFace(GL11.GL_CCW)` afterwards, because the mirror makes the model's own front faces back faces
  (`CustomPlayerItemInHandLayer.java:107` is the same correction for the mirrored held item).
  History worth keeping: the arm used to hardcode upstream's *arm* answer, `CustomTranslucentRenderType` - which
  upstream's `CustomFirstPersonArmRenderer:39` really does use unconditionally, but which in 1.20 applies itself
  through the handed-over type and in 1.7.10 has to be applied by hand. Commit 67f8044 wrote that state as
  `disableAlpha() + enableCull()`: for an opaque model that is the opposite of the `entityCutoutNoCull` the world
  path draws the same geometry with, so an alpha-0 texel was not discarded (the black patch over the unlit inside of
  the far face) and culling dropped a face of a flat or single-layer piece (the see-through holes). The grey
  single-texel back faces of `wine_fox/05_magical`'s flat decals that culling was added for are handled by the
  clamped texture upload (see the texture-wrap entry below), not by the arm's cull state.
  Two gaps are recorded rather than invented: 1.7.10 has no outline pass, so the glowing branch draws nothing
  (`getRenderType` returns null); and the engine keeps its port-only 0.001 inflate on a zero-thickness cube, which
  upstream does not have (`GeoBuilder.java:100-116`), because removing it would make a flat decal's two faces exactly
  coplanar on the cutout-no-cull branch and z-fight. `all_cutout` is upstream's `forceCulling`
  (`format/parser/pojo/manifest/settings/ModelProperties.java:35-36`); the port already parses and syncs it
  (`RawYsmModel:214`, `YSMFolderDeserializer:174`, `YSMBinarySerializer:491` / `YSMBinaryDeserializer:620`) and now
  carries it to the client as `ysm_all_cutout` into the engine's `ModelProperties.forceCulling`. **Nothing consumes
  it**, because upstream's only consumer is its native bake - carried and recorded rather than given an invented
  consumer, and it changes no behaviour while nothing reads it.
  History worth keeping: an earlier attempt dropped the degenerate faces inside `GeoCube`, which acts on geometry
  **every** model shares, and it broke models that had rendered correctly; it was reverted. Do not let a predicate of
  that kind act on shared geometry, and do not add a method upstream does not have - the improvised
  `shouldCullBackFaces` was deleted for exactly that reason.
  Two mappings were settled by reading rather than guessing. Upstream's `RenderFirstPlayerBackground` is the
  first-person **arm**'s background pass, not the HUD overlay; its counterpart here is `FirstPersonHandRenderer`,
  which applies that pass's state. The HUD extra-player overlay (`ClientEventHandler#renderSelfGuiPlayer`) is
  port-only - upstream has no equivalent - so it goes through `doRender` and takes the same per-model answer as the
  world. And upstream's third geo renderer, `GeoProjectilesRenderer`, has no counterpart in this engine, so there is
  no third place that has to ask for a type.
- **Model textures are uploaded clamped, not repeated.** Upstream relies on the platform default: its textures go
  through 1.20's `TextureManager`, whose sampler is `CLAMP_TO_EDGE`. The port's `OuterFileTexture` passed
  `clamp = false`, i.e. `GL_REPEAT`. That matters because a pack marks the faces it does not want with a zero
  `uv_size` - `wine_fox/05_magical`'s `ysmGlowdamofazhen3` declares `south` as `uv [256, 0], uv_size [0, 0]` - upstream
  builds those faces anyway (`GeoBuilder:186-193` skips only a face whose uv data is missing), and all four of that
  face's uvs normalize to `u = 1.0`. Clamped, that is the last texel of the row, which is transparent in every skin
  of that pack, so the face is invisible; repeated, it wraps to the first texel, which is opaque grey in `magic.png`
  and `water.png`, and the face becomes a 60×60 opaque slab over the model's art. Measured: `magic.png` is `A255
  RGB(57,51,67)` at (0,0) and `A0` at (255,0). `OuterFileTextureWrapTest` pins both halves. Resolved - and note that
  this fix lives in the port, so it needs the YSMU jar rebuilt, not just Geckolib.
- **`EntityModelRenderApi.isModelLoaded` is a port-only query.** Upstream's host-facing surface is events
  (`RenderModelEvent`, `RenderLayerEvent`, `RegisterRenderStateModifierEvent`, `RegisterModelLocatorEvent`) with no
  readiness query at all. The port returns true for a parked model while the renderer is drawing the built-in
  default. Open: an owner decision, recorded rather than resolved.
- **The first-person arm is posed by its own animatable, its own controller set and its own condition table.** Upstream
  ticks a `CustomFirstPersonArmEntity` every frame
  (`client/renderer/CustomFirstPersonArmRenderer.java:27-28`), drives it with `fp.arm.misc` / `fp.arm.parallel_<i>` /
  `fp.arm.armor_<slot>` (`client/controller/collections/FPArmControllerCollection.java:22-32`) off a second animation
  store and a second condition manager (`ModelRenderTargetAssembler.java:53-60`), and shares one controller map with
  the body. The port now does the same (`CustomFirstPersonArmEntity`, `FPArmConditionManager`,
  `AnimationDomain#controllerSetId`), because the arm's bones had never entered an `AnimationProcessor` at all and it
  drew at its bind pose. One deliberate divergence: upstream decides which `fp.arm.*` and `parallel_<i>` controllers
  exist while it builds the model's resource set and skips an animation with no bone channels
  (`ParallelControllerDiscovery.java:64-71`), while the port registers one fixed set per animatable and routes at
  runtime. That divergence is **not** harmless, and it was shipped as one: a fixed pool asks the engine for a name the
  current model does not declare, `AnimationController#setAnimation` returns before filling `animationQueue` and
  before updating `currentAnimationBuilder`, so the controller keeps the old builder, re-evaluates it as changed on
  the next frame, and prints `Could not load animation: parallelN. Is it missing?` once per frame while the controller
  is left with no fallback - observed as `parallel0`/`parallel5`/`parallel7` repeating in a live client, and as the
  LaPluma walk regression before it. The port's counterpart of upstream's discovery-time filter is a runtime existence
  check at the hand-over: `AnimationManager#playParallelIfPresent` asks `hasAnimation` (which installs the model's
  animations first, exactly as `definesAnimation` does) and answers `PlayState.STOP` when the name is absent - the
  ending SOURCE itself uses and documents (`client/animation/AnimationManager.java:852-855`, reason at `:822-823`).
  `ParallelAnimationExistenceGuardTest` pins it for the body and for the arm, including that the arm is asked only
  from the `fp_arm` domain and the body only from `main`. Resolved - see `.agent/phase23-first-person-arm-pose.md`.
  One consequence of the same split stays recorded rather than patched: the default-animation fallback is per domain
  (`ModelRenderTargetLoader.java:375-379` with `DefaultAnimationKey.java:36-38`, where `fp_arm` alone maps to
  `player/first-person`), so a *legacy* three-set default payload (`extra`/`main`/`arm`, i.e. one baked before
  `animations/fp.arm.animation.json` existed) can never fill the first-person fallback, and that domain stays empty
  until the model's own pack declares `fp_arm` or the built-in default is re-copied. Upstream has the same hole for a
  payload that declares no `fp_arm` set, so this is the format's behaviour, not a missing port mapping.

## Cross-Project Ownership

GeckoLib, YSMU and Touhou Little Maid are all first-party projects of the same owner:

- **GeckoLib** (mod id `geckolib`): `E:\IDEA\Geckolib`, symlinked into this workspace as `tmp/Geckolib`. The
  engine is **vendored into this repository** as a one-time source snapshot under `src/main/java/{software,
  com/eliotlash,net/geckominecraft}`, so it is no longer a mod YSMU depends on and no longer a jar in `libs/`.
  Its `@Mod` was removed and the engine now ships inside the YSMU jar. `PROVENANCE.md` records where the snapshot
  came from and the ownership rule that follows; `E:\IDEA\Geckolib` is no longer a copy to sync from.
- **YSMU**: this repository.
- **Touhou Little Maid**: `E:\IDEA\TouhouLittleMaid`, with its 1.20 reference source under `tmp/TouhouLittleMaid-1.20`.

Any of the three may be changed at any time. A feature must never be blocked, simplified, half-done or
duplicated merely because the code that would implement it lives in another of the three repositories. If the
cleanest implementation belongs there, change it there and say so in the ExecPlan. For the vendored engine the
"other repository" is no longer the place to change it: those three trees are third-party compatibility code
**owned by this repository** now, so an engine change is made here, and it must be as narrow as the defect and
recorded as a deviation from the snapshot (see `PROVENANCE.md`).

The rule that must survive is a **runtime** property, not an editing restriction:

- Each mod must still load and run without the others installed. YSMU is now self-contained: it needs no
  `geckolib` mod at runtime, and a `geckolib-*.jar` in the mods folder must be deleted because FML's single class
  loader would otherwise see two copies of `software.bernie.geckolib3`.
- YSMU and Touhou Little Maid stay optional to each other at runtime, and both already degrade cleanly when
  the other is absent (a mod-id probe plus a reflective bridge). That is one way to keep the property, not a
  ban on the two being compiled against each other. When a feature is genuinely better served by a
  compile-time coupling, propose it explicitly instead of contorting the design around it.
- Prefer one implementation to two. The same table, clip list or rule set existing in two of these
  repositories is a defect: a rename lands in only one of them and the wrong half is silent.

## ExecPlans

When writing complex features or significant refactors, use an ExecPlan (as described in .agent/PLANS.md) from design to implementation.

## Repository Layout

- `src/main/java/com/fox/ysmu`: project-specific mod code.
- `src/main/java/com/fox/ysmu/client`: client-only rendering, GUI, keybinds, animation predicates, texture/model registration, and upload state.
- `src/main/java/com/fox/ysmu/model`: server-side model discovery, built-in model copying, cache generation, and folder/`.ysm` model format handling.
- `src/main/java/com/fox/ysmu/network`: Forge `SimpleNetworkWrapper` setup and packet classes.
- `src/main/java/com/fox/ysmu/eep`: 1.7.10 `IExtendedEntityProperties` state for selected model/texture, active animation, starred models, and each player's roaming variables.
- `src/main/java/com/fox/ysmu/event`: GTNHLib event subscribers for common player sync and client rendering events.
- `src/main/java/com/fox/ysmu/compat`: optional-mod compatibility wrappers. Keep Backhand and similar direct calls behind these wrappers.
- `src/main/java/com/fox/ysmu/mixin`: Mixins only. `gradle.properties` restricts Mixins to package `com.fox.ysmu.mixin`.
- `src/main/java/software`, `src/main/java/com/eliotlash`, `src/main/java/net/geckominecraft`: the vendored GeckoLib 3 engine (327 files: 275/48/4). Third-party compatibility code owned by this repository - keep engine edits narrow and record every deviation. The trees are byte-identical to the snapshot except `software/bernie/geckolib3/GeckoLib.java`, whose `@Mod` and `preInit` were removed. See `PROVENANCE.md`.
- `src/main/resources/assets/ysmu/custom`: built-in model assets copied into `config/ysmu/custom` during reload.
- `src/main/resources/assets/ysmu/lang`: `en_US.lang` and `zh_CN.lang`. Keep new translation keys in sync.
- `src/main/resources/mixins.ysmu.json`: Mixin config. Currently only `MixinItemRenderer` is listed as a client Mixin.
- `src/main/resources/META-INF/ysmu_at.cfg`: access transformers for the Minecraft internals YSMU touches (`ItemRenderer`, `EntityRenderer`, `Minecraft.timer`, `RenderHelper`). The engine's own two accesses are already in this file, because the engine ships inside this jar.
- `tools/convert_new_ysm.py` and `tools/convert.md`: conversion utility and documentation for newer OpenYSM-style model directories.

## Minecraft and Forge Sources

RetroFuturaGradle already decompiles the patched game sources into the workspace. Read them in place with the file tools instead of opening the jars:

- `build/rfg/minecraft-src/java`: decompiled Minecraft + Forge 1.7.10 sources (stable-12 names, Forge patches applied).
- `build/rfg/minecraft-src/resources`: the matching resources.
- `build/rfg/launcher-src`: the bundled launcher sources.

Do not extract or decompile the jars in `build/rfg` (`mcp_patched_minecraft-sources.jar`, `srg_patched_minecraft-sources.jar`, `recompiled_minecraft-1.7.10.jar`, `srg_merged_minecraft.jar`). They contain the same sources as `minecraft-src`, so unpacking them only wastes time and litters the workspace. Check vanilla/Forge behavior first - dimension travel, respawn, `IExtendedEntityProperties` cloning, event firing order, render and texture reload hooks - directly under `build/rfg/minecraft-src/java`, and only fall back to the jars when that directory is missing. `build/` is generated output: never commit it, and never add workspace files to it.

## Runtime Flow

Startup begins in `ysmu.java`. `CommonProxy.preInit` loads `Config`, calls `ServerModelManager.reloadPacks()`, and logs the version. `CommonProxy.init` registers network packets through `NetworkHandler.init()`. `ClientProxy.init` additionally registers animation states/Molang variables, the custom player renderer, and key bindings.

`ServerModelManager.reloadPacks()` creates `config/ysmu`, `custom`, `export`, and `cache` directories, force-copies built-in models into `config/ysmu/custom`, initializes `cache/server/PASSWORD`, and rebuilds encrypted server cache files for both folder models and `.ysm` files.

Model sync starts with the server sending `RequestSyncModel`. The client replies with cached MD5 names via `SyncModelFiles`. The server sends an encrypted password (`SendModelPassword`), asks the client to load cache hits (`RequestLoadModel`), and sends missing cache files (`SendModelFile`). The client decrypts and registers models through `ClientModelManager.registerAll()`.

Client rendering cancels vanilla `RenderPlayerEvent.Pre` in `ClientEventHandler` and delegates to `CustomPlayerRenderer`. `CustomPlayerRenderer` chooses model/texture state from `ExtendedModelInfo` or NPC overrides, posts `SpecialPlayerRenderEvent`, then renders through the GeckoLib replacement renderer. First-person hand rendering is split between `RenderHandEvent` and the Angelica-specific `MixinItemRenderer` path.

The HUD "extra player" overlay (`ClientEventHandler.renderSelfGuiPlayer`, a preview render) draws that same local player through the same renderer and therefore the same `AnimationData`, so one frame reaches `AnimationController.process` twice for one entity at one `seekTime`. The engine must stay idempotent under that: the second process may not drop the animation a transition is moving to (`AnimationController.process` only polls `animationQueue` when it is non-empty). When it did, the controller never left `AnimationState.Transitioning` and the model rendered the transition's first frame forever - which appears as "walking animates but sprinting does not, in third person only", because in first person the HUD overlay is the only pass. See `.agent/phase7-geckolib-dependency.md`; `AnimationControllerTransitionTest` guards it.

The engine is **vendored**, not a required separate mod, and `ysmu.java` therefore no longer declares `dependencies = "required-after:geckolib"`. Where the engine needs host knowledge it goes through an interface, and YSMU implements it: `CustomPlayerEntity` implements `IMolangPhysicsScope` so `software.bernie.geckolib3.core.molang.MolangPhysicsRuntime` can key its per-frame MoLang scope, and `ClientEventHandler` pushes remote animation variables into the engine's `core.molang.RemoteAnimationVariables`. YSMU no longer ships a Molang physics runtime of its own.

## Model and Resource Rules

Folder models live under `config/ysmu/custom/<model name>` and must include `main.json`, `arm.json`, and at least one `.png`. Optional animation files are `main.animation.json`, `arm.animation.json`, and `extra.animation.json`; missing animation files fall back to the built-in default animations.

`.ysm` files in `config/ysmu/custom` are also scanned. Only files containing `main.json`, `arm.json`, and at least one `.png` are cached.

Model images are normalised to PNG while a model is parsed. `ModelImageConverter` decodes BMP/JPEG/WebP/AVIF with the decoders vendored in `libs/ImageStream--SNAPSHOT.jar` (the library upstream OpenYSM declares as `com.github.OpenYSM:ImageStream`) and re-encodes them as PNG, mirroring upstream's `YSMClientMapper`; PNG is passed through untouched and an undecodable image is kept as-is with one warning per format. Clients therefore only ever receive PNG, and `ClientModelMetadataRegistry` keeps its own format guard for older caches. That jar is embedded into the mod jar unrelocated by the block at the end of `dependencies.gradle`, because the GTNH convention relocates `shadowImplementation` dependencies to `com/fox/ysmu/shadow/...`, which would break both the direct decoder calls and the `META-INF/services/javax.imageio.spi.*` names.

The model-selection and texture screens animate each tile through `ModelPreviewRegistry` (the model's `preview_animation` from the sync payload, `idle` when it declares none) plus `ModelPreviewAnimationState`, the port of upstream's `CatalogModelPreviewAnimationState`. A model opts into the extra channels simply by defining animations named `hover`, `hover_fadeout` and `focus`; the fade-out window is that animation's length. `RenderUtil.renderEntityInInventory(..., hovered, focused)` drives the state machine and sets `AnimationData.shouldPlayWhilePaused` on the preview animatable, because opening a GUI pauses single-player and the engine otherwise refuses to advance its clock - without that flag the tiles render as frozen poses.

A model's per-player settings are *roaming variables*: named floats that packs read as `v.roaming.<name>` and that the model's `模型设置` panel (declared as `extra_animation_buttons[].config_forms` in `ysm.json`) writes. The panel, the local copy and the server authority are `ModelConfigScreen`, `ClientRoamingStore` and `ExtendedRoamingVariables`; `ModelConfigRegistry` remembers which model declares which forms, and `ClientRoamingKeys` remembers the 32-bit namespace key of each model, which is derived from `RawProperties.sha256` by `ModelRoamingKey` so client and server agree without an extra payload field. The server is the authority: the client reports deltas through `C2SRoamingChanges` (serverbound id 99) and adopts the map the server echoes in `S2CRoamingState` (clientbound id 20), which also reaches every client tracking that player. Bounds live only in `ModelRoamingLimits`. A detached GUI preview reads the local player's values for the model it draws through `IMolangPhysicsScope.getMolangVariables`, because it has no entity for the engine to look roaming variables up by - that is what stops a preview tile from reading zero while the world reads the stored value.

Packs assume a roaming variable outlives a single frame: a `timeline` statement such as `v.roaming.player_size=v.roaming.player_size?v.roaming.player_size:1` runs once per animation restart and every later frame reads what it assigned, and `parallel1` scales the whole model from it (`"MRoot":{"scale":"v.roaming.player_size"}`). That only works while the engine's MoLang scope is stable, so `MolangPhysicsRuntime.ScopeKey` compares the owning entity, model and animation **by value** - never by object identity. A GUI tile rebuilds `setMainModel(ModelIdUtil.getMainId(modelId))` on every rendered frame, so an identity component in that key would hand the tile a fresh, empty scope per frame, the variable would read 0 and the model would blink out; the world is unaffected because there the key is the player's UUID. `MolangScopeKeyStabilityTest` guards this.

`ModelIdUtil` normalizes model names for `ResourceLocation`. Safe ids match `[a-z0-9._-]+`; unsafe names are encoded as `_name_` plus UTF-8 hex. Use `ModelIdUtil` helpers instead of hand-building model, main, arm, or texture ids.

Built-in model assets in `src/main/resources/assets/ysmu/custom` are copied into the runtime config directory on reload. Be careful when changing these assets because the runtime reload path intentionally overwrites the built-in copies. The one exception is a directory the user replaced with an OpenYSM model pack: when `config/ysmu/custom/<name>/ysm-pack.json` exists, the built-in copy for that name is skipped and logged (`ServerModelManager.builtInModelTarget`) and the directory counts as a pack root, not a model (`ServerModelManager.isPackRoot`). A pack root holds model directories, its `ysm-pack.png` is a cover and never a model texture, and its `ysm-pack.json` (name/description/lang) travels to clients in the OpenYSM sync index so the model selection GUI can label the folder with the pack's own name and cover.

## Network and Threading Rules

Packet ids in `NetworkHandler` are part of the wire protocol. Add new ids when needed; do not renumber existing ids. Keep packet side registration explicit and consistent with the handler behavior.

Do not perform heavy file IO, encryption/decryption, or model parsing directly on a packet handler path. Existing code uses `ThreadTools.THREAD_POOL` for background work and `Minecraft.func_152344_a(...)` when client-side model or texture registration must return to the client thread.

The model password must be available before cached model files can decrypt. Preserve the `SendModelPassword` before `RequestLoadModel` relationship and the retry behavior in `RequestLoadModel`.

The OpenYSM sync cache is named from the model's own sha256 plus `ModelCacheWriter.OPEN_YSM_BAKE_VERSION`, never from the payload bytes, and its signature check only proves the file belongs to that hash. Bump that constant whenever the baked payload changes meaning - the serializer, `RawYsmModelAdapter`, or which parts of a model the deserializer bakes - because otherwise a payload written by an older build (for example one with no geometry, from before cubes were baked into faces) keeps being served to clients whose cache hashes match as well. That is how ten models stayed invisible across reloads and restarts while a fresh bake and every local test looked correct.

## Compatibility Notes

Runtime prerequisites are UniMixins and GTNHLib. The animation engine is part of this mod, so a `geckolib-*.jar` must not be installed alongside it. Development/runtime extras include NotEnoughItems, Nashorn, Angelica, Backhand and JUnit as declared in `dependencies.gradle`.

Use `@EventBusSubscriber` from GTNHLib for event subscribers following the existing pattern. Client-only subscribers should specify `side = Side.CLIENT`.

Use `BackhandCompat` and `AngelicaCompat` rather than scattering optional-mod API calls through core logic. Keep compatibility checks resilient when the optional mod is absent.

## Coding Conventions

Follow `.editorconfig`: UTF-8, LF line endings, 4-space Java indentation, 2-space Markdown/JSON/YAML indentation, final newline, and no trailing whitespace except in `.lang` files.

On Windows PowerShell, read UTF-8 files with `-Encoding UTF8`; otherwise Chinese README, comments, and lang files may display as mojibake.

Modern Java syntax is enabled by Jabel, and the code already uses pattern variables. The produced mod still targets JVM 8, so avoid Java 9+ library APIs unless the project already provides or shades them.

Preserve existing public names and legacy casing, including the lowercase `ysmu` mod class. The GeckoLib animation engine is **vendored into this repository** (`src/main/java/software`, `src/main/java/com/eliotlash`, `src/main/java/net/geckominecraft`). Those trees are third-party compatibility code owned by this repository, so an engine fix is made here - keep the edit as narrow as the defect, do not refactor or re-style the vendored code around it, and record every deviation from the snapshot in `.agent/` and in `PROVENANCE.md`. `E:\IDEA\Geckolib` is the source of the snapshot only and is no longer a copy to sync from.

When adding user-facing text, update both `en_US.lang` and `zh_CN.lang`. When adding config fields, update `Config`, the relevant GUI screen if applicable, and translation keys.

When adding model animation states, register names and priorities through `AnimationRegister`/`AnimationManager`, and ensure `ConditionManager.addTest` can classify conditional animation names.

Animation-file expressions are evaluated by the legacy `software.bernie.geckolib3.core.molang.MolangParser` (a `MathBuilder` subclass in `com.eliotlash.mclib.math`), not by the engine's newer MoLang VM, so a Bedrock operator missing from `Operation` breaks a whole channel instead of raising anything visible: `"scale": "v.player_size??1"` failed to tokenise, the channel evaluated to 0 and ten model packs rendered as nothing. Add pack-facing operators to `com.eliotlash.mclib.math.Operation` (the lookup is table-driven) and cover them in `MolangParserCompatibilityTest`.

## Gradle and Verification

Gradle may be run directly, but a running build occupies the project: the daemon holds the build
outputs and the source tree, so editing while it runs can conflict with it or lose work. Start a build
only as the **final confirmation**, once every change is finished and no other agent or subagent is
still working in this workspace. Never start a build in the middle of a task.

Run the narrowest task that answers the question, from the repository root:

- `.\gradlew.bat compileJava` - fastest "does it still compile" check
- `.\gradlew.bat test` - unit tests
- `.\gradlew.bat build` - final confirmation only, when all work is done and nothing else is holding the tree
- `.\gradlew.bat runClient`
- `.\gradlew.bat runServer`

Do not assemble a classpath by scanning the Gradle cache to typecheck a change. It is slow and it
produces misleading errors, because several versions of the same jar end up on that classpath. Run the
Gradle task instead.

`src/test` holds the JUnit 5 sources that cover resource formats, sync packets, security helpers, and Molang physics. Because Gradle 9 no longer injects test-framework implementation dependencies, `testRuntimeClasspath` must keep the explicit `junit-platform-launcher` entry, and `fastutil` (used by mod code and pulled in transitively only for compilation) must stay declared for tests. CI delegates build/test and tagged releases to reusable GTNH workflows in `.github/workflows`.

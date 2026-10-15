# Preserve upstream sound identity and owner semantics


This living ExecPlan follows .agent/PLANS.md. Players can play one asset under multiple numeric or string ids, selectively stop an id, explicitly replace it, loop it, and hear a remote source at its actual owner. GUI audio is centered without inventing a world entity.

## Progress


- [x] Read AGENTS.md, gap audit G07-G09 and upstream consumers independently.
- [x] Implement id registry, explicit context and vanilla/direct streaming backend with real YsmSoundFunction consumer.
- [x] Connect existing tick and reload/disconnect clear through YSMSoundManager.
- [x] Coordinate actual custom instruction/keyframe/holder lifecycle hooks with audit and player hooks with verify; implement sync hook locally.
- [x] Read integrated custom-instruction/sound listeners, player hooks, form drain and GUI close; request and receive NPC UI classification correction.
- [ ] Have Lead compile/test after all concurrent edits stop.

## Authority and discoveries


Upstream tmp/YesSteveModel-dev-1.20/src/main/java/com/elfmcys/ysm/client/animation/molang/functions/SoundFunction.java:19-32,70-123 negates integer numeric ids, rejects resulting positive ids, pools strings, accepts mode 0-7, and defaults volume/pitch to one, clamping explicit values to 0.001-1000. SoundInstanceManager.java:47-108 makes id zero anonymous, permits multiple anonymous sounds, rejects a busy named id unless replacement is requested, and stops all anonymous and named instances together.

MolangContext.java:220-235 selects animation manager, then controller manager, then the owner's global manager. Bit 2 means global manager selection, NOT all actors and NOT no attenuation. MinecraftSoundInstance.java:24-32 follows owner and stops on removal; :48-50 makes only GUI sounds nonattenuated and relative.

The port CustomPlayerRenderer.java:253-273 shares one animatable across actors. AnimationFactory.java:27-33 isolates controllers through per-entity AnimationData, not through per-entity animatable objects. Consequently world global manager identity must be the actual Entity, and GUI identity the detached tile animatable. This port-specific mapping was accepted by Lead. No side registry of guessed animatables is introduced.

The existing SoundSystem newSource path incorrectly claimed streaming. Minecraft SoundManager.java:384 uses newStreamingSource; the adaptation uses that exact method. Direct playback grants the vanilla 20-tick asynchronous startup window (SoundManager.java:397) before checking completion. Configuration has no upstream model SOUND_VOLUME equivalent; no unrelated config change is made here. Direct sources use PLAYERS volume and update it each tick; SoundSystem master gain is already applied by Minecraft SoundManager.java:119,148-151 and must not be multiplied again. A Direct instance is created before source creation so a partial reflection failure removes the source. clear publishes an atomic generation fence from any thread and schedules client-thread draining; bind/play/tick consume it before newer playback, and an already-consumed queued drain is a no-op.

## Milestones and integration


MolangSoundContext.push(owner, nullableEntity, mainModelId, nullableLocalToken, ui, allowEmitting) is AutoCloseable. Closing restores evaluation context and does not stop audio. The stable token is per actual holder/controller instance, never shared Animation resource. The audit writer wraps custom instructions and sound keyframes and calls MolangSoundPlayback.stopAll(token) at actual clip stop/reset/release, not at loop wrap. The verify writer wraps player_init/player_update with owner=entity for world and detached preview identity for UI. S2CMolangSync wraps sync with its real entity, using the same global identity. Model changes bind a different model to that owner and release its old managers, matching upstream resource-context replacement; removal and world replacement release the owner, and reload/disconnect clear all runtime state through the existing YSMSoundManager lifecycle.

SoundInstanceRegistry is consumed by MolangSoundPlayback; MolangSoundPlayback is consumed by YsmSoundFunction and explicit-context keyframes. Vanilla sources use MovingSound; model Vorbis bytes and local assets use in-memory URLs and newStreamingSource. Positional sources retain owner; UI sources use no attenuation and the listener only as a coordinate substitute for 1.7.10's missing relative flag, never as ownership. Legacy public APIs remain available.

## Validation


No Gradle, classpath assembly, commits or proxy startup is allowed for this contributor. Lead must run .\\gradlew.bat compileJava followed by .\\gradlew.bat test --tests '*YsmSoundFunctionTest' --tests '*SoundInstanceRegistryTest' --tests '*SoundOwnershipTest' once everyone stops editing. Added tests cover independent ids, explicit replacement, missing assets preserving old instance, id zero anonymity, stop-all, numeric/string namespaces, clamps, nested context restoration and global selection. These tests have NOT been executed. Runtime acceptance requires two remote owners at distinct positions, replacement toggles, looping/stopping, removing an owner, switching model, detached preview, resource reload and disconnect/reconnect.

## Decision Log


Do not copy the reference branch's id loss: the requested id never becomes soundName. Reuse existing MolangStringPool, do not add another pool. Keep G09 deferred: the parser accepts only Vorbis identification headers and uses CodecJOrbis, whereas upstream CustomSoundInstance.java:30-50 acquires managed Vorbis/Opus streams. Supporting Opus requires a real decoder/provider plus ownership cleanup; changing the validation predicate alone would feed unsupported bytes to the codec. No codec dependency was added.

## Outcomes & Retrospective


Implementation is source-reviewed but not compiled or runtime-verified. Required engine/host hooks are coordinated, not assumed complete merely because API classes exist. Remaining verification and any lifecycle corrections belong to the final integrated review and Lead build. G09 remains an explicit gap.

package com.fox.ysmu.client.audio;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.ISound;
import net.minecraft.client.audio.MovingSound;
import net.minecraft.entity.Entity;
import net.minecraft.util.ResourceLocation;
import com.fox.ysmu.compat.LocalAssetProvider;
import com.fox.ysmu.compat.SoundNamespaceCompat;

/** SoundFunction:70-123 / SoundInstanceManager:23-108 adapted to the 1.7.10 audio backend. */
public final class MolangSoundPlayback {
    private static final Map<Object, SoundInstanceRegistry> MANAGERS = new HashMap<>();
    private static final Map<Object, ResourceLocation> OWNER_MODELS = new HashMap<>();
    private static final Map<Object, java.util.Set<Object>> OWNER_MANAGERS = new HashMap<>();

    /** Upstream replaces the owner's resource contexts on model unload; the shared 1.7 renderer must do it explicitly. */
    static void bindOwner(Object owner, ResourceLocation model, Object local) {
        drainClear();
        if (owner == null) return;
        if (OWNER_MODELS.containsKey(owner) && !java.util.Objects.equals(OWNER_MODELS.get(owner), model)) {
            releaseOwner(owner);
        }
        OWNER_MODELS.put(owner, model);
        java.util.Set<Object> managers = OWNER_MANAGERS.computeIfAbsent(owner, key -> new java.util.HashSet<>());
        managers.add(owner);
        if (local != null) managers.add(local);
    }

    public static void releaseOwner(Object owner) {
        java.util.Set<Object> managers = OWNER_MANAGERS.remove(owner);
        if (managers != null) new java.util.ArrayList<>(managers).forEach(MolangSoundPlayback::stopAll);
        else stopAll(owner);
        OWNER_MODELS.remove(owner);
    }
    private static final java.util.List<SoundInstanceRegistry.Instance> SOURCES = new java.util.ArrayList<>();
    private MolangSoundPlayback() {}

    public static boolean play(Object manager, Entity owner, boolean ui, int id, String name,
        ResourceLocation model, int mode, float volume, float pitch) {
        drainClear();
        if (manager == null || (!ui && owner == null)) return false;
        return MANAGERS.computeIfAbsent(manager, key -> new SoundInstanceRegistry()).play(id, (mode & 1) != 0,
            () -> create(owner, ui, name, model, (mode & 4) != 0, volume, pitch));
    }

    public static boolean stop(Object manager, int id) {
        SoundInstanceRegistry registry = MANAGERS.get(manager);
        return registry != null && registry.stop(id);
    }

    public static void stopAll(Object manager) {
        SoundInstanceRegistry registry = MANAGERS.remove(manager);
        if (registry != null) registry.stopAll();
        OWNER_MANAGERS.values().forEach(tokens -> tokens.remove(manager));
    }

    private static final java.util.concurrent.atomic.AtomicLong CLEAR_GENERATION = new java.util.concurrent.atomic.AtomicLong();
    private static long appliedClearGeneration;

    /** May be called by Netty: publish a fence, then drain only on the client thread. */
    public static void clear() {
        CLEAR_GENERATION.incrementAndGet();
        Minecraft mc = Minecraft.getMinecraft();
        if (mc != null) mc.func_152344_a(MolangSoundPlayback::drainClear);
    }

    private static void drainClear() {
        long generation = CLEAR_GENERATION.get();
        if (generation == appliedClearGeneration) return;
        appliedClearGeneration = generation;
        MANAGERS.values().forEach(SoundInstanceRegistry::stopAll);
        MANAGERS.clear();
        OWNER_MANAGERS.clear();
        OWNER_MODELS.clear();
        SOURCES.clear();
    }

    public static void tick() {
        drainClear();
        for (Object owner : new java.util.ArrayList<>(OWNER_MODELS.keySet())) {
            if (owner instanceof Entity entity && (entity.isDead || entity.worldObj != Minecraft.getMinecraft().theWorld)) {
                releaseOwner(owner);
            }
        }
        SOURCES.removeIf(instance -> {
            if (instance instanceof Direct direct) direct.tick();
            if (instance.isStopped()) { instance.stop(); return true; }
            return false;
        });
        MANAGERS.values().forEach(SoundInstanceRegistry::trim);
        MANAGERS.values().removeIf(SoundInstanceRegistry::isEmpty);
    }

    private static SoundInstanceRegistry.Instance create(Entity owner, boolean ui, String name,
        ResourceLocation model, boolean loop, float volume, float pitch) {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null) return null;
            if (name.contains(":")) {
                ResourceLocation location = new ResourceLocation(name);
                if (!YSMSoundManager.soundExistsInHandler(name)) {
                    ResourceLocation translated = SoundNamespaceCompat.resolve(name);
                    if (translated != null && YSMSoundManager.soundExistsInHandler(translated.toString())) location = translated;
                    else {
                        java.nio.file.Path local = LocalAssetProvider.resolveSound(name);
                        return local == null ? null : direct(owner, ui, loop, volume, pitch,
                            java.nio.file.Files.readAllBytes(local), name);
                    }
                }
                Vanilla instance = new Vanilla(location, owner, ui, loop, volume, pitch);
                mc.getSoundHandler().playSound(instance);
                SOURCES.add(instance);
                return instance;
            }
            byte[] bytes = model == null ? null : YSMSoundManager.getSoundBytes(model + "::" + name);
            return bytes == null ? null : direct(owner, ui, loop, volume, pitch, bytes, name);
        } catch (Exception | LinkageError ignored) { return null; }
    }

    private static Direct direct(Entity owner, boolean ui, boolean loop, float volume, float pitch,
        byte[] bytes, String name) throws Exception {
        // G09 intentionally remains Vorbis-only: do not hand Opus to CodecJOrbis.
        if (!YSMSoundManager.isValidOgg(bytes)) return null;
        Object system = YSMSoundManager.resolveSndSystem();
        if (system == null) return null;
        String source = "ysm_molang_" + java.util.UUID.randomUUID();
        // 1.7.10 has no relative ISound flag; UI NONE attenuation follows the listener only for centering.
        Entity position = ui ? Minecraft.getMinecraft().thePlayer : owner;
        java.net.URL url = new java.net.URL("file", "", -1, "/ysmu_sounds/" + source + ".ogg", new java.net.URLStreamHandler() {
            @Override protected java.net.URLConnection openConnection(java.net.URL u) {
                return new java.net.URLConnection(u) {
                    @Override public void connect() {}
                    @Override public java.io.InputStream getInputStream() { return new java.io.ByteArrayInputStream(bytes); }
                };
            }
        });
        Direct instance = new Direct(system, source, owner, ui, volume);
        try {
            system.getClass().getMethod("newStreamingSource", boolean.class, String.class, java.net.URL.class, String.class,
                boolean.class, float.class, float.class, float.class, int.class, float.class)
                .invoke(system, false, source, url, url.toString(), loop,
                    position == null ? 0f : (float) position.posX, position == null ? 0f : (float) position.posY,
                    position == null ? 0f : (float) position.posZ,
                    ui ? 0 : 2, 16f * Math.max(1f, volume));
            system.getClass().getMethod("setVolume", String.class, float.class).invoke(system, source, instance.effectiveVolume());
            system.getClass().getMethod("setPitch", String.class, float.class).invoke(system, source, pitch);
            system.getClass().getMethod("play", String.class).invoke(system, source);
        } catch (Exception failure) {
            instance.stop();
            throw failure;
        }
        SOURCES.add(instance);
        return instance;
    }

    private static final class Direct implements SoundInstanceRegistry.Instance {
        final Object system;
        final String source;
        final Entity owner;
        final boolean ui;
        final float configuredVolume;
        boolean stopped;
        // SoundSystem processes play asynchronously; vanilla SoundManager:397 grants 20 ticks too.
        int startupTicks = 20;
        Direct(Object system, String source, Entity owner, boolean ui, float configuredVolume) {
            this.system = system; this.source = source; this.owner = owner; this.ui = ui;
            this.configuredVolume = configuredVolume;
        }
        float effectiveVolume() {
            // Master gain is already applied by SoundSystem (SoundManager:119,148-151).
            // Upstream MinecraftSoundInstance:16 uses PLAYERS; 1.7.10 also has that category.
            return Math.max(0f, Math.min(1f, configuredVolume * Minecraft.getMinecraft().gameSettings
                .getSoundLevel(net.minecraft.client.audio.SoundCategory.PLAYERS)));
        }
        void tick() {
            if (startupTicks > 0) startupTicks--;
            if (owner != null && (owner.isDead || (!ui && owner.worldObj != Minecraft.getMinecraft().theWorld))) {
                stop(); return;
            }
            try {
                if (system != YSMSoundManager.resolveSndSystem()) { stop(); return; }
                system.getClass().getMethod("setVolume", String.class, float.class)
                    .invoke(system, source, effectiveVolume());
                Entity position = ui ? Minecraft.getMinecraft().thePlayer : owner;
                if (position != null) system.getClass().getMethod("setPosition", String.class, float.class, float.class, float.class)
                    .invoke(system, source, (float) position.posX, (float) position.posY, (float) position.posZ);
            } catch (Exception ignored) { stop(); }
        }
        @Override public boolean isStopped() {
            if (stopped) return true;
            if (startupTicks > 0) return false;
            try { return !(Boolean) system.getClass().getMethod("playing", String.class).invoke(system, source); }
            catch (Exception ignored) { return true; }
        }
        @Override public void stop() {
            if (stopped) return;
            stopped = true;
            try {
                system.getClass().getMethod("stop", String.class).invoke(system, source);
                system.getClass().getMethod("removeSource", String.class).invoke(system, source);
            } catch (Exception ignored) {}
        }
    }

    private static final class Vanilla extends MovingSound implements SoundInstanceRegistry.Instance {
        final Entity owner;
        final boolean ui;
        int startupTicks = 20;
        Vanilla(ResourceLocation name, Entity owner, boolean ui, boolean loop, float volume, float pitch) {
            super(name);
            this.owner = owner; this.ui = ui; this.repeat = loop;
            this.volume = volume; this.field_147663_c = pitch;
            if (ui) this.field_147666_i = ISound.AttenuationType.NONE;
            update();
        }
        @Override public void update() {
            if (startupTicks > 0) startupTicks--;
            if (owner != null && (owner.isDead || (!ui && owner.worldObj != Minecraft.getMinecraft().theWorld))) {
                stop(); return;
            }
            Entity position = ui ? Minecraft.getMinecraft().thePlayer : owner;
            if (position != null) {
                xPosF = (float) position.posX; yPosF = (float) position.posY; zPosF = (float) position.posZ;
            }
        }
        @Override public boolean isStopped() {
            return donePlaying || (startupTicks == 0 && !Minecraft.getMinecraft().getSoundHandler().isSoundPlaying(this));
        }
        @Override public void stop() {
            donePlaying = true;
            Minecraft.getMinecraft().getSoundHandler().stopSound(this);
        }
    }
}

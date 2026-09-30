package com.fox.ysmu.client.animation.molang;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.MathHelper;

import com.eliotlash.mclib.math.IValue;
import com.eliotlash.mclib.math.functions.Function;
import com.fox.ysmu.client.renderer.CustomPlayerRenderer;
import com.fox.ysmu.ysmu;

/**
 * {@code ysm.particle(id, offsetX, offsetY, offsetZ, deltaX, deltaY, deltaZ, speed, count, lifetime)} - the particle
 * instruction OpenYSM animation timelines use, for example
 * {@code ysm.particle('minecraft:note', math.random(-0.5,0.5), 1, math.random(0.5,1.5), math.random(0,1), 0, 0, 1)}.
 * <p>
 * The argument convention is OpenYSM's {@code ParticleEffectUtil}: argument 0 is the particle id, 1-3 the offset from
 * the entity, 4-6 the spread/velocity, 7 the speed multiplier, 8 the particle count (0 = one particle with the
 * offset and velocity verbatim) and 9 the lifetime. The offset is rotated by the entity's body yaw so a particle
 * emitted "in front of" a model stays in front of it, exactly like upstream's non-absolute mode.
 * <p>
 * Two 1.7.10 differences are deliberate: the lifetime argument has no equivalent (particles here live as long as
 * their own implementation decides) and the particle id is this version's flat name, so the namespace is stripped
 * ({@code minecraft:note} becomes {@code note}). An id this version does not know is reported once and skipped,
 * because {@code World#spawnParticle} would otherwise ignore it silently.
 */
public class YsmParticleFunction extends Function {

    /** The particle names 1.7.10's {@code EffectRenderer} understands, plus the Bedrock aliases worth mapping. */
    private static final Set<String> KNOWN_PARTICLES = Collections.unmodifiableSet(
        new HashSet<>(
            Arrays.asList(
                "hugeexplosion",
                "largeexplode",
                "explode",
                "fireworksSpark",
                "bubble",
                "suspended",
                "depthsuspend",
                "townaura",
                "crit",
                "magicCrit",
                "smoke",
                "largesmoke",
                "mobSpell",
                "mobSpellAmbient",
                "spell",
                "instantSpell",
                "witchMagic",
                "note",
                "portal",
                "enchantmenttable",
                "flame",
                "lava",
                "footstep",
                "splash",
                "wake",
                "cloud",
                "reddust",
                "snowballpoof",
                "snowshovel",
                "slime",
                "heart",
                "angryVillager",
                "happyVillager",
                "dripWater",
                "dripLava",
                "waterdrop")));

    /** Bedrock ids that mean one of the names above. */
    private static final String[][] ALIASES = new String[][] { { "basic_flame", "flame" },
        { "basic_smoke", "smoke" }, { "basic_crit", "crit" }, { "basic_bubble", "bubble" },
        { "basic_splash", "splash" }, { "heart_particle", "heart" }, { "villager_happy", "happyVillager" },
        { "villager_angry", "angryVillager" }, { "note_particle", "note" }, { "lava_particle", "lava" },
        { "water_splash", "splash" }, { "water_wake", "wake" }, { "mob_spell", "mobSpell" } };

    private static final Set<String> WARNED = Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<String, Boolean>());

    private final IValue[] arguments;

    // 必须实现这个特定签名的构造函数，供 MathBuilder 反射调用
    public YsmParticleFunction(IValue[] values, String name) throws Exception {
        super(values, name);
        this.arguments = values;
    }

    @Override
    public double get() {
        Minecraft mc = Minecraft.getMinecraft();
        // C-05: a preview must not emit into the world. The GUI tiles, the HUD paper doll and the world render of a
        // player standing behind a screen all run the same animations, so without this gate a pack's `ysm.particle`
        // in a preview channel spawned particles into the world - and, for the HUD pass, fired a second time for the
        // same frame. Upstream expresses the same rule as a property of the entity it renders previews with (its
        // CustomGuiPlayerEntity is an IPreviewEntity whose context is immutable, `ParticleFunction.java:21`); this
        // port shares one model, so the equivalent fact is "a preview render is in progress".
        if (CustomPlayerRenderer.isPreviewRendering()) {
            return 0;
        }
        EntityLivingBase entity = MolangFrameContext.getEntity();
        if (mc.theWorld == null || entity == null || this.arguments == null || this.arguments.length < 1) {
            return 0;
        }
        String name = legacyName(getStringArg(0));
        if (name == null) {
            return 0;
        }
        double offsetX = getArg(1);
        double offsetY = getArg(2);
        double offsetZ = getArg(3);
        double deltaX = getArg(4);
        double deltaY = getArg(5);
        double deltaZ = getArg(6);
        double speed = getArg(7);
        int count = (int) getArg(8);
        // Argument 9 is the lifetime; 1.7.10 has no per-particle lifetime to set.

        float yaw = -entity.renderYawOffset * 0.017453292F;
        float cos = MathHelper.cos(yaw);
        float sin = MathHelper.sin(yaw);
        double rotatedX = offsetX * cos + offsetZ * sin;
        double rotatedZ = -offsetX * sin + offsetZ * cos;
        double x = entity.posX + rotatedX;
        double y = entity.posY + offsetY;
        double z = entity.posZ + rotatedZ;

        if (count <= 0) {
            mc.theWorld.spawnParticle(name, x, y, z, speed * deltaX, speed * deltaY, speed * deltaZ);
            return 1;
        }
        for (int index = 0; index < count; index++) {
            double spreadX = entity.worldObj.rand.nextGaussian() * deltaX;
            double spreadY = entity.worldObj.rand.nextGaussian() * deltaY;
            double spreadZ = entity.worldObj.rand.nextGaussian() * deltaZ;
            double velocityX = entity.worldObj.rand.nextGaussian() * speed;
            double velocityY = entity.worldObj.rand.nextGaussian() * speed;
            double velocityZ = entity.worldObj.rand.nextGaussian() * speed;
            mc.theWorld.spawnParticle(
                name,
                entity.posX + rotatedX + spreadX,
                entity.posY + offsetY + spreadY,
                entity.posZ + rotatedZ + spreadZ,
                velocityX,
                velocityY,
                velocityZ);
        }
        return 1;
    }

    /** {@code minecraft:note} to this version's {@code note}; {@code null} (reported once) when unknown. */
    private static String legacyName(String id) {
        if (id == null || id.trim()
            .isEmpty()) {
            return null;
        }
        String name = id.trim();
        int separator = name.indexOf(':');
        if (separator >= 0 && separator + 1 < name.length()) {
            name = name.substring(separator + 1);
        }
        for (String[] alias : ALIASES) {
            if (alias[0].equalsIgnoreCase(name)) {
                name = alias[1];
                break;
            }
        }
        if (KNOWN_PARTICLES.contains(name)) {
            return name;
        }
        if (WARNED.add(name)) {
            ysmu.LOG.warn("ysm.particle('{}') has no 1.7.10 equivalent; the particle is skipped", id);
        }
        return null;
    }

    /** Kept for symmetry with the other host functions: the raw id as written, for diagnostics. */
    @Override
    public String toString() {
        String id = getStringArg(0);
        return "ysm.particle(" + (id == null ? "?" : id.toLowerCase(Locale.ROOT)) + ")";
    }
}

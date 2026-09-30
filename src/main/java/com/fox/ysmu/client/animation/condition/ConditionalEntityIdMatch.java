package com.fox.ysmu.client.animation.condition;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

import javax.annotation.Nullable;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.EntityLivingBase;

/**
 * The {@code <prefix>$<entity id>} condition names a model may declare, matched against one entity.
 * <p>
 * Upstream keeps two near-identical classes for this - {@code ConditionalVehicle} and {@code ConditionalPassenger}
 * ({@code client/animation/condition/ConditionalVehicle.java:20-79} and {@code ConditionalPassenger.java:20-79}) -
 * whose only difference is which entity they look at. The matching rule itself lives here once, because a rename of a
 * prefix or of the id shape would otherwise land in one copy and not the other.
 * <p>
 * Upstream also keeps a second set for {@code <prefix>#<tag>} names. 1.7.10 has no entity-type tags (they arrived
 * with 1.13 and there is no tag manager to ask), so only the id half exists here and
 * {@link ConditionNameDiagnostics} reports a {@code #} name as unsupported instead of dropping it silently.
 */
public abstract class ConditionalEntityIdMatch {

    private static final String EMPTY = "";

    /** The pack's own spelling of each declared id, so the name handed back is the declared animation name. */
    private final Set<String> ids = new LinkedHashSet<>();

    /** The prefix a declared name carries, e.g. {@code vehicle$}. */
    protected abstract String idPrefix();

    /** The entity to compare against this frame, or {@code null} when there is nothing to match. */
    @Nullable
    protected abstract Entity matchedEntity(EntityLivingBase entity);

    /** Feeds one animation name in; names with another prefix are ignored. */
    public void addTest(String name) {
        if (name == null || !name.startsWith(idPrefix()) || name.length() <= idPrefix().length()) {
            return;
        }
        ids.add(name.substring(idPrefix().length()));
    }

    /**
     * The first declared name whose id is this entity's type, or an empty string.
     *
     * @return the animation name, spelled exactly as the model declared it
     */
    public String doTest(EntityLivingBase entity) {
        Entity matched = entity == null ? null : matchedEntity(entity);
        if (matched == null || !matched.isEntityAlive() || ids.isEmpty()) {
            return EMPTY;
        }
        String candidate = normalizedId(matched);
        if (candidate.isEmpty()) {
            return EMPTY;
        }
        for (String id : ids) {
            if (id.equalsIgnoreCase(candidate)) {
                return idPrefix() + id;
            }
        }
        return EMPTY;
    }

    /**
     * 1.7.10 identifies an entity type by the string it was registered with ({@code EntityList.getEntityString}) -
     * {@code "Boat"} / {@code "Minecart"} for vanilla entities, and usually already {@code "modid:name"} for mods -
     * while upstream compares a {@code ResourceLocation} such as {@code minecraft:boat}
     * ({@code ConditionalVehicle.java:60-67}). Turning the local string into the same {@code domain:path} shape is what
     * lets one pack name mean the same thing on both, so the rule is one pure function rather than two inline ones.
     *
     * @return the lower-cased {@code domain:path} form, or an empty string when there is no name to compare
     */
    static String normalizedId(String registryName) {
        if (registryName == null || registryName.isEmpty()) {
            return EMPTY;
        }
        String lowered = registryName.toLowerCase(Locale.US);
        return lowered.indexOf(':') >= 0 ? lowered : "minecraft:" + lowered;
    }

    private static String normalizedId(Entity entity) {
        return normalizedId(EntityList.getEntityString(entity));
    }
}

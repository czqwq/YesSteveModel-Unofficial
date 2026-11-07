package com.fox.ysmu.client.animation.condition;

import java.util.regex.Pattern;

import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;

import org.apache.commons.lang3.StringUtils;

import com.fox.ysmu.client.compat.tacz.TACZCompat;

import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;

/**
 * Port of upstream {@code client/animation/condition/ConditionTAC.java:10-57}: the per-gun override names a model may
 * declare for TacZ's animation channels.
 *
 * <p>
 * A name looks like {@code tac:hold$tacz:ak47} - the channel prefix (with its trailing colon dropped) joined with the
 * gun id by {@code $} - which is why the prefix is split here and rebuilt against the id in {@link #doTest}. Upstream
 * asks {@link TACZCompat#getGunId} (its {@code TACZCompat.java:34}); with no TacZ gun in hand there is nothing to
 * override, so the answer is the empty string and the caller falls back to the weapon-type name.
 *
 * <p>
 * One 1.7.10 substitution: upstream validates the id with {@code ResourceLocation.isValidResourceLocation}
 * (1.20-only helper); this port uses the same 1.20 rule inline because 1.7.10's {@code ResourceLocation} accepts
 * anything (see the vanilla constructor at {@code build/rfg/minecraft-src/java/net/minecraft/util/ResourceLocation.java:29}).
 */
public final class ConditionTAC {

    private static final String EMPTY = "";
    /** 1.20's {@code ResourceLocation.isValidResourceLocation} rule: namespace and path character sets. */
    private static final Pattern VALID_ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9/._-]+");

    private final ObjectOpenHashSet<String> nameTest = new ObjectOpenHashSet<>();
    private final ObjectOpenHashSet<ResourceLocation> idTest = new ObjectOpenHashSet<>();

    public void addTest(String name) {
        if (name == null || !name.startsWith("tac:") || !name.contains("$")) {
            return;
        }
        String[] split = StringUtils.split(name, "$", 2);
        if (split.length < 2) {
            return;
        }
        String itemId = split[1];
        if (VALID_ID.matcher(itemId)
            .matches()) {
            nameTest.add(name);
            idTest.add(new ResourceLocation(itemId));
        }
    }

    public String doTest(ItemStack itemInHand, String prefix) {
        if (itemInHand == null || itemInHand.getItem() == null) {
            return EMPTY;
        }
        ResourceLocation gunId = TACZCompat.getGunId(itemInHand);
        if (gunId == null) {
            return EMPTY;
        }
        if (idTest.contains(gunId)) {
            String animationName = prefix.substring(0, prefix.length() - 1) + "$" + gunId;
            if (nameTest.contains(animationName)) {
                return animationName;
            }
        }
        return EMPTY;
    }
}

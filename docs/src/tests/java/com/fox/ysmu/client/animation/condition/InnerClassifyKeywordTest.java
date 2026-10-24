package com.fox.ysmu.client.animation.condition;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Guards the {@code :kind} keyword vocabulary the condition classifiers match on.
 * <p>
 * {@code InnerClassify}'s own comment keeps {@code KNOWN_TYPE_KEYWORDS} next to {@code getItemType}'s returns "so the
 * two lists cannot drift", but nothing enforced that. A kind missing from the set makes a pack name such as
 * {@code hold_mainhand:slashblade} silently unselectable - the failure mode this whole audit keeps finding - and the
 * only reason A-10 and A-07 were visible at all is that someone read the two lists side by side.
 * <p>
 * The check is deliberately offline: {@code isKnownTypeKeyword} is a pure lookup, so no ItemStack or Minecraft class
 * is needed. The names below are the ones the 1.7.10 port added or the audit named.
 */
class InnerClassifyKeywordTest {

    @Test
    void theKindsTheClassifiersMatchOnAreKnownKeywords() {
        for (String kind : new String[] { "slashblade", "gohei", "sword", "axe", "pickaxe", "shovel", "hoe", "spyglass",
            "spear", "trident", "fishing_rod", "shield", "crossbow", "bow" }) {
            assertTrue(InnerClassify.isKnownTypeKeyword(kind), kind);
        }
    }

    @Test
    void aPackNameIsMatchedWhateverItCasesTheKeywordAs() {
        assertTrue(InnerClassify.isKnownTypeKeyword("SlashBlade"));
        assertFalse(InnerClassify.isKnownTypeKeyword("definitely_not_a_kind"));
        assertFalse(InnerClassify.isKnownTypeKeyword(null));
    }
}

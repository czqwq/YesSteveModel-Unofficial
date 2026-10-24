package com.fox.ysmu.client.animation.condition;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Guards the id normalisation the {@code vehicle$…} / {@code passenger$…} classifiers compare on.
 * <p>
 * The rule is derived rather than copied: upstream compares the vehicle's {@code ResourceLocation}
 * ({@code minecraft:boat}, {@code ConditionalVehicle.java:60-67}) while 1.7.10 identifies an entity type by the string
 * it was registered with ({"Boat"}, or already {"modid:name"} for mods). Both spellings have to end up in one shape or
 * a pack name that upstream would match silently matches nothing here - the same class of silence the whole audit is
 * about. No Minecraft classes are touched: this is the pure half of the comparison.
 */
class ConditionalEntityIdMatchTest {

    @Test
    void vanillaRegistryNamesGainTheMinecraftDomain() {
        assertEquals("minecraft:boat", ConditionalEntityIdMatch.normalizedId("Boat"));
        assertEquals("minecraft:minecart", ConditionalEntityIdMatch.normalizedId("Minecart"));
        assertEquals("minecraft:pig", ConditionalEntityIdMatch.normalizedId("Pig"));
    }

    @Test
    void aNameThatAlreadyCarriesADomainIsOnlyLowerCased() {
        assertEquals("modid:thing", ConditionalEntityIdMatch.normalizedId("modid:thing"));
        assertEquals("modid:thing", ConditionalEntityIdMatch.normalizedId("MODID:Thing"));
        assertEquals("minecraft:boat", ConditionalEntityIdMatch.normalizedId("minecraft:boat"));
    }

    @Test
    void nothingToCompareNormalisesToEmpty() {
        assertEquals("", ConditionalEntityIdMatch.normalizedId(null));
        assertEquals("", ConditionalEntityIdMatch.normalizedId(""));
    }
}

package com.fox.ysmu.client.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import net.minecraft.util.ResourceLocation;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.fox.ysmu.client.ClientModelManager;
import com.fox.ysmu.data.ModelData;
import com.fox.ysmu.data.WheelDeclarations;
import com.fox.ysmu.model.format.Type;
import com.fox.ysmu.model.resource.pojo.RawYsmModel;

/**
 * {@code metadata.links} on the client: the registration funnel, the per-model index and the information screen's cap.
 *
 * <p>The chain these tests walk is the one that was missing: a synced payload's links
 * ({@code ModelData#getLinks()}) are indexed by {@code ClientModelManager.registerAll} into
 * {@link ModelConfigRegistry}, and {@link ModelInfoScreen#visibleLinks(ResourceLocation)} is what the screen draws -
 * upstream's own rule, at most two entries in declaration order
 * ({@code tmp/YesSteveModel-dev-1.20/.../client/gui/ModelInfoScreen.java:111-124}).</p>
 */
class ModelInfoLinksTest {

    private static final ResourceLocation MODEL = new ResourceLocation("ysmu", "links_screen_probe");
    /** Distinguishes this class's payloads so each one is really registered; see {@link #payload}. */
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    @AfterEach
    void cleanup() {
        ModelConfigRegistry.clear();
    }

    /**
     * A payload carrying links, in the same shape the sync paths hand to {@code registerAll}.
     * <p>
     * Each payload gets its own wheel-declaration digest on purpose: {@code ClientModelManager.registerAll} skips a
     * payload whose content signature is unchanged ({@code ClientModelManager.java:541-544}, and the signature covers
     * the wheel declarations but not the links), so two payloads that differ only in their links would be the *same*
     * signature in production - a statement about the model, not about the sync path. A unique digest keeps every
     * registration in this test class a real registration.
     */
    private static ModelData payload(Map<String, String> links) {
        RawYsmModel.RawProperties properties = new RawYsmModel.RawProperties();
        properties.sha256 = "links-screen-probe-" + SEQUENCE.incrementAndGet();
        return new ModelData(
            MODEL.getResourcePath(),
            Type.FOLDER,
            Collections.emptyMap(),
            Collections.emptyMap(),
            Collections.emptyMap(),
            Collections.emptyMap(),
            WheelDeclarations.from(properties),
            Collections.emptyMap(),
            links);
    }

    private static Map<String, String> links(String... typeToUrl) {
        Map<String, String> links = new LinkedHashMap<>();
        for (int i = 0; i < typeToUrl.length; i += 2) {
            links.put(typeToUrl[i], typeToUrl[i + 1]);
        }
        return links;
    }

    /**
     * The registration funnel: what a payload declares is what the index answers, in declaration order - "the first
     * two links" is only meaningful if the order survived.
     */
    @Test
    void registeringAPayloadIndexesItsLinksInOrder() {
        Map<String, String> declared = links("home", "https://example.invalid/home", "donate", "https://example.invalid/pay");

        ClientModelManager.registerAll(payload(declared));

        assertEquals(
            Arrays.asList("home", "donate"),
            new ArrayList<>(ModelConfigRegistry.linksFor(MODEL).keySet()),
            "declaration order is part of the value");
        assertEquals("https://example.invalid/home", ModelConfigRegistry.linksFor(MODEL).get("home"));
    }

    /** The screen shows at most two, in declaration order (upstream's {@code Math.min(links.size(), 2)}). */
    @Test
    void theScreenShowsAtMostTheFirstTwoLinks() {
        ClientModelManager.registerAll(payload(links("home", "u1", "donate", "u2", "wiki", "u3")));

        List<Map.Entry<String, String>> visible = ModelInfoScreen.visibleLinks(MODEL);

        assertEquals(2, visible.size(), "upstream caps the row at two entries");
        assertEquals("home", visible.get(0)
            .getKey());
        assertEquals("donate", visible.get(1)
            .getKey());
        assertTrue(
            visible.stream()
                .noneMatch(link -> "wiki".equals(link.getKey())),
            "the third link is not drawn");
    }

    /** A model with no links draws no buttons, and neither does a null id (the screen can be built before any model). */
    @Test
    void noLinksMeansNoRow() {
        assertTrue(ModelInfoScreen.visibleLinks(MODEL).isEmpty(), "a model that was never registered has none");
        assertTrue(ModelInfoScreen.visibleLinks(null).isEmpty());
        assertTrue(ModelConfigRegistry.linksFor(null).isEmpty());

        ClientModelManager.registerAll(payload(Collections.emptyMap()));
        assertTrue(ModelInfoScreen.visibleLinks(MODEL).isEmpty(), "a payload that declares none shows none");
    }

    /**
     * The index is self-healing like the wheel tables beside it: a payload that declares no links removes the previous
     * bytes' links rather than leaving the old model's addresses on the same id, and {@code clear()} drops everything
     * at a reload/session boundary.
     */
    @Test
    void aLaterPayloadWithoutLinksClearsTheEarlierOnes() {
        ClientModelManager.registerAll(payload(links("home", "u1")));
        assertFalse(ModelConfigRegistry.linksFor(MODEL).isEmpty());

        ClientModelManager.registerAll(payload(Collections.emptyMap()));
        assertTrue(ModelConfigRegistry.linksFor(MODEL).isEmpty(), "empty payload = this model declares none");

        ClientModelManager.registerAll(payload(links("home", "u1")));
        ModelConfigRegistry.clear();
        assertTrue(ModelConfigRegistry.linksFor(MODEL).isEmpty());
    }

    /**
     * An entry without a URL is not indexed: the screen would otherwise draw a button that can never open anything
     * (and hand the tooltip renderer a null string).
     */
    @Test
    void aLinkWithoutAUrlIsDropped() {
        Map<String, String> declared = new LinkedHashMap<>();
        declared.put("home", "u1");
        declared.put("broken", null);
        declared.put("blank", "   ");
        declared.put(null, "u2");
        ClientModelManager.registerAll(payload(declared));

        assertEquals(
            Collections.singletonList("home"),
            new ArrayList<>(ModelConfigRegistry.linksFor(MODEL).keySet()),
            "only the complete entry is usable");
    }
}

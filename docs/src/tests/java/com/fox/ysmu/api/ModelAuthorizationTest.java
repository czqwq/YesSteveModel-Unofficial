package com.fox.ysmu.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

import net.minecraft.util.ResourceLocation;

import org.junit.jupiter.api.Test;

import com.fox.ysmu.eep.ExtendedModelInfo;
import com.fox.ysmu.model.catalog.CatalogAccess;
import com.fox.ysmu.network.message.SetModelAndTexture;

/**
 * The access decision of {@link ModelAuthorization} - the port of upstream's {@code canSelect}
 * ({@code model/session/server/ServerModelSession.java:317-322}) - as a pure function, with no player and no server.
 *
 * <p>The cases pinned here are exactly upstream's rule plus the two concessions its call site makes: a public model is
 * selectable with an empty grant table, a restricted one is not, an <em>absent</em> grant table (a build that never
 * attached the EEP) does not lock anybody out, and the intrinsic default is never gated because every refusal resets
 * to it. The registry-driven half - which models count as restricted, and the asset gate - lives in
 * {@code model/ModelAccessPolicyTest}.</p>
 *
 * <b>What a unit JVM cannot drive</b>, recorded rather than approximated: the call site itself
 * ({@code SetModelAndTexture.Handler.handleEEP}) takes an {@code EntityPlayerMP}, which cannot be fabricated here -
 * its constructor dereferences a live {@code WorldServer.provider} and {@code MinecraftServer.getConfigurationManager()}
 * (the same limit {@code YsmCommandTest} documents). The last test therefore reads the compiled class's constant pool
 * to prove the handler really consults this class, so "the decision is tested" cannot silently become "the decision is
 * tested but not used".
 */
class ModelAuthorizationTest {

    private static final ResourceLocation MODEL_A = new ResourceLocation("ysmu", "model_a");
    private static final ResourceLocation MODEL_B = new ResourceLocation("ysmu", "model_b");
    private static final ResourceLocation INTRINSIC_DEFAULT = ExtendedModelInfo.defaultModelId();

    private static Set<ResourceLocation> grants(ResourceLocation... ids) {
        return new LinkedHashSet<>(Arrays.asList(ids));
    }

    // ---------------------------------------------------------------------------------------------------------------
    // the selection rule: public, or granted
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    void aPublicModelIsSelectableWithAnEmptyGrantTable() {
        // Upstream CatalogRecord.java:32-33 marks everything outside the auth root PUBLIC, and canSelect accepts a
        // public record without consulting the grant table (ServerModelSession.java:317-322). This is also the upgrade
        // guard: a server whose config/ysmu/auth is empty restricts nothing, so nothing changes for it.
        assertTrue(ModelAuthorization.canSelect(grants(), MODEL_A, CatalogAccess.PUBLIC));
        assertTrue(ModelAuthorization.canSelect(grants(MODEL_B), MODEL_A, CatalogAccess.PUBLIC));
        assertTrue(ModelAuthorization.canSelect(null, MODEL_A, CatalogAccess.PUBLIC));
    }

    @Test
    void aRestrictedModelNeedsAGrant() {
        assertFalse(ModelAuthorization.canSelect(grants(), MODEL_A, CatalogAccess.AUTHORIZED));
        assertFalse(ModelAuthorization.canSelect(grants(MODEL_A), MODEL_B, CatalogAccess.AUTHORIZED));
    }

    @Test
    void aGrantedRestrictedModelIsAccepted() {
        assertTrue(ModelAuthorization.canSelect(grants(MODEL_A), MODEL_A, CatalogAccess.AUTHORIZED));
        assertTrue(ModelAuthorization.canSelect(grants(MODEL_A, MODEL_B), MODEL_B, CatalogAccess.AUTHORIZED));
    }

    @Test
    void anAbsentGrantTableMeansTheBuildDidNotAttachItNotAnEmptyGrantList() {
        // Attached from EntityEvent.EntityConstructing (ExtendedAuthModels.register); an absent table is a wiring
        // fault, and answering "deny" for it would lock every player out of every restricted model.
        assertTrue(ModelAuthorization.canSelect(null, MODEL_A, CatalogAccess.AUTHORIZED));
    }

    @Test
    void theServersOwnDefaultModelIsNeverGated() {
        // It is the fallback this class resets players to; upstream accepts its selection kind unconditionally
        // (ServerModelSession.java:153-156) and resolves it without a grant check (ModelSelectionService.java:15-31).
        assertTrue(ModelAuthorization.canSelect(grants(), INTRINSIC_DEFAULT, CatalogAccess.AUTHORIZED));
        assertTrue(ModelAuthorization.canSelect(null, INTRINSIC_DEFAULT, CatalogAccess.AUTHORIZED));
    }

    @Test
    void anEmptySelectionIsNotAGrantDecision() {
        // The packet maps an empty model string to null; that path already keeps the previous selection (the EEP's own
        // guard) and must not be turned into a refusal here.
        assertTrue(ModelAuthorization.canSelect(grants(), null, CatalogAccess.AUTHORIZED));
    }

    @Test
    void theSelectionPointReallyConsultsThisDecision() throws IOException {
        // The compiled handler must reference ModelAuthorization: without this, the tests above would keep passing
        // while the packet path went back to storing whatever the client sent. The call lives in the nested Handler,
        // so its own class file is the one to read.
        Class<?> handler = null;
        for (Class<?> nested : SetModelAndTexture.class.getDeclaredClasses()) {
            if (nested.getSimpleName()
                .equals("Handler")) {
                handler = nested;
                break;
            }
        }
        assertNotNull(handler, "SetModelAndTexture must still declare its Handler");

        String pool = new String(readClassBytes(handler), StandardCharsets.ISO_8859_1);
        assertTrue(
            pool.contains("com/fox/ysmu/api/ModelAuthorization"),
            "SetModelAndTexture.Handler must still call api/ModelAuthorization");
        assertTrue(
            pool.contains("maySelect") || pool.contains("canSelect"),
            "the call must still name the decision method, not merely import the class");
    }

    // ---------------------------------------------------------------------------------------------------------------
    // argument resolution and the "all" grant
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    void aRegisteredInternalIdResolvesToTheIdTheClientSends() {
        ResourceLocation resolved = ModelAuthorization
            .resolveModelArgument("wine_fox/01_taisho_maid", ids("wine_fox/01_taisho_maid", "default"));

        assertEquals(new ResourceLocation("ysmu", "wine_fox/01_taisho_maid"), resolved);
    }

    @Test
    void anEncodedDisplayNameResolvesToItsEncodedId() {
        // A directory whose name needs encoding is registered as _name_<utf8 hex>; an operator types the readable name.
        String encoded = com.fox.ysmu.util.ModelIdUtil.getInternalModelId("example model");

        assertEquals(
            new ResourceLocation("ysmu", encoded),
            ModelAuthorization.resolveModelArgument("example model", ids(encoded, "default")));
        assertEquals(
            new ResourceLocation("ysmu", encoded),
            ModelAuthorization.resolveModelArgument("  example model  ", ids(encoded)),
            "surrounding whitespace is trimmed");
    }

    @Test
    void aNamespacedArgumentResolvesToTheIdThePacketCarries() {
        assertEquals(
            new ResourceLocation("ysmu", "default"),
            ModelAuthorization.resolveModelArgument("ysmu:default", ids("default")));
    }

    @Test
    void anUnknownNameResolvesToNothing() {
        assertNull(ModelAuthorization.resolveModelArgument("nope", ids("default")));
        assertNull(ModelAuthorization.resolveModelArgument("", ids("default")));
        assertNull(ModelAuthorization.resolveModelArgument("   ", ids("default")));
        assertNull(ModelAuthorization.resolveModelArgument("ysmu:", ids("default")));
        assertNull(ModelAuthorization.resolveModelArgument(null, ids("default")));
    }

    @Test
    void grantingAllTakesEveryRegisteredModelInTheClientsIdentity() {
        Set<ResourceLocation> all = ModelAuthorization.allRegisteredModelIds(ids("default", "wine_fox/01_x"));

        assertEquals(2, all.size());
        assertTrue(all.contains(new ResourceLocation("ysmu", "default")));
        assertTrue(all.contains(new ResourceLocation("ysmu", "wine_fox/01_x")));
    }

    @Test
    void theServerRegistryOverloadsAgreeWithThePureOnes() {
        // The public entry points read CACHE_NAME_INFO; this only proves they are wired to the same helpers (the map is
        // empty in a unit JVM, unless another test populated it).
        assertNotNull(ModelAuthorization.allRegisteredModelIds());
        assertEquals(
            ModelAuthorization.allRegisteredModelIds(com.fox.ysmu.model.ServerModelManager.CACHE_NAME_INFO.keySet()),
            ModelAuthorization.allRegisteredModelIds());
    }

    private static Set<String> ids(String... values) {
        return new HashSet<>(Arrays.asList(values));
    }

    /** The compiled bytes of {@code type}, straight from the test classpath. */
    private static byte[] readClassBytes(Class<?> type) throws IOException {
        String resource = type.getName()
            .replace('.', '/') + ".class";
        try (InputStream in = type.getClassLoader()
            .getResourceAsStream(resource)) {
            assertNotNull(in, resource + " must be readable from the classpath");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }
}

package com.fox.ysmu.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraft.util.ResourceLocation;

import org.junit.jupiter.api.Test;

import com.fox.ysmu.data.ModelData;
import com.fox.ysmu.data.WheelDeclarations;
import com.fox.ysmu.model.format.Type;

import software.bernie.geckolib3.geo.raw.pojo.ExtraInfo;

/**
 * The display-time name resolution behind the three name-drawing sites ({@code ModelButton.declaredName},
 * {@code ModelInfoScreen} and {@code PlayerModelScreen}): the pack's own language table for the running locale, then
 * the name the geometry declares, then the leaf name.
 *
 * <p>Each case uses its own model id: the registry is static client state that lives as long as the models do, so two
 * cases sharing an id would depend on execution order.</p>
 *
 * <p>The lookup takes the locale as a parameter instead of reading it from Minecraft, which is what makes this
 * testable here; {@code currentLocale()} is the only part that needs a running client, and it is a one-line adapter
 * over {@code Language.getLanguageCode()}.</p>
 */
class ModelLocalizedNameTest {

    private static ResourceLocation id(String path) {
        return new ResourceLocation("ysmu", path);
    }

    /** Upstream's lookup is exactly two steps ({@code ModelInfoView.translate:95-102}): locale, then {@code en_us}. */
    @Test
    void theRunningLocaleWinsAndEnglishIsTheSecondStep() {
        ResourceLocation model = id("test/lang_locale");
        Map<String, Map<String, String>> files = new LinkedHashMap<>();
        files.put("zh_cn", Map.of("metadata.name", "艾利克斯"));
        files.put("en_us", Map.of("metadata.name", "Alex"));
        ClientModelMetadataRegistry.acceptLanguageFiles(model, files);

        assertEquals("艾利克斯", ClientModelMetadataRegistry.displayName(model, "zh_cn", "1_alex"));
        assertEquals("Alex", ClientModelMetadataRegistry.displayName(model, "en_us", "1_alex"));
        // A locale the pack has no table for therefore reads the en_us table rather than the leaf name.
        assertEquals("Alex", ClientModelMetadataRegistry.displayName(model, "fr_fr", "1_alex"));
    }

    @Test
    void aModelWithoutLanguageTablesFallsBackToTheGeometryNameThenTheLeafName() {
        ResourceLocation withGeometryName = id("test/lang_geometry_name");
        ExtraInfo info = new ExtraInfo();
        info.setName("Geometry Name");
        ClientModelMetadataRegistry.acceptExtraInfo(withGeometryName, info);
        assertEquals("Geometry Name", ClientModelMetadataRegistry.displayName(withGeometryName, "zh_cn", "leaf"));

        ResourceLocation bare = id("test/lang_bare");
        assertEquals("leaf", ClientModelMetadataRegistry.displayName(bare, "zh_cn", "leaf"));
    }

    /** A blank translation must not shadow the name the geometry declares - the same {@code isNotBlank} rule as ever. */
    @Test
    void aBlankLocalizedValueDoesNotShadowTheGeometryName() {
        ResourceLocation model = id("test/lang_blank");
        ExtraInfo info = new ExtraInfo();
        info.setName("Geometry Name");
        ClientModelMetadataRegistry.acceptExtraInfo(model, info);
        ClientModelMetadataRegistry.acceptLanguageFiles(model, Map.of("zh_cn", Map.of("metadata.name", "   ")));

        assertEquals("Geometry Name", ClientModelMetadataRegistry.displayName(model, "zh_cn", "leaf"));
    }

    /** 1.7.10 reports {@code zh_CN}/{@code en_US} while the packs name their files {@code zh_cn.json}. */
    @Test
    void localeCodesAreNormalizedToThePackFileForm() {
        assertEquals("zh_cn", ClientModelMetadataRegistry.normalizeLocale("zh_CN"));
        assertEquals("en_us", ClientModelMetadataRegistry.normalizeLocale("en_US"));
        assertEquals("en_us", ClientModelMetadataRegistry.normalizeLocale("en-US"));
        assertEquals("", ClientModelMetadataRegistry.normalizeLocale(null));
    }

    /** The payload must not alias the maps its builder still holds, or a later edit would change a registered model. */
    @Test
    void modelDataKeepsADefensiveCopyOfTheLanguageTables() {
        Map<String, Map<String, String>> source = new LinkedHashMap<>();
        Map<String, String> table = new LinkedHashMap<>();
        table.put("metadata.name", "Alex");
        source.put("en_us", table);

        ModelData data = new ModelData(
            "test_model",
            Type.FOLDER,
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of(),
            WheelDeclarations.empty(),
            Map.of(),
            null,
            "",
            false,
            source);
        assertEquals(Map.of("en_us", Map.of("metadata.name", "Alex")), data.getLanguageFiles());

        table.put("metadata.name", "Changed");
        source.put("zh_cn", Map.of("metadata.name", "艾利克斯"));
        assertEquals(
            "Alex",
            data.getLanguageFiles()
                .get("en_us")
                .get("metadata.name"),
            "a later edit of the source map must not reach the payload");
        assertFalse(data.getLanguageFiles().containsKey("zh_cn"), "a locale added after construction must not appear");
        assertThrows(
            UnsupportedOperationException.class,
            () -> data.getLanguageFiles()
                .put("x", Map.of()));
    }
}

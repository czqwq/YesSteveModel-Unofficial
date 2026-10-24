package com.fox.ysmu.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.minecraft.util.ResourceLocation;

import org.junit.jupiter.api.Test;

/**
 * Model ids encode the whole disk-relative path into one {@code ResourceLocation} segment, and the folder-aware model
 * selection GUI decodes it again to derive folders. These tests pin that round trip.
 */
class ModelIdUtilTest {

    private static final String NAMESPACE = "ysmu";

    @Test
    void nestedDiskPathRoundTripsThroughTheEncodedId() {
        String diskPath = "wine_fox/01_taisho_maid";
        ResourceLocation id = new ResourceLocation(NAMESPACE, ModelIdUtil.getInternalModelId(diskPath));

        assertEquals(diskPath, ModelIdUtil.getModelDisplayName(id));
        assertEquals("01_taisho_maid", ModelIdUtil.getModelFileName(id));
        assertEquals("wine_fox/", ModelPathUtil.parentHierarchy(ModelIdUtil.getModelDisplayName(id)));
    }

    @Test
    void nonAsciiNameRoundTripsToo() {
        String diskPath = "酒狐/我的模型";
        ResourceLocation id = new ResourceLocation(NAMESPACE, ModelIdUtil.getInternalModelId(diskPath));

        assertEquals(diskPath, ModelIdUtil.getModelDisplayName(id));
        assertEquals("我的模型", ModelIdUtil.getModelFileName(id));
    }

    @Test
    void topLevelSafeNameStaysUnencoded() {
        ResourceLocation id = new ResourceLocation(NAMESPACE, ModelIdUtil.getInternalModelId("steve"));

        assertEquals("steve", id.getResourcePath());
        assertEquals("steve", ModelIdUtil.getModelDisplayName(id));
        assertEquals("", ModelPathUtil.parentHierarchy(ModelIdUtil.getModelDisplayName(id)));
    }

    @Test
    void parentModelIdStripsTheSubModelSegment() {
        ResourceLocation nested = new ResourceLocation(NAMESPACE, "_name_77696e655f666f78/main");
        ResourceLocation flat = new ResourceLocation(NAMESPACE, "steve/arm");
        ResourceLocation noSegment = new ResourceLocation(NAMESPACE, "steve");

        assertEquals("_name_77696e655f666f78", ModelIdUtil.getParentModelId(nested).getResourcePath());
        assertEquals("steve", ModelIdUtil.getParentModelId(flat).getResourcePath());
        assertEquals(noSegment, ModelIdUtil.getParentModelId(noSegment));
    }
}

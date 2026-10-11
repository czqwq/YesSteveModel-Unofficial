package com.fox.ysmu.model.resource;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import com.fox.ysmu.data.ModelData;
import com.fox.ysmu.model.resource.pojo.RawYsmModel;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

class MaterialPayloadBridgeTest {
    private RawYsmModel model() {
        RawYsmModel raw = new RawYsmModel();
        RawYsmModel.RawGeometry geo = new RawYsmModel.RawGeometry();
        geo.identifier = "geometry.test";
        geo.sourceJson = ("{\"format_version\":\"1.12.0\",\"minecraft:geometry\":[{\"description\":{"
            + "\"identifier\":\"geometry.test\",\"texture_width\":1,\"texture_height\":1},\"bones\":[]}]}").getBytes(StandardCharsets.UTF_8);
        raw.mainEntity.mainModel = geo; raw.mainEntity.armModel = geo;
        RawYsmModel.RawTexture base = new RawYsmModel.RawTexture();
        base.name = "skin.png"; base.sourceFileName = "skin.png"; base.imageFormat = 2; base.data = new byte[] {1};
        raw.mainEntity.textures.put(base.name, base);
        // Avoid built-in fallback resource IO.
        for (String key : new String[] {"main", "arm", "extra"}) {
            RawYsmModel.RawAnimationFile file = new RawYsmModel.RawAnimationFile();
            file.sourceJson = "{\"animations\":{}}".getBytes(StandardCharsets.UTF_8);
            raw.mainEntity.animationFiles.put(key, file);
        }
        return raw;
    }

    @Test void componentsStayAttachedToBaseAndNeverBecomeSelectable() throws Exception {
        RawYsmModel raw = model();
        for (int role = 1; role <= 2; role++) {
            RawYsmModel.RawTexture.SubTexture sub = new RawYsmModel.RawTexture.SubTexture();
            sub.specularType = role; sub.imageFormat = 2; sub.data = new byte[] {(byte) role};
            raw.mainEntity.textures.get("skin.png").subTextures.add(sub);
        }
        ModelData payload = RawYsmModelAdapter.toLegacyModelData(raw, "test");
        assertEquals(java.util.Set.of("skin.png"), payload.getTexture().keySet());
        assertEquals(java.util.Set.of(1, 2), payload.getPbrComponents().get("skin.png").keySet());
        assertArrayEquals(new byte[] {1}, payload.getPbrComponents().get("skin.png").get(1));
        assertArrayEquals(new byte[] {2}, payload.getPbrComponents().get("skin.png").get(2));
    }

    @Test void normalizedTimelineReplacesOnlyTimelinePreservingUnknownSourceFields() throws Exception {
        RawYsmModel raw = model();
        RawYsmModel.RawAnimationFile file = raw.mainEntity.animationFiles.get("main");
        file.sourceJson = ("{\"unknown_root\":3,\"animations\":{\"idle\":{\"unknown_anim\":\"keep\","
            + "\"timeline\":{\"0\":[\"bad-old\"]}}}}").getBytes(StandardCharsets.UTF_8);
        RawYsmModel.RawAnimation animation = new RawYsmModel.RawAnimation(); animation.name = "idle";
        RawYsmModel.RawTimelineEvent event = new RawYsmModel.RawTimelineEvent();
        event.timestamp = 0; event.events.add("v.a=1;v.b=2;"); animation.timelineEvents.add(event);
        file.animations.put(animation.name, animation);
        JsonObject root = new JsonParser().parse(new String(
            RawYsmModelAdapter.toLegacyModelData(raw, "test").getAnimation().get("main"), StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals(3, root.get("unknown_root").getAsInt());
        JsonObject idle = root.getAsJsonObject("animations").getAsJsonObject("idle");
        assertEquals("keep", idle.get("unknown_anim").getAsString());
        assertTrue(idle.getAsJsonObject("timeline").get("0.0").isJsonPrimitive());
        assertTrue(idle.getAsJsonObject("timeline").toString().contains("v.a=1;v.b=2;"));
        assertFalse(idle.getAsJsonObject("timeline").toString().contains("bad-old"));
    }
}

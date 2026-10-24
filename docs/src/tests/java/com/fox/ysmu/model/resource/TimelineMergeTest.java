package com.fox.ysmu.model.resource;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.Collections;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonParser;
import com.fox.ysmu.model.resource.pojo.RawYsmModel;

class TimelineMergeTest {
    @Test
    void defaultPreservesIndependentStringInstructions() {
        assertFalse(new RawYsmModel.RawProperties().mergeMultilineExpr);
        RawYsmModel.RawAnimation animation = new RawYsmModel.RawAnimation();
        YSMFolderDeserializer.parseTimeline(new JsonParser().parse(
            "{\"timeline\":{\"0\":[\"v.a=1;\",null,2,true,{},\"v.b=2;\"]}}")
            .getAsJsonObject(), animation, false);
        assertEquals(Arrays.asList("v.a=1;", "v.b=2;"), animation.timelineEvents.get(0).events);
    }

    @Test
    void timelineUsesTimestampOrderNotJsonInsertionOrder() {
        RawYsmModel.RawAnimation animation = new RawYsmModel.RawAnimation();
        YSMFolderDeserializer.parseTimeline(new JsonParser().parse(
            "{\"timeline\":{\"2\":\"second;\",\"0\":\"first;\"}}")
            .getAsJsonObject(), animation, false);
        assertEquals(Collections.singletonList("first;"), animation.timelineEvents.get(0).events);
        assertEquals(Collections.singletonList("second;"), animation.timelineEvents.get(1).events);
    }

    @Test
    void enabledMergeUsesNewlinesIncludingTrailingNewline() {
        RawYsmModel.RawAnimation animation = new RawYsmModel.RawAnimation();
        YSMFolderDeserializer.parseTimeline(new JsonParser().parse(
            "{\"timeline\":{\"1\":[\"v.a=1;\",null,2,\"v.b=2;\"],\"2\":\"v.c=3;\",\"3\":[]}}")
            .getAsJsonObject(), animation, true);
        assertEquals(Collections.singletonList("v.a=1;\nv.b=2;\n"), animation.timelineEvents.get(0).events);
        assertEquals(Collections.singletonList("v.c=3;"), animation.timelineEvents.get(1).events);
        assertEquals(Collections.singletonList(""), animation.timelineEvents.get(2).events);
    }
}

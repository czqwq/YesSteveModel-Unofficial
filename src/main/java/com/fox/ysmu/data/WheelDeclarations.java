package com.fox.ysmu.data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fox.ysmu.model.resource.pojo.RawYsmModel;
import com.google.gson.Gson;

/**
 * A detached snapshot of the settings declarations, never an image or geometry resource.
 * Upstream ModelInfoView:69-87,133-142 keeps ordered pairs, buttons/forms and classifications together.
 * The legacy channel has no manifest, so it carries this independent JSON section in its encrypted container.
 * No dispatch, form or roaming-variable rules live in this transport object.
 */
public final class WheelDeclarations {

    private static final Gson GSON = new Gson();
    private final String json;

    public static final class Values {
        public String sha256 = "";
        public Map<String, String> animations = new LinkedHashMap<>();
        public List<RawYsmModel.ExtraAnimationButton> buttons = new ArrayList<>();
        public List<RawYsmModel.ExtraAnimationClassify> classifies = new ArrayList<>();
    }

    private WheelDeclarations(Values values) {
        // Serializing once detaches all nested mutable forms/maps from the parser's raw model.
        json = GSON.toJson(values);
    }

    public static WheelDeclarations empty() {
        return new WheelDeclarations(new Values());
    }

    public static WheelDeclarations from(RawYsmModel.RawProperties properties) {
        Values values = new Values();
        if (properties != null) {
            values.sha256 = properties.sha256;
            values.animations = properties.extraAnimations;
            values.buttons = properties.extraAnimationButtons;
            values.classifies = properties.extraAnimationClassifies;
        }
        return new WheelDeclarations(values);
    }

    public static WheelDeclarations decode(String json) {
        Values values = GSON.fromJson(json, Values.class);
        if (values == null || values.sha256 == null || values.animations == null
            || values.buttons == null || values.classifies == null) {
            throw new IllegalArgumentException("Incomplete wheel declarations");
        }
        for (RawYsmModel.ExtraAnimationButton button : values.buttons) {
            if (button == null || button.forms == null) {
                throw new IllegalArgumentException("Incomplete wheel button");
            }
            for (RawYsmModel.ConfigForm form : button.forms) {
                if (form == null || form.labels == null) {
                    throw new IllegalArgumentException("Incomplete wheel form");
                }
            }
        }
        for (RawYsmModel.ExtraAnimationClassify classify : values.classifies) {
            if (classify == null || classify.extras == null) {
                throw new IllegalArgumentException("Incomplete wheel classification");
            }
        }
        return new WheelDeclarations(values);
    }

    public String encode() {
        return json;
    }

    /** Each consumer gets a fresh deep copy, with declaration order preserved explicitly. */
    public Values values() {
        Values values = GSON.fromJson(json, Values.class);
        values.animations = new LinkedHashMap<>(values.animations);
        for (RawYsmModel.ExtraAnimationClassify classify : values.classifies) {
            classify.extras = new LinkedHashMap<>(classify.extras);
        }
        for (RawYsmModel.ExtraAnimationButton button : values.buttons) {
            for (RawYsmModel.ConfigForm form : button.forms) {
                form.labels = new LinkedHashMap<>(form.labels);
            }
        }
        return values;
    }
}

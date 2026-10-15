package com.fox.ysmu.model.resource.pojo;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class RawYsmModel {

    public String modelId = "";
    public int formatVersion;
    public RawMetadata metadata = new RawMetadata();
    public RawProperties properties = new RawProperties();
    public RawMainEntity mainEntity = new RawMainEntity();
    public Map<String, RawSubEntity> vehicles = new LinkedHashMap<>();
    public Map<String, RawSubEntity> projectiles = new LinkedHashMap<>();
    public Map<String, RawDataFile> soundFiles = new LinkedHashMap<>();
    public Map<String, RawDataFile> functionFiles = new LinkedHashMap<>();
    public Map<String, RawLanguageFile> languageFiles = new LinkedHashMap<>();
    public RawFooter footer = new RawFooter();

    public static class RawMainEntity {
        public RawGeometry mainModel;
        public RawGeometry armModel;
        public Map<String, RawTexture> textures = new LinkedHashMap<>();
        public Map<String, RawAnimationFile> animationFiles = new LinkedHashMap<>();
        public List<RawAnimationControllerFile> animationControllerFiles = new ArrayList<>();
    }

    public static class RawAnimationControllerFile {
        public String name = "";
        public String hash = "";
        public int legacyUnknownInt;
        public byte[] sourceJson;
        public Map<String, RawAnimationController> controllers = new LinkedHashMap<>();
    }

    public static class RawSubEntity {
        public String identifier = "";
        public String[] matchIds;
        public RawGeometry model;
        public Map<String, RawTexture> textures = new LinkedHashMap<>();
        public Map<String, RawAnimationFile> animationFiles = new LinkedHashMap<>();
        public List<RawAnimationControllerFile> animationControllerFiles = new ArrayList<>();
    }

    public static class RawGeometry {
        public int modelType;
        public String identifier = "";
        public String sha256 = "";
        public float textureWidth = 64f;
        public float textureHeight = 64f;
        public float visibleBoundsWidth;
        public float visibleBoundsHeight;
        public float[] visibleBoundsOffset;
        public float unkFloat1;
        public float unkFloat2;
        public int footerPad1;
        public int footerPad2;
        public int footerPad3;
        public byte[] sourceJson;
        public List<RawBone> bones = new ArrayList<>();
    }

    public static class RawBone {
        public String name = "";
        public String parentName = "";
        public float[] pivot = new float[3];
        public float[] rotation = new float[3];
        public int unkPad1;
        public int unkPad2;
        public int unkPad3;
        public int unkPad4;
        public int unkPad5;
        public List<RawCube> cubes = new ArrayList<>();
    }

    public static class RawCube {
        public List<RawFace> faces = new ArrayList<>();
        public int unkInt1;
        public int unkInt2;
        public int unkInt3;
    }

    public static class RawFace {
        public float[] normal = new float[3];
        public float[][] positions = new float[4][3];
        public float[] u = new float[4];
        public float[] v = new float[4];
    }

    public static class RawAnimationFile {
        public int animType;
        public String fileHash = "";
        public byte[] sourceJson;
        public Map<String, RawAnimation> animations = new LinkedHashMap<>();
    }

    public static class RawAnimation {
        public String name = "";
        public float length;
        public int loopMode;
        public Object blendWeight;
        public int unkInt1;
        public int unkInt2;
        public int unkInt4;
        public List<RawBoneAnimation> boneAnimations = new ArrayList<>();
        public List<RawTimelineEvent> timelineEvents = new ArrayList<>();
        public List<RawSoundEffect> soundEffects = new ArrayList<>();
        /**
         * Bedrock's per-animation {@code anim_speed}: a number or a MoLang expression returning the playback rate.
         * Carried to the engine's {@link software.bernie.geckolib3.core.builder.Animation#animSpeed} by
         * {@code RawYsmModelAdapter.createAnimationJson} and read there by {@code ClientModelManager.attachAnimSpeed}.
         *
         * <p>Before this field existed the value only survived the paths that keep the animation's original JSON
         * ({@code RawAnimationFile.sourceJson}, i.e. a flat folder model and a legacy bare-YSGP {@code .ysm}); the
         * OpenYSM binary payload is re-emitted from these structure fields, so without a field here the value was
         * dropped for every model on the binary channel - which is the default channel
         * ({@code Config.ENABLE_OPEN_YSM_SYNC_PROTOCOL}). See {@code YSMBinarySerializer.writeAnimationFileContent}.
         */
        public String animSpeed;
        /**
         * Bedrock's per-animation {@code anim_time_update}: a MoLang expression returning the animation time in
         * seconds. Same chain and same reason as {@link #animSpeed}; consumed by the engine's
         * {@link software.bernie.geckolib3.core.builder.Animation#animTimeUpdate}, whose advance
         * {@code AnimationController} already implements.
         */
        public String animTimeUpdate;
    }

    public static class RawBoneAnimation {
        public String boneName = "";
        public List<RawKeyframe> rotation = new ArrayList<>();
        public List<RawKeyframe> position = new ArrayList<>();
        public List<RawKeyframe> scale = new ArrayList<>();
    }

    public static class RawKeyframe {
        public float timestamp;
        public int interpolationMode;
        public Object[] postData = new Object[] { 0f, 0f, 0f };
        public Object[] preData = new Object[] { 0f, 0f, 0f };
        public boolean hasPreData;
    }

    public static class RawTimelineEvent {
        public float timestamp;
        public List<String> events = new ArrayList<>();
    }

    public static class RawSoundEffect {
        public String effectName = "";
        public float timestamp;
    }

    public static class RawTexture {
        public String name = "";
        public String sourceFileName = "";
        public String hash = "";
        public int width;
        public int height;
        public int imageFormat;
        public byte[] data;
        public int unknownFlag = 1;
        public List<SubTexture> subTextures = new ArrayList<>();

        public static class SubTexture {
            public String hash = "";
            public int specularType;
            public int width;
            public int height;
            public int imageFormat;
            public byte[] data;
            public int unknownFlag = 1;
        }
    }

    public static class RawAnimationController {
        public String animationName = "";
        public String initialState = "";
        public List<RawControllerState> states = new ArrayList<>();
    }

    public static class RawControllerState {
        public String name = "";
        public Map<String, String> animations = new LinkedHashMap<>();
        public Map<String, String> transitions = new LinkedHashMap<>();
        public List<String> onEntry = new ArrayList<>();
        public List<String> onExit = new ArrayList<>();
        public List<String> soundEffects = new ArrayList<>();
        public float blendTransitionValue;
        public boolean blendViaShortestPath;
        public Map<Float, Float> blendTransitions = new LinkedHashMap<>();
    }

    public static class RawMetadata {
        public String name = "";
        public String tips = "";
        public String licenseType = "";
        public String licenseDescription = "";
        public List<Author> authors = new ArrayList<>();
        public Map<String, String> links = new LinkedHashMap<>();
        public List<RawImage> extraAvatars = new ArrayList<>();

        public static class Author {
            public String name = "";
            public String role = "";
            public String comment = "";
            public Map<String, String> contacts = new LinkedHashMap<>();
            public String avatar = "";
            public RawImage avatarImage;
        }
    }

    public static class RawImage {
        public String name = "";
        public byte[] data;
        public int width;
        public int height;
        public int format;
        public int unknownFlag = 1;
        public boolean isPng;
    }

    public static class RawProperties {
        public String sha256 = "";
        public float widthScale = 0.7f;
        public float heightScale = 0.7f;
        public String defaultTexture = "default";
        public String previewAnimation = "";
        public boolean isFree;
        public boolean renderLayersFirst;
        public boolean allCutout;
        public boolean disablePreviewRotation;
        /**
         * {@code gui_no_lighting} - parsed (YSMFolderDeserializer:176) and carried over the wire
         * (YSMBinarySerializer:552 / YSMBinaryDeserializer:672), and it has <b>no consumer in this repository</b> on
         * purpose, because no authority for its meaning was found:
         * <ul>
         * <li>upstream's only consumer copies the value into the native protobuf
         * ({@code tmp/YesSteveModel-dev-1.20/.../format/parser/ModelInfoBuilder.java:95}
         * {@code .setGuiNoLighting(properties.guiNoLighting)},
         * {@code src/main/proto/manifest/info/model_settings.proto:11} {@code bool gui_no_lighting = 5;}), i.e. the
         * behaviour lives in native code this repository does not build or read;</li>
         * <li>the available native source has no live reader either: the field is decoded from the legacy container
         * and projected into the protobuf ({@code tmp/YesSteveModel-Native-dev/modules/legacy/src/v3/container/
         * decoder.cc:1192}, {@code .../v3/conversion/projector.cc:525},
         * {@code .../proto/include/proto/manifest/info/model_settings.proto.h:13}) but the renderer's only mention is
         * a commented-out block, {@code modules/gfx/src/renderer/render_impl.inc:97-101}, guarding on a
         * {@code kGuiNoLighting} template parameter that is not declared anywhere in that module (the renderer's
         * template parameters are {@code kCulling}, {@code kIris}, {@code kHasPbr}, {@code kPosOnly}), so it is dead
         * text rather than a consumer;</li>
         * <li>the reference branch does consume it - {@code
         * tmp/YesSteveModel-Unofficial-perf-previewUI/.../client/ClientModelManager.java:112-113,2192} +
         * {@code util/RenderUtil.java:607-614}, which turns off {@code RenderHelper.enableStandardItemLighting} and
         * drives a full-bright lightmap for a model that opts in - but {@code AGENTS.md} says that branch is a
         * completed port, not a correct one, so its choice is not authority for inventing the semantics here.</li>
         * </ul>
         * Kept as a recorded gap: wiring it would mean inventing a brightness behaviour and a switch to control it.
         * See the task-9 decision briefing for the options and their costs.
         */
        public boolean guiNoLighting;
        public boolean mergeMultilineExpr = false;
        public String guiForeground = "";
        public String guiBackground = "";
        public List<RawImage> backgroundImages = new ArrayList<>();
        public Map<String, String> extraAnimations = new LinkedHashMap<>();
        public List<ExtraAnimationClassify> extraAnimationClassifies = new ArrayList<>();
        public List<ExtraAnimationButton> extraAnimationButtons = new ArrayList<>();
    }

    public static class ExtraAnimationClassify {
        public String id = "";
        public Map<String, String> extras = new LinkedHashMap<>();
    }

    public static class ExtraAnimationButton {
        public String id = "";
        public String name = "";
        public String description = "";
        public List<ConfigForm> forms = new ArrayList<>();
    }

    public static class ConfigForm {
        public String type = "";
        public String title = "";
        public String description = "";
        /**
         * The MoLang expression this form reads and writes, taken from the JSON key {@code "value"} - for example
         * {@code v.roaming.player_size}. The name is historic (upstream keeps the same expression as
         * {@code read_program} and builds {@code write_program} as {@code expression + "=t.value"}); it is not a
         * numeric default, so do not parse it as one.
         */
        public String defaultValue = "";
        public float step;
        public float min;
        public float max;
        public Map<String, String> labels = new LinkedHashMap<>();
    }

    public static class RawDataFile {
        public String hash = "";
        public byte[] data;

        public RawDataFile() {}

        public RawDataFile(String hash, byte[] data) {
            this.hash = hash;
            this.data = data;
        }
    }

    public static class RawLanguageFile {
        public String hash = "";
        public Map<String, String> data = new LinkedHashMap<>();

        public RawLanguageFile() {}

        public RawLanguageFile(String hash, Map<String, String> data) {
            this.hash = hash;
            this.data = data;
        }
    }

    public static class RawFooter {
        public int version = 65535;
        public int unkInt1 = 1;
        public String rand = "";
        public long time;
        public String extra = "";
        public int unkInt2;
    }
}

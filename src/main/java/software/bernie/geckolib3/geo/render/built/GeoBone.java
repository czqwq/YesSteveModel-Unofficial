package software.bernie.geckolib3.geo.render.built;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import net.minecraft.util.ResourceLocation;

import software.bernie.geckolib3.core.processor.IBone;
import software.bernie.geckolib3.core.snapshot.BoneSnapshot;

public class GeoBone implements IBone, Serializable {

    private static final long serialVersionUID = 42L;
    public GeoBone parent;

    public List<GeoBone> childBones = new ArrayList<>();
    public List<GeoCube> childCubes = new ArrayList<>();

    public String name;
    private BoneSnapshot initialSnapshot;

    public Boolean mirror;
    public Double inflate;
    public Boolean dontRender;
    public boolean isHidden;
    public boolean areCubesHidden = false;
    public boolean hideChildBonesToo;
    // I still have no idea what this field does, but its in the json file so
    // ¯\_(ツ)_/¯
    public Boolean reset;

    private float scaleX = 1;
    private float scaleY = 1;
    private float scaleZ = 1;

    private float positionX;
    private float positionY;
    private float positionZ;

    public float rotationPointX;
    public float rotationPointY;
    public float rotationPointZ;

    private float rotateX;
    private float rotateY;
    private float rotateZ;

    public transient Object extraData;

    /**
     * Per-bone render state: the port of upstream's packed transparency+glow attribute
     * ({@code com/elfmcys/ysm/geckolib3/model/AnimatedGeoBone.java:9-16, 201-211}), which lives in slot 13 of that
     * fork's 14-float-per-bone attribute array. The colour is a multiplier whose default is white, the transparency
     * is an alpha in 0..255 whose default is opaque, and the glow is an emissive level where -1 means "not
     * emissive" - upstream stores that as {@code 0xFF} in the high byte. Upstream rebuilds the array every frame;
     * this bone is cached per model, so {@code MolangPhysicsRuntime} resets these at the start of each frame.
     */
    private int renderColor = 0xFFFFFF;
    private int renderTransparency = 255;
    private int renderGlow = -1;

    public int getRenderColor() {
        return this.renderColor;
    }

    public float getRenderRed() {
        return (this.renderColor & 0xFF) / 255.0F;
    }

    public float getRenderGreen() {
        return ((this.renderColor >> 8) & 0xFF) / 255.0F;
    }

    public float getRenderBlue() {
        return ((this.renderColor >> 16) & 0xFF) / 255.0F;
    }

    public int getRenderTransparency() {
        return this.renderTransparency;
    }

    public float getRenderAlpha() {
        return this.renderTransparency / 255.0F;
    }

    public int getRenderGlow() {
        return this.renderGlow;
    }

    /** Packs each channel into 0..255 the way upstream's {@code setColor} does, then packs red|green&lt;&lt;8|blue&lt;&lt;16. */
    public void setRenderColor(int red, int green, int blue) {
        this.renderColor = (red & 0xFF) | ((green & 0xFF) << 8) | ((blue & 0xFF) << 16);
    }

    public void setRenderTransparency(int alpha) {
        this.renderTransparency = alpha & 0xFF;
    }

    /** @param level 0..15, or -1 for "not emissive"; upstream's {@code setGlow} stores -1 as {@code 0xFF}. */
    public void setRenderGlow(int level) {
        this.renderGlow = level < -1 ? -1 : Math.min(level, 15);
    }

    public void resetRenderState() {
        this.renderColor = 0xFFFFFF;
        this.renderTransparency = 255;
        this.renderGlow = -1;
    }

    /**
     * Optional texture override for this bone. When set, the render pipeline
     * binds this texture before rendering cubes of this bone instead of the
     * model-level default texture.
     * <p>
     * Used by OpenYSM-style multi-texture models (e.g. bow bones that render
     * with the projectile arrow texture while the rest of the model uses the
     * player texture).
     */
    @Nullable
    public ResourceLocation textureOverride;

    @Override
    public void setModelRendererName(String modelRendererName) {
        this.name = modelRendererName;
    }

    @Override
    public void saveInitialSnapshot() {
        if (this.initialSnapshot == null) {
            this.initialSnapshot = new BoneSnapshot(this, true);
        }
    }

    @Override
    public BoneSnapshot getInitialSnapshot() {
        return this.initialSnapshot;
    }

    @Override
    public String getName() {
        return this.name;
    }

    // Boilerplate code incoming

    @Override
    public float getRotationX() {
        return rotateX;
    }

    @Override
    public float getRotationY() {
        return rotateY;
    }

    @Override
    public float getRotationZ() {
        return rotateZ;
    }

    @Override
    public float getPositionX() {
        return positionX;
    }

    @Override
    public float getPositionY() {
        return positionY;
    }

    @Override
    public float getPositionZ() {
        return positionZ;
    }

    @Override
    public float getScaleX() {
        return scaleX;
    }

    @Override
    public float getScaleY() {
        return scaleY;
    }

    @Override
    public float getScaleZ() {
        return scaleZ;
    }

    @Override
    public void setRotationX(float value) {
        this.rotateX = value;
    }

    @Override
    public void setRotationY(float value) {
        this.rotateY = value;
    }

    @Override
    public void setRotationZ(float value) {
        this.rotateZ = value;
    }

    @Override
    public void setPositionX(float value) {
        this.positionX = value;
    }

    @Override
    public void setPositionY(float value) {
        this.positionY = value;
    }

    @Override
    public void setPositionZ(float value) {
        this.positionZ = value;
    }

    @Override
    public void setScaleX(float value) {
        this.scaleX = value;
    }

    @Override
    public void setScaleY(float value) {
        this.scaleY = value;
    }

    @Override
    public void setScaleZ(float value) {
        this.scaleZ = value;
    }

    @Override
    public boolean isHidden() {
        return this.isHidden;
    }

    @Override
    public void setHidden(boolean hidden) {
        this.setHidden(hidden, hidden);
    }

    @Override
    public void setPivotX(float value) {
        this.rotationPointX = value;
    }

    @Override
    public void setPivotY(float value) {
        this.rotationPointY = value;
    }

    @Override
    public void setPivotZ(float value) {
        this.rotationPointZ = value;
    }

    @Override
    public float getPivotX() {
        return this.rotationPointX;
    }

    @Override
    public float getPivotY() {
        return this.rotationPointY;
    }

    @Override
    public float getPivotZ() {
        return this.rotationPointZ;
    }

    @Override
    public boolean cubesAreHidden() {
        return areCubesHidden;
    }

    @Override
    public boolean childBonesAreHiddenToo() {
        return hideChildBonesToo;
    }

    @Override
    public void setCubesHidden(boolean hidden) {
        this.areCubesHidden = hidden;
    }

    @Override
    public void setHidden(boolean selfHidden, boolean skipChildRendering) {
        this.isHidden = selfHidden;
        this.hideChildBonesToo = skipChildRendering;
    }
}

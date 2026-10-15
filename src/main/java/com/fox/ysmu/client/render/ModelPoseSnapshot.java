package com.fox.ysmu.client.render;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import net.minecraft.util.ResourceLocation;

import com.fox.ysmu.util.ModelIdUtil;

import software.bernie.geckolib3.geo.render.built.GeoBone;
import software.bernie.geckolib3.geo.render.built.GeoModel;
import software.bernie.geckolib3.resource.GeckoLibCache;

/**
 * Saves and restores the bone transforms of a model.
 * <p>
 * A {@code GeoModel} is shared by everything that renders it - the world entity, the model-selection preview and the
 * HUD overlay all mutate the same {@code GeoBone} instances - while an animation only writes the channels it declares.
 * A bone that the preview animation posed (the pack's {@code preview_animation} scales the model root to zero for its
 * GUI layout) therefore keeps that pose after the preview ends, and the next world frame renders it, because that bone
 * has no channel in the world animation and so nothing resets it. The visible result is a model whose body is gone,
 * even though its geometry is intact.
 * <p>
 * Upstream keeps the two apart with a dedicated {@code CustomGuiPlayerEntity}. This port renders both through one
 * model, so a preview render has to leave the pose as it found it.
 */
public final class ModelPoseSnapshot {

    private final List<BonePose> bones;

    private ModelPoseSnapshot(List<BonePose> bones) {
        this.bones = bones;
    }

    /** Captures the model registered under {@code modelId} (main or plain id), or {@code null} when it is not loaded. */
    @Nullable
    public static ModelPoseSnapshot capture(ResourceLocation modelId) {
        if (modelId == null) {
            return null;
        }
        GeoModel model = GeckoLibCache.getInstance()
            .getGeoModels()
            .get(ModelIdUtil.getMainId(modelId));
        return model == null ? null : capture(model);
    }

    public static ModelPoseSnapshot capture(GeoModel model) {
        List<BonePose> poses = new ArrayList<>();
        for (GeoBone bone : model.topLevelBones) {
            collect(bone, poses);
        }
        return new ModelPoseSnapshot(poses);
    }

    /** Puts every captured bone back exactly where it was. */
    public void restore() {
        for (BonePose pose : this.bones) {
            pose.restore();
        }
    }

    private static void collect(GeoBone bone, List<BonePose> poses) {
        poses.add(new BonePose(bone));
        for (GeoBone child : bone.childBones) {
            collect(child, poses);
        }
    }

    /** One bone's animated transform. */
    private static final class BonePose {

        private final GeoBone bone;
        private final float positionX;
        private final float positionY;
        private final float positionZ;
        private final float rotationX;
        private final float rotationY;
        private final float rotationZ;
        private final float scaleX;
        private final float scaleY;
        private final float scaleZ;

        private BonePose(GeoBone bone) {
            this.bone = bone;
            this.positionX = bone.getPositionX();
            this.positionY = bone.getPositionY();
            this.positionZ = bone.getPositionZ();
            this.rotationX = bone.getRotationX();
            this.rotationY = bone.getRotationY();
            this.rotationZ = bone.getRotationZ();
            this.scaleX = bone.getScaleX();
            this.scaleY = bone.getScaleY();
            this.scaleZ = bone.getScaleZ();
        }

        private void restore() {
            this.bone.setPositionX(this.positionX);
            this.bone.setPositionY(this.positionY);
            this.bone.setPositionZ(this.positionZ);
            this.bone.setRotationX(this.rotationX);
            this.bone.setRotationY(this.rotationY);
            this.bone.setRotationZ(this.rotationZ);
            this.bone.setScaleX(this.scaleX);
            this.bone.setScaleY(this.scaleY);
            this.bone.setScaleZ(this.scaleZ);
        }
    }
}

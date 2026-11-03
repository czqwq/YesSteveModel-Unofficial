package com.fox.ysmu.client.render;

import java.util.ArrayDeque;
import java.util.Arrays;

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
 *
 * <h3>Why the storage is reused</h3>
 * <p>This bracket runs once per preview draw, i.e. up to hundreds of times a second while the model-selection screen is
 * open (one capture per tile bake, and the tile rate is the whole point of {@code PreviewRefreshPolicy}), so the
 * previous implementation - one {@code BonePose} object per bone plus the list holding them - was pure garbage at
 * exactly the rate the screen was tuned to: 344 bones of {@code wine_fox/05_magical} meant ~19 KB of short-lived
 * objects per bake. The values are nine floats per bone, so they are stored in one growable {@code float[]} here and
 * the instance itself comes from a small pool; a steady-state capture allocates nothing. The pool is a render-thread
 * structure (every caller is a draw path, and {@code capture}/{@code restore} bracket a single draw), so it needs no
 * locking.
 * <p>
 * The bones are held only for the duration of the bracket: {@link #restore()} writes the values back, drops every
 * bone reference and only then returns the snapshot to the pool, so a pooled snapshot cannot keep geometry alive after
 * the resource framework releases it.
 */
public final class ModelPoseSnapshot {

    /** position X/Y/Z, rotation X/Y/Z, scale X/Y/Z - {@link software.bernie.geckolib3.core.processor.IBone}'s transform. */
    private static final int STRIDE = 9;

    /** Maximum number of nested open snapshots kept for reuse; a preview inside a draw is the deepest nesting. */
    private static final int MAX_POOLED = 4;

    /** Reused snapshots, render thread only; see the class javadoc. */
    private static final ArrayDeque<ModelPoseSnapshot> POOL = new ArrayDeque<>();

    /** Bones captured, in pre-order; references are dropped again by {@link #restore()}. */
    private GeoBone[] bones = new GeoBone[0];
    /** Captured transforms, {@link #STRIDE} floats per bone; grown with {@link #bones}. */
    private float[] pose = new float[0];
    private int count;

    private ModelPoseSnapshot() {}

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
        ModelPoseSnapshot snapshot = POOL.poll();
        if (snapshot == null) {
            snapshot = new ModelPoseSnapshot();
        }
        int index = 0;
        for (GeoBone bone : model.topLevelBones) {
            index = snapshot.collect(bone, index);
        }
        snapshot.count = index;
        return snapshot;
    }

    /** Appends {@code bone} and its subtree to the arrays, growing them when the model has more bones than before. */
    private int collect(GeoBone bone, int index) {
        if (index == this.bones.length) {
            this.bones = Arrays.copyOf(this.bones, Math.max(16, index * 2));
            this.pose = Arrays.copyOf(this.pose, this.bones.length * STRIDE);
        }
        this.bones[index] = bone;
        int value = index * STRIDE;
        this.pose[value] = bone.getPositionX();
        this.pose[value + 1] = bone.getPositionY();
        this.pose[value + 2] = bone.getPositionZ();
        this.pose[value + 3] = bone.getRotationX();
        this.pose[value + 4] = bone.getRotationY();
        this.pose[value + 5] = bone.getRotationZ();
        this.pose[value + 6] = bone.getScaleX();
        this.pose[value + 7] = bone.getScaleY();
        this.pose[value + 8] = bone.getScaleZ();
        index++;
        for (GeoBone child : bone.childBones) {
            index = collect(child, index);
        }
        return index;
    }

    /**
     * Puts every captured bone back exactly where it was, then returns this snapshot to the pool.
     * <p>
     * A second call is a no-op (the capture is consumed by the first), which keeps a doubled {@code finally} from
     * putting the same instance into the pool twice.
     */
    public void restore() {
        int n = this.count;
        if (n == 0) {
            return;
        }
        this.count = 0;
        GeoBone[] bones = this.bones;
        float[] pose = this.pose;
        int value = 0;
        for (int i = 0; i < n; i++) {
            GeoBone bone = bones[i];
            bone.setPositionX(pose[value++]);
            bone.setPositionY(pose[value++]);
            bone.setPositionZ(pose[value++]);
            bone.setRotationX(pose[value++]);
            bone.setRotationY(pose[value++]);
            bone.setRotationZ(pose[value++]);
            bone.setScaleX(pose[value++]);
            bone.setScaleY(pose[value++]);
            bone.setScaleZ(pose[value++]);
        }
        // The pooled instance must not pin the geometry it was taken from.
        Arrays.fill(bones, 0, n, null);
        if (POOL.size() < MAX_POOLED) {
            POOL.push(this);
        }
    }
}

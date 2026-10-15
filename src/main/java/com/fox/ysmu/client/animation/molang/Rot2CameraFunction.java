package com.fox.ysmu.client.animation.molang;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;

import com.eliotlash.mclib.math.IValue;
import com.eliotlash.mclib.math.functions.Function;

/**
 * {@code query.rotation_to_camera(axis)} - the camera's own orientation, one axis at a time.
 *
 * <p>Upstream registers it as {@code function("rotation_to_camera", new Rot2Camera())}
 * ({@code tmp/YesSteveModel-dev-1.20 .../geckolib3/core/molang/builtin/QueryBinding.java:47}) and its body is
 * {@code client/animation/molang/functions/Rot2Camera.java:9-27}: exactly one argument
 * ({@code validateArgumentSize == 1}), axis {@code 0} answers {@code -camera.getXRot()} and axis {@code 1} answers
 * {@code 180 + camera.getYRot()}, anything else answers nothing. The camera is the main camera, i.e. a client-global
 * value rather than a per-entity one.
 *
 * <p>1.7.10's camera angles are the render-view entity's, interpolated by the partial tick exactly as
 * {@code EntityRenderer#orientCamera} interpolates them (the modern {@code Camera#setup} lerps the same two fields),
 * so the values are read from {@code Minecraft#renderViewEntity} with {@code timer.renderPartialTicks}. Without a
 * client (or before a view entity exists) the function answers 0, which is upstream's answer for an axis it cannot
 * serve.
 *
 * <p>The name has to exist: a pack's expression containing an unknown function fails to parse, and the whole
 * keyframe channel is dropped - that is the defect this class closes for {@code query.rotation_to_camera}.
 */
public class Rot2CameraFunction extends Function {

    private final IValue[] arguments;

    // 必须实现这个特定签名的构造函数，供 MathBuilder 反射调用
    public Rot2CameraFunction(IValue[] values, String name) throws Exception {
        super(values, name);
        this.arguments = values;
    }

    @Override
    public double get() {
        // Upstream's validateArgumentSize(1) plus its `axis < 0 || axis > 1 -> null` guard.
        if (this.arguments == null || this.arguments.length != 1) {
            return 0.0D;
        }
        int axis = (int) this.arguments[0].get();
        if (axis < 0 || axis > 1) {
            return 0.0D;
        }
        Entity view = viewEntity();
        if (view == null) {
            return 0.0D;
        }
        float partialTicks = partialTick();
        if (axis == 0) {
            float pitch = view.prevRotationPitch + (view.rotationPitch - view.prevRotationPitch) * partialTicks;
            return -pitch;
        }
        float yaw = view.prevRotationYaw + (view.rotationYaw - view.prevRotationYaw) * partialTicks;
        return 180.0D + yaw;
    }

    private static Entity viewEntity() {
        Minecraft mc = Minecraft.getMinecraft();
        return mc == null ? null : mc.renderViewEntity;
    }

    private static float partialTick() {
        Minecraft mc = Minecraft.getMinecraft();
        return mc == null || mc.timer == null ? 0.0F : mc.timer.renderPartialTicks;
    }
}

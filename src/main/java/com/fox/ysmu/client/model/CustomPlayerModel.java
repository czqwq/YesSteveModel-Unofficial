package com.fox.ysmu.client.model;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ResourceLocation;

import com.fox.ysmu.client.animation.AnimationRegister;
import com.fox.ysmu.client.animation.RemotePlayerAnimationQueries;
import com.fox.ysmu.client.ClientModelManager;
import com.fox.ysmu.client.entity.CustomPlayerEntity;
import com.fox.ysmu.Config;
import com.fox.ysmu.util.ModelIdUtil;
import com.fox.ysmu.ysmu;

import software.bernie.geckolib3.core.IAnimatable;
import software.bernie.geckolib3.core.builder.Animation;
import software.bernie.geckolib3.core.event.predicate.AnimationEvent;
import software.bernie.geckolib3.core.molang.MolangParser;
import software.bernie.geckolib3.core.molang.MolangPhysicsRuntime;
import software.bernie.geckolib3.core.processor.IBone;
import software.bernie.geckolib3.geo.render.built.GeoBone;
import software.bernie.geckolib3.geo.render.built.GeoModel;
import software.bernie.geckolib3.model.AnimatedGeoModel;
import software.bernie.geckolib3.model.provider.data.EntityModelData;
import software.bernie.geckolib3.resource.GeckoLibCache;

@SuppressWarnings("all")
public class CustomPlayerModel extends AnimatedGeoModel {

    public static final ResourceLocation DEFAULT_MAIN_MODEL = ModelIdUtil
        .getMainId(new ResourceLocation(ysmu.MODID, "default"));
    public static final ResourceLocation DEFAULT_MAIN_ANIMATION = ModelIdUtil
        .getMainId(new ResourceLocation(ysmu.MODID, "default"));
    public static final ResourceLocation DEFAULT_TEXTURE = new ResourceLocation(ysmu.MODID, "default/default.png");
    /**
     * 首人称相机高度/头部偏移的移植锚点(R-10):**目前只写不读**,保留字段是为了将来接线;
     * 不要在没有消费方时依赖它的值。相机高度接线本身属非目标。
     */
    public static float FIRST_PERSON_HEAD_POS;
    private final Map<IBone, HeadPoseOffset> headPoseOffsets = new IdentityHashMap<>();

    @Override

    public ResourceLocation getModelLocation(Object object) {
        if (object instanceof CustomPlayerEntity customPlayer) {
            return customPlayer.getMainModel();
        }
        return DEFAULT_MAIN_MODEL;
    }

    @Override

    public ResourceLocation getTextureLocation(Object object) {
        return object instanceof CustomPlayerEntity customPlayer ? textureFor(customPlayer) : DEFAULT_TEXTURE;
    }

    /**
     * The texture to bind for an entity, resolved the way upstream resolves it: through the model's own texture list.
     * <p>
     * Upstream's render target owns its texture id, so the selection is looked up inside the model that was loaded and
     * can never name a texture that model does not have. YSMU receives the selection as an id from the server instead,
     * so the same guarantee has to be re-established here: an id that is not one of this model's textures is not
     * bindable, and the model's own default stands in for it - upstream's {@code playerResources()
     * .defaultTextureName()}. That covers a selection left over from a pack that renamed its files, and a mismatched
     * pair, without inventing a rule upstream does not have.
     * <p>
     * While the model is not published the renderer is drawing the built-in default (see {@link #getModel}), so the
     * default's texture is the one that belongs with it.
     * <p>
     * A texture deliberately outside the model - the player's own Minecraft skin for the built-in {@code steve} /
     * {@code alex} models, or one a companion mod supplies - never travels through here: it is carried by
     * {@code SpecialPlayerRenderEvent#getTextureLocationOverride} and preferred by the renderer and the arm, which is
     * the seam upstream uses for exactly that case.
     */
    public static ResourceLocation textureFor(CustomPlayerEntity customPlayer) {
        ResourceLocation requested = customPlayer.getRequestedMainModel();
        if (!ClientModelManager.isModelPublished(requested)) {
            return DEFAULT_TEXTURE;
        }
        List<ResourceLocation> textures = ClientModelManager.MODELS.get(
            ModelIdUtil.getModelIdFromMainId(requested));
        ResourceLocation selected = customPlayer.getTexture();
        if (textures != null && textures.contains(selected)) {
            return selected;
        }
        ResourceLocation preferred = ModelIdUtil.getSubModelId(
            ModelIdUtil.getModelIdFromMainId(requested),
            Config.DEFAULT_MODEL_TEXTURE);
        if (textures != null && textures.contains(preferred)) {
            return preferred;
        }
        return textures != null && !textures.isEmpty() ? textures.get(0) : DEFAULT_TEXTURE;
    }

    @Override

    public ResourceLocation getAnimationFileLocation(Object object) {
        if (object instanceof CustomPlayerEntity customPlayer) {
            return customPlayer.getAnimation();
        }
        return DEFAULT_MAIN_ANIMATION;
    }

    /**
     * Installs a model's animations the first time anything asks for one of them.
     * <p>
     * Animations are the largest part of a synced payload and nothing needs them in order to list the models, so the
     * client parks them at registration and this is the single choke point every play path already goes through -
     * the engine looks an animation up by name both when one is requested and again on every frame for the one
     * already playing. After the first call this is one lookup in a map that only holds the models not yet drawn.
     */
    @Override
    public Animation getAnimation(String name, IAnimatable animatable) {
        if (animatable instanceof CustomPlayerEntity customPlayer) {
            // Keyed on the model, not on getAnimation(): that one answers with the built-in default until this
            // model's own animations exist, so asking it first would install the wrong model's payload.
            ClientModelManager.ensureAnimations(customPlayer.getMainModel());
        }
        return super.getAnimation(name, animatable);
    }

    /**
     * Draws the model that has been asked for, or the built-in default until it has been built.
     * <p>
     * Geometry is built on demand rather than at registration, so the first frame a model is wanted it is usually
     * still being parsed. Returning the default for those frames is what makes the change invisible to the player -
     * something is drawn immediately and the real model replaces it on the frame the build is published - and it is
     * the same thing the engine would do if the model were genuinely absent, minus the warning.
     * <p>
     * This is also where the build is requested, which is deliberate: every path that draws a model comes through
     * here, including the GUI tiles, so no caller can forget to ask for it.
     */
    @Override
    public GeoModel getModel(ResourceLocation location) {
        // The cache test is not redundant with ensureGeometry: a model can be "not pending" without being drawable -
        // quarantined after a failed build, or registered through a low-level entry point that does not mark it -
        // and AnimatedGeoModel.getModel throws when the location is absent, on a path the GUI does not guard. The
        // quarantine is tested explicitly because the engine's cache can still hold geometry for such a model from an
        // earlier connection, and that geometry is not what this connection asked for.
        if (ClientModelManager.isGeometryFailed(location)
            || !ClientModelManager.ensureGeometry(location)
            || GeckoLibCache.getInstance()
                .getGeoModels()
                .get(location) == null) {
            return super.getModel(DEFAULT_MAIN_MODEL);
        }
        return super.getModel(location);
    }

    @Override
    public void setLivingAnimations(IAnimatable animatable, Integer instanceId, AnimationEvent animationEvent) {
        clearHeadPoseOffsets();
        List extraData = animationEvent.getExtraData();
        MolangParser parser = GeckoLibCache.getInstance().parser;
        if (!Minecraft.getMinecraft()
            .isGamePaused() && extraData.size() == 1
            && extraData.get(0) instanceof EntityModelData data
            && animatable instanceof CustomPlayerEntity customPlayer
            && customPlayer.getPlayer() != null) {
            EntityPlayer player = customPlayer.getPlayer();
            AnimationRegister.setParserValue(animationEvent, parser, data, player);
            try {
                super.setLivingAnimations(animatable, instanceId, animationEvent);
                this.codeAnimation(animationEvent, data, player);
            } finally {
                MolangPhysicsRuntime.end();
            }
        } else {
            try {
                super.setLivingAnimations(animatable, instanceId, animationEvent);
            } finally {
                MolangPhysicsRuntime.end();
            }
        }
    }

    private void codeAnimation(AnimationEvent animationEvent, EntityModelData data, EntityPlayer player) {
        // FIXME: 2023/6/21 这一块设计应该改成 molang 的，而且这个寻找效率低下
        IBone head = getBone("Head");
        FIRST_PERSON_HEAD_POS = 24;
        if (head != null) {
            float headPitch = (float) Math.toRadians(data.headPitch);
            float headYaw = (float) Math.toRadians(
                RemotePlayerAnimationQueries.get(animationEvent, player, data.netHeadYaw)
                    .headYaw());
            head.setRotationX(head.getRotationX() + headPitch);
            head.setRotationY(head.getRotationY() + headYaw);
            headPoseOffsets.put(head, new HeadPoseOffset(headPitch, headYaw));
            FIRST_PERSON_HEAD_POS = head.getPivotY()
                * ((CustomPlayerEntity) animationEvent.getAnimatable()).getHeightScale();
        }
        // R-10: getCurrentModel() is a plain field read and can be null when super.setLivingAnimations never
        // ran (no model resolved yet), so it is resolved once and guarded here.
        GeoModel currentModel = getCurrentModel();
        GeoBone locator = currentModel == null ? null : currentModel.firstPersonViewLocator;
        if (locator != null) {
            float heightScale = ((CustomPlayerEntity) animationEvent.getAnimatable()).getHeightScale();
            FIRST_PERSON_HEAD_POS = locator.getPivotY() * heightScale;
        }
    }

    private void clearHeadPoseOffsets() {
        if (headPoseOffsets.isEmpty()) {
            return;
        }
        for (Map.Entry<IBone, HeadPoseOffset> entry : headPoseOffsets.entrySet()) {
            IBone bone = entry.getKey();
            HeadPoseOffset offset = entry.getValue();
            bone.setRotationX(bone.getRotationX() - offset.rotationX);
            bone.setRotationY(bone.getRotationY() - offset.rotationY);
        }
        headPoseOffsets.clear();
    }

    private static final class HeadPoseOffset {
        private final float rotationX;
        private final float rotationY;

        private HeadPoseOffset(float rotationX, float rotationY) {
            this.rotationX = rotationX;
            this.rotationY = rotationY;
        }
    }

    @Override

    @Nullable
    public IBone getBone(String boneName) {
        return getAnimationProcessor().getBone(boneName);
    }

    @Override

    public void setMolangQueries(IAnimatable animatable, double seekTime) {
        if (animatable instanceof CustomPlayerEntity customPlayer) {
            MolangPhysicsRuntime.begin(customPlayer, seekTime, getAnimationProcessor());
        }
    }
}

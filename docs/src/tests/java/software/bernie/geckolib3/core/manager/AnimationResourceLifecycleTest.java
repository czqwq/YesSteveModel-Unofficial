package software.bernie.geckolib3.core.manager;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import net.minecraft.util.ResourceLocation;
import org.apache.commons.lang3.tuple.Pair;
import org.junit.jupiter.api.Test;

import software.bernie.geckolib3.core.IAnimatable;
import software.bernie.geckolib3.core.PlayState;
import software.bernie.geckolib3.core.builder.Animation;
import software.bernie.geckolib3.core.controller.AnimationController;
import software.bernie.geckolib3.core.processor.IBone;
import software.bernie.geckolib3.core.snapshot.BoneSnapshot;
import software.bernie.geckolib3.geo.render.built.GeoBone;

/**
 * 资源生命周期与播放状态的分工：几何被淘汰（{@code releaseGeometry}）只松开骨骼绑定，
 * 播放时钟（{@code tick} / {@code startTick}）与其它几何的快照必须留着；动画文件被淘汰
 * （{@code releaseAnimationFile}）才清掉控制器自己的时间参照，而且只清匹配那一份的
 * {@code AnimationData}；{@link AnimationFactory#dispose()} 之后拿到的必须是全新的播放数据。
 *
 * <p><b>移植说明（YSMU）：</b>参考分支用 {@code com.fox.ysmu.client.animation.VirtualBone}
 * 造两条骨骼。本仓故意没有那个生产类（phase40 判定虚拟骨骼注入是 upstream 没有的概念），
 * 所以替身是一条<b>不属于任何几何</b>的 {@link GeoBone}（本仓唯一的 {@link IBone} 实现）：
 * 这里只用到它的名字与身份，与 VirtualBone 的差别不被断言触及。
 */
class AnimationResourceLifecycleTest {

    /** 参考分支的 {@code new VirtualBone(name)}：本仓没有那个类，用一条裸几何骨骼代替。 */
    private static IBone detachedBone(String name) {
        GeoBone bone = new GeoBone();
        bone.name = name;
        return bone;
    }

    private static final class Actor implements IAnimatable {
        final AnimationFactory factory = new AnimationFactory(this);
        public AnimationFactory getFactory() { return factory; }
        public void registerControllers(AnimationData data) {
            data.addAnimationController(new AnimationController<Actor>(this, "test", 0, event -> PlayState.STOP));
        }
    }

    private static Field field(String name) throws Exception {
        Field field = AnimationController.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    @Test
    void geometryReleasePreservesClockAndOtherGeometry() throws Exception {
        Actor actor = new Actor();
        AnimationData data = actor.factory.getOrCreateAnimationData(1);
        AnimationController controller = data.getAnimationControllers().get("test");
        IBone retired = detachedBone("old");
        IBone retained = detachedBone("other");
        Map<String, IBone> index = new HashMap<>();
        index.put("old", retired);
        field("boneNameToBone").set(controller, index);
        data.getBoneSnapshotCollection().put("old", Pair.of(retired, new BoneSnapshot(retired)));
        data.getBoneSnapshotCollection().put("other", Pair.of(retained, new BoneSnapshot(retained)));
        Animation animation = new Animation();
        field("currentAnimation").set(controller, animation);
        data.tick = 120;
        data.startTick = 20.0;
        data.releaseGeometry(Collections.singleton(retired), index);
        assertEquals(120, data.tick);
        assertEquals(20.0, data.startTick);
        assertSame(animation, field("currentAnimation").get(controller));
        assertFalse(data.getBoneSnapshotCollection().containsKey("old"));
        assertSame(retained, data.getBoneSnapshotCollection().get("other").getLeft());
        assertTrue(((Map<?, ?>) field("boneNameToBone").get(controller)).isEmpty());
    }

    @Test
    void animationEvictionClearsCustomTimeReferenceOnlyForMatchingResource() throws Exception {
        Actor actor = new Actor();
        AnimationData first = actor.factory.getOrCreateAnimationData(1);
        AnimationData second = actor.factory.getOrCreateAnimationData(2);
        ResourceLocation firstId = new ResourceLocation("ysmu_test", "lifecycle_first");
        first.bindAnimationFile(firstId);
        second.bindAnimationFile(new ResourceLocation("ysmu_test", "lifecycle_second"));
        Animation animation = new Animation();
        for (AnimationData data : new AnimationData[] { first, second }) {
            AnimationController controller = data.getAnimationControllers().get("test");
            field("currentAnimation").set(controller, animation);
            field("lastAnimTimeAnimation").set(controller, animation);
            field("lastAnimTimeTick").setDouble(controller, 100);
            field("lastActualTick").setDouble(controller, 110);
        }
        AnimationData.releaseAnimationFile(firstId);
        AnimationController released = first.getAnimationControllers().get("test");
        assertNull(field("currentAnimation").get(released));
        assertNull(field("lastAnimTimeAnimation").get(released));
        assertEquals(-1.0, field("lastAnimTimeTick").getDouble(released));
        assertEquals(-1.0, field("lastActualTick").getDouble(released));
        assertSame(animation, field("currentAnimation").get(second.getAnimationControllers().get("test")));
    }

    @Test
    void disposedFactoryCreatesFreshPlaybackData() {
        Actor actor = new Actor();
        AnimationData before = actor.factory.getOrCreateAnimationData(1);
        before.tick = 200;
        actor.factory.dispose();
        AnimationData after = actor.factory.getOrCreateAnimationData(1);
        assertNotSame(before, after);
        assertEquals(0, after.tick);
        assertEquals(1, after.getAnimationControllers().size());
    }
}

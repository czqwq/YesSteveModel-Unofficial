package software.bernie.geckolib3.molang.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;

import net.minecraft.entity.Entity;

import org.junit.jupiter.api.Test;

import software.bernie.geckolib3.molang.context.IContext;

/**
 * {@code ysm.person_view} / {@code is_first_person} 的整个特性压在这三个数上。
 *
 * <p>模型的 Molang 写的是一串数值比较（{@code query.person_view == 2} 这类），而这三个常量必须与
 * 1.20.1 的 {@code CameraType} 序号 —— 也是 1.7.10 的 {@code gameSettings.thirdPersonView} —— 逐值一致。
 * 值错一位不会报错，只会让"第三人称正面"被当成"第三人称背面"：模型的朝向与相机相反。
 *
 * <p>另一条被钉住的行为是兜底：不是"本地玩家正在世界里被渲染"时一律回
 * {@link PersonView#THIRD_PERSON_FRONT}，源码注释记载这是 GUI 里让模型面向相机的原做法。
 */
class PersonViewTest {

    @Test
    void theCameraTypeOrdinalsAreTheOnesTheQueriesCompareAgainst() {
        assertEquals(0, PersonView.FIRST_PERSON);
        assertEquals(1, PersonView.THIRD_PERSON_BACK);
        assertEquals(2, PersonView.THIRD_PERSON_FRONT);

        // 三个值必须互不相同且按 CameraType 的顺序递增，否则比较里有两个模式永远分不开。
        assertNotEquals(PersonView.FIRST_PERSON, PersonView.THIRD_PERSON_BACK);
        assertNotEquals(PersonView.THIRD_PERSON_BACK, PersonView.THIRD_PERSON_FRONT);
        assertNotEquals(PersonView.FIRST_PERSON, PersonView.THIRD_PERSON_FRONT);
        assertTrue(
            PersonView.FIRST_PERSON < PersonView.THIRD_PERSON_BACK
                && PersonView.THIRD_PERSON_BACK < PersonView.THIRD_PERSON_FRONT);
    }

    /**
     * 没有客户端时（单测 JVM，或任何不是"本地玩家在世界里"的上下文）一律是第三人称正面，
     * 于是 {@code is_first_person} 为假 —— 这正是 GUI 预览想要的答案。
     */
    @Test
    void withoutAClientEveryContextAnswersThirdPersonFront() {
        IContext<Entity> context = contextWithoutAClient();

        assertEquals(PersonView.THIRD_PERSON_FRONT, PersonView.getPersonView(context));
        assertFalse(PersonView.isFirstPersonView(context));
        assertNotEquals(PersonView.FIRST_PERSON, PersonView.getPersonView(context));
    }

    /**
     * {@link IContext} 有二十多个方法，而 {@code getPersonView} 在客户端缺席时只会取 {@code mc()} 与
     * {@code entity()}（两者都先取值，`&&` 短路的是后面的 {@code mc.thePlayer} 与
     * {@code animationEvent()}）。用动态代理而不是写一个二十多个空方法的桩：处理器只放行这两个，
     * 别的调用直接抛，这样"person_view 的判定开始依赖新的上下文方法"会立刻让这条测试失败，
     * 而不是静默改变答案。
     */
    @SuppressWarnings("unchecked")
    private static IContext<Entity> contextWithoutAClient() {
        return (IContext<Entity>) Proxy.newProxyInstance(
            IContext.class.getClassLoader(),
            new Class<?>[] { IContext.class },
            (proxy, method, args) -> {
                switch (method.getName()) {
                    case "mc":
                        return null;
                    case "entity":
                        // mc() 已经是 null，`mc != null && ...` 不会再看这个值。
                        return null;
                    case "equals":
                        return proxy == args[0];
                    case "hashCode":
                        return System.identityHashCode(proxy);
                    case "toString":
                        return "IContext without a client";
                    default:
                        throw new UnsupportedOperationException(
                            "person_view must not reach " + method.getName() + " without a client");
                }
            });
    }
}

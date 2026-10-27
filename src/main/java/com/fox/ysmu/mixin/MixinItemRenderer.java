package com.fox.ysmu.mixin;

import com.fox.ysmu.client.compat.AngelicaCompat;
import com.fox.ysmu.client.renderer.FirstPersonHandRenderer;
import com.fox.ysmu.compat.BackhandCompat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ItemRenderer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ItemRenderer.class, priority = 900)
public abstract class MixinItemRenderer {

    /**
     * 主手拿物品时 YSM 不接管第一人称渲染（{@code shouldRenderCustomHand} 要求
     * {@code itemRenderer.itemToRender == null}），副手由 Backhand 在其 RETURN 注入里自己画 ——
     * 隐藏名单会被绕过。这里在方法开头（Backhand 的副手绘制之前）清掉 Backhand 副手渲染器的
     * pending 物品，使那一笔绘制无物可画；该字段每 tick 会被 Backhand 重填，不存在丢物品风险。
     * 详见 {@code BackhandCompat.suppressHiddenOffhandRender}
     * (reference branch MixinItemRenderer.java:23-26)。
     */
    @Inject(method = "renderItemInFirstPerson", at = @At("HEAD"))
    private void ysmu$suppressHiddenOffhand(float partialTicks, CallbackInfo ci) {
        BackhandCompat.suppressHiddenOffhandRender();
    }

    /**
     * Angelica/Iris 在它自己的 hand pass 里把第一人称手再画一次：{@code HandRenderer#renderSolid} 直接调
     * {@code mc.entityRenderer.itemRenderer.renderItemInFirstPerson}
     * ({@code tmp/Angelica-2.2.37/src/main/java/net/coderbot/iris/pipeline/HandRenderer.java:158}，
     * 由 {@code RenderGlobal.sortAndRender} 的 pass 1 触发，见同版本
     * {@code mixins/early/celeritas/terrain/MixinRenderGlobal.java:348-356})。那一笔画的是<b>原版手臂</b>，
     * 会与本 mod 从 {@code RenderHandEvent} 画的手臂叠在同一个位置，所以在这里取消掉。
     *
     * <p><b>手臂本身不从这条回调里画</b>：Iris 的 deferred pass 在 Forge {@code RenderHandEvent} 之前就被
     * 合成到主帧缓冲（{@code MixinEntityRenderer#iris$endLevelRender} 注入在
     * {@code ForgeHooksClient.dispatchRenderLast} 之前，而事件在
     * {@code EntityRenderer.renderWorld} 的 {@code renderFirstPersonHand} 才发），而端口的手臂是直接
     * {@code Tessellator} 提交的固定管线绘制——它在 hand pass 里画出来不可见（2.2.37 的手是被它的 hand
     * program / display list 状态渲染的），于是"在 pass 里画 + 取消原版"这条组合在实机上得到的是<b>两只手
     * 都没有</b>，而"在 pass 里不画 + 不取消原版"得到的是原版手臂。事件路径是光影开关两种情况下都能出图的
     * 那条路（关光影时实机可见），因此它保持唯一绘制点，这里只负责把宿主自己的手臂从画面里去掉。</p>
     *
     * <p>取消会跳过宿主 RETURN 注入里的 {@code ItemIdManager.resetItemId()}，所以这里照旧自己清一次
     * （{@link AngelicaCompat#resetFirstPersonItemId()}）。</p>
     */
    @Inject(method = "renderItemInFirstPerson", at = @At("HEAD"), cancellable = true)
    private void ysmu$suppressVanillaHandInShaderPass(float partialTicks, CallbackInfo ci) {
        if (!AngelicaCompat.isRenderingSolidHandPass()) {
            return;
        }

        ItemRenderer itemRenderer = (ItemRenderer) (Object) this;
        if (!FirstPersonHandRenderer.willRenderCustomHand(Minecraft.getMinecraft(), itemRenderer)) {
            return;
        }
        AngelicaCompat.resetFirstPersonItemId();
        ci.cancel();
    }
}

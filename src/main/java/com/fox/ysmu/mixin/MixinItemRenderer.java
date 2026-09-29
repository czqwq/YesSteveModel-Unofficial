package com.fox.ysmu.mixin;

import com.fox.ysmu.client.compat.AngelicaCompat;
import com.fox.ysmu.client.renderer.FirstPersonHandRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ItemRenderer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ItemRenderer.class, priority = 900)
public abstract class MixinItemRenderer {

    @Inject(method = "renderItemInFirstPerson", at = @At("HEAD"), cancellable = true)
    private void ysmu$renderAngelicaCustomHand(float partialTicks, CallbackInfo ci) {
        // R-03 契约：Iris 只在"正在渲染 solid hand pass"时走到这条路径；translucent pass 由 Iris 的
        // 原生渲染负责，因此本注入不会与事件路径（ClientEventHandler.onRenderHand 在 shader pack 使用时
        // 直接 return）重复渲染。副手是否在本 pass 绘制由 AngelicaCompat 按半透明性判定，数据缺失时它
        // 会退化为"只画一次"并打一条一次性日志。
        if (!AngelicaCompat.isRenderingSolidHandPass()) {
            return;
        }

        ItemRenderer itemRenderer = (ItemRenderer) (Object) this;
        if (FirstPersonHandRenderer.tryRenderInActiveFirstPersonPass(
            Minecraft.getMinecraft(),
            itemRenderer,
            partialTicks,
            AngelicaCompat.shouldRenderOffhandInCurrentHandPass())) {
            AngelicaCompat.resetFirstPersonItemId();
            ci.cancel();
        }
    }
}

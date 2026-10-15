package com.fox.ysmu.client.animation.controller;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ResourceLocation;

import com.fox.ysmu.client.animation.molang.MolangScriptInterpreter;
import com.fox.ysmu.client.animation.molang.MolangScriptRegistry;

import software.bernie.geckolib3.core.event.predicate.AnimationEvent;
import software.bernie.geckolib3.core.molang.MolangPhysicsRuntime;

/**
 * 游戏侧的 {@link MolangScriptInterpreter.MolangScriptScope}：把脚本的变量读写接到控制器求值器
 * 与 {@link MolangPhysicsRuntime}，函数调用转发到控制器的函数表（{@code ysm.*} / {@code math.*}
 * 都在那里），函数体来自 {@link MolangScriptRegistry}。
 * <p>
 * 放在 controller 包里是有意的：{@link OpenYsmControllerExpressionEvaluator.Context} 与它的
 * {@code Argument} 都是包内可见，复用它们就不必把 {@code q.*}/{@code ysm.*}/{@code ctrl.*}
 * 那几百行查询逻辑再写一遍。
 * <p>
 * {@code state == null}（没有控制器状态机）意味着 {@code v.*} 走共享作用域、{@code anim_time} 之类
 * 返回 0 —— 与 `.molang` 条件求值是同一条路径。事件触发点没有 {@code AnimationEvent} 时
 * {@code event} 允许为 null，此时依赖动画自身的查询按 0 处理。
 * <p>
 * 与参考实现的两处差异，都是 1.7.10 这一侧的架构决定的，不是遗漏：
 * <ul>
 * <li>变量存储用引擎的 {@code software.bernie.geckolib3.core.molang.MolangPhysicsRuntime}
 * （参考库有一份自己的 {@code com.fox.ysmu.client.animation.molang.MolangPhysicsRuntime}，
 * 本仓没有 —— 物理作用域已随引擎 vendor 进本仓，见 {@code AGENTS.md}）；</li>
 * <li>{@link #setVariableValue} 里脚本对 {@code v.roaming.<name>} 的写入只落到引擎的本帧作用域，
 * 参考库那句 {@code PENDING_ROAMING.put + markRoamingExplicit}（客户端权威、直接广播）
 * 按移植计划决策 D5 不移植：本仓的漫游写入必须走服务器权威的
 * {@code C2SRoamingChanges} / {@code S2CRoamingState}，读取语义才在这一批。</li>
 * </ul>
 */
public final class OpenYsmScriptScope implements MolangScriptInterpreter.MolangScriptScope {

    private final ResourceLocation modelId;
    private final OpenYsmControllerExpressionEvaluator.Context context;
    private final List<Double> arguments;

    public OpenYsmScriptScope(EntityPlayer player, AnimationEvent<?> event, ResourceLocation modelId,
        List<Double> arguments) {
        this.modelId = modelId;
        this.arguments = arguments == null ? Collections.emptyList() : arguments;
        this.context = new OpenYsmControllerExpressionEvaluator.Context(event, player, null);
    }

    @Override
    public double variableValue(String name) {
        return this.context.variableValue(name);
    }

    /**
     * 脚本的赋值写回：与控制器的 {@code onEntry}/{@code onExit} 走**同一条**路径
     * （{@code RuntimeState#setVariable} → {@code MolangPhysicsRuntime}），否则"脚本写了 v.x，
     * 骨骼却读不到"。
     * <p>
     * 参考实现这里还跟着一段 {@code v.roaming.<name>} 的持久化与显式标记（写入
     * {@code PENDING_ROAMING}、按模型标记），本仓的漫游写入按决策 D5 走服务器权威链路，
     * 不属于本批；因此漫游变量在脚本里赋值只在本帧可见，跨帧读回要等漫游那一批接上。
     */
    @Override
    public void setVariableValue(String name, double value) {
        String normalized = name.startsWith("variable.") ? "v." + name.substring("variable.".length()) : name;
        if (!normalized.startsWith("v.")) {
            return;
        }
        MolangPhysicsRuntime.setVariable(normalized, value);
    }

    @Override
    public double functionValue(String name, List<MolangScriptInterpreter.Argument> args) {
        List<OpenYsmControllerExpressionEvaluator.Argument> converted =
            new ArrayList<>(args == null ? 0 : args.size());
        if (args != null) {
            for (MolangScriptInterpreter.Argument argument : args) {
                converted.add(
                    argument.isString()
                        ? OpenYsmControllerExpressionEvaluator.Argument.string(argument.asString())
                        : OpenYsmControllerExpressionEvaluator.Argument.number(argument.asNumber()));
            }
        }
        return this.context.functionValue(name, converted);
    }

    @Override
    public double argument(int index) {
        return index >= 0 && index < this.arguments.size() ? this.arguments.get(index) : 0.0d;
    }

    @Override
    public int argumentCount() {
        return this.arguments.size();
    }

    /** {@code fn.*}：从模型的脚本表里取函数体（wiki「链式调用」）。 */
    @Override
    public String functionScript(String name) {
        return MolangScriptRegistry.functionScript(this.modelId, name.toLowerCase(Locale.ROOT));
    }
}

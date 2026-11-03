package com.fox.ysmu.client.animation.molang;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.World;

import com.fox.ysmu.client.roaming.ModelFormExecution;
import com.fox.ysmu.eep.ExtendedModelInfo;

/**
 * Client half of {@code /ysm molang execute <targets> <expression>}: resolves the target ids in this client's world
 * and runs the expression inside each target player's model scope.
 *
 * <p>Port of upstream's {@code network/forge/ControlHandler.executeMolang} ({@code
 * tmp/YesSteveModel-dev-1.20/src/main/java/com/elfmcys/ysm/network/forge/ControlHandler.java:253-272}), which is the
 * consumer of the {@code ExecuteMolangEvent} that {@code command/sub/MolangCommand.java:44-50} broadcasts to every
 * player:</p>
 * <ul>
 * <li>upstream reads {@code Minecraft.getInstance().level} and returns when there is no level ({@code :254-255}) -
 * here {@link Minecraft#theWorld}, the same "current client level" fact;</li>
 * <li>it looks each id up with {@code level.getEntity(id)} and keeps only {@code entity instanceof Player}
 * ({@code :258-259,268-270}) - here {@link World#getEntityByID} and {@code instanceof EntityPlayer}, which is 1.7.10's
 * player type;</li>
 * <li>it parses the expression and calls {@code capability.executeMolangExp(value, true, false, null)}
 * ({@code :260-267}) - here {@link ModelFormExecution#submit}, the port's port of that call (it compiles through the
 * same {@code GeckoLibCache.parser} and evaluates inside the animatable's live physics/query scope, with side effects
 * allowed, which is what {@code allowEmitting = true} means upstream). A parse failure is reported by
 * {@code ModelFormExecution} and only skips that target, like upstream's {@code catch (ParseException)}.</li>
 * </ul>
 *
 * <p><b>No model identity on the wire.</b> Upstream's message carries only ids and the source, and its capability is
 * asked for whatever model the player currently wears; the port's {@code ModelFormExecution} has to name one because
 * it only runs once the renderer has established that model's scope, so this class passes the target's own current
 * model from its {@link ExtendedModelInfo} - the client's copy of exactly the fact upstream's capability holds. If
 * the selection changes before the animatable renders, {@code ModelFormExecution} drops the request rather than
 * running it on a model it was not asked for.</p>
 *
 * <p>Runs on the client thread (the packet handler hops through {@code DeferredWork.client}), so world and entity
 * lookups are safe; entity ids that are not in the current world are skipped, which is also upstream's behaviour.</p>
 */
public final class RemoteMolangExecution {

    private RemoteMolangExecution() {}

    public static void execute(String expression, int[] entityIds) {
        if (expression == null || entityIds == null || entityIds.length == 0) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        World world = mc == null ? null : mc.theWorld;
        if (world == null) {
            return;
        }
        for (int entityId : entityIds) {
            Entity entity = world.getEntityByID(entityId);
            if (!(entity instanceof EntityPlayer player)) {
                continue;
            }
            ExtendedModelInfo info = ExtendedModelInfo.get(player);
            ResourceLocation modelId = info == null ? null : info.getModelId();
            if (modelId == null) {
                continue;
            }
            // `() -> true` as the liveness test: ModelFormExecution already drops a request whose owner left this
            // world or died, which is the whole of what it checks for the local form path too (ModelConfigScreen:580).
            ModelFormExecution.submit(player, modelId, expression, null, () -> true);
        }
    }
}

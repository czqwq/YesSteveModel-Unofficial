package com.fox.ysmu.client.animation.molang;

import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;

import com.eliotlash.mclib.math.IValue;
import com.eliotlash.mclib.math.functions.Function;
import com.fox.ysmu.network.NetworkHandler;
import com.fox.ysmu.network.message.C2SMolangSync;

/**
 * A-16: {@code ysm.sync(v0, v1, ...)} - a pack asking for values to reach every client that can see the model.
 * <p>
 * Upstream registers this function in the same list as the rest of its MoLang API
 * ({@code client/animation/molang/YSMBinding.java:190} {@code function("sync", new Sync())}), and its body forwards
 * the call's arguments to the server ({@code client/animation/molang/functions/Sync.java:29}). The echo comes back as
 * {@link com.fox.ysmu.network.message.S2CMolangSync} and runs the model's {@code @sync} script, so what a pack gets is
 * a value it can hand to other clients - wine_fox/15_kluonoa uses it for its horn event.
 * <p>
 * Sending needs a world and a client connection; outside one (a preview on the main menu, a dedicated server) the call
 * simply does nothing, the same way the other host functions answer their default when there is no frame.
 * <p>
 * There is one send point, {@link #request(EntityPlayer, List)}, shared by the two evaluation paths: this
 * mclib {@code Function} (the animation-file/keyframe path) and the controller-condition evaluator
 * ({@code OpenYsmControllerExpressionEvaluator.Context#functionValue}), whose arguments are plain numbers rather
 * than {@code IValue}s. Two copies of the packet body is exactly the drift this port keeps paying for.
 */
public class YsmSyncFunction extends Function {

    public YsmSyncFunction(IValue[] values, String name) throws Exception {
        super(values, name);
    }

    @Override
    public double get() {
        if (this.args == null || this.args.length == 0) {
            return 0.0D;
        }
        int count = Math.min(this.args.length, C2SMolangSync.MAX_VALUES);
        java.util.List<Double> values = new java.util.ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            values.add(this.args[i].get());
        }
        // No caller entity on this path: the function is evaluated inside the frame the animation file belongs to,
        // and the pack cannot name one. `request` skips its local-player check for a null caller, which is the
        // behaviour this path has always had.
        return request(null, values);
    }

    /**
     * A pack's {@code ysm.sync(...)} request. Always answers 0: the wiki's "发起同步后将立刻结束并返回 null" is 0 in
     * numeric form, and upstream's {@code Sync#eval} returns {@code null} on every branch
     * ({@code functions/Sync.java:18-49}).
     *
     * @param caller the entity the expression belongs to, or {@code null} when the caller has no entity (the keyframe
     *               path). Upstream emits only for the <em>local</em> player and leaves a remote player's model to
     *               wait for the server's echo ({@code functions/Sync.java:26-33}); a non-local caller is therefore a
     *               no-op here too - the packet this port sends is attributed to the local player, so emitting for a
     *               remote entity would run the wrong player's {@code @sync} script.
     * @param values the already-evaluated arguments; truncated to {@link C2SMolangSync#MAX_VALUES} like upstream's
     *               {@code MAX_ARGS_SIZE = 16} ({@code functions/Sync.java:15})
     */
    public static double request(EntityPlayer caller, List<Double> values) {
        Minecraft mc = Minecraft.getMinecraft();
        // `mc == null` cannot happen in game; it keeps a headless caller (a unit test, a dedicated-server command)
        // from failing on the null check below instead of answering the pack's default.
        if (mc == null || mc.theWorld == null || mc.thePlayer == null) {
            return 0.0D;
        }
        if (caller != null && caller != mc.thePlayer) {
            return 0.0D;
        }
        if (values == null || values.isEmpty()) {
            return 0.0D;
        }
        int count = Math.min(values.size(), C2SMolangSync.MAX_VALUES);
        double[] packed = new double[count];
        for (int i = 0; i < count; i++) {
            Double value = values.get(i);
            packed[i] = value == null ? 0.0D : value;
        }
        NetworkHandler.CHANNEL.sendToServer(new C2SMolangSync(packed));
        return 0.0D;
    }
}

package com.fox.ysmu.client.animation.molang;

import net.minecraft.client.Minecraft;

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
 */
public class YsmSyncFunction extends Function {

    public YsmSyncFunction(IValue[] values, String name) throws Exception {
        super(values, name);
    }

    @Override
    public double get() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld == null || mc.thePlayer == null || this.args == null || this.args.length == 0) {
            return 0.0D;
        }
        int count = Math.min(this.args.length, C2SMolangSync.MAX_VALUES);
        double[] values = new double[count];
        for (int i = 0; i < count; i++) {
            values[i] = this.args[i].get();
        }
        NetworkHandler.CHANNEL.sendToServer(new C2SMolangSync(values));
        return 0.0D;
    }
}

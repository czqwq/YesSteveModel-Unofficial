package com.fox.ysmu.client.roaming;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ResourceLocation;

import com.fox.ysmu.client.entity.CustomPlayerEntity;
import com.fox.ysmu.client.entity.CustomGuiPlayerEntity;
import com.fox.ysmu.client.audio.MolangSoundContext;
import com.fox.ysmu.model.roaming.ModelRoamingLimits;
import com.fox.ysmu.util.ModelIdUtil;
import com.fox.ysmu.ysmu;

import software.bernie.geckolib3.core.molang.MolangPhysicsRuntime;
import software.bernie.geckolib3.resource.GeckoLibCache;

/** Executes form programs only inside the renderer-established entity/model MoLang context. */
public final class ModelFormExecution {
    private static final List<Request> PENDING = new ArrayList<>();

    private ModelFormExecution() {}

    public static boolean submit(EntityPlayer owner, ResourceLocation modelId, String source,
        Consumer<Double> result, BooleanSupplier stillCurrent) {
        try {
            RoamingProgram.Compiled program = RoamingProgram.compile(GeckoLibCache.getInstance().parser, source);
            PENDING.add(new Request(owner, modelId, program, result, stillCurrent));
            return true;
        } catch (Exception failure) {
            // Upstream AnimationRouletteScreen.java:524-530 reports a parse error and does not run the program.
            ysmu.LOG.warn("Failed to parse model form Molang '{}': {}", source, failure.getMessage());
            return false;
        }
    }

    /** Called after both the entity query bindings and the real physics scope have been established. */
    public static void drain(CustomPlayerEntity animatable) {
        EntityPlayer owner = animatable instanceof CustomGuiPlayerEntity preview
            ? preview.getExecutionOwner() : animatable.getPlayer();
        if (owner == null) return;
        ResourceLocation modelId = ModelIdUtil.getModelIdFromMainId(animatable.getMolangModelId());
        List<Request> ready = new ArrayList<>();
        for (Iterator<Request> iterator = PENDING.iterator(); iterator.hasNext();) {
            Request request = iterator.next();
            if (!request.stillCurrent.getAsBoolean() || request.ownerEntity.worldObj != Minecraft.getMinecraft().theWorld
                || request.ownerEntity.isDead) {
                iterator.remove();
            } else if (request.owner.equals(owner.getUniqueID()) && request.modelId.equals(modelId)) {
                ready.add(request);
                iterator.remove();
            }
        }
        for (Request request : ready) {
            boolean ui = animatable instanceof CustomGuiPlayerEntity;
            try (MolangSoundContext sound = MolangSoundContext.push(ui ? animatable : animatable.getMolangEntity(),
                ui ? null : animatable.getMolangEntity(), animatable.getMolangModelId(), null, ui, true)) {
                Map<String, Double> before = new HashMap<>();
                MolangPhysicsRuntime.syncToRuntimeState(before);
                double value = request.program.evaluate();
                Map<String, Double> after = new HashMap<>();
                MolangPhysicsRuntime.syncToRuntimeState(after);
                EntityPlayer local = Minecraft.getMinecraft().thePlayer;
                if (local != null && local.getUniqueID().equals(owner.getUniqueID())) {
                    int modelKey = ClientRoamingKeys.keyFor(modelId);
                    reportRoamingChanges(before, after,
                        (name, changedValue) -> ClientRoamingStore.setLocal(owner.getUniqueID(), modelKey, name, changedValue));
                }
                if (request.result != null && request.stillCurrent.getAsBoolean()) request.result.accept(value);
            } catch (Exception failure) {
                ysmu.LOG.warn("Failed to execute model form Molang '{}': {}", request.program.source(), failure.getMessage());
            }
        }
    }

    /**
     * Reports only changed roaming members from the actual physics snapshots. syncToRuntimeState strips exactly
     * the v. prefix (MolangPhysicsRuntime.java:363-377), so snapshot keys here are roaming.x, not v.roaming.x.
     * The callback is the production setLocal boundary and also permits a host-free regression test.
     */
    static void reportRoamingChanges(Map<String, Double> before, Map<String, Double> after,
        java.util.function.BiConsumer<String, Double> report) {
        for (Map.Entry<String, Double> entry : after.entrySet()) {
            String name = "v." + entry.getKey();
            if (name.startsWith(ModelRoamingLimits.PREFIX)
                && name.length() > ModelRoamingLimits.PREFIX.length()
                && ModelRoamingLimits.isAcceptableName(name)
                && !entry.getValue().equals(before.get(entry.getKey()))) {
                report.accept(name, entry.getValue());
            }
        }
    }

    /** Prune cancelled GUI reads immediately rather than retaining closed screens until another render pass. */
    public static void cancelInvalid() {
        PENDING.removeIf(request -> !request.stillCurrent.getAsBoolean());
    }

    public static void clear() { PENDING.clear(); }

    private static final class Request {
        final UUID owner;
        final EntityPlayer ownerEntity;
        final ResourceLocation modelId;
        final RoamingProgram.Compiled program;
        final Consumer<Double> result;
        final BooleanSupplier stillCurrent;

        Request(EntityPlayer owner, ResourceLocation modelId, RoamingProgram.Compiled program,
            Consumer<Double> result, BooleanSupplier stillCurrent) {
            this.owner = owner.getUniqueID();
            this.ownerEntity = owner;
            this.modelId = modelId;
            this.program = program;
            this.result = result;
            this.stillCurrent = stillCurrent;
        }
    }
}

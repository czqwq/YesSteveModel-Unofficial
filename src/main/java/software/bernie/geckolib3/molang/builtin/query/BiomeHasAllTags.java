package software.bernie.geckolib3.molang.builtin.query;

import net.minecraft.entity.Entity;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.biome.BiomeGenBase;

import software.bernie.geckolib3.molang.context.IContext;
import software.bernie.geckolib3.molang.function.entity.EntityFunction;
import software.bernie.geckolib3.molang.runtime.ExecutionContext;
import software.bernie.geckolib3.molang.util.MolangUtils;

/**
 * 1.7.10 has no biome tags, so a name is matched against the registered biome name instead.
 */
public class BiomeHasAllTags extends EntityFunction {
    @Override
    protected Object eval(ExecutionContext<IContext<Entity>> context, ArgumentCollection arguments) {
        Entity entity = context.entity()
            .entity();
        if (entity == null || entity.worldObj == null) {
            return null;
        }
        BiomeGenBase biome = entity.worldObj.getBiomeGenForCoords((int) Math.floor(entity.posX),
            (int) Math.floor(entity.posZ));

        for (int i = 0; i < arguments.size(); i++) {
            ResourceLocation id = arguments.getAsResourceLocation(context, i);
            if (id == null) {
                return null;
            }
            if (!MolangUtils.biomeMatchesTag(biome, id)) {
                return false;
            }
        }

        return true;
    }

    @Override
    public boolean validateArgumentSize(int size) {
        return size >= 1;
    }
}

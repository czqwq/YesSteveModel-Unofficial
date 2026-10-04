package software.bernie.geckolib3.molang.util;

import org.jetbrains.annotations.Nullable;

import net.minecraft.block.Block;
import net.minecraft.entity.Entity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.World;
import net.minecraft.world.biome.BiomeGenBase;
import net.minecraftforge.oredict.OreDictionary;

import software.bernie.geckolib3.molang.context.IContext;
import software.bernie.geckolib3.molang.runtime.ExecutionContext;
import software.bernie.geckolib3.molang.runtime.Function;

/**
 * Shared helpers for the MoLang query/function implementations.
 * <p>
 * 1.20.1 read block states, item tags and an equipment-slot enum here; 1.7.10 has blocks with metadata only, so the
 * relative-block lookup returns the {@link Block} and the tag queries fall back to the OreDictionary.
 */
public class MolangUtils {

    /** Range a model may look around the entity in, kept small so a model cannot be used to x-ray the world. */
    private static final int MAX_RELATIVE_BLOCK_POS = 5;

    public static float normalizeTime(long timestamp) {
        return ((float) (timestamp + 6000L) / 24000) % 1;
    }

    /** Linear interpolation, which 1.7.10's MathHelper does not provide. */
    public static float lerp(float delta, float start, float end) {
        return start + delta * (end - start);
    }

    @Nullable
    public static Block getRelativeBlock(ExecutionContext<IContext<Entity>> ctx, Function.ArgumentCollection args) {
        return getRelativeBlock(ctx, args, 0);
    }

    @Nullable
    public static Block getRelativeBlock(ExecutionContext<IContext<Entity>> ctx, Function.ArgumentCollection args,
        int argsOffset) {
        double offsetX = args.getAsDouble(ctx, argsOffset);
        double offsetY = args.getAsDouble(ctx, argsOffset + 1);
        double offsetZ = args.getAsDouble(ctx, argsOffset + 2);
        if (Math.abs(offsetX) > MAX_RELATIVE_BLOCK_POS || Math.abs(offsetY) > MAX_RELATIVE_BLOCK_POS
            || Math.abs(offsetZ) > MAX_RELATIVE_BLOCK_POS) {
            return null;
        }
        Entity entity = ctx.entity()
            .entity();
        World world = entity == null ? null : entity.worldObj;
        if (world == null) {
            return null;
        }
        int x = (int) Math.round(entity.posX + offsetX - 0.5d);
        int y = (int) Math.round(entity.posY + offsetY - 0.5d);
        int z = (int) Math.round(entity.posZ + offsetZ - 0.5d);
        if (!world.blockExists(x, y, z)) {
            return null;
        }
        return world.getBlock(x, y, z);
    }

    /** Resolves a MoLang slot name such as {@code "head"} or {@code "mainhand"}. */
    @Nullable
    public static MolangEquipmentSlot parseSlotType(IContext<?> context, String value) {
        MolangEquipmentSlot slot = MolangEquipmentSlot.byName(value);
        if (slot == null && context != null) {
            context.debugPrint("Illegal slot type: %s.", value);
        }
        return slot;
    }

    /**
     * 1.20.1 item/block/biome tags do not exist on 1.7.10. The closest equivalent available to the engine is the
     * OreDictionary for items and blocks, and the registered biome name for biomes, so a model written for a 1.20.1
     * pack can still ask these questions. A name that matches nothing simply returns {@code false}.
     */
    private static boolean matchesOreName(int[] oreIds, ResourceLocation tagId) {
        if (oreIds == null || tagId == null) {
            return false;
        }
        String path = tagId.getResourcePath();
        String full = tagId.toString();
        for (int oreId : oreIds) {
            String oreName = OreDictionary.getOreName(oreId);
            if (path.equals(oreName) || full.equals(oreName)) {
                return true;
            }
        }
        return false;
    }

    public static boolean blockMatchesTag(Block block, ResourceLocation tagId) {
        if (block == null || tagId == null) {
            return false;
        }
        return matchesOreName(OreDictionary.getOreIDs(new ItemStack(block, 1, 0)), tagId);
    }

    public static boolean itemMatchesTag(@Nullable ItemStack stack, ResourceLocation tagId) {
        if (stack == null || stack.getItem() == null || tagId == null) {
            return false;
        }
        return matchesOreName(OreDictionary.getOreIDs(stack), tagId);
    }

    public static boolean biomeMatchesTag(@Nullable BiomeGenBase biome, ResourceLocation tagId) {
        if (biome == null || tagId == null) {
            return false;
        }
        String path = tagId.getResourcePath();
        return biome.biomeName != null
            && (biome.biomeName.equalsIgnoreCase(path) || biome.biomeName.equalsIgnoreCase(tagId.toString()));
    }

    /**
     * Parses a {@code namespace:path} identifier with the same rules 1.20.1's {@code ResourceLocation.tryParse}
     * applied, returning {@code null} for anything it would have rejected.
     * <p>
     * This has to be done by hand: 1.7.10's {@code ResourceLocation(String)} accepts every string it is given (it
     * splits on the first colon and defaults the namespace), so a model naming a resource that does not exist would
     * otherwise be handed a plausible looking identifier instead of the "illegal resource location" outcome the
     * modern API produced.
     */
    @Nullable
    public static ResourceLocation parseResourceLocation(@Nullable String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        int separator = value.indexOf(':');
        String namespace;
        String path;
        if (separator >= 0) {
            if (separator == 0) {
                return null;
            }
            namespace = value.substring(0, separator);
            path = value.substring(separator + 1);
        } else {
            namespace = "minecraft";
            path = value;
        }
        if (!isValidNamespace(namespace) || !isValidPath(path)) {
            return null;
        }
        return new ResourceLocation(namespace, path);
    }

    private static boolean isValidNamespace(String namespace) {
        if (namespace.isEmpty()) {
            return false;
        }
        for (int i = 0; i < namespace.length(); i++) {
            if (!isValidNamespaceChar(namespace.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isValidPath(String path) {
        if (path.isEmpty()) {
            return false;
        }
        for (int i = 0; i < path.length(); i++) {
            if (!isValidPathChar(path.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isValidNamespaceChar(char c) {
        return c == '_' || c == '-' || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '.';
    }

    private static boolean isValidPathChar(char c) {
        return isValidNamespaceChar(c) || c == '/';
    }

    /** The registry name of a block, used by the tag and name queries. */
    @Nullable
    public static ResourceLocation blockId(Block block) {
        if (block == null) {
            return null;
        }
        Object name = Block.blockRegistry.getNameForObject(block);
        return name == null ? null : new ResourceLocation(name.toString());
    }

    /** The registry name of an item stack, used by the {@code is_item_name_any} query. */
    @Nullable
    public static ResourceLocation itemId(@Nullable ItemStack stack) {
        if (stack == null || stack.getItem() == null) {
            return null;
        }
        Object name = Item.itemRegistry.getNameForObject(stack.getItem());
        return name == null ? null : new ResourceLocation(name.toString());
    }
}

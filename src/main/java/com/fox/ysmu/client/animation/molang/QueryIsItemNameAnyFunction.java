package com.fox.ysmu.client.animation.molang;

import java.util.Locale;

import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.item.ItemStack;

import com.eliotlash.mclib.math.IValue;
import com.eliotlash.mclib.math.functions.Function;
import com.fox.ysmu.client.animation.condition.InnerClassify;
import com.fox.ysmu.compat.BackhandCompat;

/**
 * {@code query.is_item_name_any(slot, name, ...)} - whether the item in {@code slot} is one of the named items.
 * <p>
 * Ported from OpenYSM's {@code builtin/query/IsItemNameAny}: the slot is a Bedrock slot name
 * ({@code slot.weapon.mainhand}, {@code slot.armor.head}, {@code slot.hotbar.3}, ...) and the names are item registry
 * ids ({@code minecraft:apple}). The 1.7.10 translation maps the slot onto this version's equipment and inventory
 * accessors and compares against {@link InnerClassify#registryId}, the single item-to-name lookup this codebase
 * already uses for held-item conditions - so a name matches exactly the same way it does in {@code hold_mainhand$...}.
 * <p>
 * A bare name without a namespace also matches ({@code 'apple'} matches {@code minecraft:apple}), because Bedrock
 * packs in the wild write both. The off hand is a Backhand mod slot in 1.7.10 and answers empty when Backhand is
 * absent.
 */
public class QueryIsItemNameAnyFunction extends Function {

    private final IValue[] arguments;

    // 必须实现这个特定签名的构造函数，供 MathBuilder 反射调用
    public QueryIsItemNameAnyFunction(IValue[] values, String name) throws Exception {
        super(values, name);
        this.arguments = values;
    }

    @Override
    public double get() {
        if (this.arguments == null || this.arguments.length < 2) {
            return 0;
        }
        EntityLivingBase entity = MolangFrameContext.getEntity();
        if (entity == null) {
            return 0;
        }
        ItemStack stack = stackInSlot(entity, getStringArg(0));
        if (stack == null || stack.getItem() == null) {
            return 0;
        }
        String id = InnerClassify.registryId(stack);
        if (id.isEmpty()) {
            return 0;
        }
        int separator = id.indexOf(':');
        String path = separator < 0 ? id : id.substring(separator + 1);
        for (int index = 1; index < this.arguments.length; index++) {
            String candidate = getStringArg(index);
            if (candidate == null || candidate.isEmpty()) {
                continue;
            }
            String normalized = candidate.toLowerCase(Locale.ROOT);
            if (normalized.equals(id)) {
                return 1;
            }
            if (normalized.indexOf(':') < 0 && normalized.equals(path)) {
                return 1;
            }
        }
        return 0;
    }

    /**
     * The stack in a Bedrock slot name. Prefixes are optional ({@code slot.weapon.mainhand} and {@code mainhand} both
     * work), and the numbered forms address this version's {@link InventoryPlayer} directly.
     */
    private static ItemStack stackInSlot(EntityLivingBase entity, String slot) {
        if (slot == null) {
            return null;
        }
        String key = slot.toLowerCase(Locale.ROOT)
            .trim();
        for (String prefix : new String[] { "slot.", "weapon.", "armor." }) {
            if (key.startsWith(prefix)) {
                key = key.substring(prefix.length());
            }
        }
        switch (key) {
            case "mainhand":
                return entity.getHeldItem();
            case "offhand":
                return entity instanceof EntityPlayer ? BackhandCompat.getOffhandItem((EntityPlayer) entity) : null;
            case "head":
                return entity.getEquipmentInSlot(4);
            case "chest":
            case "body":
                return entity.getEquipmentInSlot(3);
            case "legs":
                return entity.getEquipmentInSlot(2);
            case "feet":
                return entity.getEquipmentInSlot(1);
            default:
                break;
        }
        if (!(entity instanceof EntityPlayer)) {
            return null;
        }
        int dot = key.lastIndexOf('.');
        if (dot <= 0) {
            return null;
        }
        InventoryPlayer inventory = ((EntityPlayer) entity).inventory;
        if (inventory == null) {
            return null;
        }
        try {
            int index = Integer.parseInt(key.substring(dot + 1));
            if (index < 0) {
                return null;
            }
            if (key.startsWith("hotbar.") || key.startsWith("container.")) {
                // 快捷栏在 1.7.10 的 InventoryPlayer 里就是 0..8
                return index < 9 ? inventory.getStackInSlot(index) : null;
            }
            return index < inventory.getSizeInventory() ? inventory.getStackInSlot(index) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}

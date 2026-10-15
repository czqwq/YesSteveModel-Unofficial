package com.fox.ysmu.client.animation.molang;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

import com.eliotlash.mclib.math.IValue;
import com.eliotlash.mclib.math.functions.Function;

import com.fox.ysmu.client.particle.ParticleEffectUtil;

import software.bernie.geckolib3.core.molang.MolangStringPool;

/**
 * {@code query.is_item_name_any(slotType, id1, id2...)} 的 mclib 实现。
 *
 * <p>YSM-wiki: molang/ref —— 判断指定槽位（{@code mainhand}/{@code offhand}/{@code head}/
 * {@code chest}/{@code legs}/{@code feet}）里物品的 id 是否命中列表中的任意一个。</p>
 *
 * <p>字符串参数（slotType 与每个 id）在解析时被 {@code MolangParser.replaceStringLiterals}
 * 池化成 int id，这里用 {@link MolangStringPool#get(int)} 还原。物品 id 取自 FML 物品注册表
 * （形如 {@code minecraft:diamond_sword}），比较忽略大小写且允许任意一侧省略命名空间
 * （{@code 'diamond_sword'} 等价于 {@code 'minecraft:diamond_sword'}）。</p>
 *
 * <p>物品标签类查询（{@code equipped_item_any_tag} 等）不在本类范围内：1.7.10 没有数据驱动的
 * 物品标签，只能用矿物词典近似。</p>
 */
public class QueryItemNameAnyFunction extends Function {

    public QueryItemNameAnyFunction(IValue[] values, String name) throws Exception {
        super(values, name);
    }

    @Override
    public int getRequiredArguments() {
        return 2;
    }

    @Override
    public double get() {
        try {
            String slotType = MolangStringPool.get((int) getArg(0));
            if (slotType == null) {
                return 0.0d;
            }
            Entity entity = ParticleEffectUtil.getCurrentEntity();
            if (!(entity instanceof EntityPlayer)) {
                return 0.0d;
            }
            ItemStack stack = getStack((EntityPlayer) entity, slotType.toLowerCase(java.util.Locale.ROOT));
            if (stack == null || stack.getItem() == null) {
                return 0.0d;
            }
            String registryName = Item.itemRegistry.getNameForObject(stack.getItem());
            if (registryName == null) {
                return 0.0d;
            }
            for (int i = 1; i < this.args.length; i++) {
                String candidate = MolangStringPool.get((int) getArg(i));
                if (matches(registryName, candidate)) {
                    return 1.0d;
                }
            }
            return 0.0d;
        } catch (Exception e) {
            return 0.0d;
        }
    }

    /**
     * 物品 id 比较：忽略大小写，允许任意一侧省略命名空间。
     *
     * <p>公开是给别的兼容层复用（{@code BaublesCompat} 判断饰品槽里的物品），保证"物品 id 怎么写
     * 才算命中"全仓只有一套规则。</p>
     */
    public static boolean matches(String registryName, String candidate) {
        if (registryName == null || candidate == null || candidate.isEmpty()) {
            return false;
        }
        String left = registryName.toLowerCase(java.util.Locale.ROOT);
        String right = candidate.toLowerCase(java.util.Locale.ROOT);
        return left.equals(right) || stripNamespace(left).equals(stripNamespace(right));
    }

    private static String stripNamespace(String name) {
        int colon = name.indexOf(':');
        return colon >= 0 ? name.substring(colon + 1) : name;
    }

    /**
     * 与 {@code ysm.equipped_enchantment_level} 使用同一套槽位语义（见 {@link MolangEquipmentSlots}）。
     *
     * <p>移植注：参考分支只认那六个具名槽位，本移植**保留**了原实现额外支持的编号写法
     * （{@code slot.hotbar.3}、{@code container.N}、任意 {@code <前缀>.<下标>}），因为参考分支没有
     * 对应能力 —— 照抄会把 {@code query.is_item_name_any('slot.hotbar.3', ...)} 从"能命中"变成"恒 0"。
     * 具名槽位仍先走 {@link MolangEquipmentSlots}，两边语义不冲突。</p>
     */
    private static ItemStack getStack(EntityPlayer player, String slotType) {
        ItemStack named = MolangEquipmentSlots.get(player, slotType);
        if (named != null) {
            return named;
        }
        return numberedSlot(player, slotType);
    }

    /**
     * {@code <前缀>.<下标>} → 本版 {@link InventoryPlayer}。
     *
     * <p>映射按 Bedrock 语义：{@code hotbar.N} 是快捷栏第 N 格（1.7.10 的
     * {@code InventoryPlayer} 里就是下标 0..8），{@code container.N} 是主背包第 N 格（1.7.10 里
     * 紧跟在快捷栏之后，即 9+N）；其余前缀按下标直接取，越界返回 null。</p>
     *
     * <p>被替换掉的旧实现把 {@code container.N} 也当成 0..8 取（于是 {@code container.3} 会读到快捷栏
     * 第 3 格），这里按上面的语义改正 —— 这是"直接替换可优化部分"的一处：旧映射几乎不可能是 pack
     * 作者想要的。</p>
     */
    private static ItemStack numberedSlot(EntityPlayer player, String slotType) {
        int dot = slotType.lastIndexOf('.');
        if (dot <= 0) {
            return null;
        }
        InventoryPlayer inventory = player.inventory;
        if (inventory == null) {
            return null;
        }
        int index;
        try {
            index = Integer.parseInt(slotType.substring(dot + 1));
        } catch (NumberFormatException e) {
            return null;
        }
        if (index < 0) {
            return null;
        }
        if (slotType.startsWith("hotbar.")) {
            return index < 9 ? inventory.getStackInSlot(index) : null;
        }
        int slot = slotType.startsWith("container.") ? 9 + index : index;
        return slot < inventory.getSizeInventory() ? inventory.getStackInSlot(slot) : null;
    }
}

package software.bernie.geckolib3.molang.builtin.query;

import net.minecraft.entity.EntityLivingBase;
import net.minecraft.item.ItemStack;

import software.bernie.geckolib3.molang.context.IContext;
import software.bernie.geckolib3.molang.function.entity.LivingEntityFunction;
import software.bernie.geckolib3.molang.runtime.ExecutionContext;
import software.bernie.geckolib3.molang.util.MolangEquipmentSlot;
import software.bernie.geckolib3.molang.util.MolangEquipmentUtil;
import software.bernie.geckolib3.molang.util.MolangUtils;

public class ItemRemainingDurability extends LivingEntityFunction {
    @Override
    protected Object eval(ExecutionContext<IContext<EntityLivingBase>> context, ArgumentCollection arguments) {
        MolangEquipmentSlot equipmentSlot = MolangUtils.parseSlotType(context.entity(),
            arguments.getAsString(context, 0));
        EntityLivingBase entity = context.entity()
            .entity();
        ItemStack itemBySlot = MolangEquipmentUtil.getEquippedItem(entity, equipmentSlot);
        return itemBySlot == null ? 0 : itemBySlot.getMaxDamage() - itemBySlot.getItemDamage();
    }

    @Override
    public boolean validateArgumentSize(int size) {
        return size == 1;
    }
}

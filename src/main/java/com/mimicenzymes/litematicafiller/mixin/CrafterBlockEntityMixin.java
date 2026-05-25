package com.mimicenzymes.litematicafiller.mixin;

import net.minecraft.world.level.block.entity.CrafterBlockEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(CrafterBlockEntity.class)
public class CrafterBlockEntityMixin {

    @Inject(method = "getItem", at = @At("RETURN"), cancellable = true)
    private void onGetStack(int slot, CallbackInfoReturnable<ItemStack> cir) {
        net.minecraft.world.level.Level world = ((net.minecraft.world.level.block.entity.BlockEntity) (Object) this).getLevel();
        if (world != null && world.isClientSide() && world.getClass().getSimpleName().contains("Schematic")) {
            ItemStack replaced = com.mimicenzymes.litematicafiller.core.MaterialReplacer.replaceSingleStack(cir.getReturnValue());
            cir.setReturnValue(replaced);
        }
    }
}

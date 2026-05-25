package com.mimicenzymes.litematicafiller.mixin;

import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.core.HolderLookup;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BlockEntity.class)
public class BlockEntityMixin {

    @Inject(method = {
            "saveWithoutMetadata",
            "saveWithFullMetadata"
    }, at = @At("RETURN"))
    private void onSerializeNbtAny(HolderLookup.Provider registries, CallbackInfoReturnable<CompoundTag> cir) {
        if (!com.mimicenzymes.litematicafiller.config.Configs.ENABLE_MOD.getBooleanValue()) return;
        net.minecraft.world.level.Level world = ((BlockEntity) (Object) this).getLevel();
        if (world != null && world.isClientSide() && world.getClass().getSimpleName().contains("Schematic")) {
            CompoundTag nbt = cir.getReturnValue();
            if (nbt != null && nbt.contains("Items")) {
                com.mimicenzymes.litematicafiller.core.MaterialReplacer.replaceInListTag((ListTag) nbt.get("Items"), registries);
            }
        }
    }
}

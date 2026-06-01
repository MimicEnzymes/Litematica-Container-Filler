package com.mimicenzymes.litematicafiller.mixin;

import net.minecraft.block.entity.BlockEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.RegistryWrapper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BlockEntity.class)
public class BlockEntityMixin {

    @Inject(method = {
            "createNbt",
            "createNbtWithIdentifyingData"
    }, at = @At("RETURN"))
    private void onSerializeNbtAny(RegistryWrapper.WrapperLookup registries, CallbackInfoReturnable<NbtCompound> cir) {
        if (!com.mimicenzymes.litematicafiller.config.Configs.ENABLE_MOD.getBooleanValue()) return;
        if (com.mimicenzymes.litematicafiller.core.MaterialReplacer.isNbtReplacementSuppressed()) return;

        BlockEntity blockEntity = (BlockEntity) (Object) this;
        net.minecraft.world.World world = blockEntity.getWorld();
        if (world != null && world.isClient() && world.getClass().getSimpleName().contains("Schematic")) {
            NbtCompound nbt = cir.getReturnValue();
            if (nbt != null && nbt.contains("Items")) {
                String schematicKey = com.mimicenzymes.litematicafiller.core.LitematicaPlacementContainerData.getSchematicKey(blockEntity.getPos());
                if (schematicKey == null) {
                    schematicKey = com.mimicenzymes.litematicafiller.core.LitematicaContainerReader.findSchematicKeyForPosition(blockEntity.getPos());
                }
                com.mimicenzymes.litematicafiller.core.MaterialReplacer.replaceInNbtList((NbtList) nbt.get("Items"), registries, schematicKey);
            }
        }
    }
}

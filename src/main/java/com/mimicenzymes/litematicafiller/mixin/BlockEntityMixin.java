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
        net.minecraft.world.World world = ((BlockEntity) (Object) this).getWorld();
        if (world == null || !world.isClient()) return;
        // 引用比较：仅在该 BlockEntity 确实挂在 Litematica 的 schematic world 上时才改写返回值，
        // 避免用字符串类名匹配导致误中其它 mod 同名世界，进而污染真实容器 NBT。
        if (world != fi.dy.masa.litematica.world.SchematicWorldHandler.getSchematicWorld()) return;
        NbtCompound nbt = cir.getReturnValue();
        if (nbt != null && nbt.contains("Items")) {
            com.mimicenzymes.litematicafiller.core.MaterialReplacer.replaceInNbtList((NbtList) nbt.get("Items"), registries);
        }
    }
}
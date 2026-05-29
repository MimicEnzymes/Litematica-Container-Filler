package com.mimicenzymes.litematicafiller.mixin;

import com.mimicenzymes.litematicafiller.config.Configs;
import com.mimicenzymes.litematicafiller.core.RealContainerCache;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MultiPlayerGameMode.class)
public class ClientPlayerInteractionManagerMixin {
    @Inject(method = "useItemOn", at = @At("HEAD"))
    private void litematicaContainerFiller$rememberInteractedBlock(LocalPlayer player,
                                                                  InteractionHand InteractionHand,
                                                                  BlockHitResult hitResult,
                                                                  CallbackInfoReturnable<?> cir) {
        Minecraft client = Minecraft.getInstance();
        if (!Configs.ENABLE_MOD.getBooleanValue() || !RealContainerCache.hasActiveConsumers() ||
                player == null || client.level == null || hitResult == null) {
            return;
        }

        BlockPos pos = hitResult.getBlockPos();
        RealContainerCache.rememberPendingScreenTarget(client.level, pos);
    }
}

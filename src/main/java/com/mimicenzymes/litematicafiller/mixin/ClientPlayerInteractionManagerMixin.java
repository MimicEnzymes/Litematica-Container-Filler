package com.mimicenzymes.litematicafiller.mixin;

import com.mimicenzymes.litematicafiller.config.Configs;
import com.mimicenzymes.litematicafiller.core.RealContainerCache;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ClientPlayerInteractionManager.class)
public class ClientPlayerInteractionManagerMixin {
    @Inject(method = "interactBlock", at = @At("HEAD"))
    private void litematicaContainerFiller$rememberInteractedBlock(ClientPlayerEntity player,
                                                                  Hand hand,
                                                                  BlockHitResult hitResult,
                                                                  CallbackInfoReturnable<?> cir) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!Configs.ENABLE_MOD.getBooleanValue() || !RealContainerCache.hasActiveConsumers() ||
                player == null || client.world == null || hitResult == null) {
            return;
        }

        BlockPos pos = hitResult.getBlockPos();
        RealContainerCache.rememberPendingScreenTarget(client.world, pos);
    }
}

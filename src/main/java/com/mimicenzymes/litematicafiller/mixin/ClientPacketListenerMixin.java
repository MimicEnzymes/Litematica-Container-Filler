package com.mimicenzymes.litematicafiller.mixin;

import com.mimicenzymes.litematicafiller.config.Configs;
import com.mimicenzymes.litematicafiller.core.RealContainerCache;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundTagQueryPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public class ClientPacketListenerMixin {

    @Inject(method = "handleTagQueryPacket", at = @At("HEAD"))
    private void onNbtQueryResponse(ClientboundTagQueryPacket packet, CallbackInfo ci) {
        if (!Configs.ENABLE_MOD.getBooleanValue() || !RealContainerCache.hasActiveConsumers()) return;
        if (RealContainerCache.canUseOpNbtQuery()) {
            RealContainerCache.handleNbtResponse(packet.getTransactionId(), packet.getTag());
        }
    }
}

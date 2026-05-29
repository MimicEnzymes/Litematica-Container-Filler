package com.mimicenzymes.litematicafiller.mixin;

import com.mimicenzymes.litematicafiller.config.Configs;
import com.mimicenzymes.litematicafiller.core.RealContainerCache;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.s2c.play.NbtQueryResponseS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPlayNetworkHandler.class)
public class ClientPlayNetworkHandlerMixin {

    @Inject(method = "onNbtQueryResponse", at = @At("HEAD"))
    private void onNbtQueryResponse(NbtQueryResponseS2CPacket packet, CallbackInfo ci) {
        if (!Configs.ENABLE_MOD.getBooleanValue() || !RealContainerCache.hasActiveConsumers()) return;
        if (Configs.ENABLE_OP_NBT_QUERY.getBooleanValue()) {
            RealContainerCache.handleNbtResponse(packet.getTransactionId(), packet.getNbt());
        }
    }
}

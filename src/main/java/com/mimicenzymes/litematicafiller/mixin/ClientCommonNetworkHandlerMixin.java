package com.mimicenzymes.litematicafiller.mixin;

import com.mimicenzymes.litematicafiller.network.ClickPacketRateLimiter;
import net.minecraft.client.network.ClientCommonNetworkHandler;
import net.minecraft.network.packet.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientCommonNetworkHandler.class)
public class ClientCommonNetworkHandlerMixin {
    @Inject(method = "sendPacket", at = @At("HEAD"), cancellable = true)
    private void litematicaContainerFiller$rateLimitContainerPackets(Packet<?> packet, CallbackInfo ci) {
        if (ClickPacketRateLimiter.bufferIfNeeded(packet)) {
            ci.cancel();
        }
    }
}

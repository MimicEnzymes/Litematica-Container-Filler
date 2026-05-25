package com.mimicenzymes.litematicafiller.mixin;

import com.mimicenzymes.litematicafiller.network.ClickPacketRateLimiter;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientCommonPacketListenerImpl.class)
public class ClientCommonNetworkHandlerMixin {
    @Inject(method = "send", at = @At("HEAD"), cancellable = true)
    private void litematicaContainerFiller$rateLimitContainerPackets(Packet<?> packet, CallbackInfo ci) {
        if (ClickPacketRateLimiter.bufferIfNeeded(packet)) {
            ci.cancel();
        }
    }
}


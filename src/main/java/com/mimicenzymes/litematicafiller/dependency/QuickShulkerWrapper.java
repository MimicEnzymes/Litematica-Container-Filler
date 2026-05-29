package com.mimicenzymes.litematicafiller.dependency;

import net.kyrptonaught.quickshulker.network.OpenShulkerPacket;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

public class QuickShulkerWrapper implements IShulkerExtractor {

    @Override
    public boolean requestOpenShulker(int playerSlotIndex) {
        try {
            int packetSlot = playerSlotIndex < 9 ? playerSlotIndex + 36 : playerSlotIndex;
            ClientPlayNetworking.send(new OpenShulkerPacket(packetSlot));
            return true;

        } catch (Exception ignored) {
            return false;
        }
    }
}

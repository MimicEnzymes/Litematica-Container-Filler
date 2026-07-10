package com.mimicenzymes.litematicafiller.dependency;

import net.kyrptonaught.quickshulker.network.OpenShulkerPacket;

public class QuickShulkerWrapper implements IShulkerExtractor {

    @Override
    public boolean requestOpenShulker(int playerSlotIndex) {
        try {
            int packetSlot = playerSlotIndex < 9 ? playerSlotIndex + 36 : playerSlotIndex;
            OpenShulkerPacket.sendOpenPacket(packetSlot);
            return true;

        } catch (Exception ignored) {
            return false;
        }
    }
}

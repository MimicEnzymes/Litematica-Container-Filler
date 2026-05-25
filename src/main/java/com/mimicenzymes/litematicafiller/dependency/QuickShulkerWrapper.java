package com.mimicenzymes.litematicafiller.dependency;

import net.kyrptonaught.quickshulker.network.OpenShulkerPacket;
import net.kyrptonaught.quickshulker.client.ClientUtil;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.item.ItemStack;

public class QuickShulkerWrapper implements IShulkerExtractor {

    @Override
    public boolean requestOpenShulker(int playerSlotIndex) {
        try {
            int packetSlot = playerSlotIndex < 9 ? playerSlotIndex + 36 : playerSlotIndex;
            ClientPlayNetworking.send(new OpenShulkerPacket(packetSlot));
            return true;

        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    @Override
    public boolean requestOpenShulkerFromClick(ItemStack stack, int playerSlotIndex) {
        try {
            int packetSlot = playerSlotIndex < 9 ? playerSlotIndex + 36 : playerSlotIndex;
            return ClientUtil.CheckAndSend(stack, packetSlot);
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }
}

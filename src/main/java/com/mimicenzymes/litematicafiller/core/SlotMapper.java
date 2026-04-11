package com.mimicenzymes.litematicafiller.core;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;

public class SlotMapper {

    private final Map<Integer, Integer> playerToUiMap = new HashMap<>();
    private final Map<Integer, Integer> containerToUiMap = new HashMap<>();

    public SlotMapper(AbstractContainerMenu handler, Inventory playerInv) {
        for (int uiSlotId = 0; uiSlotId < handler.slots.size(); uiSlotId++) {
            Slot slot = handler.slots.get(uiSlotId);
            if (slot.container == null) continue;

            if (slot.container == playerInv) {
                playerToUiMap.putIfAbsent(slot.getContainerSlot(), uiSlotId);
            } else {
                if (handler instanceof net.minecraft.world.inventory.CrafterMenu && slot.getContainerSlot() == 9) {
                    continue;
                }
                containerToUiMap.putIfAbsent(slot.getContainerSlot(), uiSlotId);
            }
        }
    }

    public int getUiSlotForPlayer(int playerSlotIndex) {
        return playerToUiMap.getOrDefault(playerSlotIndex, -1);
    }

    public int getUiSlotForContainer(int containerSlotIndex) {
        return containerToUiMap.getOrDefault(containerSlotIndex, -1);
    }
}
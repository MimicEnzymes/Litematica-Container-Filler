package com.mimicenzymes.litematicafiller.network;

import com.mimicenzymes.litematicafiller.config.Configs;
import net.minecraft.client.MinecraftClient;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.play.ClickSlotC2SPacket;
import net.minecraft.network.packet.c2s.play.CloseHandledScreenC2SPacket;
import net.minecraft.network.packet.c2s.play.CreativeInventoryActionC2SPacket;
import net.minecraft.network.packet.c2s.play.SlotChangedStateC2SPacket;

import java.util.ArrayDeque;
import java.util.Queue;

public class ClickPacketRateLimiter {
    private static final int MAX_BUFFERED_PACKETS = 4096;
    private static final Queue<Packet<?>> BUFFER = new ArrayDeque<>();
    private static boolean operationActive = false;
    private static boolean replaying = false;
    private static boolean overflowed = false;

    private ClickPacketRateLimiter() {
    }

    public static void setOperationActive(boolean active) {
        operationActive = active;
    }

    public static boolean hasPendingPackets() {
        return !BUFFER.isEmpty();
    }

    public static void reset() {
        BUFFER.clear();
        operationActive = false;
        replaying = false;
        overflowed = false;
    }

    public static boolean consumeOverflowed() {
        boolean result = overflowed;
        overflowed = false;
        return result;
    }

    public static boolean bufferIfNeeded(Packet<?> packet) {
        if (replaying || packet == null) return false;
        if (!isEnabled()) return false;
        if (!operationActive || !isContainerMutationPacket(packet)) return false;

        if (BUFFER.size() >= MAX_BUFFERED_PACKETS) {
            BUFFER.clear();
            operationActive = false;
            replaying = false;
            overflowed = true;
            return true;
        }
        BUFFER.offer(packet);
        return true;
    }

    public static void tick(MinecraftClient client) {
        if (client == null || client.player == null || client.world == null || client.getNetworkHandler() == null) {
            reset();
            return;
        }

        if (BUFFER.isEmpty()) return;

        int limit = isEnabled() ? Math.max(1, Configs.CLICK_PACKET_RATE_LIMIT.getIntegerValue()) : BUFFER.size();
        replaying = true;
        try {
            for (int i = 0; i < limit && !BUFFER.isEmpty(); i++) {
                client.getNetworkHandler().sendPacket(BUFFER.poll());
            }
        } finally {
            replaying = false;
        }
    }

    private static boolean isContainerMutationPacket(Packet<?> packet) {
        return packet instanceof ClickSlotC2SPacket ||
                packet instanceof CloseHandledScreenC2SPacket ||
                packet instanceof SlotChangedStateC2SPacket ||
                packet instanceof CreativeInventoryActionC2SPacket;
    }

    private static boolean isEnabled() {
        return Configs.ENABLE_MOD.getBooleanValue() && Configs.RATE_LIMIT_CLICK_PACKETS.getBooleanValue();
    }
}

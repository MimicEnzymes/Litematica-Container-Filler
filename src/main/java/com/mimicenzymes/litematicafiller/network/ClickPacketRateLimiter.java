package com.mimicenzymes.litematicafiller.network;

import com.mimicenzymes.litematicafiller.config.Configs;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket;
import net.minecraft.network.protocol.game.ServerboundContainerSlotStateChangedPacket;

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

    public static void tick(Minecraft client) {
        if (client == null || client.player == null || client.level == null || client.getConnection() == null) {
            reset();
            return;
        }

        if (BUFFER.isEmpty()) return;

        int limit = isEnabled() ? Math.max(1, Configs.CLICK_PACKET_RATE_LIMIT.getIntegerValue()) : BUFFER.size();
        replaying = true;
        try {
            for (int i = 0; i < limit && !BUFFER.isEmpty(); i++) {
                client.getConnection().send(BUFFER.poll());
            }
        } finally {
            replaying = false;
        }
    }

    private static boolean isContainerMutationPacket(Packet<?> packet) {
        return packet instanceof ServerboundContainerClickPacket ||
                packet instanceof ServerboundContainerClosePacket ||
                packet instanceof ServerboundContainerSlotStateChangedPacket ||
                packet instanceof ServerboundSetCreativeModeSlotPacket;
    }

    private static boolean isEnabled() {
        return Configs.ENABLE_MOD.getBooleanValue() && Configs.RATE_LIMIT_CLICK_PACKETS.getBooleanValue();
    }
}


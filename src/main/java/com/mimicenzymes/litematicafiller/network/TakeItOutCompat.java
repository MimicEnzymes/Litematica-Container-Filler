package com.mimicenzymes.litematicafiller.network;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public class TakeItOutCompat {
    private static final boolean HAS_CLIENT_TAKEITOUT = FabricLoader.getInstance().isModLoaded("takeitout");
    private static boolean payloadRegistered = false;
    private static boolean payloadRegistrationAttempted = false;

    public static void registerPayload() {
        if (payloadRegistrationAttempted) return;
        payloadRegistrationAttempted = true;

        if (HAS_CLIENT_TAKEITOUT) {
            payloadRegistered = true;
            return;
        }

        try {
            PayloadTypeRegistry.serverboundPlay().register(TakeItOutPayload.ID, TakeItOutPayload.CODEC);
            payloadRegistered = true;
        } catch (Exception ignored) {
            payloadRegistered = false;
        }
    }

    public static boolean canRequestStack() {
        try {
            return payloadRegistered && ClientPlayNetworking.canSend(TakeItOutPayload.ID);
        } catch (Exception ignored) {
            return false;
        }
    }

    public static boolean requestStack(int slotInShulker, int shulkerSlot) {
        if (!canRequestStack()) return false;

        try {
            if (HAS_CLIENT_TAKEITOUT) {
                ClientPlayNetworking.send(createClientTakeItOutPayload(slotInShulker, shulkerSlot));
            } else {
                ClientPlayNetworking.send(new TakeItOutPayload(slotInShulker, shulkerSlot));
            }
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static CustomPacketPayload createClientTakeItOutPayload(int slotInShulker, int shulkerSlot) throws ReflectiveOperationException {
        Class<?> payloadClass = Class.forName("net.maxbel.takeitout.Takeitout$GetShulkerStackPayload");

        try {
            return (CustomPacketPayload) payloadClass
                    .getDeclaredConstructor(int.class, int.class)
                    .newInstance(slotInShulker, shulkerSlot);
        } catch (NoSuchMethodException ignored) {
            return (CustomPacketPayload) payloadClass
                    .getDeclaredConstructor(int.class, int.class, boolean.class)
                    .newInstance(slotInShulker, shulkerSlot, false);
        }
    }
}


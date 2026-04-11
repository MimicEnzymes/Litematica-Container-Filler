package com.mimicenzymes.litematicafiller.network;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ServuxSyncHandler {

    public static final Map<BlockPos, Map<Integer, ItemStack>> INDEPENDENT_CACHE = new ConcurrentHashMap<>();

    private static boolean minihudChecked = false;
    private static boolean hasMinihud = false;
    private static boolean payloadsRegistered = false;

    public static void registerPayloads() {
        if (payloadsRegistered) return;
        try {
            PayloadTypeRegistry.playC2S().register(ServuxRequestPayload.ID, ServuxRequestPayload.CODEC);
            PayloadTypeRegistry.playS2C().register(ServuxResponsePayload.ID, ServuxResponsePayload.CODEC);

            ClientPlayNetworking.registerGlobalReceiver(ServuxResponsePayload.ID, (payload, context) -> {
                context.client().execute(() -> {
                    if (payload.pos() != null && payload.items() != null) {
                        INDEPENDENT_CACHE.put(payload.pos().immutable(), payload.items());
                    }
                });
            });
            payloadsRegistered = true;
        } catch (Exception ignored) {}
    }

    private static void checkMinihud() {
        if (!minihudChecked) {
            try {
                Class.forName("fi.dy.masa.minihud.inventory.InventoryCache");
                hasMinihud = true;
            } catch (Throwable t) {
                hasMinihud = false;
            }
            minihudChecked = true;
        }
    }

    @SuppressWarnings("unchecked")
    public static Map<Integer, ItemStack> getCachedData(BlockPos pos) {
        checkMinihud();

        // 偷你缓存气不气
        if (hasMinihud) {
            try {
                Class<?> cacheClass = Class.forName("fi.dy.masa.minihud.inventory.InventoryCache");
                Object cacheInstance = null;
                for (java.lang.reflect.Method m : cacheClass.getDeclaredMethods()) {
                    if (m.getName().equals("getInstance") && m.getParameterCount() == 0) {
                        cacheInstance = m.invoke(null); break;
                    }
                }
                if (cacheInstance != null) {
                    for (java.lang.reflect.Method m : cacheClass.getDeclaredMethods()) {
                        if (m.getParameterCount() == 1 && m.getParameterTypes()[0] == BlockPos.class && m.getReturnType() == java.util.List.class) {
                            java.util.List<ItemStack> list = (java.util.List<ItemStack>) m.invoke(cacheInstance, pos);
                            if (list != null && !list.isEmpty()) {
                                Map<Integer, ItemStack> map = new HashMap<>();
                                for (int i = 0; i < list.size(); i++) {
                                    ItemStack stack = list.get(i);
                                    if (stack != null && !stack.isEmpty()) map.put(i, stack.copy());
                                }
                                return map;
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }

        return INDEPENDENT_CACHE.get(pos);
    }

    public static boolean requestData(BlockPos pos) {
        checkMinihud();

        if (hasMinihud) {
            try {
                Class<?> senderClass = Class.forName("fi.dy.masa.minihud.network.ClientPacketSender");
                for (java.lang.reflect.Method m : senderClass.getDeclaredMethods()) {
                    if (m.getParameterCount() == 1 && m.getParameterTypes()[0] == BlockPos.class) {
                        String name = m.getName().toLowerCase();
                        if (name.contains("container") || name.contains("inventory") || name.contains("request") || name.contains("data")) {
                            m.invoke(null, pos);
                            return true;
                        }
                    }
                }
            } catch (Throwable t) {}
        }

        if (payloadsRegistered && ClientPlayNetworking.canSend(ServuxRequestPayload.ID)) {
            ClientPlayNetworking.send(new ServuxRequestPayload(0, pos));
            return true;
        }

        return false;
    }
}
package com.mimicenzymes.litematicafiller.network;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.BlockPos;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ServuxSyncHandler {
    private static final int MAX_INDEPENDENT_CACHE_SIZE = 1024;

    public static final Map<BlockPos, Map<Integer, ItemStack>> INDEPENDENT_CACHE = new ConcurrentHashMap<>();

    private static boolean minihudChecked = false;
    private static Class<?> minihudCacheClass = null;
    private static Class<?> minihudSenderClass = null;
    private static boolean payloadsRegistered = false;

    public static void registerPayloads() {
        if (payloadsRegistered) return;
        try {
            PayloadTypeRegistry.serverboundPlay().register(ServuxRequestPayload.ID, ServuxRequestPayload.CODEC);
            PayloadTypeRegistry.clientboundPlay().register(ServuxResponsePayload.ID, ServuxResponsePayload.CODEC);

            ClientPlayNetworking.registerGlobalReceiver(ServuxResponsePayload.ID, (payload, context) -> {
                context.client().execute(() -> {
                    if (payload.pos() != null && payload.items() != null) {
                        putIndependentCache(payload.pos().immutable(), payload.items());
                    }
                });
            });
            payloadsRegistered = true;
        } catch (Exception ignored) {}

    }

    private static void checkMinihud() {
        if (!minihudChecked) {
            String[] cacheClasses = {
                    "fi.dy.masa.minihud.feature.InventoryCache",
                    "fi.dy.masa.minihud.container.InventoryCache",
                    "fi.dy.masa.minihud.util.InventoryCache"
            };
            for (String c : cacheClasses) {
                try { minihudCacheClass = Class.forName(c); break; } catch (Throwable ignored) {}
            }

            String[] senderClasses = {
                    "fi.dy.masa.minihud.network.ClientPacketSender",
                    "fi.dy.masa.minihud.network.PacketSender"
            };
            for (String c : senderClasses) {
                try { minihudSenderClass = Class.forName(c); break; } catch (Throwable ignored) {}
            }

            minihudChecked = true;
        }
    }

    private static Map<Integer, ItemStack> extractItemsFromObject(Object obj) {
        if (obj == null) return null;
        Map<Integer, ItemStack> map = new HashMap<>();

        if (obj instanceof java.util.Collection<?> list) {
            int slot = 0;
            for (Object item : list) {
                if (item instanceof ItemStack stack && !stack.isEmpty()) map.put(slot, stack.copy());
                slot++;
            }
            if (!map.isEmpty()) return map;
        }
        else if (obj instanceof ItemStack[] arr) {
            for (int i = 0; i < arr.length; i++) {
                if (arr[i] != null && !arr[i].isEmpty()) map.put(i, arr[i].copy());
            }
            if (!map.isEmpty()) return map;
        }

        try {
            for (Field f : obj.getClass().getDeclaredFields()) {
                f.setAccessible(true);
                Object val = f.get(obj);

                if (val instanceof java.util.Collection<?> list) {
                    int slot = 0;
                    for (Object item : list) {
                        if (item instanceof ItemStack stack && !stack.isEmpty()) map.put(slot, stack.copy());
                        slot++;
                    }
                    if (!map.isEmpty()) return map;
                }
                else if (val instanceof ItemStack[] arr) {
                    for (int i = 0; i < arr.length; i++) {
                        if (arr[i] != null && !arr[i].isEmpty()) map.put(i, arr[i].copy());
                    }
                    if (!map.isEmpty()) return map;
                }
            }
        } catch (Throwable ignored) {}

        return null;
    }

    public static Map<Integer, ItemStack> getCachedData(BlockPos pos) {
        checkMinihud();

        if (minihudCacheClass != null) {
            try {
                for (Field f : minihudCacheClass.getDeclaredFields()) {
                    if (java.lang.reflect.Modifier.isStatic(f.getModifiers()) && Map.class.isAssignableFrom(f.getType())) {
                        f.setAccessible(true);
                        Map<?, ?> map = (Map<?, ?>) f.get(null);
                        if (map != null) {
                            Object result = map.get(pos);
                            if (result != null) {
                                Map<Integer, ItemStack> extracted = extractItemsFromObject(result);
                                if (extracted != null && !extracted.isEmpty()) return extracted;
                            }
                        }
                    }
                }

                Object cacheInstance = null;
                for (Method m : minihudCacheClass.getDeclaredMethods()) {
                    if (m.getName().equals("getInstance") && m.getParameterCount() == 0 && java.lang.reflect.Modifier.isStatic(m.getModifiers())) {
                        cacheInstance = m.invoke(null); break;
                    }
                }

                for (Method m : minihudCacheClass.getDeclaredMethods()) {
                    if (m.getParameterCount() == 1 && m.getParameterTypes()[0] == BlockPos.class) {
                        m.setAccessible(true);
                        boolean isStatic = java.lang.reflect.Modifier.isStatic(m.getModifiers());
                        if (!isStatic && cacheInstance == null) continue;

                        Object result = isStatic ? m.invoke(null, pos) : m.invoke(cacheInstance, pos);
                        if (result != null) {
                            Map<Integer, ItemStack> extracted = extractItemsFromObject(result);
                            if (extracted != null && !extracted.isEmpty()) return extracted;
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }

        return INDEPENDENT_CACHE.get(pos);
    }

    public static boolean requestData(BlockPos pos) {
        checkMinihud();

        if (minihudSenderClass != null) {
            try {
                for (Method m : minihudSenderClass.getDeclaredMethods()) {
                    if (m.getParameterCount() == 1 && m.getParameterTypes()[0] == BlockPos.class) {
                        String name = m.getName().toLowerCase();
                        if (name.contains("container") || name.contains("inventory") || name.contains("request") || name.contains("data") || name.contains("sync")) {
                            m.setAccessible(true);
                            m.invoke(null, pos);
                            return true;
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }

        if (payloadsRegistered && ClientPlayNetworking.canSend(ServuxRequestPayload.ID)) {
            ClientPlayNetworking.send(new ServuxRequestPayload(0, pos));
            return true;
        }

        return false;
    }

    private static void putIndependentCache(BlockPos pos, Map<Integer, ItemStack> items) {
        if (INDEPENDENT_CACHE.size() >= MAX_INDEPENDENT_CACHE_SIZE) {
            var iterator = INDEPENDENT_CACHE.keySet().iterator();
            if (iterator.hasNext()) {
                iterator.next();
                iterator.remove();
            }
        }
        INDEPENDENT_CACHE.put(pos, items);
    }
}

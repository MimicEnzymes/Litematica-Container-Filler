package com.mimicenzymes.litematicafiller.network;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ServuxSyncHandler {

    public static final Map<BlockPos, Map<Integer, ItemStack>> INDEPENDENT_CACHE = new ConcurrentHashMap<>();

    // 防御性上限：恶意/异常服务器发来的 payload 不能让缓存无限增长
    private static final int MAX_ITEMS_PER_CONTAINER = 64;   // 单容器槽位数上限（大型箱 54，双联 108 仍可接受，这里保守用 64 / 单侧）
    private static final int MAX_CACHE_ENTRIES = 10000;      // 整个缓存条目数上限

    private static boolean minihudChecked = false;
    private static Class<?> minihudCacheClass = null;
    private static Class<?> minihudSenderClass = null;
    private static boolean payloadsRegistered = false;

    // 反射结果缓存：#12 修复 —— 原实现每次查询都会扫全类 declaredFields/Methods，
    // CONTINUOUS_FILL 下每 tick 都跑一次，CPU 开销明显。此处在首次成功发现后把结果缓存下来复用。
    private static volatile boolean minihudReflectionCached = false;
    private static java.lang.reflect.Field[] minihudStaticMapFields = null;   // static Map<?,?> 类型字段
    private static Object minihudCacheInstance = null;                        // getInstance() 返回值
    private static java.lang.reflect.Method[] minihudLookupByPosMethods = null; // 单参 BlockPos 的方法
    private static java.lang.reflect.Method[] minihudSenderByPosMethods = null; // sender 里匹配命名的发包方法

    private static void cacheMinihudReflection() {
        if (minihudReflectionCached) return;

        synchronized (ServuxSyncHandler.class) {
            if (minihudReflectionCached) return;

            if (minihudCacheClass != null) {
                java.util.List<java.lang.reflect.Field> staticMapFields = new java.util.ArrayList<>();
                for (java.lang.reflect.Field f : minihudCacheClass.getDeclaredFields()) {
                    if (java.lang.reflect.Modifier.isStatic(f.getModifiers()) && Map.class.isAssignableFrom(f.getType())) {
                        f.setAccessible(true);
                        staticMapFields.add(f);
                    }
                }
                minihudStaticMapFields = staticMapFields.toArray(new java.lang.reflect.Field[0]);

                java.util.List<java.lang.reflect.Method> lookupMethods = new java.util.ArrayList<>();
                for (java.lang.reflect.Method m : minihudCacheClass.getDeclaredMethods()) {
                    if (m.getParameterCount() == 0
                            && m.getName().equals("getInstance")
                            && java.lang.reflect.Modifier.isStatic(m.getModifiers())) {
                        m.setAccessible(true);
                        try {
                            minihudCacheInstance = m.invoke(null);
                        } catch (Throwable ignored) {}
                    }

                    if (m.getParameterCount() == 1 && m.getParameterTypes()[0] == BlockPos.class) {
                        m.setAccessible(true);
                        lookupMethods.add(m);
                    }
                }
                minihudLookupByPosMethods = lookupMethods.toArray(new java.lang.reflect.Method[0]);
            }

            if (minihudSenderClass != null) {
                java.util.List<java.lang.reflect.Method> senderMethods = new java.util.ArrayList<>();
                for (java.lang.reflect.Method m : minihudSenderClass.getDeclaredMethods()) {
                    if (m.getParameterCount() == 1 && m.getParameterTypes()[0] == BlockPos.class) {
                        String name = m.getName().toLowerCase();
                        if (name.contains("container") || name.contains("inventory") || name.contains("request") || name.contains("data") || name.contains("sync")) {
                            m.setAccessible(true);
                            senderMethods.add(m);
                        }
                    }
                }
                minihudSenderByPosMethods = senderMethods.toArray(new java.lang.reflect.Method[0]);
            }

            minihudReflectionCached = true;
        }
    }

    public static void registerPayloads() {
        if (payloadsRegistered) return;
        try {
            PayloadTypeRegistry.playC2S().register(ServuxRequestPayload.ID, ServuxRequestPayload.CODEC);
            PayloadTypeRegistry.playS2C().register(ServuxResponsePayload.ID, ServuxResponsePayload.CODEC);

            ClientPlayNetworking.registerGlobalReceiver(ServuxResponsePayload.ID, (payload, context) -> {
                context.client().execute(() -> {
                    if (payload.pos() == null || payload.items() == null) return;
                    // 单个 payload 里 items 太大：直接丢弃，防止恶意服务器借此塞爆内存
                    if (payload.items().size() > MAX_ITEMS_PER_CONTAINER) return;
                    // 缓存总量超上限：仅允许更新已有条目，不再追加新 key
                    if (INDEPENDENT_CACHE.size() >= MAX_CACHE_ENTRIES
                            && !INDEPENDENT_CACHE.containsKey(payload.pos())) {
                        return;
                    }
                    INDEPENDENT_CACHE.put(payload.pos().toImmutable(), payload.items());
                });
            });
            payloadsRegistered = true;
        } catch (Exception ignored) {}

    }

    private static void checkMinihud() {
        if (!minihudChecked) {
            String[] cacheClasses = {
                    "fi.dy.masa.minihud.feature.InventoryCache",
                    "fi.dy.masa.minihud.inventory.InventoryCache",
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
            for (java.lang.reflect.Field f : obj.getClass().getDeclaredFields()) {
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
        cacheMinihudReflection();

        if (minihudCacheClass != null) {
            try {
                if (minihudStaticMapFields != null) {
                    for (java.lang.reflect.Field f : minihudStaticMapFields) {
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

                if (minihudLookupByPosMethods != null) {
                    for (java.lang.reflect.Method m : minihudLookupByPosMethods) {
                        boolean isStatic = java.lang.reflect.Modifier.isStatic(m.getModifiers());
                        if (!isStatic && minihudCacheInstance == null) continue;

                        Object result = isStatic ? m.invoke(null, pos) : m.invoke(minihudCacheInstance, pos);
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
        cacheMinihudReflection();

        if (minihudSenderClass != null) {
            try {
                if (minihudSenderByPosMethods != null) {
                    for (java.lang.reflect.Method m : minihudSenderByPosMethods) {
                        m.invoke(null, pos);
                        return true;
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
}

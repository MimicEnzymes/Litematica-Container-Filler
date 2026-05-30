package com.mimicenzymes.litematicafiller.network;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ServuxSyncHandler {
    private static final int MAX_INDEPENDENT_CACHE_SIZE = 1024;

    public static final Map<BlockPos, Map<Integer, ItemStack>> INDEPENDENT_CACHE = new ConcurrentHashMap<>();
    private static final Map<BlockPos, Integer> SLOT_COUNT_CACHE = new ConcurrentHashMap<>();

    private static boolean minihudChecked = false;
    private static Class<?> minihudCacheClass = null;
    private static Class<?> minihudSenderClass = null;
    private static Field[] minihudCacheMapFields = new Field[0];
    private static Method minihudCacheInstanceGetter = null;
    private static Method[] minihudCacheLookupMethods = new Method[0];
    private static Method[] minihudSenderMethods = new Method[0];
    private static Object minihudCacheInstance = null;
    private static boolean payloadsRegistered = false;

    public static void registerPayloads() {
        if (payloadsRegistered) return;
        try {
            PayloadTypeRegistry.playC2S().register(ServuxRequestPayload.ID, ServuxRequestPayload.CODEC);
            PayloadTypeRegistry.playS2C().register(ServuxResponsePayload.ID, ServuxResponsePayload.CODEC);

            ClientPlayNetworking.registerGlobalReceiver(ServuxResponsePayload.ID, (payload, context) -> {
                context.client().execute(() -> {
                    if (payload.pos() != null && payload.items() != null) {
                        putIndependentCache(payload.pos().toImmutable(), payload.items());
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
                    "fi.dy.masa.minihud.inventory.InventoryCache",
                    "fi.dy.masa.minihud.util.InventoryCache"
            };
            for (String c : cacheClasses) {
                try { minihudCacheClass = Class.forName(c); break; } catch (Throwable ignored) {}
            }
            try {
                if (minihudCacheClass != null) {
                    initMinihudCacheAccessors();
                }
            } catch (Throwable ignored) {}

            String[] senderClasses = {
                    "fi.dy.masa.minihud.network.ClientPacketSender",
                    "fi.dy.masa.minihud.network.PacketSender"
            };
            for (String c : senderClasses) {
                try { minihudSenderClass = Class.forName(c); break; } catch (Throwable ignored) {}
            }
            try {
                if (minihudSenderClass != null) {
                    initMinihudSenderAccessors();
                }
            } catch (Throwable ignored) {}

            minihudChecked = true;
        }
    }

    private static void initMinihudCacheAccessors() {
        List<Field> mapFields = new ArrayList<>();
        List<Method> lookupMethods = new ArrayList<>();

        for (Field field : minihudCacheClass.getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(field.getModifiers()) && Map.class.isAssignableFrom(field.getType())) {
                field.setAccessible(true);
                mapFields.add(field);
            }
        }

        for (Method method : minihudCacheClass.getDeclaredMethods()) {
            if (method.getName().equals("getInstance") &&
                    method.getParameterCount() == 0 &&
                    java.lang.reflect.Modifier.isStatic(method.getModifiers())) {
                method.setAccessible(true);
                minihudCacheInstanceGetter = method;
                continue;
            }

            if (method.getParameterCount() == 1 && method.getParameterTypes()[0] == BlockPos.class) {
                method.setAccessible(true);
                lookupMethods.add(method);
            }
        }

        minihudCacheMapFields = mapFields.toArray(new Field[0]);
        minihudCacheLookupMethods = lookupMethods.toArray(new Method[0]);
    }

    private static void initMinihudSenderAccessors() {
        List<Method> senderMethods = new ArrayList<>();
        for (Method method : minihudSenderClass.getDeclaredMethods()) {
            if (method.getParameterCount() == 1 && method.getParameterTypes()[0] == BlockPos.class) {
                String name = method.getName().toLowerCase();
                if (name.contains("container") || name.contains("inventory") ||
                        name.contains("request") || name.contains("data") || name.contains("sync")) {
                    method.setAccessible(true);
                    senderMethods.add(method);
                }
            }
        }
        minihudSenderMethods = senderMethods.toArray(new Method[0]);
    }

    private static Object getMinihudCacheInstance() {
        if (minihudCacheInstance != null || minihudCacheInstanceGetter == null) {
            return minihudCacheInstance;
        }

        try {
            minihudCacheInstance = minihudCacheInstanceGetter.invoke(null);
        } catch (Throwable ignored) {}
        return minihudCacheInstance;
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
                for (Field field : minihudCacheMapFields) {
                    Map<?, ?> map = (Map<?, ?>) field.get(null);
                    if (map != null) {
                        Object result = map.get(pos);
                        if (result != null) {
                            rememberSlotCount(pos, result);
                            Map<Integer, ItemStack> extracted = extractItemsFromObject(result);
                            if (extracted != null && !extracted.isEmpty()) return extracted;
                        }
                    }
                }

                Object cacheInstance = getMinihudCacheInstance();

                for (Method method : minihudCacheLookupMethods) {
                    boolean isStatic = java.lang.reflect.Modifier.isStatic(method.getModifiers());
                    if (!isStatic && cacheInstance == null) continue;

                    Object result = isStatic ? method.invoke(null, pos) : method.invoke(cacheInstance, pos);
                    if (result != null) {
                        rememberSlotCount(pos, result);
                        Map<Integer, ItemStack> extracted = extractItemsFromObject(result);
                        if (extracted != null && !extracted.isEmpty()) return extracted;
                    }
                }
            } catch (Throwable ignored) {}
        }

        return INDEPENDENT_CACHE.get(pos);
    }

    public static int getCachedSlotCount(BlockPos pos) {
        Integer slotCount = SLOT_COUNT_CACHE.get(pos);
        if (slotCount != null) return slotCount;
        return inferSlotCountFromItems(INDEPENDENT_CACHE.get(pos));
    }

    public static void clearCachedData(BlockPos pos) {
        if (pos == null) return;
        BlockPos key = pos.toImmutable();
        INDEPENDENT_CACHE.remove(key);
        SLOT_COUNT_CACHE.remove(key);
    }

    public static void clearAllCachedData() {
        INDEPENDENT_CACHE.clear();
        SLOT_COUNT_CACHE.clear();
    }

    public static boolean requestData(BlockPos pos) {
        checkMinihud();

        if (minihudSenderClass != null) {
            try {
                for (Method method : minihudSenderMethods) {
                    method.invoke(null, pos);
                    return true;
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
                BlockPos evicted = iterator.next();
                iterator.remove();
                SLOT_COUNT_CACHE.remove(evicted);
            }
        }
        INDEPENDENT_CACHE.put(pos.toImmutable(), items);
        rememberSlotCount(pos, items);
    }

    private static void rememberSlotCount(BlockPos pos, Object inventoryData) {
        int slotCount = inferSlotCountFromObject(inventoryData);
        if (slotCount <= 0) return;
        SLOT_COUNT_CACHE.merge(pos.toImmutable(), slotCount, Math::max);
    }

    private static int inferSlotCountFromObject(Object obj) {
        if (obj == null) return -1;

        if (obj instanceof java.util.Collection<?> list) {
            return normalizeSlotCount(list.size());
        }
        if (obj instanceof ItemStack[] arr) {
            return normalizeSlotCount(arr.length);
        }
        if (obj instanceof Map<?, ?> map) {
            return inferSlotCountFromMap(map);
        }

        int methodCount = inferSlotCountFromMethods(obj);
        if (methodCount > 0) return methodCount;

        try {
            for (Field f : obj.getClass().getDeclaredFields()) {
                f.setAccessible(true);
                Object val = f.get(obj);
                if (val instanceof java.util.Collection<?> list) {
                    int count = normalizeSlotCount(list.size());
                    if (count > 0) return count;
                }
                if (val instanceof ItemStack[] arr) {
                    int count = normalizeSlotCount(arr.length);
                    if (count > 0) return count;
                }
                if (val instanceof Map<?, ?> map) {
                    int count = inferSlotCountFromMap(map);
                    if (count > 0) return count;
                }
            }
        } catch (Throwable ignored) {}

        return -1;
    }

    private static int inferSlotCountFromMethods(Object obj) {
        for (Method method : obj.getClass().getDeclaredMethods()) {
            if (method.getParameterCount() != 0) continue;
            String name = method.getName().toLowerCase();
            if (!name.equals("size") && !name.equals("getsize") &&
                    !name.equals("getinventorysize") && !name.equals("getslotcount") &&
                    !name.equals("slotcount")) {
                continue;
            }

            try {
                method.setAccessible(true);
                Object value = method.invoke(obj);
                if (value instanceof Number number) {
                    int count = normalizeSlotCount(number.intValue());
                    if (count > 0) return count;
                }
            } catch (Throwable ignored) {}
        }
        return -1;
    }

    private static int inferSlotCountFromMap(Map<?, ?> map) {
        if (map == null || map.isEmpty()) return -1;

        int maxSlot = -1;
        for (Object key : map.keySet()) {
            int slot = parseSlotKey(key);
            if (slot > maxSlot) maxSlot = slot;
        }
        if (maxSlot < 0) return -1;
        return normalizeSlotCount(maxSlot + 1);
    }

    private static int inferSlotCountFromItems(Map<Integer, ItemStack> items) {
        if (items == null || items.isEmpty()) return -1;

        int maxSlot = -1;
        for (Integer slot : items.keySet()) {
            if (slot != null && slot > maxSlot) maxSlot = slot;
        }
        if (maxSlot < 0) return -1;
        return normalizeSlotCount(maxSlot + 1);
    }

    private static int parseSlotKey(Object key) {
        if (key instanceof Number number) return number.intValue();
        if (key instanceof String string) {
            try {
                return Integer.parseInt(string);
            } catch (NumberFormatException ignored) {}
        }
        return -1;
    }

    private static int normalizeSlotCount(int rawCount) {
        if (rawCount >= 54) return 54;
        if (rawCount >= 27) return 27;
        return rawCount > 0 ? rawCount : -1;
    }
}

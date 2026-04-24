package com.mimicenzymes.litematicafiller.core;

import com.mimicenzymes.litematicafiller.config.Configs;
import com.mimicenzymes.litematicafiller.network.ServuxSyncHandler;
import fi.dy.masa.litematica.data.EntitiesDataStorage;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtOps;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ConcurrentHashMap;

public class RealContainerCache {
    private static final Map<BlockPos, Map<Integer, ItemStack>> CACHE = new ConcurrentHashMap<>();
    private static final Map<BlockPos, Set<Integer>> LOCK_CACHE = new ConcurrentHashMap<>();
    private static final Map<BlockPos, Map<Integer, ItemStack>> SYNC_SNAPSHOT_CACHE = new ConcurrentHashMap<>();
    private static final Map<BlockPos, Long> SYNC_SNAPSHOT_TIME = new ConcurrentHashMap<>();
    private static BlockPos lastLookedPos = null;
    private static final long SYNC_SNAPSHOT_TTL_MS = 15000L;

    private static final Map<BlockPos, Map<Integer, ItemStack>> NBT_QUERY_CACHE = new ConcurrentHashMap<>();
    private static final Map<Integer, BlockPos> PENDING_NBT_REQUESTS = new ConcurrentHashMap<>();
    private static final Map<BlockPos, Long> LAST_REQUEST_TIME = new ConcurrentHashMap<>();
    private static int transactionCounter = 10000;

    private static final AtomicInteger cacheVersion = new AtomicInteger();

    public static int getCacheVersion() {
        return cacheVersion.get();
    }

    /**
     * 直接返回已缓存的真实容器内容（若存在），优先走 {@link #CACHE}，再退回 {@link #NBT_QUERY_CACHE}。
     * 找不到返回 null。本方法仅暴露内部缓存，不会触发网络请求或回退到 sync snapshot。
     * 之所以提供该 getter 是为了让同包里的状态机避免通过反射读私有字段。
     */
    public static Map<Integer, ItemStack> getReliableCached(BlockPos pos) {
        if (pos == null) return null;
        Map<Integer, ItemStack> items = CACHE.get(pos);
        if (items != null) return items;
        return NBT_QUERY_CACHE.get(pos);
    }

    public static void tick(MinecraftClient client) {
        if (client.world == null || client.player == null) return;

        if (client.world.getTime() % 100 == 0) {
            PENDING_NBT_REQUESTS.clear();
        }

        if (client.currentScreen == null && client.crosshairTarget instanceof BlockHitResult bhr) {
            lastLookedPos = bhr.getBlockPos();
        }

        if (client.currentScreen instanceof HandledScreen<?> screen) {
            updateFromHandler(client, screen.getScreenHandler());
        }
    }

    public static void updateFromScreen(MinecraftClient client, HandledScreen<?> screen) {
        if (screen != null) {
            updateFromHandler(client, screen.getScreenHandler());
        }
    }

    public static void updateFromHandler(MinecraftClient client, ScreenHandler handler) {
        if (handler == null) return;

        if (handler instanceof net.minecraft.screen.PlayerScreenHandler ||
                handler.getClass().getSimpleName().contains("CreativeScreenHandler")) {
            return;
        }

        BlockPos pos = AutoFillerStateMachine.getInstance().getCurrentTaskPos();
        if (pos == null) pos = lastLookedPos;
        if (pos == null) return;

        Map<Integer, ItemStack> items = new HashMap<>();

        net.minecraft.inventory.Inventory primaryInv = null;
        if (!handler.slots.isEmpty()) {
            primaryInv = handler.slots.get(0).inventory;
        }

        for (Slot slot : handler.slots) {
            if (slot.inventory != null && slot.inventory == primaryInv) {
                if (!slot.getStack().isEmpty()) {
                    items.put(slot.getIndex(), slot.getStack().copy());
                }
            }
        }

        BlockState state = client.world.getBlockState(pos);
        BlockPos[] halves = LitematicaContainerReader.getDoubleContainerHalves(client.world, pos, state);

        if (halves != null) {
            CACHE.put(halves[0].toImmutable(), items);
            CACHE.put(halves[1].toImmutable(), items);
        } else {
            CACHE.put(pos.toImmutable(), items);
        }

        if (handler instanceof net.minecraft.screen.CrafterScreenHandler crafterHandler) {
            Set<Integer> locks = new HashSet<>();
            for (int i = 0; i < 9; i++) {
                if (crafterHandler.isSlotDisabled(i)) locks.add(i);
            }
            LOCK_CACHE.put(pos.toImmutable(), locks);
        }

        cacheVersion.incrementAndGet();
    }

    public static Map<Integer, ItemStack> getCachedItems(BlockPos pos) {
        if (CACHE.containsKey(pos)) return CACHE.get(pos);

        var schematicWorld = fi.dy.masa.litematica.world.SchematicWorldHandler.getSchematicWorld();
        if (schematicWorld != null) {
            BlockState state = schematicWorld.getBlockState(pos);
            BlockPos[] halves = LitematicaContainerReader.getDoubleContainerHalves(schematicWorld, pos, state);

            if (halves != null) {
                Map<Integer, ItemStack> combined = getCombinedLitematicaSyncedItems(halves[0], halves[1]);
                if (combined != null) {
                    rememberSyncedData(halves, combined);
                    return combined;
                }

                combined = combineHalves(ServuxSyncHandler.getCachedData(halves[0]), ServuxSyncHandler.getCachedData(halves[1]));
                if (combined != null) {
                    rememberSyncedData(halves, combined);
                    return combined;
                }

                combined = combineHalves(NBT_QUERY_CACHE.get(halves[0]), NBT_QUERY_CACHE.get(halves[1]));
                if (combined != null) return combined;

                Map<Integer, ItemStack> snapshot = getSyncSnapshot(pos);
                if (snapshot != null) {
                    requestContainerData(pos);
                    return snapshot;
                }

                return null;
            }
        }

        Map<Integer, ItemStack> litematicaData = getLitematicaSyncedItems(pos);
        if (litematicaData != null) {
            rememberSyncedData(pos, litematicaData);
            return litematicaData;
        }

        Map<Integer, ItemStack> servuxData = ServuxSyncHandler.getCachedData(pos);
        if (servuxData != null) {
            rememberSyncedData(pos, servuxData);
            return servuxData;
        }

        Map<Integer, ItemStack> snapshot = getSyncSnapshot(pos);
        if (snapshot != null) {
            requestContainerData(pos);
            return snapshot;
        }

        return NBT_QUERY_CACHE.get(pos);
    }

    public static void requestContainerData(BlockPos pos) {
        long now = System.currentTimeMillis();
        if (now - LAST_REQUEST_TIME.getOrDefault(pos, 0L) < 2000) return;

        boolean isDouble = false;
        BlockPos[] halves = null;
        var schematicWorld = fi.dy.masa.litematica.world.SchematicWorldHandler.getSchematicWorld();
        if (schematicWorld != null) {
            BlockState state = schematicWorld.getBlockState(pos);
            halves = LitematicaContainerReader.getDoubleContainerHalves(schematicWorld, pos, state);
            if (halves != null) isDouble = true;
        }

        if (Configs.ENABLE_DATA_SYNC.getBooleanValue()) {
            if (requestLitematicaData(pos, halves, isDouble)) {
                LAST_REQUEST_TIME.put(pos, now);
                return;
            }

            if (isDouble) {
                boolean s1 = ServuxSyncHandler.requestData(halves[0]);
                boolean s2 = ServuxSyncHandler.requestData(halves[1]);
                if (s1 || s2) {
                    LAST_REQUEST_TIME.put(pos, now);
                    return;
                }
            } else {
                if (ServuxSyncHandler.requestData(pos)) {
                    LAST_REQUEST_TIME.put(pos, now);
                    return;
                }
            }
        }

        if (!Configs.ENABLE_OP_NBT_QUERY.getBooleanValue()) return;

        if (PENDING_NBT_REQUESTS.size() > 60) return;

        LAST_REQUEST_TIME.put(pos, now);
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.getNetworkHandler() == null) return;

        if (isDouble) {
            int id1 = transactionCounter++;
            PENDING_NBT_REQUESTS.put(id1, halves[0]);
            client.getNetworkHandler().sendPacket(new net.minecraft.network.packet.c2s.play.QueryBlockNbtC2SPacket(id1, halves[0]));

            int id2 = transactionCounter++;
            PENDING_NBT_REQUESTS.put(id2, halves[1]);
            client.getNetworkHandler().sendPacket(new net.minecraft.network.packet.c2s.play.QueryBlockNbtC2SPacket(id2, halves[1]));
            return;
        }

        int id = transactionCounter++;
        PENDING_NBT_REQUESTS.put(id, pos);
        client.getNetworkHandler().sendPacket(new net.minecraft.network.packet.c2s.play.QueryBlockNbtC2SPacket(id, pos));
    }

    public static void handleNbtResponse(int transactionId, NbtCompound nbt) {
        BlockPos pos = PENDING_NBT_REQUESTS.remove(transactionId);
        if (pos != null && nbt != null) {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.world != null) {
                Map<Integer, ItemStack> items = new HashMap<>();
                if (nbt.contains("Items")) {
                    items = parseNbtInventory(nbt, client.world.getRegistryManager());
                }
                NBT_QUERY_CACHE.put(pos.toImmutable(), items);

                if (nbt.contains("disabled_slots")) {
                    LOCK_CACHE.put(pos.toImmutable(), parseDisabledSlots(nbt));
                }

                cacheVersion.incrementAndGet();
            }
        }
    }

    public static Set<Integer> getCachedLocks(BlockPos pos) {
        Set<Integer> locks = LOCK_CACHE.get(pos);
        if (locks != null) return locks;

        NbtCompound nbt = getLitematicaSyncedNbt(pos);
        if (nbt == null) return null;

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world != null && (nbt.contains("disabled_slots") ||
                client.world.getBlockState(pos).getBlock() instanceof net.minecraft.block.CrafterBlock)) {
            locks = parseDisabledSlots(nbt);
            LOCK_CACHE.put(pos.toImmutable(), locks);
            return locks;
        }

        return null;
    }

    public static void putLock(BlockPos pos, Set<Integer> locks) {
        if (pos == null || locks == null) return;
        LOCK_CACHE.put(pos.toImmutable(), locks);
        cacheVersion.incrementAndGet();
    }

    public static boolean isSatisfied(BlockPos pos, Map<Integer, ItemStack> required) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null) return false;

        BlockState state = client.world.getBlockState(pos);
        boolean isCrafter = state.getBlock() instanceof net.minecraft.block.CrafterBlock;
        if (isCrafter && LitematicaContainerReader.doesCrafterNeedLocking(pos, client)) {
            return false;
        }

        Map<Integer, ItemStack> realItems = getCachedItems(pos);
        if (realItems != null) {
            return checkMapStrict(realItems, required, isCrafter);
        }
        return false;
    }

    private static boolean checkMapStrict(Map<Integer, ItemStack> realItems, Map<Integer, ItemStack> required, boolean isCrafter) {
        if (realItems == null) return false;
        int maxSlot = isCrafter ? 9 : 54;

        for (int i = 0; i < maxSlot; i++) {
            ItemStack real = realItems.getOrDefault(i, ItemStack.EMPTY);
            ItemStack req = (required != null) ? required.getOrDefault(i, ItemStack.EMPTY) : ItemStack.EMPTY;
            if (real.isEmpty() && req.isEmpty()) continue;

            if (real.isEmpty() != req.isEmpty() || !ItemMatcher.isSameItem(real, req) || real.getCount() != req.getCount()) {
                return false;
            }
        }
        return true;
    }

    public static Map<Integer, ItemStack> parseNbtInventory(NbtCompound nbt, RegistryWrapper.WrapperLookup registries) {
        Map<Integer, ItemStack> items = new HashMap<>();
        NbtElement itemsElem = nbt.get("Items");
        if (itemsElem instanceof NbtList list) {
            for (int i = 0; i < list.size(); i++) {
                NbtElement itemElem = list.get(i);
                if (itemElem instanceof NbtCompound itemTag) {
                    int slot = 0;
                    if (itemTag.contains("Slot")) {
                        try { slot = Integer.parseInt(itemTag.get("Slot").toString().replaceAll("[^0-9]", "")) & 255; } catch (Exception ignored) {}
                    }

                    ItemStack stack = ItemStack.EMPTY;
                    try {
                        stack = ItemStack.OPTIONAL_CODEC.parse(registries.getOps(NbtOps.INSTANCE), itemTag).resultOrPartial().orElse(ItemStack.EMPTY);
                    } catch (Exception ignored) {}

                    if (stack.isEmpty() && itemTag.contains("id")) {
                        String idStr = itemTag.get("id").toString().replace("\"", "");
                        net.minecraft.util.Identifier id = net.minecraft.util.Identifier.tryParse(idStr);
                        if (id != null) {
                            net.minecraft.item.Item item = net.minecraft.registry.Registries.ITEM.get(id);
                            if (item != null && item != net.minecraft.item.Items.AIR) {
                                int count = 1;
                                try {
                                    if (itemTag.contains("Count")) count = Integer.parseInt(itemTag.get("Count").toString().replaceAll("[^0-9]", ""));
                                    else if (itemTag.contains("count")) count = Integer.parseInt(itemTag.get("count").toString().replaceAll("[^0-9]", ""));
                                } catch (Exception ignored) {}
                                stack = new ItemStack(item, count);
                            }
                        }
                    }

                    if (!stack.isEmpty()) items.put(slot, stack);
                }
            }
        }
        return items;
    }

    public static Set<Integer> parseDisabledSlots(NbtCompound nbt) {
        Set<Integer> disabledSlots = new HashSet<>();
        if (nbt != null && nbt.contains("disabled_slots")) {
            NbtElement elem = nbt.get("disabled_slots");
            if (elem instanceof NbtList list) {
                for (int i = 0; i < list.size(); i++) {
                    if (list.get(i) instanceof net.minecraft.nbt.AbstractNbtNumber num) {
                        disabledSlots.add(num.intValue());
                    }
                }
            }
            else if (elem instanceof net.minecraft.nbt.NbtIntArray intArray) {
                for (int val : intArray.getIntArray()) {
                    disabledSlots.add(val);
                }
            }
        }
        return disabledSlots;
    }

    public static void clear() {
        CACHE.clear();
        LOCK_CACHE.clear();
        SYNC_SNAPSHOT_CACHE.clear();
        SYNC_SNAPSHOT_TIME.clear();
        NBT_QUERY_CACHE.clear();
        PENDING_NBT_REQUESTS.clear();
        LAST_REQUEST_TIME.clear();
        ServuxSyncHandler.INDEPENDENT_CACHE.clear();
        cacheVersion.incrementAndGet();
    }

    public static void put(BlockPos pos, Map<Integer, ItemStack> items) {
        if (pos == null || items == null) return;
        CACHE.put(pos.toImmutable(), items);
        cacheVersion.incrementAndGet();
    }

    public static void remove(BlockPos pos) {
        if (pos == null) return;

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world != null) {
            BlockState state = client.world.getBlockState(pos);
            BlockPos[] halves = LitematicaContainerReader.getDoubleContainerHalves(client.world, pos, state);
            if (halves != null) {
                CACHE.remove(halves[0]);
                CACHE.remove(halves[1]);
                SYNC_SNAPSHOT_CACHE.remove(halves[0]);
                SYNC_SNAPSHOT_CACHE.remove(halves[1]);
                SYNC_SNAPSHOT_TIME.remove(halves[0]);
                SYNC_SNAPSHOT_TIME.remove(halves[1]);
                NBT_QUERY_CACHE.remove(halves[0]);
                NBT_QUERY_CACHE.remove(halves[1]);
                ServuxSyncHandler.INDEPENDENT_CACHE.remove(halves[0]);
                ServuxSyncHandler.INDEPENDENT_CACHE.remove(halves[1]);
                LAST_REQUEST_TIME.remove(halves[0]);
                LAST_REQUEST_TIME.remove(halves[1]);
            }
        }

        CACHE.remove(pos);
        SYNC_SNAPSHOT_CACHE.remove(pos);
        SYNC_SNAPSHOT_TIME.remove(pos);
        NBT_QUERY_CACHE.remove(pos);
        ServuxSyncHandler.INDEPENDENT_CACHE.remove(pos);
        LAST_REQUEST_TIME.remove(pos);
        cacheVersion.incrementAndGet();
    }

    private static Map<Integer, ItemStack> getLitematicaSyncedItems(BlockPos pos) {
        NbtCompound nbt = getLitematicaSyncedNbt(pos);
        if (nbt == null) return null;

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null) return null;

        if (nbt.contains("disabled_slots") ||
                client.world.getBlockState(pos).getBlock() instanceof net.minecraft.block.CrafterBlock) {
            LOCK_CACHE.put(pos.toImmutable(), parseDisabledSlots(nbt));
        }

        return parseNbtInventory(nbt, client.world.getRegistryManager());
    }

    private static Map<Integer, ItemStack> getCombinedLitematicaSyncedItems(BlockPos rightPos, BlockPos leftPos) {
        NbtCompound rightNbt = getLitematicaSyncedNbt(rightPos);
        NbtCompound leftNbt = getLitematicaSyncedNbt(leftPos);
        if (rightNbt == null || leftNbt == null) return null;

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null) return null;

        Map<Integer, ItemStack> right = parseInventoryAndLocks(rightPos, rightNbt, client);
        Map<Integer, ItemStack> left = parseInventoryAndLocks(leftPos, leftNbt, client);
        return combineHalves(right, left);
    }

    private static NbtCompound getLitematicaSyncedNbt(BlockPos pos) {
        try {
            return EntitiesDataStorage.getInstance().getFromBlockEntityCacheNbt(pos);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Map<Integer, ItemStack> parseInventoryAndLocks(BlockPos pos, NbtCompound nbt, MinecraftClient client) {
        if (nbt.contains("disabled_slots") ||
                client.world.getBlockState(pos).getBlock() instanceof net.minecraft.block.CrafterBlock) {
            LOCK_CACHE.put(pos.toImmutable(), parseDisabledSlots(nbt));
        }

        return parseNbtInventory(nbt, client.world.getRegistryManager());
    }

    private static Map<Integer, ItemStack> combineHalves(Map<Integer, ItemStack> right, Map<Integer, ItemStack> left) {
        if (right == null || left == null) return null;

        Map<Integer, ItemStack> combined = new HashMap<>();
        combined.putAll(right);
        left.forEach((k, v) -> combined.put(k + 27, v));
        return combined;
    }

    private static void rememberSyncedData(BlockPos pos, Map<Integer, ItemStack> items) {
        SYNC_SNAPSHOT_CACHE.put(pos.toImmutable(), new HashMap<>(items));
        SYNC_SNAPSHOT_TIME.put(pos.toImmutable(), System.currentTimeMillis());
    }

    private static void rememberSyncedData(BlockPos[] halves, Map<Integer, ItemStack> items) {
        Map<Integer, ItemStack> snapshot = new HashMap<>(items);
        SYNC_SNAPSHOT_CACHE.put(halves[0].toImmutable(), snapshot);
        SYNC_SNAPSHOT_CACHE.put(halves[1].toImmutable(), snapshot);
        long now = System.currentTimeMillis();
        SYNC_SNAPSHOT_TIME.put(halves[0].toImmutable(), now);
        SYNC_SNAPSHOT_TIME.put(halves[1].toImmutable(), now);
    }

    private static Map<Integer, ItemStack> getSyncSnapshot(BlockPos pos) {
        Long seenAt = SYNC_SNAPSHOT_TIME.get(pos);
        if (seenAt == null) return null;

        long age = System.currentTimeMillis() - seenAt;
        if (age > SYNC_SNAPSHOT_TTL_MS || age < 0L) {
            SYNC_SNAPSHOT_TIME.remove(pos);
            SYNC_SNAPSHOT_CACHE.remove(pos);
            return null;
        }

        return SYNC_SNAPSHOT_CACHE.get(pos);
    }

    private static boolean requestLitematicaData(BlockPos pos, BlockPos[] halves, boolean isDouble) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null || !isLitematicaSyncAvailable()) return false;

        try {
            EntitiesDataStorage storage = EntitiesDataStorage.getInstance();

            if (isDouble) {
                storage.requestBlockEntity(client.world, halves[0]);
                storage.requestBlockEntity(client.world, halves[1]);
            } else {
                storage.requestBlockEntity(client.world, pos);
            }

            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isLitematicaSyncAvailable() {
        try {
            return fi.dy.masa.litematica.config.Configs.Generic.ENTITY_DATA_SYNC.getBooleanValue() ||
                    fi.dy.masa.litematica.config.Configs.Generic.ENTITY_DATA_SYNC_BACKUP.getBooleanValue();
        } catch (Throwable ignored) {
            return false;
        }
    }
}

package com.mimicenzymes.litematicafiller.core;

import com.mimicenzymes.litematicafiller.config.Configs;
import com.mimicenzymes.litematicafiller.network.ServuxSyncHandler;
import com.mimicenzymes.litematicafiller.tool.ContainerToolStateMachine;
import fi.dy.masa.litematica.data.EntitiesDataStorage;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.item.ItemStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.core.BlockPos;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class RealContainerCache {
    private static final long CACHE_TTL_MS = 300000L;
    private static final int MAX_CACHE_ENTRIES = 2048;
    private static final int MAX_PENDING_NBT_REQUESTS = 2048;
    private static final Map<BlockPos, Map<Integer, ItemStack>> CACHE = new ConcurrentHashMap<>();
    private static final Map<BlockPos, Set<Integer>> LOCK_CACHE = new ConcurrentHashMap<>();
    private static final Map<BlockPos, Integer> SLOT_COUNT_CACHE = new ConcurrentHashMap<>();
    private static final Map<BlockPos, Map<Integer, ItemStack>> SYNC_SNAPSHOT_CACHE = new ConcurrentHashMap<>();
    private static final Map<BlockPos, Long> SYNC_SNAPSHOT_TIME = new ConcurrentHashMap<>();
    private static final Map<BlockPos, Long> CACHE_TIME = new ConcurrentHashMap<>();
    private static final Map<BlockPos, BlockState> BLOCK_STATE_CACHE = new ConcurrentHashMap<>();
    private static BlockPos lastLookedPos = null;
    private static final long SYNC_SNAPSHOT_TTL_MS = 15000L;
    private static AbstractContainerMenu lastObservedHandler = null;
    private static int lastObservedSyncId = Integer.MIN_VALUE;
    private static long lastObservedSignature = Long.MIN_VALUE;
    private static long lastObservedTick = Long.MIN_VALUE;

    private static final Map<BlockPos, Map<Integer, ItemStack>> NBT_QUERY_CACHE = new ConcurrentHashMap<>();
    private static final Map<Integer, BlockPos> PENDING_NBT_REQUESTS = new ConcurrentHashMap<>();
    private static final Map<BlockPos, Long> LAST_REQUEST_TIME = new ConcurrentHashMap<>();
    private static int transactionCounter = 10000;

    private static int cacheVersion = 0;

    public static int getCacheVersion() {
        return cacheVersion;
    }

    public static void tick(Minecraft client) {
        if (client.level == null || client.player == null) return;
        boolean activeOperation = AutoFillerStateMachine.getInstance().isWorking() || ContainerToolStateMachine.getInstance().isWorking();
        boolean hasConsumer = Configs.HIGHLIGHT_CONTAINERS.getBooleanValue() ||
                Configs.WORKING_STATE.getBooleanValue() ||
                activeOperation ||
                Configs.TOOL_ENABLED.getBooleanValue();
        if (!Configs.ENABLE_MOD.getBooleanValue() || !hasConsumer) {
            lastObservedHandler = null;
            lastObservedSyncId = Integer.MIN_VALUE;
            lastObservedSignature = Long.MIN_VALUE;
            lastObservedTick = Long.MIN_VALUE;
            return;
        }

        if (client.level.getGameTime() % 100 == 0) {
            PENDING_NBT_REQUESTS.clear();
        }

        if (client.level.getGameTime() % 200 == 0) {
            cleanupExpiredCache();
        }

        if (client.screen == null && client.hitResult instanceof BlockHitResult bhr) {
            lastLookedPos = bhr.getBlockPos();
        }

        if (client.screen instanceof AbstractContainerScreen<?> screen) {
            updateFromHandlerIfNeeded(client, screen.getMenu());
        } else {
            lastObservedHandler = null;
            lastObservedSyncId = Integer.MIN_VALUE;
            lastObservedSignature = Long.MIN_VALUE;
            lastObservedTick = Long.MIN_VALUE;
        }
    }

    public static void updateFromScreen(Minecraft client, AbstractContainerScreen<?> screen) {
        if (screen != null) {
            updateFromHandler(client, screen.getMenu());
        }
    }

    public static void updateFromHandler(Minecraft client, AbstractContainerMenu handler) {
        if (handler == null) return;

        if (shouldIgnoreHandler(handler)) {
            return;
        }

        updateFromHandlerAndGetSignature(client, handler);
    }

    private static long updateFromHandlerAndGetSignature(Minecraft client, AbstractContainerMenu handler) {
        BlockPos pos = AutoFillerStateMachine.getInstance().getCurrentTaskPos();
        if (pos == null) pos = lastLookedPos;
        if (pos == null) return Long.MIN_VALUE;

        Map<Integer, ItemStack> items = new HashMap<>();

        net.minecraft.world.Container primaryInv = null;
        if (!handler.slots.isEmpty()) {
            primaryInv = handler.slots.get(0).container;
        }
        long signature = 0xcbf29ce484222325L;
        if (primaryInv != null) {
            signature = mix(signature, primaryInv.getContainerSize());
        }

        for (Slot slot : handler.slots) {
            if (slot.container != null && slot.container == primaryInv) {
                ItemStack stack = slot.getItem();
                if (!stack.isEmpty()) {
                    items.put(slot.getContainerSlot(), stack.copy());
                    signature = mix(signature, slot.getContainerSlot());
                    signature = mix(signature, stack.getCount());
                    signature = mix(signature, ItemStack.hashItemAndComponents(stack));
                }
            }
        }

        int slotCount = primaryInv != null ? primaryInv.getContainerSize() : inferSlotCount(items);
        BlockState state = client.level.getBlockState(pos);
        BlockPos[] halves = LitematicaContainerReader.getDoubleContainerHalves(client.level, pos, state, slotCount);

        boolean changed;
        if (halves != null) {
            changed = putCachedItemsIfChanged(halves[0].immutable(), items);
            changed |= putCachedItemsIfChanged(halves[1].immutable(), items);
            changed |= putSlotCountIfChanged(halves[0], slotCount);
            changed |= putSlotCountIfChanged(halves[1], slotCount);
            rememberSyncedData(halves, items);
        } else {
            changed = putCachedItemsIfChanged(pos.immutable(), items);
            changed |= putSlotCountIfChanged(pos, slotCount);
            rememberSyncedData(pos, items);
        }

        if (handler instanceof net.minecraft.world.inventory.CrafterMenu crafterHandler) {
            Set<Integer> locks = new HashSet<>();
            int disabledMask = 0;
            for (int i = 0; i < 9; i++) {
                if (crafterHandler.isSlotDisabled(i)) {
                    locks.add(i);
                    disabledMask |= 1 << i;
                }
            }
            signature = mix(signature, disabledMask);
            Set<Integer> previousLocks = LOCK_CACHE.put(pos.immutable(), locks);
            changed |= !locks.equals(previousLocks);
        }

        if (changed) {
            cacheVersion++;
        }

        return signature;
    }

    private static void updateFromHandlerIfNeeded(Minecraft client, AbstractContainerMenu handler) {
        if (handler == null || client.level == null) return;
        if (shouldIgnoreHandler(handler)) {
            lastObservedHandler = null;
            lastObservedSyncId = Integer.MIN_VALUE;
            lastObservedSignature = Long.MIN_VALUE;
            lastObservedTick = Long.MIN_VALUE;
            return;
        }

        boolean activeOperation = AutoFillerStateMachine.getInstance().isWorking() || ContainerToolStateMachine.getInstance().isWorking();
        long worldTime = client.level.getGameTime();
        boolean newHandler = handler != lastObservedHandler || handler.containerId != lastObservedSyncId;
        int intervalTicks = activeOperation ? 1 : 4;

        if (!newHandler && worldTime - lastObservedTick < intervalTicks) {
            return;
        }

        lastObservedTick = worldTime;
        if (newHandler) {
            lastObservedHandler = handler;
            lastObservedSyncId = handler.containerId;
            long signature = updateFromHandlerAndGetSignature(client, handler);
            lastObservedSignature = signature != Long.MIN_VALUE ? signature : computeHandlerSignature(handler, client);
            return;
        }

        long signature = computeHandlerSignature(handler, client);
        if (signature == lastObservedSignature) {
            return;
        }

        lastObservedHandler = handler;
        lastObservedSyncId = handler.containerId;
        long updatedSignature = updateFromHandlerAndGetSignature(client, handler);
        lastObservedSignature = updatedSignature != Long.MIN_VALUE ? updatedSignature : signature;
    }

    private static boolean shouldIgnoreHandler(AbstractContainerMenu handler) {
        return handler instanceof net.minecraft.world.inventory.InventoryMenu ||
                handler.getClass().getSimpleName().contains("CreativeAbstractContainerMenu");
    }

    private static long computeHandlerSignature(AbstractContainerMenu handler, Minecraft client) {
        if (handler == null) return 0L;

        net.minecraft.world.Container primaryInv = null;
        if (!handler.slots.isEmpty()) {
            primaryInv = handler.slots.get(0).container;
        }

        long hash = 0xcbf29ce484222325L;
        if (primaryInv != null) {
            hash = mix(hash, primaryInv.getContainerSize());
        }

        for (Slot slot : handler.slots) {
            if (slot.container == null || slot.container != primaryInv) continue;

            ItemStack stack = slot.getItem();
            if (stack.isEmpty()) continue;

            hash = mix(hash, slot.getContainerSlot());
            hash = mix(hash, stack.getCount());
            hash = mix(hash, ItemStack.hashItemAndComponents(stack));
        }

        if (handler instanceof net.minecraft.world.inventory.CrafterMenu crafterHandler) {
            int disabledMask = 0;
            for (int i = 0; i < 9; i++) {
                if (crafterHandler.isSlotDisabled(i)) {
                    disabledMask |= 1 << i;
                }
            }
            hash = mix(hash, disabledMask);
        }

        return hash;
    }

    private static long mix(long hash, int value) {
        hash ^= value;
        return hash * 0x100000001b3L;
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

                Map<Integer, ItemStack> rightServux = ServuxSyncHandler.getCachedData(halves[0]);
                Map<Integer, ItemStack> leftServux = ServuxSyncHandler.getCachedData(halves[1]);
                combined = combineHalves(rightServux, leftServux);
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
        requestContainerData(pos, 2000L);
    }

    public static void requestContainerData(BlockPos pos, long minIntervalMs) {
        requestContainerData(pos, minIntervalMs, false);
    }

    public static void requestContainerData(BlockPos pos, long minIntervalMs, boolean preferOpQuery) {
        long now = System.currentTimeMillis();
        if (now - LAST_REQUEST_TIME.getOrDefault(pos, 0L) < minIntervalMs) return;

        boolean isDouble = false;
        BlockPos[] halves = null;
        var schematicWorld = fi.dy.masa.litematica.world.SchematicWorldHandler.getSchematicWorld();
        if (schematicWorld != null) {
            BlockState state = schematicWorld.getBlockState(pos);
            halves = LitematicaContainerReader.getDoubleContainerHalves(schematicWorld, pos, state);
            if (halves != null) isDouble = true;
        }

        if (preferOpQuery && requestOpNbtData(pos, halves, isDouble, now)) {
            return;
        }

        boolean requested = false;

        if (Configs.ENABLE_DATA_SYNC.getBooleanValue()) {
            requested |= requestLitematicaData(pos, halves, isDouble);
            if (isDouble) {
                boolean s1 = ServuxSyncHandler.requestData(halves[0]);
                boolean s2 = ServuxSyncHandler.requestData(halves[1]);
                requested |= s1 || s2;
            } else {
                requested |= ServuxSyncHandler.requestData(pos);
            }
        }

        if (requested) {
            LAST_REQUEST_TIME.put(pos, now);
        }

        if (requestOpNbtData(pos, halves, isDouble, now)) {
            return;
        }
    }

    private static boolean requestOpNbtData(BlockPos pos, BlockPos[] halves, boolean isDouble, long now) {
        if (!Configs.ENABLE_OP_NBT_QUERY.getBooleanValue()) return false;

        int requestCount = isDouble ? 2 : 1;
        if (PENDING_NBT_REQUESTS.size() + requestCount > MAX_PENDING_NBT_REQUESTS) return false;

        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.getConnection() == null) return false;

        LAST_REQUEST_TIME.put(pos, now);
        if (isDouble) {
            int id1 = transactionCounter++;
            PENDING_NBT_REQUESTS.put(id1, halves[0]);
            client.getConnection().send(new net.minecraft.network.protocol.game.ServerboundBlockEntityTagQueryPacket(id1, halves[0]));

            int id2 = transactionCounter++;
            PENDING_NBT_REQUESTS.put(id2, halves[1]);
            client.getConnection().send(new net.minecraft.network.protocol.game.ServerboundBlockEntityTagQueryPacket(id2, halves[1]));
            return true;
        }

        int id = transactionCounter++;
        PENDING_NBT_REQUESTS.put(id, pos);
        client.getConnection().send(new net.minecraft.network.protocol.game.ServerboundBlockEntityTagQueryPacket(id, pos));
        return true;
    }

    public static void handleNbtResponse(int transactionId, CompoundTag nbt) {
        BlockPos pos = PENDING_NBT_REQUESTS.remove(transactionId);
        if (pos != null && nbt != null) {
            Minecraft client = Minecraft.getInstance();
            if (client.level != null) {
                boolean changed = false;

                Map<Integer, ItemStack> items = parseNbtInventory(nbt, client.level.registryAccess());
                NBT_QUERY_CACHE.put(pos.immutable(), items);
                putSlotCount(pos, inferSlotCount(nbt, items));
                CACHE_TIME.put(pos.immutable(), System.currentTimeMillis());
                changed = true;

                if (nbt.contains("disabled_slots")) {
                    LOCK_CACHE.put(pos.immutable(), parseDisabledSlots(nbt));
                    changed = true;
                }

                if (changed) {
                    cacheVersion++;
                }
            }
        }
    }

    public static Set<Integer> getCachedLocks(BlockPos pos) {
        Set<Integer> locks = LOCK_CACHE.get(pos);
        if (locks != null) return locks;

        CompoundTag nbt = getLitematicaSyncedNbt(pos);
        if (nbt == null) return null;

        Minecraft client = Minecraft.getInstance();
        if (client.level != null && (nbt.contains("disabled_slots") ||
                client.level.getBlockState(pos).getBlock() instanceof net.minecraft.world.level.block.CrafterBlock)) {
            locks = parseDisabledSlots(nbt);
            LOCK_CACHE.put(pos.immutable(), locks);
            return locks;
        }

        return null;
    }

    public static void putLock(BlockPos pos, Set<Integer> locks) {
        if (pos == null || locks == null) return;
        LOCK_CACHE.put(pos.immutable(), locks);
        cacheVersion++;
    }

    public static boolean isSatisfied(BlockPos pos, Map<Integer, ItemStack> required) {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null) return false;

        BlockState state = client.level.getBlockState(pos);
        boolean isCrafter = state.getBlock() instanceof net.minecraft.world.level.block.CrafterBlock;
        Set<Integer> ignoredSlots = LitematicaContainerReader.getIgnoredSlots(pos, client.level.registryAccess());
        if (isCrafter && LitematicaContainerReader.doesCrafterNeedLocking(pos, client)) {
            return false;
        }

        Map<Integer, ItemStack> realItems = getCachedItems(pos);
        if (realItems != null) {
            return checkMapStrict(realItems, required, ignoredSlots, isCrafter);
        }
        return false;
    }

    private static boolean checkMapStrict(Map<Integer, ItemStack> realItems, Map<Integer, ItemStack> required, Set<Integer> ignoredSlots, boolean isCrafter) {
        if (realItems == null) return false;
        int maxSlot = isCrafter ? 9 : 54;

        for (int i = 0; i < maxSlot; i++) {
            if (ignoredSlots != null && ignoredSlots.contains(i)) continue;

            ItemStack real = realItems.getOrDefault(i, ItemStack.EMPTY);
            ItemStack req = (required != null) ? required.getOrDefault(i, ItemStack.EMPTY) : ItemStack.EMPTY;
            if (real.isEmpty() && req.isEmpty()) continue;

            if (real.isEmpty() != req.isEmpty() || !ItemMatcher.isSameItem(real, req) || real.getCount() != req.getCount()) {
                return false;
            }
        }
        return true;
    }

    public static Map<Integer, ItemStack> parseNbtInventory(CompoundTag nbt, HolderLookup.Provider registries) {
        Map<Integer, ItemStack> items = new HashMap<>();
        Tag itemsElem = nbt.get("Items");
        if (itemsElem instanceof ListTag list) {
            for (int i = 0; i < list.size(); i++) {
                Tag itemElem = list.get(i);
                if (itemElem instanceof CompoundTag itemTag) {
                    int slot = 0;
                    if (itemTag.contains("Slot")) {
                        try { slot = Integer.parseInt(itemTag.get("Slot").toString().replaceAll("[^0-9]", "")) & 255; } catch (Exception ignored) {}
                    }

                    ItemStack stack = ItemStack.EMPTY;
                    try {
                        stack = ItemStack.OPTIONAL_CODEC.parse(NbtOps.INSTANCE, itemTag).resultOrPartial().orElse(ItemStack.EMPTY);
                    } catch (Exception ignored) {}

                    if (stack.isEmpty() && itemTag.contains("id")) {
                        String idStr = itemTag.get("id").toString().replace("\"", "");
                        net.minecraft.resources.Identifier id = net.minecraft.resources.Identifier.tryParse(idStr);
                        if (id != null) {
                            net.minecraft.world.item.Item item = net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(id);
                            if (item != null && item != net.minecraft.world.item.Items.AIR) {
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

    public static Set<Integer> parseDisabledSlots(CompoundTag nbt) {
        Set<Integer> disabledSlots = new HashSet<>();
        if (nbt != null && nbt.contains("disabled_slots")) {
            Tag elem = nbt.get("disabled_slots");
            if (elem instanceof ListTag list) {
                for (int i = 0; i < list.size(); i++) {
                    if (list.get(i) instanceof net.minecraft.nbt.NumericTag num) {
                        disabledSlots.add(num.intValue());
                    }
                }
            }
            else if (elem instanceof net.minecraft.nbt.IntArrayTag intArray) {
                for (int val : intArray.getAsIntArray()) {
                    disabledSlots.add(val);
                }
            }
        }
        return disabledSlots;
    }

    public static void clear() {
        CACHE.clear();
        LOCK_CACHE.clear();
        SLOT_COUNT_CACHE.clear();
        SYNC_SNAPSHOT_CACHE.clear();
        SYNC_SNAPSHOT_TIME.clear();
        CACHE_TIME.clear();
        BLOCK_STATE_CACHE.clear();
        NBT_QUERY_CACHE.clear();
        PENDING_NBT_REQUESTS.clear();
        LAST_REQUEST_TIME.clear();
        ServuxSyncHandler.INDEPENDENT_CACHE.clear();
        cacheVersion++;
    }

    public static void put(BlockPos pos, Map<Integer, ItemStack> items) {
        if (pos == null || items == null) return;
        putCachedItems(pos.immutable(), items);
        cacheVersion++;
    }

    public static void putPredicted(BlockPos pos, Map<Integer, ItemStack> items) {
        if (pos == null || items == null) return;

        Minecraft client = Minecraft.getInstance();
        if (client.level != null) {
            BlockState state = client.level.getBlockState(pos);
            BlockPos[] halves = LitematicaContainerReader.getDoubleContainerHalves(client.level, pos, state);
            if (halves != null) {
                Map<Integer, ItemStack> snapshot = copyItems(items);
                putCachedItems(halves[0].immutable(), snapshot);
                putCachedItems(halves[1].immutable(), snapshot);
                rememberSyncedData(halves, snapshot);
                cacheVersion++;
                return;
            }
        }

        Map<Integer, ItemStack> snapshot = copyItems(items);
        putCachedItems(pos.immutable(), snapshot);
        rememberSyncedData(pos, snapshot);
        cacheVersion++;
    }

    public static void remove(BlockPos pos) {
        if (pos == null) return;

        Minecraft client = Minecraft.getInstance();
        if (client.level != null) {
            BlockState state = client.level.getBlockState(pos);
            BlockPos[] halves = LitematicaContainerReader.getDoubleContainerHalves(client.level, pos, state);
            if (halves != null) {
                removeCachedDataOnly(halves[0]);
                removeCachedDataOnly(halves[1]);
            }
        }

        removeCachedDataOnly(pos);
        cacheVersion++;
    }

    public static void observeBlockState(BlockPos pos, BlockState state) {
        if (pos == null || state == null) return;

        BlockPos key = pos.immutable();
        BlockState previous = BLOCK_STATE_CACHE.put(key, state);
        if (previous != null && hasMeaningfulBlockStateChange(previous, state)) {
            removeCachedDataOnly(key);
            cacheVersion++;
        }
    }

    private static boolean hasMeaningfulBlockStateChange(BlockState previous, BlockState current) {
        if (previous.equals(current)) return false;

        if (previous.is(net.minecraft.world.level.block.Blocks.BARREL) &&
                current.is(net.minecraft.world.level.block.Blocks.BARREL) &&
                previous.getValue(net.minecraft.world.level.block.BarrelBlock.FACING) == current.getValue(net.minecraft.world.level.block.BarrelBlock.FACING)) {
            return false;
        }

        return true;
    }

    private static void removeCachedDataOnly(BlockPos pos) {
        CACHE.remove(pos);
        LOCK_CACHE.remove(pos);
        SLOT_COUNT_CACHE.remove(pos);
        SYNC_SNAPSHOT_CACHE.remove(pos);
        SYNC_SNAPSHOT_TIME.remove(pos);
        NBT_QUERY_CACHE.remove(pos);
        CACHE_TIME.remove(pos);
        ServuxSyncHandler.INDEPENDENT_CACHE.remove(pos);
        LAST_REQUEST_TIME.remove(pos);
    }

    private static Map<Integer, ItemStack> getLitematicaSyncedItems(BlockPos pos) {
        CompoundTag nbt = getLitematicaSyncedNbt(pos);
        if (nbt == null) return null;

        Minecraft client = Minecraft.getInstance();
        if (client.level == null) return null;

        boolean hasItems = nbt.contains("Items");
        if (nbt.contains("disabled_slots") ||
                client.level.getBlockState(pos).getBlock() instanceof net.minecraft.world.level.block.CrafterBlock) {
            LOCK_CACHE.put(pos.immutable(), parseDisabledSlots(nbt));
        }

        return parseNbtInventory(nbt, client.level.registryAccess());
    }

    private static Map<Integer, ItemStack> getCombinedLitematicaSyncedItems(BlockPos rightPos, BlockPos leftPos) {
        CompoundTag rightNbt = getLitematicaSyncedNbt(rightPos);
        CompoundTag leftNbt = getLitematicaSyncedNbt(leftPos);
        if (rightNbt == null || leftNbt == null) return null;

        Minecraft client = Minecraft.getInstance();
        if (client.level == null) return null;

        Map<Integer, ItemStack> right = parseInventoryAndLocks(rightPos, rightNbt, client);
        Map<Integer, ItemStack> left = parseInventoryAndLocks(leftPos, leftNbt, client);
        return combineHalves(right, left);
    }

    private static CompoundTag getLitematicaSyncedNbt(BlockPos pos) {
        try {
            return EntitiesDataStorage.getInstance().getFromBlockEntityCacheNbt(pos);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Map<Integer, ItemStack> parseInventoryAndLocks(BlockPos pos, CompoundTag nbt, Minecraft client) {
        boolean hasItems = nbt.contains("Items");

        if (nbt.contains("disabled_slots") ||
                client.level.getBlockState(pos).getBlock() instanceof net.minecraft.world.level.block.CrafterBlock) {
            LOCK_CACHE.put(pos.immutable(), parseDisabledSlots(nbt));
        }

        return parseNbtInventory(nbt, client.level.registryAccess());
    }

    private static Map<Integer, ItemStack> combineHalves(Map<Integer, ItemStack> right, Map<Integer, ItemStack> left) {
        if (right == null || left == null) return null;

        Map<Integer, ItemStack> combined = new HashMap<>();
        combined.putAll(right);
        left.forEach((k, v) -> combined.put(k + 27, v));
        return combined;
    }

    private static void rememberSyncedData(BlockPos pos, Map<Integer, ItemStack> items) {
        SYNC_SNAPSHOT_CACHE.put(pos.immutable(), new HashMap<>(items));
        SYNC_SNAPSHOT_TIME.put(pos.immutable(), System.currentTimeMillis());
    }

    private static void rememberSyncedData(BlockPos[] halves, Map<Integer, ItemStack> items) {
        Map<Integer, ItemStack> snapshot = new HashMap<>(items);
        SYNC_SNAPSHOT_CACHE.put(halves[0].immutable(), snapshot);
        SYNC_SNAPSHOT_CACHE.put(halves[1].immutable(), snapshot);
        long now = System.currentTimeMillis();
        SYNC_SNAPSHOT_TIME.put(halves[0].immutable(), now);
        SYNC_SNAPSHOT_TIME.put(halves[1].immutable(), now);
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
        Minecraft client = Minecraft.getInstance();
        if (client.level == null || !isLitematicaSyncAvailable()) return false;

        try {
            EntitiesDataStorage storage = EntitiesDataStorage.getInstance();

            if (isDouble) {
                storage.requestBlockEntity(client.level, halves[0]);
                storage.requestBlockEntity(client.level, halves[1]);
            } else {
                storage.requestBlockEntity(client.level, pos);
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

    private static void putCachedItems(BlockPos pos, Map<Integer, ItemStack> items) {
        evictIfNeeded();
        CACHE.put(pos, items);
        CACHE_TIME.put(pos, System.currentTimeMillis());
    }

    private static boolean putCachedItemsIfChanged(BlockPos pos, Map<Integer, ItemStack> items) {
        evictIfNeeded();
        Map<Integer, ItemStack> snapshot = copyItems(items);
        Map<Integer, ItemStack> previous = CACHE.get(pos);
        boolean changed = !sameItems(previous, snapshot);
        if (changed) {
            CACHE.put(pos, snapshot);
        }
        CACHE_TIME.put(pos, System.currentTimeMillis());
        return changed;
    }

    private static boolean putSlotCountIfChanged(BlockPos pos, int slotCount) {
        if (pos == null || slotCount <= 0) return false;
        Integer previous = SLOT_COUNT_CACHE.put(pos.immutable(), slotCount);
        return previous == null || previous != slotCount;
    }

    private static boolean sameItems(Map<Integer, ItemStack> first, Map<Integer, ItemStack> second) {
        if (first == second) return true;
        if (first == null || second == null || first.size() != second.size()) return false;
        for (Map.Entry<Integer, ItemStack> entry : second.entrySet()) {
            ItemStack a = first.getOrDefault(entry.getKey(), ItemStack.EMPTY);
            ItemStack b = entry.getValue();
            if (a.getCount() != b.getCount() || !ItemMatcher.isSameItem(a, b)) {
                return false;
            }
        }
        return true;
    }

    private static Map<Integer, ItemStack> copyItems(Map<Integer, ItemStack> items) {
        Map<Integer, ItemStack> copy = new HashMap<>();
        for (Map.Entry<Integer, ItemStack> entry : items.entrySet()) {
            if (entry.getValue() != null && !entry.getValue().isEmpty()) {
                copy.put(entry.getKey(), entry.getValue().copy());
            }
        }
        return copy;
    }

    private static void cleanupExpiredCache() {
        long now = System.currentTimeMillis();
        CACHE_TIME.entrySet().removeIf(entry -> now - entry.getValue() > CACHE_TTL_MS);
        CACHE.keySet().removeIf(pos -> !CACHE_TIME.containsKey(pos));
        LOCK_CACHE.keySet().removeIf(pos -> !CACHE_TIME.containsKey(pos));
        NBT_QUERY_CACHE.keySet().removeIf(pos -> !CACHE_TIME.containsKey(pos));
        LAST_REQUEST_TIME.keySet().removeIf(pos -> !CACHE_TIME.containsKey(pos));
    }

    private static void evictIfNeeded() {
        if (CACHE.size() < MAX_CACHE_ENTRIES) {
            return;
        }

        BlockPos oldest = null;
        long oldestTime = Long.MAX_VALUE;
        for (Map.Entry<BlockPos, Long> entry : CACHE_TIME.entrySet()) {
            if (entry.getValue() < oldestTime) {
                oldestTime = entry.getValue();
                oldest = entry.getKey();
            }
        }

        if (oldest != null) {
            CACHE.remove(oldest);
            LOCK_CACHE.remove(oldest);
            SLOT_COUNT_CACHE.remove(oldest);
            NBT_QUERY_CACHE.remove(oldest);
            LAST_REQUEST_TIME.remove(oldest);
            CACHE_TIME.remove(oldest);
        }
    }

    public static int getKnownSlotCount(BlockPos pos) {
        if (pos == null) return -1;

        Integer slotCount = SLOT_COUNT_CACHE.get(pos);
        if (slotCount != null) return slotCount;

        Map<Integer, ItemStack> cached = CACHE.get(pos);
        if (cached != null) return inferSlotCount(cached);

        Map<Integer, ItemStack> nbt = NBT_QUERY_CACHE.get(pos);
        if (nbt != null) return inferSlotCount(nbt);

        Map<Integer, ItemStack> servux = ServuxSyncHandler.getCachedData(pos);
        if (servux != null) return inferSlotCount(servux);

        Map<Integer, ItemStack> snapshot = getSyncSnapshot(pos);
        if (snapshot != null) return inferSlotCount(snapshot);

        return -1;
    }

    private static void putSlotCount(BlockPos pos, int slotCount) {
        if (pos == null || slotCount <= 0) return;
        SLOT_COUNT_CACHE.put(pos.immutable(), slotCount);
    }

    private static int inferSlotCount(CompoundTag nbt, Map<Integer, ItemStack> items) {
        int inferred = inferSlotCount(items);
        if (nbt != null && nbt.contains("Items")) {
            inferred = Math.max(inferred, inferSlotCountFromItemsNbt(nbt));
        }
        return inferred;
    }

    private static int inferSlotCount(Map<Integer, ItemStack> items) {
        if (items == null || items.isEmpty()) return -1;

        int maxSlot = -1;
        for (Integer slot : items.keySet()) {
            if (slot != null && slot > maxSlot) {
                maxSlot = slot;
            }
        }

        if (maxSlot >= 27) return 54;
        if (maxSlot >= 0) return 27;
        return -1;
    }

    private static int inferSlotCountFromItemsNbt(CompoundTag nbt) {
        Tag itemsElem = nbt.get("Items");
        if (!(itemsElem instanceof ListTag list)) return -1;

        int maxSlot = -1;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i) instanceof CompoundTag itemTag && itemTag.contains("Slot")) {
                try {
                    int slot = Integer.parseInt(itemTag.get("Slot").toString().replaceAll("[^0-9]", "")) & 255;
                    if (slot > maxSlot) maxSlot = slot;
                } catch (Exception ignored) {}
            }
        }

        if (maxSlot >= 27) return 54;
        if (maxSlot >= 0) return 27;
        return -1;
    }
}

package com.mimicenzymes.litematicafiller.core;

import com.mimicenzymes.litematicafiller.config.Configs;
import com.mimicenzymes.litematicafiller.filter.ContainerBlockFilter;
import com.mimicenzymes.litematicafiller.materials.FillMaterialCalculator;
import com.mimicenzymes.litematicafiller.network.ServuxSyncHandler;
import com.mimicenzymes.litematicafiller.tool.ContainerToolStateMachine;
import fi.dy.masa.litematica.data.EntityDataManager;
import fi.dy.masa.litematica.gui.GuiMaterialList;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.CompoundContainer;
import net.minecraft.world.Container;
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
import net.minecraft.world.level.Level;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class RealContainerCache {
    private static final long CACHE_TTL_MS = 300000L;
    private static final int MAX_PENDING_NBT_REQUESTS = 2048;
    private static final Map<BlockPos, Map<Integer, ItemStack>> CACHE = new ConcurrentHashMap<>();
    private static final Map<BlockPos, Set<Integer>> LOCK_CACHE = new ConcurrentHashMap<>();
    private static final Map<BlockPos, Integer> SLOT_COUNT_CACHE = new ConcurrentHashMap<>();
    private static final Map<BlockPos, Map<Integer, ItemStack>> SYNC_SNAPSHOT_CACHE = new ConcurrentHashMap<>();
    private static final Map<BlockPos, Long> SYNC_SNAPSHOT_TIME = new ConcurrentHashMap<>();
    private static final Map<BlockPos, Long> CACHE_TIME = new ConcurrentHashMap<>();
    private static final Map<BlockPos, BlockState> BLOCK_STATE_CACHE = new ConcurrentHashMap<>();
    private static final Map<BlockPos, BlockEntity> BLOCK_ENTITY_CACHE = new ConcurrentHashMap<>();
    private static final Map<BlockPos, Long> INVALIDATED_ENTITY_TIME = new ConcurrentHashMap<>();
    private static final Set<BlockPos> CHANGED_POSITIONS = ConcurrentHashMap.newKeySet();
    private static BlockPos lastLookedPos = null;
    private static final long SYNC_SNAPSHOT_TTL_MS = 15000L;
    private static AbstractContainerMenu lastObservedHandler = null;
    private static int lastObservedSyncId = Integer.MIN_VALUE;
    private static long lastObservedSignature = Long.MIN_VALUE;
    private static long lastObservedTick = Long.MIN_VALUE;
    private static BlockPos pendingScreenTargetPos = null;
    private static long pendingScreenTargetWorldTime = Long.MIN_VALUE;
    private static AbstractContainerMenu boundScreenHandler = null;
    private static int boundScreenSyncId = Integer.MIN_VALUE;
    private static BlockPos boundScreenTargetPos = null;
    private static final long PENDING_SCREEN_TARGET_TTL_TICKS = 20L;
    private static final long ENTITY_INVALIDATION_SYNC_BLOCK_MS = 5000L;
    private static final Set<BlockPos> CONFIRMED_LARGE_BARREL_POSITIONS = ConcurrentHashMap.newKeySet();

    private static final Map<BlockPos, Map<Integer, ItemStack>> NBT_QUERY_CACHE = new ConcurrentHashMap<>();
    private static final Map<Integer, BlockPos> PENDING_NBT_REQUESTS = new ConcurrentHashMap<>();
    private static final Map<Integer, Long> PENDING_NBT_REQUEST_TIME = new ConcurrentHashMap<>();
    private static final Map<BlockPos, Long> LAST_REQUEST_TIME = new ConcurrentHashMap<>();
    private static int transactionCounter = 10000;

    private static int cacheVersion = 0;

    public static int getCacheVersion() {
        return cacheVersion;
    }

    public static Set<BlockPos> drainChangedPositions() {
        if (CHANGED_POSITIONS.isEmpty()) {
            return Collections.emptySet();
        }

        Set<BlockPos> changed = new HashSet<>(CHANGED_POSITIONS);
        CHANGED_POSITIONS.removeAll(changed);
        return changed;
    }

    public static void rememberPendingScreenTarget(Level Level, BlockPos pos) {
        if (!hasActiveConsumers()) return;
        if (Level == null || !Level.isClientSide() || pos == null) return;
        Minecraft client = Minecraft.getInstance();
        if (client.level != Level) return;
        if (!isCacheableTargetContainer(client, pos)) return;

        pendingScreenTargetPos = pos.immutable();
        pendingScreenTargetWorldTime = Level.getGameTime();
    }

    public static void tick(Minecraft client) {
        if (client.level == null || client.player == null) return;
        if (!hasActiveConsumers()) {
            lastObservedHandler = null;
            lastObservedSyncId = Integer.MIN_VALUE;
            lastObservedSignature = Long.MIN_VALUE;
            lastObservedTick = Long.MIN_VALUE;
            resetBoundScreenTarget();
            return;
        }

        if (client.level.getGameTime() % 100 == 0) {
            PENDING_NBT_REQUESTS.clear();
            PENDING_NBT_REQUEST_TIME.clear();
        }

        if (client.level.getGameTime() % 200 == 0) {
            cleanupExpiredCache();
        }

        if (client.gui.screen() == null && client.hitResult instanceof BlockHitResult bhr) {
            lastLookedPos = bhr.getBlockPos();
        }

        if (isPlayerInventoryScreen(client.gui.screen())) {
            resetObservedHandler();
            resetBoundScreenTarget();
        } else if (client.gui.screen() instanceof AbstractContainerScreen<?> screen) {
            updateFromHandlerIfNeeded(client, screen.getMenu());
        } else {
            resetObservedHandler();
            resetBoundScreenTarget();
        }
    }

    public static boolean hasActiveConsumers() {
        if (!Configs.ENABLE_MOD.getBooleanValue()) return false;

        boolean activeOperation = AutoFillerStateMachine.getInstance().isWorking() ||
                ContainerToolStateMachine.getInstance().isWorking();
        if (Configs.HIGHLIGHT_CONTAINERS.getBooleanValue() ||
                Configs.WORKING_STATE.getBooleanValue() ||
                activeOperation ||
                Configs.TOOL_ENABLED.getBooleanValue()) {
            return true;
        }

        Minecraft client = Minecraft.getInstance();
        return FillMaterialCalculator.listMode != 0 && client.gui.screen() instanceof GuiMaterialList;
    }

    public static void updateFromScreen(Minecraft client, AbstractContainerScreen<?> screen) {
        if (!hasActiveConsumers()) return;
        if (screen != null) {
            updateFromHandler(client, screen.getMenu());
        }
    }

    public static void updateFromHandler(Minecraft client, AbstractContainerMenu handler) {
        if (!hasActiveConsumers()) return;
        if (handler == null) return;

        if (isIgnoredHandlerType(client, handler)) {
            return;
        }

        Container containerInv = findPrimaryContainerInventory(client, handler);
        if (containerInv == null) {
            return;
        }

        updateFromHandler(client, handler, containerInv);
    }

    private static long updateFromHandler(Minecraft client, AbstractContainerMenu handler, Container containerInv) {
        BlockPos pos = resolveHandlerTargetPos(client, handler, containerInv);
        if (pos == null) return Long.MIN_VALUE;

        if (!isCacheableTargetContainer(client, pos)) {
            return Long.MIN_VALUE;
        }

        if (!isBoundHandlerTarget(handler, pos) && !isCurrentTaskTarget(pos) &&
                !isHandlerInventoryForTarget(client, pos, containerInv)) {
            return Long.MIN_VALUE;
        }

        int slotCount = containerInv.getContainerSize();
        if (!isPlausibleSlotCountForTarget(client, pos, slotCount)) {
            return Long.MIN_VALUE;
        }
        rememberLargeBarrelIfObserved(client, pos, slotCount);

        Map<Integer, ItemStack> items = new HashMap<>();
        long signature = 0xcbf29ce484222325L;
        signature = mix(signature, slotCount);

        for (Slot slot : handler.slots) {
            if (slot.container != null && slot.container == containerInv && slot.isActive()) {
                ItemStack stack = slot.getItem();
                if (!stack.isEmpty()) {
                    items.put(slot.getContainerSlot(), stack.copy());
                    signature = mix(signature, slot.getContainerSlot());
                    signature = mix(signature, stack.getCount());
                    signature = mix(signature, ItemStack.hashItemAndComponents(stack));
                }
            }
        }

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
        rememberBlockEntityIdentity(pos, halves);

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
            clearInvalidation(pos, halves);
            markChanged(pos, halves);
        }

        return signature;
    }

    private static void updateFromHandlerIfNeeded(Minecraft client, AbstractContainerMenu handler) {
        if (handler == null || client.level == null) return;
        if (isIgnoredHandlerType(client, handler)) {
            resetObservedHandler();
            return;
        }

        Container containerInv = findPrimaryContainerInventory(client, handler);
        if (containerInv == null) {
            resetObservedHandler();
            return;
        }

        boolean activeOperation = AutoFillerStateMachine.getInstance().isWorking() || ContainerToolStateMachine.getInstance().isWorking();
        if (activeOperation) {
            resetObservedHandler();
            return;
        }

        long worldTime = client.level.getGameTime();
        boolean newHandler = handler != lastObservedHandler || handler.containerId != lastObservedSyncId;
        int intervalTicks = 1;

        if (!newHandler && worldTime - lastObservedTick < intervalTicks) {
            return;
        }

        lastObservedTick = worldTime;
        if (newHandler) {
            lastObservedHandler = handler;
            lastObservedSyncId = handler.containerId;
            long signature = updateFromHandler(client, handler, containerInv);
            lastObservedSignature = signature != Long.MIN_VALUE ? signature : computeHandlerSignature(handler, containerInv);
            return;
        }

        long signature = computeHandlerSignature(handler, containerInv);
        if (!newHandler && signature == lastObservedSignature) {
            return;
        }

        lastObservedHandler = handler;
        lastObservedSyncId = handler.containerId;
        long updatedSignature = updateFromHandler(client, handler, containerInv);
        lastObservedSignature = updatedSignature != Long.MIN_VALUE ? updatedSignature : signature;
    }

    private static boolean shouldIgnoreHandler(Minecraft client, AbstractContainerMenu handler) {
        return isIgnoredHandlerType(client, handler) || findPrimaryContainerInventory(client, handler) == null;
    }

    private static boolean isIgnoredHandlerType(Minecraft client, AbstractContainerMenu handler) {
        if (client == null || client.player == null || handler == null) return true;
        if (handler == client.player.inventoryMenu) return true;
        return handler instanceof net.minecraft.world.inventory.InventoryMenu ||
                handler.getClass().getSimpleName().contains("CreativeScreenHandler");
    }

    private static boolean isPlayerInventoryScreen(Object screen) {
        if (screen == null) return false;
        return screen instanceof InventoryScreen ||
                screen instanceof CreativeModeInventoryScreen;
    }

    private static boolean isCacheableTargetContainer(Minecraft client, BlockPos pos) {
        if (client == null || client.level == null || pos == null) return false;
        return ContainerBlockFilter.isContainerLike(client.level.getBlockState(pos), client.level, pos);
    }

    private static boolean isPlausibleSlotCountForTarget(Minecraft client, BlockPos pos, int slotCount) {
        if (client == null || client.level == null || pos == null || slotCount <= 0) return false;

        BlockState state = client.level.getBlockState(pos);
        if (state.getBlock() instanceof net.minecraft.world.level.block.ChestBlock ||
                state.getBlock() instanceof net.minecraft.world.level.block.BarrelBlock ||
                state.getBlock() instanceof net.minecraft.world.level.block.ShulkerBoxBlock ||
                state.is(net.minecraft.world.level.block.Blocks.ENDER_CHEST)) {
            return slotCount == 27 || slotCount == 54;
        }
        if (state.is(net.minecraft.world.level.block.Blocks.HOPPER) ||
                state.is(net.minecraft.world.level.block.Blocks.BREWING_STAND)) {
            return slotCount == 5;
        }
        if (state.is(net.minecraft.world.level.block.Blocks.FURNACE) ||
                state.is(net.minecraft.world.level.block.Blocks.BLAST_FURNACE) ||
                state.is(net.minecraft.world.level.block.Blocks.SMOKER)) {
            return slotCount == 3;
        }
        if (state.is(net.minecraft.world.level.block.Blocks.DISPENSER) ||
                state.is(net.minecraft.world.level.block.Blocks.DROPPER) ||
                state.getBlock() instanceof net.minecraft.world.level.block.CrafterBlock) {
            return slotCount == 9;
        }

        if (client.level.getBlockEntity(pos) instanceof Container blockInventory) {
            int expected = blockInventory.getContainerSize();
            return slotCount == expected || (expected == 27 && slotCount == 54);
        }

        return slotCount >= 5;
    }

    private static boolean isHandlerInventoryForTarget(Minecraft client, BlockPos pos, Container containerInv) {
        if (client == null || client.level == null || pos == null || containerInv == null) return false;

        Container blockInventory = getBlockInventory(client, pos);
        if (blockInventory == null) return false;
        if (containerInv == blockInventory) return true;

        if (containerInv instanceof CompoundContainer || blockInventory instanceof CompoundContainer) {
            return containerInv.getContainerSize() == blockInventory.getContainerSize() && containerInv.getContainerSize() >= 54;
        }

        return false;
    }

    private static BlockPos resolveHandlerTargetPos(Minecraft client, AbstractContainerMenu handler, Container containerInv) {
        if (client == null || client.level == null || handler == null || containerInv == null) return null;

        if (handler == boundScreenHandler && handler.containerId == boundScreenSyncId &&
                isCacheableTargetContainer(client, boundScreenTargetPos)) {
            return boundScreenTargetPos.immutable();
        }

        BlockPos currentTaskPos = AutoFillerStateMachine.getInstance().getCurrentTaskPos();
        if (isValidHandlerTarget(client, currentTaskPos, containerInv, true) ||
                isPlausibleCurrentTaskTarget(client, currentTaskPos, containerInv)) {
            return currentTaskPos.immutable();
        }

        BlockPos inventoryPos = getInventoryBlockPos(client, containerInv);
        if (isValidHandlerTarget(client, inventoryPos, containerInv, true)) {
            return inventoryPos.immutable();
        }

        BlockPos pending = consumePendingScreenTarget(client, handler);
        if (isValidHandlerTarget(client, pending, containerInv, false) &&
                isPlausibleSlotCountForTarget(client, pending, containerInv.getContainerSize())) {
            bindScreenTarget(handler, pending);
            return pending.immutable();
        }

        return null;
    }

    private static boolean isValidHandlerTarget(Minecraft client, BlockPos pos, Container containerInv, boolean requireInventoryMatch) {
        return pos != null &&
                isCacheableTargetContainer(client, pos) &&
                (!requireInventoryMatch || isHandlerInventoryForTarget(client, pos, containerInv));
    }

    private static boolean isPlausibleCurrentTaskTarget(Minecraft client, BlockPos pos, Container containerInv) {
        if (pos == null || !isCurrentTaskTarget(pos) || !isCacheableTargetContainer(client, pos)) return false;

        BlockState state = client.level.getBlockState(pos);
        BlockPos[] halves = LitematicaContainerReader.getDoubleContainerHalves(client.level, pos, state);
        if (halves != null) {
            return containerInv.getContainerSize() >= 54;
        }

        return false;
    }

    private static boolean isCurrentTaskTarget(BlockPos pos) {
        BlockPos currentTaskPos = AutoFillerStateMachine.getInstance().getCurrentTaskPos();
        return pos != null && currentTaskPos != null && pos.equals(currentTaskPos);
    }

    private static BlockPos consumePendingScreenTarget(Minecraft client, AbstractContainerMenu handler) {
        if (pendingScreenTargetPos == null || client == null || client.level == null || handler == null) return null;
        long age = client.level.getGameTime() - pendingScreenTargetWorldTime;
        if (age < 0L || age > PENDING_SCREEN_TARGET_TTL_TICKS) {
            pendingScreenTargetPos = null;
            pendingScreenTargetWorldTime = Long.MIN_VALUE;
            return null;
        }

        BlockPos pos = pendingScreenTargetPos;
        pendingScreenTargetPos = null;
        pendingScreenTargetWorldTime = Long.MIN_VALUE;
        return pos;
    }

    private static void bindScreenTarget(AbstractContainerMenu handler, BlockPos pos) {
        if (handler == null || pos == null) return;
        boundScreenHandler = handler;
        boundScreenSyncId = handler.containerId;
        boundScreenTargetPos = pos.immutable();
    }

    private static boolean isBoundHandlerTarget(AbstractContainerMenu handler, BlockPos pos) {
        return handler != null && pos != null &&
                handler == boundScreenHandler &&
                handler.containerId == boundScreenSyncId &&
                pos.equals(boundScreenTargetPos);
    }

    private static BlockPos getInventoryBlockPos(Minecraft client, Container inventory) {
        if (client == null || client.level == null || inventory == null) return null;
        if (inventory instanceof BlockEntity blockEntity && blockEntity.getLevel() == client.level) {
            return blockEntity.getBlockPos();
        }
        return null;
    }

    private static Container getBlockInventory(Minecraft client, BlockPos pos) {
        BlockState state = client.level.getBlockState(pos);
        if (state.getBlock() instanceof net.minecraft.world.level.block.ChestBlock chest) {
            Container chestInventory = net.minecraft.world.level.block.ChestBlock.getContainer(chest, state, client.level, pos, true);
            if (chestInventory != null) return chestInventory;
        }

        if (client.level.getBlockEntity(pos) instanceof Container inventory) {
            return inventory;
        }

        return null;
    }

    private static int getRealBlockInventorySlotCount(Minecraft client, BlockPos pos) {
        if (client == null || client.level == null || pos == null || !client.level.isLoaded(pos)) return -1;
        Container inventory = getBlockInventory(client, pos);
        return inventory != null ? inventory.getContainerSize() : -1;
    }

    private static Container findPrimaryContainerInventory(Minecraft client, AbstractContainerMenu handler) {
        if (client == null || client.player == null || handler == null) return null;

        Container Container = client.player.getInventory();
        Container bestInventory = null;
        int bestCount = 0;
        Map<Container, Integer> counts = new IdentityHashMap<>();

        for (Slot slot : handler.slots) {
            if (slot.container == null || slot.container == Container || !slot.isActive()) continue;

            Container inventory = slot.container;
            Integer cachedCount = counts.get(inventory);
            int count = cachedCount != null ? cachedCount : countEnabledSlotsForInventory(handler, inventory);
            if (cachedCount == null) counts.put(inventory, count);
            if (count > bestCount) {
                bestInventory = inventory;
                bestCount = count;
            }
        }

        return bestCount > 0 ? bestInventory : null;
    }

    private static int countEnabledSlotsForInventory(AbstractContainerMenu handler, Container inventory) {
        int count = 0;
        for (Slot slot : handler.slots) {
            if (slot.container == inventory && slot.isActive()) {
                count++;
            }
        }
        return count;
    }

    private static void resetObservedHandler() {
        lastObservedHandler = null;
        lastObservedSyncId = Integer.MIN_VALUE;
        lastObservedSignature = Long.MIN_VALUE;
        lastObservedTick = Long.MIN_VALUE;
    }

    private static void resetBoundScreenTarget() {
        boundScreenHandler = null;
        boundScreenSyncId = Integer.MIN_VALUE;
        boundScreenTargetPos = null;
    }

    private static long computeHandlerSignature(AbstractContainerMenu handler, Minecraft client) {
        if (handler == null) return 0L;

        Container primaryInv = findPrimaryContainerInventory(client, handler);
        if (primaryInv == null) return 0L;
        return computeHandlerSignature(handler, primaryInv);
    }

    private static long computeHandlerSignature(AbstractContainerMenu handler, Container primaryInv) {
        if (handler == null || primaryInv == null) return 0L;
        long hash = 0xcbf29ce484222325L;
        hash = mix(hash, primaryInv.getContainerSize());

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
                    if (isEntityInvalidated(halves[0]) || isEntityInvalidated(halves[1])) return null;
                    rememberSyncedData(halves, combined);
                    return combined;
                }

                Map<Integer, ItemStack> rightServux = ServuxSyncHandler.getCachedData(halves[0]);
                Map<Integer, ItemStack> leftServux = ServuxSyncHandler.getCachedData(halves[1]);
                combined = combineHalves(rightServux, leftServux);
                if (combined != null) {
                    if (isEntityInvalidated(halves[0]) || isEntityInvalidated(halves[1])) return null;
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
            if (isEntityInvalidated(pos)) return null;
            rememberSyncedData(pos, litematicaData);
            return litematicaData;
        }

        Map<Integer, ItemStack> servuxData = ServuxSyncHandler.getCachedData(pos);
        if (servuxData != null) {
            if (isEntityInvalidated(pos)) return null;
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
        if (!hasActiveConsumers() || pos == null || now - LAST_REQUEST_TIME.getOrDefault(pos, 0L) < minIntervalMs) return;

        boolean isDouble = false;
        BlockPos[] halves = null;
        var schematicWorld = fi.dy.masa.litematica.world.SchematicWorldHandler.getSchematicWorld();
        if (schematicWorld != null) {
            BlockState state = schematicWorld.getBlockState(pos);
            halves = LitematicaContainerReader.getDoubleContainerHalves(schematicWorld, pos, state);
            if (halves == null) {
                halves = LitematicaContainerReader.getLargeBarrelConfirmationPair(schematicWorld, pos, state);
            }
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
            rememberRequestTime(pos, halves, now);
        }

        if (requestOpNbtData(pos, halves, isDouble, now)) {
            return;
        }
    }

    private static void rememberRequestTime(BlockPos pos, BlockPos[] halves, long now) {
        if (pos != null) LAST_REQUEST_TIME.put(pos.immutable(), now);
        if (halves != null) {
            for (BlockPos half : halves) {
                if (half != null) LAST_REQUEST_TIME.put(half.immutable(), now);
            }
        }
    }

    private static boolean requestOpNbtData(BlockPos pos, BlockPos[] halves, boolean isDouble, long now) {
        if (!canUseOpNbtQuery()) return false;

        int requestCount = isDouble ? 2 : 1;
        if (PENDING_NBT_REQUESTS.size() + requestCount > MAX_PENDING_NBT_REQUESTS) return false;

        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.getConnection() == null) return false;

        rememberRequestTime(pos, isDouble ? halves : null, now);
        if (isDouble) {
            int id1 = transactionCounter++;
            PENDING_NBT_REQUESTS.put(id1, halves[0]);
            PENDING_NBT_REQUEST_TIME.put(id1, now);
            client.getConnection().send(new net.minecraft.network.protocol.game.ServerboundBlockEntityTagQueryPacket(id1, halves[0]));

            int id2 = transactionCounter++;
            PENDING_NBT_REQUESTS.put(id2, halves[1]);
            PENDING_NBT_REQUEST_TIME.put(id2, now);
            client.getConnection().send(new net.minecraft.network.protocol.game.ServerboundBlockEntityTagQueryPacket(id2, halves[1]));
            return true;
        }

        int id = transactionCounter++;
        PENDING_NBT_REQUESTS.put(id, pos);
        PENDING_NBT_REQUEST_TIME.put(id, now);
        client.getConnection().send(new net.minecraft.network.protocol.game.ServerboundBlockEntityTagQueryPacket(id, pos));
        return true;
    }

    public static boolean canUseOpNbtQuery() {
        if (Configs.ENABLE_OP_NBT_QUERY.getBooleanValue()) return true;

        Minecraft client = Minecraft.getInstance();
        return client != null && client.isLocalServer();
    }

    public static void handleNbtResponse(int transactionId, CompoundTag nbt) {
        BlockPos pos = PENDING_NBT_REQUESTS.remove(transactionId);
        Long requestedAt = PENDING_NBT_REQUEST_TIME.remove(transactionId);
        if (!hasActiveConsumers()) return;
        if (pos != null && nbt != null) {
            Minecraft client = Minecraft.getInstance();
            if (client.level != null) {
                if (isStaleExternalResponse(pos, requestedAt)) {
                    return;
                }

                boolean changed = false;

                Map<Integer, ItemStack> items = parseNbtInventory(nbt, client.level.registryAccess());
                NBT_QUERY_CACHE.put(pos.immutable(), items);
                int inferredSlotCount = inferSlotCount(nbt, items);
                rememberLargeBarrelIfObserved(client, pos, inferredSlotCount);
                putSlotCount(pos, inferredSlotCount);
                CACHE_TIME.put(pos.immutable(), System.currentTimeMillis());
                rememberBlockEntityIdentity(pos, null);
                changed = true;

                if (nbt.contains("disabled_slots")) {
                    LOCK_CACHE.put(pos.immutable(), parseDisabledSlots(nbt));
                    changed = true;
                }

                BlockState state = client.level.getBlockState(pos);
                BlockPos[] halves = LitematicaContainerReader.getDoubleContainerHalves(client.level, pos, state);
                if (halves != null) {
                    Map<Integer, ItemStack> combined = combineHalves(NBT_QUERY_CACHE.get(halves[0]), NBT_QUERY_CACHE.get(halves[1]));
                    if (combined != null) {
                        changed |= putCachedItemsIfChanged(halves[0].immutable(), combined);
                        changed |= putCachedItemsIfChanged(halves[1].immutable(), combined);
                        changed |= putSlotCountIfChanged(halves[0], 54);
                        changed |= putSlotCountIfChanged(halves[1], 54);
                        rememberSyncedData(halves, combined);
                        rememberBlockEntityIdentity(pos, halves);
                    }
                }

                if (changed) {
                    cacheVersion++;
                    clearInvalidation(pos, halves);
                    markChanged(pos, halves);
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

    public static Map<Integer, ItemStack> getAuthoritativeCachedItems(BlockPos pos) {
        if (pos == null) return null;

        BlockPos key = pos.immutable();
        if (CACHE.containsKey(key)) return CACHE.get(key);
        return NBT_QUERY_CACHE.get(key);
    }

    public static void putLock(BlockPos pos, Set<Integer> locks) {
        if (pos == null || locks == null) return;
        LOCK_CACHE.put(pos.immutable(), locks);
        cacheVersion++;
        clearInvalidation(pos, null);
        markChanged(pos, null);
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
                        var ops = registries != null ? registries.createSerializationContext(NbtOps.INSTANCE) : NbtOps.INSTANCE;
                        stack = ItemStack.OPTIONAL_CODEC.parse(ops, itemTag).result().orElse(ItemStack.EMPTY);
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
        BLOCK_ENTITY_CACHE.clear();
        INVALIDATED_ENTITY_TIME.clear();
        CHANGED_POSITIONS.clear();
        NBT_QUERY_CACHE.clear();
        PENDING_NBT_REQUESTS.clear();
        PENDING_NBT_REQUEST_TIME.clear();
        LAST_REQUEST_TIME.clear();
        CONFIRMED_LARGE_BARREL_POSITIONS.clear();
        ServuxSyncHandler.clearAllCachedData();
        cacheVersion++;
    }

    public static void put(BlockPos pos, Map<Integer, ItemStack> items) {
        if (pos == null || items == null) return;
        putCachedItems(pos.immutable(), items);
        rememberBlockEntityIdentity(pos, null);
        cacheVersion++;
        clearInvalidation(pos, null);
        markChanged(pos, null);
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
                putSlotCountIfChanged(halves[0], 54);
                putSlotCountIfChanged(halves[1], 54);
                clearExternalHalfData(halves);
                rememberSyncedData(halves, snapshot);
                rememberBlockEntityIdentity(pos, halves);
                cacheVersion++;
                clearInvalidation(pos, halves);
                markChanged(pos, halves);
                return;
            }
        }

        Map<Integer, ItemStack> snapshot = copyItems(items);
        putCachedItems(pos.immutable(), snapshot);
        rememberSyncedData(pos, snapshot);
        rememberBlockEntityIdentity(pos, null);
        cacheVersion++;
        clearInvalidation(pos, null);
        markChanged(pos, null);
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
        markChanged(pos, null);
    }

    public static void observeBlockState(BlockPos pos, BlockState state) {
        if (pos == null || state == null) return;

        BlockPos key = pos.immutable();
        BlockState previous = BLOCK_STATE_CACHE.put(key, state);
        BlockEntity currentEntity = null;
        Minecraft client = Minecraft.getInstance();
        if (client.level != null) {
            currentEntity = client.level.getBlockEntity(key);
        }
        BlockEntity previousEntity = currentEntity == null
                ? BLOCK_ENTITY_CACHE.remove(key)
                : BLOCK_ENTITY_CACHE.put(key, currentEntity);
        if ((previous != null && hasMeaningfulBlockStateChange(previous, state)) ||
                (previousEntity != null && previousEntity != currentEntity)) {
            removeCachedDataForContainer(key, state);
            markEntityInvalidated(key, state);
            cacheVersion++;
            markChanged(key, null);
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

    private static void markEntityInvalidated(BlockPos pos, BlockState state) {
        long now = System.currentTimeMillis();
        Minecraft client = Minecraft.getInstance();
        if (client.level != null && state != null) {
            BlockPos[] halves = LitematicaContainerReader.getDoubleContainerHalves(client.level, pos, state);
            if (halves != null) {
                INVALIDATED_ENTITY_TIME.put(halves[0].immutable(), now);
                INVALIDATED_ENTITY_TIME.put(halves[1].immutable(), now);
                return;
            }
        }

        INVALIDATED_ENTITY_TIME.put(pos.immutable(), now);
    }

    private static boolean isEntityInvalidated(BlockPos pos) {
        Long invalidatedAt = INVALIDATED_ENTITY_TIME.get(pos);
        if (invalidatedAt == null) return false;

        long age = System.currentTimeMillis() - invalidatedAt;
        if (age > ENTITY_INVALIDATION_SYNC_BLOCK_MS || age < 0L) {
            INVALIDATED_ENTITY_TIME.remove(pos);
            return false;
        }
        return true;
    }

    private static void clearInvalidation(BlockPos pos, BlockPos[] halves) {
        if (halves != null) {
            for (BlockPos half : halves) {
                if (half != null) {
                    INVALIDATED_ENTITY_TIME.remove(half.immutable());
                }
            }
            return;
        }

        if (pos != null) {
            INVALIDATED_ENTITY_TIME.remove(pos.immutable());
        }
    }

    private static void removeCachedDataForContainer(BlockPos pos, BlockState state) {
        Minecraft client = Minecraft.getInstance();
        if (client.level != null && state != null) {
            BlockPos[] halves = LitematicaContainerReader.getDoubleContainerHalves(client.level, pos, state);
            if (halves != null) {
                removeCachedDataOnly(halves[0]);
                removeCachedDataOnly(halves[1]);
                return;
            }
        }

        removeCachedDataOnly(pos);
    }

    private static void rememberBlockEntityIdentity(BlockPos pos, BlockPos[] halves) {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null) return;

        if (halves != null) {
            for (BlockPos half : halves) {
                if (half != null) {
                    rememberSingleBlockEntityIdentity(client, half);
                }
            }
            return;
        }

        rememberSingleBlockEntityIdentity(client, pos);
    }

    private static void rememberSingleBlockEntityIdentity(Minecraft client, BlockPos pos) {
        if (pos == null) return;
        BlockEntity blockEntity = client.level.getBlockEntity(pos);
        if (blockEntity != null) {
            BLOCK_ENTITY_CACHE.put(pos.immutable(), blockEntity);
        }
    }

    private static void markChanged(BlockPos pos, BlockPos[] halves) {
        if (halves != null) {
            for (BlockPos half : halves) {
                if (half != null) {
                    CHANGED_POSITIONS.add(half.immutable());
                }
            }
            return;
        }

        if (pos != null) {
            CHANGED_POSITIONS.add(pos.immutable());
        }
    }

    private static void removeCachedDataOnly(BlockPos pos) {
        CACHE.remove(pos);
        LOCK_CACHE.remove(pos);
        SLOT_COUNT_CACHE.remove(pos);
        SYNC_SNAPSHOT_CACHE.remove(pos);
        SYNC_SNAPSHOT_TIME.remove(pos);
        NBT_QUERY_CACHE.remove(pos);
        CACHE_TIME.remove(pos);
        ServuxSyncHandler.clearCachedData(pos);
        LAST_REQUEST_TIME.remove(pos);
    }

    private static boolean isStaleExternalResponse(BlockPos pos, Long requestedAt) {
        if (pos == null || requestedAt == null) return false;

        Minecraft client = Minecraft.getInstance();
        if (client.level != null) {
            BlockState state = client.level.getBlockState(pos);
            BlockPos[] halves = LitematicaContainerReader.getDoubleContainerHalves(client.level, pos, state);
            if (halves != null) {
                return hasNewerAuthoritativeCache(halves[0], requestedAt) ||
                        hasNewerAuthoritativeCache(halves[1], requestedAt) ||
                        wasInvalidatedAfterRequest(halves[0], requestedAt) ||
                        wasInvalidatedAfterRequest(halves[1], requestedAt);
            }
        }

        return hasNewerAuthoritativeCache(pos, requestedAt) ||
                wasInvalidatedAfterRequest(pos, requestedAt);
    }

    private static boolean hasNewerAuthoritativeCache(BlockPos pos, long requestedAt) {
        if (pos == null) return false;
        BlockPos key = pos.immutable();
        Long writtenAt = CACHE_TIME.get(key);
        return writtenAt != null && writtenAt >= requestedAt && CACHE.containsKey(key);
    }

    private static boolean wasInvalidatedAfterRequest(BlockPos pos, long requestedAt) {
        if (pos == null) return false;
        Long invalidatedAt = INVALIDATED_ENTITY_TIME.get(pos.immutable());
        if (invalidatedAt == null || invalidatedAt < requestedAt) return false;

        long age = System.currentTimeMillis() - invalidatedAt;
        return age >= 0L && age <= ENTITY_INVALIDATION_SYNC_BLOCK_MS;
    }

    private static void clearExternalHalfData(BlockPos[] halves) {
        if (halves == null) return;
        for (BlockPos half : halves) {
            if (half == null) continue;
            BlockPos key = half.immutable();
            NBT_QUERY_CACHE.remove(key);
            ServuxSyncHandler.clearCachedData(key);
        }
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
            return EntityDataManager.getInstance().getCache().getBlockEntityNbtFromCache(pos);
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
        return combineDoubleContainerItems(right, left);
    }

    public static Map<Integer, ItemStack> combineDoubleContainerItems(Map<Integer, ItemStack> right, Map<Integer, ItemStack> left) {
        if (right == null || left == null) return null;

        Map<Integer, ItemStack> combined = new HashMap<>();
        boolean rightUsesAbsoluteSlots = hasUpperContainerSlots(right);
        boolean leftUsesAbsoluteSlots = hasUpperContainerSlots(left);
        if (rightUsesAbsoluteSlots || leftUsesAbsoluteSlots) {
            boolean rightIsFullSnapshot = hasLowerContainerSlots(right) && rightUsesAbsoluteSlots;
            boolean leftIsFullSnapshot = hasLowerContainerSlots(left) && leftUsesAbsoluteSlots;

            if (rightIsFullSnapshot) copyDoubleContainerSlots(right, combined, true);
            if (leftIsFullSnapshot) copyDoubleContainerSlots(left, combined, true);
            if (!rightIsFullSnapshot) copyDoubleContainerSlots(right, combined, false);
            if (!leftIsFullSnapshot) copyDoubleContainerSlots(left, combined, false);
            return combined;
        }

        copyHalfContainerSlots(right, combined, 0);
        copyHalfContainerSlots(left, combined, 27);
        return combined;
    }

    private static boolean hasUpperContainerSlots(Map<Integer, ItemStack> items) {
        if (items == null) return false;
        for (Integer slot : items.keySet()) {
            if (slot != null && slot >= 27 && slot < 54) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasLowerContainerSlots(Map<Integer, ItemStack> items) {
        if (items == null) return false;
        for (Integer slot : items.keySet()) {
            if (slot != null && slot >= 0 && slot < 27) {
                return true;
            }
        }
        return false;
    }

    private static void copyDoubleContainerSlots(Map<Integer, ItemStack> source, Map<Integer, ItemStack> target, boolean overwrite) {
        if (source == null) return;
        for (Map.Entry<Integer, ItemStack> entry : source.entrySet()) {
            Integer slot = entry.getKey();
            ItemStack stack = entry.getValue();
            if (slot == null || slot < 0 || slot >= 54 || stack == null || stack.isEmpty()) continue;
            if (!overwrite && target.containsKey(slot)) continue;
            target.put(slot, stack.copy());
        }
    }

    private static void copyHalfContainerSlots(Map<Integer, ItemStack> source, Map<Integer, ItemStack> target, int offset) {
        if (source == null) return;
        for (Map.Entry<Integer, ItemStack> entry : source.entrySet()) {
            Integer slot = entry.getKey();
            ItemStack stack = entry.getValue();
            if (slot == null || slot < 0 || slot >= 27 || stack == null || stack.isEmpty()) continue;
            target.put(slot + offset, stack.copy());
        }
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
            EntityDataManager storage = EntityDataManager.getInstance();

            if (isDouble) {
                storage.requestBlockEntityWrapped(client.level, halves[0]);
                storage.requestBlockEntityWrapped(client.level, halves[1]);
            } else {
                storage.requestBlockEntityWrapped(client.level, pos);
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
        BlockPos key = pos.immutable();
        Integer previous = SLOT_COUNT_CACHE.get(key);
        int best = previous == null ? slotCount : Math.max(previous, slotCount);
        if (previous != null && previous == best) return false;
        SLOT_COUNT_CACHE.put(key, best);
        return true;
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
        SYNC_SNAPSHOT_TIME.entrySet().removeIf(entry -> now - entry.getValue() > SYNC_SNAPSHOT_TTL_MS);
        CACHE.keySet().removeIf(pos -> !CACHE_TIME.containsKey(pos));
        LOCK_CACHE.keySet().removeIf(pos -> !CACHE_TIME.containsKey(pos));
        SLOT_COUNT_CACHE.keySet().removeIf(pos -> !CACHE_TIME.containsKey(pos));
        SYNC_SNAPSHOT_CACHE.keySet().removeIf(pos -> !SYNC_SNAPSHOT_TIME.containsKey(pos));
        NBT_QUERY_CACHE.keySet().removeIf(pos -> !CACHE_TIME.containsKey(pos));
        LAST_REQUEST_TIME.keySet().removeIf(pos -> !CACHE_TIME.containsKey(pos));
    }

    private static void evictIfNeeded() {
        int maxEntries = Math.max(1, Configs.REAL_CONTAINER_CACHE_SIZE.getIntegerValue());
        while (CACHE.size() >= maxEntries) {
            BlockPos oldest = null;
            long oldestTime = Long.MAX_VALUE;
            for (Map.Entry<BlockPos, Long> entry : CACHE_TIME.entrySet()) {
                if (entry.getValue() < oldestTime) {
                    oldestTime = entry.getValue();
                    oldest = entry.getKey();
                }
            }

            if (oldest == null) {
                return;
            }

            removeLocalCacheEntry(oldest);
        }
    }

    private static void removeLocalCacheEntry(BlockPos pos) {
        CACHE.remove(pos);
        LOCK_CACHE.remove(pos);
        SLOT_COUNT_CACHE.remove(pos);
        SYNC_SNAPSHOT_CACHE.remove(pos);
        SYNC_SNAPSHOT_TIME.remove(pos);
        NBT_QUERY_CACHE.remove(pos);
        LAST_REQUEST_TIME.remove(pos);
        CACHE_TIME.remove(pos);
    }

    public static int getKnownSlotCount(BlockPos pos) {
        if (pos == null) return -1;

        Integer slotCount = SLOT_COUNT_CACHE.get(pos);
        if (slotCount != null) return slotCount;

        Minecraft client = Minecraft.getInstance();
        int externalSlotCount = ServuxSyncHandler.getCachedSlotCount(pos);
        if (externalSlotCount > 0) {
            rememberLargeBarrelIfObserved(client, pos, externalSlotCount);
            int best = Math.max(slotCount == null ? -1 : slotCount, externalSlotCount);
            putSlotCount(pos, best);
            return best;
        }

        int realInventorySlotCount = getRealBlockInventorySlotCount(client, pos);
        if (realInventorySlotCount > 0) {
            rememberLargeBarrelIfObserved(client, pos, realInventorySlotCount);
            int best = Math.max(slotCount == null ? -1 : slotCount, realInventorySlotCount);
            putSlotCount(pos, best);
            return best;
        }

        Map<Integer, ItemStack> nbt = NBT_QUERY_CACHE.get(pos);
        if (nbt != null) {
            int inferred = inferSlotCount(nbt);
            rememberLargeBarrelIfObserved(client, pos, inferred);
            return inferred;
        }

        if (slotCount != null) return slotCount;

        Map<Integer, ItemStack> cached = CACHE.get(pos);
        if (cached != null) return inferSlotCount(cached);

        Map<Integer, ItemStack> servux = ServuxSyncHandler.getCachedData(pos);
        if (servux != null) {
            int inferred = Math.max(ServuxSyncHandler.getCachedSlotCount(pos), inferSlotCount(servux));
            if (inferred > 0) {
                rememberLargeBarrelIfObserved(client, pos, inferred);
                putSlotCount(pos, inferred);
                return inferred;
            }
        }

        Map<Integer, ItemStack> snapshot = getSyncSnapshot(pos);
        if (snapshot != null) return inferSlotCount(snapshot);

        return -1;
    }

    public static int getCachedKnownSlotCount(BlockPos pos) {
        if (pos == null) return -1;
        Integer slotCount = SLOT_COUNT_CACHE.get(pos);
        return slotCount != null ? slotCount : -1;
    }

    public static boolean isConfirmedLargeBarrel(BlockPos pos) {
        return pos != null && CONFIRMED_LARGE_BARREL_POSITIONS.contains(pos.immutable());
    }

    private static void rememberLargeBarrelIfObserved(Minecraft client, BlockPos pos, int slotCount) {
        if (client == null || client.level == null || pos == null || slotCount < 54) return;
        BlockState state = client.level.getBlockState(pos);
        if (!state.is(net.minecraft.world.level.block.Blocks.BARREL)) return;

        BlockPos[] halves = LitematicaContainerReader.getDoubleContainerHalves(client.level, pos, state, slotCount);
        if (halves != null) {
            CONFIRMED_LARGE_BARREL_POSITIONS.add(halves[0].immutable());
            CONFIRMED_LARGE_BARREL_POSITIONS.add(halves[1].immutable());
        }
    }

    private static void putSlotCount(BlockPos pos, int slotCount) {
        if (pos == null || slotCount <= 0) return;
        SLOT_COUNT_CACHE.merge(pos.immutable(), slotCount, Math::max);
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

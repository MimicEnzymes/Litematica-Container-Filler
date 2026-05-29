package com.mimicenzymes.litematicafiller.tool;

import com.mimicenzymes.litematicafiller.config.Configs;
import com.mimicenzymes.litematicafiller.config.Hotkeys;
import com.mimicenzymes.litematicafiller.config.QuickShulkerOpenMode;
import com.mimicenzymes.litematicafiller.core.AutoFillerStateMachine;
import com.mimicenzymes.litematicafiller.core.ItemMatcher;
import com.mimicenzymes.litematicafiller.core.RealContainerCache;
import com.mimicenzymes.litematicafiller.filter.ContainerBlockFilter;
import com.mimicenzymes.litematicafiller.dependency.DependencyChecker;
import com.mimicenzymes.litematicafiller.dependency.DummyExtractor;
import com.mimicenzymes.litematicafiller.dependency.IShulkerExtractor;
import com.mimicenzymes.litematicafiller.dependency.QuickShulkerWrapper;
import com.mimicenzymes.litematicafiller.network.ClickPacketRateLimiter;
import com.mimicenzymes.litematicafiller.network.TakeItOutCompat;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ContainerToolStateMachine {
    private enum Phase {
        IDLE,
        OPENING_TEMPLATE,
        OPENING_TARGET,
        PROCESSING,
        WAITING_SYNC_TAKEITOUT,
        OPENING_SYNC_SHULKER,
        EXTRACTING_SYNC_SHULKER,
        OPENING_PACK_SHULKER,
        PACKING_SHULKER
    }

    private static final ContainerToolStateMachine INSTANCE = new ContainerToolStateMachine();
    private static final int CONTINUOUS_TRIGGER_HOLD_THRESHOLD_TICKS = 6;

    private final Map<Integer, ItemStack> syncTemplate = new LinkedHashMap<>();

    private Phase phase = Phase.IDLE;
    private ContainerToolMode mode = null;
    private BlockPos currentPos = null;
    private BlockPos syncTemplatePos = null;
    private Identifier syncTemplateBlockId = null;
    private int uiWaitTicks = 0;
    private boolean openedByTool = false;
    private int packingShulkerSlot = -1;
    private ItemStack packingStack = ItemStack.EMPTY;
    private int packingCountBefore = 0;
    private int packingMovedCount = 0;
    private int packingCapacity = 0;
    private int packingLastTargetSlot = -1;
    private final List<Integer> packingBufferSlots = new ArrayList<>();
    private final List<ItemStack> syncNeededToFetch = new ArrayList<>();
    private final Set<Integer> syncPendingShulkers = new LinkedHashSet<>();
    private int syncActiveShulkerSlot = -1;
    private int syncTakeItOutWaitTicks = 0;
    private TakeItOutRequest syncPendingTakeItOutRequest = null;
    private boolean syncTakeItOutBlocked = false;
    private boolean triggerKeyHeldLastTick = false;
    private int triggerKeyHeldTicks = 0;
    private boolean continuousTriggerReady = false;
    private boolean continuousTriggerSuppressedUntilRelease = false;
    private int continuousSameTargetCooldownTicks = 0;
    private BlockPos continuousLastTarget = null;
    private ContainerToolMode continuousLastMode = null;
    private boolean currentOperationContinuous = false;

    private final IShulkerExtractor shulkerExtractor;

    private static int uiSlot(AbstractContainerMenu handler, Slot slot) {
        return handler == null || slot == null ? -1 : handler.slots.indexOf(slot);
    }

    private ContainerToolStateMachine() {
        this.shulkerExtractor = DependencyChecker.HAS_QUICK_SHULKER ? new QuickShulkerWrapper() : new DummyExtractor();
    }

    public static ContainerToolStateMachine getInstance() {
        return INSTANCE;
    }

    public boolean isWorking() {
        return phase != Phase.IDLE;
    }

    public boolean shouldBlockScreens() {
        return isWorking() && Configs.HIDE_TOOL_GUI.getBooleanValue();
    }

    public boolean isToolEnabled() {
        return Configs.ENABLE_MOD.getBooleanValue() && Configs.TOOL_ENABLED.getBooleanValue();
    }

    public ContainerToolMode getActiveMode() {
        if (Configs.CONTAINER_TOOL_MODE.getOptionListValue() instanceof ContainerToolMode toolMode) {
            if (!toolMode.isAvailable()) {
                Configs.CONTAINER_TOOL_MODE.setOptionListValue(ContainerToolMode.CLEAR);
                Configs.TOOL_ENABLED.setBooleanValue(false);
                return ContainerToolMode.CLEAR;
            }
            return toolMode;
        }
        return ContainerToolMode.CLEAR;
    }

    public BlockPos getLookedContainerForHud(Minecraft client) {
        return client == null || client.level == null ? null : getLookedContainerPos(client);
    }

    public void switchMode(Minecraft client) {
        if (!Configs.ENABLE_MOD.getBooleanValue()) {
            return;
        }

        ContainerToolMode next = (ContainerToolMode) getActiveMode().cycle(true);
        Configs.CONTAINER_TOOL_MODE.setOptionListValue(next);
        Configs.TOOL_ENABLED.setBooleanValue(true);
        send(client, "litematica_container_filler.message.tool_mode_switched", next.getDisplayName());
    }

    public void closeAll(Minecraft client) {
        reset(client, true, true);
        Configs.TOOL_ENABLED.setBooleanValue(false);
        send(client, "litematica_container_filler.message.tool_all_closed");
    }

    public void stopForDisabledMod(Minecraft client) {
        reset(client, true, true);
        resetContinuousTriggerState();
    }

    public void triggerCurrent(Minecraft client) {
        if (!Configs.ENABLE_MOD.getBooleanValue()) {
            return;
        }

        if (!isToolEnabled()) {
            send(client, "litematica_container_filler.message.tool_disabled");
            return;
        }

        ContainerToolMode toolMode = getActiveMode();
        if (!toolMode.isAvailable()) {
            Configs.CONTAINER_TOOL_MODE.setOptionListValue(ContainerToolMode.CLEAR);
            Configs.TOOL_ENABLED.setBooleanValue(false);
            send(client, "litematica_container_filler.message.tool_pack_no_shulker_support");
            return;
        }
        if (toolMode == ContainerToolMode.COPY) {
            triggerSync(client, false);
        } else {
            startSingleTarget(client, toolMode, false);
        }
    }

    private void triggerSync(Minecraft client, boolean continuous) {
        if (client.player == null || client.level == null) return;

        BlockPos looked = getLookedContainerPos(client);
        if (looked == null) {
            send(client, "litematica_container_filler.message.target_invalid");
            return;
        }

        if (isWorking()) {
            continuousTriggerSuppressedUntilRelease = Configs.ENABLE_TOOL_HOLD_REPEAT.getBooleanValue();
            reset(client, true, false);
            send(client, "litematica_container_filler.message.tool_cancelled");
            return;
        }

        Identifier lookedBlockId = BuiltInRegistries.BLOCK.getKey(client.level.getBlockState(looked).getBlock());
        boolean updateTemplate = syncTemplate.isEmpty() || (!continuous && (looked.equals(syncTemplatePos) || !lookedBlockId.equals(syncTemplateBlockId)));
        if (continuous && !syncTemplate.isEmpty()) {
            if (looked.equals(syncTemplatePos) || (syncTemplateBlockId != null && !lookedBlockId.equals(syncTemplateBlockId))) {
                return;
            }
            updateTemplate = false;
        }
        mode = ContainerToolMode.COPY;
        currentPos = looked.immutable();
        openedByTool = false;
        uiWaitTicks = 0;
        currentOperationContinuous = continuous;

        if (updateTemplate) {
            phase = Phase.OPENING_TEMPLATE;
            if (!continuous) send(client, "litematica_container_filler.message.tool_sync_template_started");
        } else {
            phase = Phase.OPENING_TARGET;
            if (!continuous) send(client, "litematica_container_filler.message.tool_sync_started");
        }
    }

    public void tick(Minecraft client) {
        if (client.player == null || client.level == null) {
            reset(client, false, false);
            resetContinuousTriggerState();
            return;
        }

        if (!Configs.ENABLE_MOD.getBooleanValue()) {
            reset(client, true, true);
            resetContinuousTriggerState();
            return;
        }

        tickContinuousToolTrigger(client);
        if (continuousSameTargetCooldownTicks > 0) {
            continuousSameTargetCooldownTicks--;
        }

        if (phase == Phase.IDLE) {
            ClickPacketRateLimiter.setOperationActive(AutoFillerStateMachine.getInstance().isWorking());
            if (!ClickPacketRateLimiter.hasPendingPackets()) {
                tryStartContinuousTool(client);
            }
            return;
        }
        ClickPacketRateLimiter.setOperationActive(true);
        if (ClickPacketRateLimiter.hasPendingPackets()) return;

        AbstractContainerMenu handler = client.player.containerMenu;
        boolean inContainer = !(handler instanceof InventoryMenu);

        switch (phase) {
            case OPENING_TEMPLATE -> {
                if (!inContainer) {
                    openOrTimeout(client, currentPos);
                    return;
                }

                syncTemplate.clear();
                syncTemplate.putAll(readContainerSlots(handler, client));
                syncTemplatePos = currentPos;
                syncTemplateBlockId = BuiltInRegistries.BLOCK.getKey(client.level.getBlockState(currentPos).getBlock());
                RealContainerCache.updateFromHandler(client, handler);
                client.player.closeContainer();
                send(client, "litematica_container_filler.message.tool_sync_template_saved");
                reset(client, false, true);
            }
            case OPENING_TARGET -> {
                if (!inContainer) {
                    openOrTimeout(client, currentPos);
                    return;
                }

                phase = Phase.PROCESSING;
            }
            case PROCESSING -> {
                if (!inContainer) {
                    finish(client);
                    return;
                }

                boolean completed = false;
                switch (mode) {
                    case CLEAR -> completed = clearContainer(client, handler);
                    case FILL_FULL -> completed = fillContainerFull(client, handler);
                    case COPY -> completed = syncIntoContainer(client, handler);
                    case PACK -> {
                        if (packContainerBatch(client, handler)) {
                            return;
                        }
                        completed = true;
                    }
                }

                if (!completed) {
                    if (mode == ContainerToolMode.COPY && phase != Phase.PROCESSING) {
                        return;
                    }
                    RealContainerCache.updateFromHandler(client, handler);
                    client.player.closeContainer();
                    reset(client, false, true);
                    return;
                }

                RealContainerCache.updateFromHandler(client, handler);
                client.player.closeContainer();
                finish(client);
            }
            case WAITING_SYNC_TAKEITOUT -> {
                if (inContainer) {
                    client.player.closeContainer();
                    return;
                }

                if (syncPendingTakeItOutRequest == null || hasTakeItOutResultArrived(client, syncPendingTakeItOutRequest)) {
                    syncPendingTakeItOutRequest = null;
                    syncTakeItOutWaitTicks = 0;
                    openedByTool = false;
                    phase = Phase.OPENING_TARGET;
                    return;
                }

                if (++syncTakeItOutWaitTicks > 8) {
                    syncPendingTakeItOutRequest = null;
                    syncTakeItOutWaitTicks = 0;
                    syncTakeItOutBlocked = true;
                    openedByTool = false;
                    phase = Phase.OPENING_TARGET;
                }
            }
            case OPENING_SYNC_SHULKER -> {
                if (!openedByTool) {
                    if (inContainer) {
                        client.player.closeContainer();
                        if (++uiWaitTicks > 20) {
                            fail(client, "litematica_container_filler.message.container_timeout");
                        }
                        return;
                    }
                    openSyncShulkerOrTimeout(client);
                    return;
                }

                if (!inContainer) {
                    if (++uiWaitTicks > 20) {
                        fail(client, "litematica_container_filler.message.tool_missing_items_for_sync");
                    }
                    return;
                }

                phase = Phase.EXTRACTING_SYNC_SHULKER;
            }
            case EXTRACTING_SYNC_SHULKER -> {
                if (!inContainer) {
                    fail(client, "litematica_container_filler.message.tool_missing_items_for_sync");
                    return;
                }

                extractSyncItemsFromShulker(client, handler);
                client.player.closeContainer();
                openedByTool = false;
                syncActiveShulkerSlot = -1;
                uiWaitTicks = 0;

                if (syncNeededToFetch.isEmpty() || hasEnoughItemsForSync(client, syncNeededToFetch)) {
                    syncNeededToFetch.clear();
                    syncPendingShulkers.clear();
                    phase = Phase.OPENING_TARGET;
                } else if (!syncPendingShulkers.isEmpty()) {
                    phase = Phase.OPENING_SYNC_SHULKER;
                } else {
                    fail(client, "litematica_container_filler.message.tool_missing_items_for_sync");
                }
            }
            case OPENING_PACK_SHULKER -> {
                if (!openedByTool) {
                    if (inContainer) {
                        client.player.closeContainer();
                        if (++uiWaitTicks > 20) {
                            failPackingSupport(client);
                        }
                        return;
                    }
                    openPackingShulkerOrTimeout(client);
                    return;
                }

                if (!inContainer) {
                    if (++uiWaitTicks > 20) {
                        failPackingSupport(client);
                    }
                    return;
                }

                if (inContainer) {
                    phase = Phase.PACKING_SHULKER;
                }
            }
            case PACKING_SHULKER -> {
                if (!inContainer) {
                    fail(client, "litematica_container_filler.message.tool_pack_no_shulker_support");
                    return;
                }

                stashPackedStackIntoShulker(client, handler);
                RealContainerCache.remove(currentPos);
                client.player.closeContainer();
                openedByTool = false;
                uiWaitTicks = 0;
                phase = Phase.OPENING_TARGET;
            }
            case IDLE -> {
            }
        }
    }

    private void startSingleTarget(Minecraft client, ContainerToolMode toolMode, boolean continuous) {
        if (client.player == null || client.level == null) return;

        if (!toolMode.isAvailable()) {
            Configs.CONTAINER_TOOL_MODE.setOptionListValue(ContainerToolMode.CLEAR);
            Configs.TOOL_ENABLED.setBooleanValue(false);
            send(client, "litematica_container_filler.message.tool_pack_no_shulker_support");
            return;
        }

        if (isWorking()) {
            continuousTriggerSuppressedUntilRelease = Configs.ENABLE_TOOL_HOLD_REPEAT.getBooleanValue();
            reset(client, true, false);
            send(client, "litematica_container_filler.message.tool_cancelled");
            return;
        }

        if (AutoFillerStateMachine.getInstance().isWorking()) {
            AutoFillerStateMachine.getInstance().emergencyStop(client);
            Configs.WORKING_STATE.setBooleanValue(false);
        }

        BlockPos looked = getLookedContainerPos(client);
        if (looked == null) {
            send(client, "litematica_container_filler.message.target_invalid");
            return;
        }

        mode = toolMode;
        currentPos = looked.immutable();
        openedByTool = false;
        uiWaitTicks = 0;
        currentOperationContinuous = continuous;
        phase = Phase.OPENING_TARGET;
        if (!continuous) {
            send(client, switch (toolMode) {
                case CLEAR -> "litematica_container_filler.message.tool_clear_started";
                case FILL_FULL -> "litematica_container_filler.message.tool_fill_full_started";
                case PACK -> "litematica_container_filler.message.tool_pack_started";
                case COPY -> "litematica_container_filler.message.tool_sync_started";
            });
        }
    }

    private void tickContinuousToolTrigger(Minecraft client) {
        if (!Configs.ENABLE_TOOL_HOLD_REPEAT.getBooleanValue()) {
            resetContinuousTriggerState();
            return;
        }

        boolean held = Hotkeys.TOOL_TRIGGER.getKeybind().isKeybindHeld();
        if (!held) {
            resetContinuousTriggerState();
            return;
        }

        if (!triggerKeyHeldLastTick) {
            triggerKeyHeldTicks = 1;
            continuousTriggerReady = false;
        } else if (triggerKeyHeldTicks < Integer.MAX_VALUE) {
            triggerKeyHeldTicks++;
        }

        if (triggerKeyHeldTicks >= CONTINUOUS_TRIGGER_HOLD_THRESHOLD_TICKS && !continuousTriggerSuppressedUntilRelease) {
            continuousTriggerReady = true;
        }
        triggerKeyHeldLastTick = true;
    }

    private void resetContinuousTriggerState() {
        triggerKeyHeldLastTick = false;
        triggerKeyHeldTicks = 0;
        continuousTriggerReady = false;
        continuousTriggerSuppressedUntilRelease = false;
    }

    private void tryStartContinuousTool(Minecraft client) {
        if (!continuousTriggerReady || continuousTriggerSuppressedUntilRelease || !isToolEnabled()) return;

        ContainerToolMode toolMode = getActiveMode();
        if (toolMode == ContainerToolMode.PACK || !toolMode.isAvailable()) return;

        BlockPos looked = getLookedContainerPos(client);
        if (looked == null) return;
        if (continuousSameTargetCooldownTicks > 0 && toolMode == continuousLastMode && looked.equals(continuousLastTarget)) {
            return;
        }

        if (toolMode == ContainerToolMode.COPY) {
            triggerSync(client, true);
        } else {
            startSingleTarget(client, toolMode, true);
        }
    }

    private BlockPos getLookedContainerPos(Minecraft client) {
        if (!(client.hitResult instanceof BlockHitResult hit) || client.hitResult.getType() != HitResult.Type.BLOCK) {
            return null;
        }

        BlockPos pos = hit.getBlockPos();
        BlockState state = client.level.getBlockState(pos);
        if (state == null || state.isAir() || !state.hasBlockEntity() || !ContainerBlockFilter.isAllowedForTools(state, client.level, pos)) {
            return null;
        }

        return pos;
    }

    private void openOrTimeout(Minecraft client, BlockPos pos) {
        if (!openedByTool) {
            openContainer(client, pos);
            openedByTool = true;
        } else if (++uiWaitTicks > 20) {
            fail(client, "litematica_container_filler.message.container_timeout");
        }
    }

    private void openContainer(Minecraft client, BlockPos pos) {
        uiWaitTicks = 0;
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);
        client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND, hit);
    }

    private boolean clearContainer(Minecraft client, AbstractContainerMenu handler) {
        ContainerClearOutputMode outputMode = getClearOutputMode();
        boolean toInventory = outputMode == ContainerClearOutputMode.INVENTORY;
        boolean inventoryThenDrop = outputMode == ContainerClearOutputMode.INVENTORY_THEN_DROP;
        if (toInventory && !canAbsorbContainerIntoPlayerInventory(client, handler)) {
            send(client, "litematica_container_filler.message.tool_clear_inventory_full");
            return false;
        }

        for (Slot slot : getContainerSlots(handler, client)) {
            if (!slot.hasItem() || !slot.mayPickup(client.player)) continue;
            if (toInventory || inventoryThenDrop) {
                client.gameMode.handleContainerInput(handler.containerId, uiSlot(handler, slot), 0, ContainerInput.QUICK_MOVE, client.player);
            }
            if (!toInventory && slot.hasItem()) {
                client.gameMode.handleContainerInput(handler.containerId, uiSlot(handler, slot), 1, ContainerInput.THROW, client.player);
            }
        }
        return true;
    }

    private boolean fillContainerFull(Minecraft client, AbstractContainerMenu handler) {
        List<Slot> containerSlots = getContainerSlots(handler, client);
        Slot best = findMostCommonPlayerSlot(handler, client, containerSlots);
        if (best == null) {
            send(client, "litematica_container_filler.message.tool_no_fill_item");
            return false;
        }

        boolean changed = false;
        ItemStack target = best.getItem().copy();
        for (Slot slot : containerSlots) {
            if (!canReceive(slot, target)) continue;
            int src = findPlayerSlotWithItem(handler, client, target);
            if (src < 0) break;
            client.gameMode.handleContainerInput(handler.containerId, src, 0, ContainerInput.QUICK_MOVE, client.player);
            changed = true;
        }
        return changed;
    }

    private boolean syncIntoContainer(Minecraft client, AbstractContainerMenu handler) {
        if (syncTemplate.isEmpty()) {
            send(client, "litematica_container_filler.message.tool_sync_no_template");
            return false;
        }
        if (!handler.getCarried().isEmpty()) {
            send(client, "litematica_container_filler.message.cursor_stuck");
            return false;
        }

        List<Slot> containerSlots = getContainerSlots(handler, client);
        List<ItemStack> neededToFetch = computeNeededItemsForSync(client, handler, containerSlots);
        if (!neededToFetch.isEmpty()) {
            if (prepareSyncShulkerExtraction(client, handler, neededToFetch)) {
                return false;
            }
            send(client, "litematica_container_filler.message.tool_missing_items_for_sync");
            return false;
        }

        boolean changed = false;
        for (Slot slot : containerSlots) {
            ItemStack target = syncTemplate.getOrDefault(slot.getContainerSlot(), ItemStack.EMPTY);
            ItemStack current = slot.getItem();
            if (target.isEmpty()) {
                if (!current.isEmpty() && slot.mayPickup(client.player)) {
                    client.gameMode.handleContainerInput(handler.containerId, uiSlot(handler, slot), 1, ContainerInput.THROW, client.player);
                    changed = true;
                }
                continue;
            }

            if (!current.isEmpty() && (!ItemMatcher.isSameItem(current, target) || current.getCount() > target.getCount())) {
                client.gameMode.handleContainerInput(handler.containerId, uiSlot(handler, slot), 1, ContainerInput.THROW, client.player);
                changed = true;
                continue;
            }

            int currentCount = current.isEmpty() ? 0 : current.getCount();
            while (currentCount < target.getCount()) {
                int src = findPlayerSlotWithItem(handler, client, target);
                if (src < 0 || !canReceive(slot, target)) break;
                client.gameMode.handleContainerInput(handler.containerId, src, 0, ContainerInput.PICKUP, client.player);
                int amount = Math.min(target.getCount() - currentCount, handler.getCarried().getCount());
                for (int i = 0; i < amount; i++) {
                    client.gameMode.handleContainerInput(handler.containerId, uiSlot(handler, slot), 1, ContainerInput.PICKUP, client.player);
                    currentCount++;
                }
                client.gameMode.handleContainerInput(handler.containerId, src, 0, ContainerInput.PICKUP, client.player);
                changed = true;
            }
        }

        if (!changed) {
            send(client, "litematica_container_filler.message.tool_sync_already_matched");
        }
        return true;
    }

    private boolean prepareSyncShulkerExtraction(Minecraft client, AbstractContainerMenu handler, List<ItemStack> neededToFetch) {
        if (!canUseSyncShulkerExtraction()) {
            return false;
        }
        if (!handler.getCarried().isEmpty()) {
            send(client, "litematica_container_filler.message.cursor_stuck");
            return false;
        }

        if (tryStartSyncTakeItOutFetch(client, neededToFetch)) {
            RealContainerCache.updateFromHandler(client, handler);
            client.player.closeContainer();
            openedByTool = false;
            uiWaitTicks = 0;
            phase = Phase.WAITING_SYNC_TAKEITOUT;
            return true;
        }

        syncNeededToFetch.clear();
        syncNeededToFetch.addAll(copyNonEmpty(neededToFetch));
        syncPendingShulkers.clear();
        syncPendingShulkers.addAll(findShulkersContaining(client, syncNeededToFetch));
        if (syncPendingShulkers.isEmpty()) {
            return false;
        }

        RealContainerCache.updateFromHandler(client, handler);
        client.player.closeContainer();
        openedByTool = false;
        syncActiveShulkerSlot = -1;
        uiWaitTicks = 0;
        phase = Phase.OPENING_SYNC_SHULKER;
        send(client, "litematica_container_filler.message.tool_sync_fetching_shulker");
        return true;
    }

    private boolean packContainerBatch(Minecraft client, AbstractContainerMenu handler) {
        if (!ContainerToolMode.isPackingAvailable()) {
            Configs.CONTAINER_TOOL_MODE.setOptionListValue(ContainerToolMode.CLEAR);
            Configs.TOOL_ENABLED.setBooleanValue(false);
            fail(client, "litematica_container_filler.message.tool_pack_no_shulker_support");
            return true;
        }

        if (!handler.getCarried().isEmpty()) {
            fail(client, "litematica_container_filler.message.cursor_stuck");
            return true;
        }

        List<Slot> containerSlots = getContainerSlots(handler, client);
        Slot source = findNextPackSource(client, containerSlots);

        if (source == null) {
            RealContainerCache.updateFromHandler(client, handler);
            client.player.closeContainer();
            finish(client);
            return true;
        }

        ItemStack sourceStack = source.getItem().copy();
        packingShulkerSlot = findPackingShulker(client, sourceStack, -1, sourceStack.getCount());
        if (packingShulkerSlot < 0) {
            fail(client, "litematica_container_filler.message.tool_pack_no_shulker");
            return true;
        }

        packingCapacity = getShulkerCapacityForItem(client.player.getInventory().getItem(packingShulkerSlot), sourceStack);
        if (!isolateStackedPackingShulker(client, handler)) {
            fail(client, "litematica_container_filler.message.tool_pack_no_inventory_space");
            return true;
        }

        packingStack = sourceStack;
        packingCountBefore = countItemInPlayerInv(client, sourceStack);
        packingMovedCount = 0;
        packingBufferSlots.clear();

        List<Integer> emptyPlayerSlots = findEmptyPlayerSlots(client, packingShulkerSlot);
        if (emptyPlayerSlots.isEmpty()) {
            fail(client, "litematica_container_filler.message.tool_pack_no_inventory_space");
            return true;
        }

        int emptySlotCursor = 0;
        for (Slot slot : containerSlots) {
            if (emptySlotCursor >= emptyPlayerSlots.size()) break;
            if (packingMovedCount >= packingCapacity) break;
            if (!slot.hasItem() || !slot.mayPickup(client.player)) continue;
            if (!ItemMatcher.isSameItem(slot.getItem(), sourceStack)) continue;
            if (slot.getItem().getCount() > packingCapacity - packingMovedCount) continue;

            int playerSlot = emptyPlayerSlots.get(emptySlotCursor++);
            int uiPlayerSlot = getPlayerinventoryMenuSlot(handler, client, playerSlot);
            if (uiPlayerSlot < 0) continue;

            ItemStack moving = slot.getItem().copy();
            client.gameMode.handleContainerInput(handler.containerId, uiSlot(handler, slot), 0, ContainerInput.PICKUP, client.player);
            client.gameMode.handleContainerInput(handler.containerId, uiPlayerSlot, 0, ContainerInput.PICKUP, client.player);
            if (!handler.getCarried().isEmpty()) {
                client.gameMode.handleContainerInput(handler.containerId, uiSlot(handler, slot), 0, ContainerInput.PICKUP, client.player);
                fail(client, "litematica_container_filler.message.cursor_stuck");
                return true;
            }
            packingMovedCount += moving.getCount();
            packingBufferSlots.add(playerSlot);
        }

        RealContainerCache.updateFromHandler(client, handler);
        client.player.closeContainer();

        if (packingMovedCount <= 0) {
            fail(client, "litematica_container_filler.message.tool_pack_no_inventory_space");
            return true;
        }

        openedByTool = false;
        uiWaitTicks = 0;
        phase = Phase.OPENING_PACK_SHULKER;
        return true;
    }

    private void openPackingShulkerOrTimeout(Minecraft client) {
        if (!openedByTool) {
            openedByTool = true;
            uiWaitTicks = 0;
            if (getQuickShulkerOpenMode() == QuickShulkerOpenMode.SIMULATE_CLICK) {
                directRightClickPlayerSlot(client, packingShulkerSlot);
            } else if (!shulkerExtractor.requestOpenShulker(packingShulkerSlot)) {
                failPackingSupport(client);
            }
        } else if (++uiWaitTicks > 20) {
            failPackingSupport(client);
        }
    }

    private void openSyncShulkerOrTimeout(Minecraft client) {
        if (syncActiveShulkerSlot < 0) {
            if (syncPendingShulkers.isEmpty()) {
                fail(client, "litematica_container_filler.message.tool_missing_items_for_sync");
                return;
            }
            syncActiveShulkerSlot = syncPendingShulkers.iterator().next();
            syncPendingShulkers.remove(syncActiveShulkerSlot);
        }

        openedByTool = true;
        uiWaitTicks = 0;
        if (getQuickShulkerOpenMode() == QuickShulkerOpenMode.SIMULATE_CLICK) {
            directRightClickPlayerSlot(client, syncActiveShulkerSlot);
        } else if (!shulkerExtractor.requestOpenShulker(syncActiveShulkerSlot)) {
            fail(client, "litematica_container_filler.message.tool_missing_items_for_sync");
        }
    }

    private void extractSyncItemsFromShulker(Minecraft client, AbstractContainerMenu handler) {
        if (syncNeededToFetch.isEmpty()) return;

        Map<StrictItemStackKey, Integer> missing = new LinkedHashMap<>();
        Map<StrictItemStackKey, ItemStack> prototypes = new LinkedHashMap<>();
        Set<Integer> usedEmptyUiSlots = new LinkedHashSet<>();
        int remainingEmptySlots = countEmptyPlayerSlots(client);
        Map<StrictItemStackKey, Integer> partialSpaces = getPlayerPartialSpaces(client);

        for (ItemStack stack : syncNeededToFetch) {
            addCount(missing, prototypes, stack, stack.getCount());
        }

        for (Slot slot : getContainerSlots(handler, client)) {
            ItemStack slotStack = slot.getItem();
            if (slotStack.isEmpty()) continue;

            StrictItemStackKey key = new StrictItemStackKey(slotStack);
            int remaining = missing.getOrDefault(key, 0);
            if (remaining <= 0) continue;

            int partialSpace = partialSpaces.getOrDefault(key, 0);
            if (remainingEmptySlots <= 0 && partialSpace <= 0) continue;

            int amountAvailable = slotStack.getCount();
            int amountToTake = Math.min(remaining, amountAvailable);
            if (remainingEmptySlots <= 0) {
                amountToTake = Math.min(amountToTake, partialSpace);
            }
            if (amountToTake <= 0) continue;

            if (amountToTake == amountAvailable) {
                client.gameMode.handleContainerInput(handler.containerId, uiSlot(handler, slot), 0, ContainerInput.QUICK_MOVE, client.player);
            } else {
                int emptyUiSlot = findEmptyPlayerUiSlot(handler, client, usedEmptyUiSlots);
                if (emptyUiSlot >= 0) {
                    usedEmptyUiSlots.add(emptyUiSlot);
                    client.gameMode.handleContainerInput(handler.containerId, uiSlot(handler, slot), 0, ContainerInput.PICKUP, client.player);
                    for (int i = 0; i < amountToTake; i++) {
                        client.gameMode.handleContainerInput(handler.containerId, emptyUiSlot, 1, ContainerInput.PICKUP, client.player);
                    }
                    client.gameMode.handleContainerInput(handler.containerId, uiSlot(handler, slot), 0, ContainerInput.PICKUP, client.player);
                } else {
                    client.gameMode.handleContainerInput(handler.containerId, uiSlot(handler, slot), 0, ContainerInput.QUICK_MOVE, client.player);
                }
            }

            missing.put(key, Math.max(0, remaining - amountToTake));
            if (amountToTake > partialSpace) {
                remainingEmptySlots = Math.max(0, remainingEmptySlots - 1);
                int overflow = amountToTake - partialSpace;
                partialSpaces.put(key, Math.max(0, slotStack.getMaxStackSize() - overflow));
            } else {
                partialSpaces.put(key, Math.max(0, partialSpace - amountToTake));
            }
        }

        syncNeededToFetch.clear();
        for (Map.Entry<StrictItemStackKey, Integer> entry : missing.entrySet()) {
            int count = entry.getValue();
            if (count <= 0) continue;
            ItemStack stack = prototypes.get(entry.getKey()).copy();
            stack.setCount(count);
            syncNeededToFetch.add(stack);
        }
    }

    private void stashPackedStackIntoShulker(Minecraft client, AbstractContainerMenu handler) {
        int moved = 0;
        for (Slot slot : handler.slots) {
            if (slot.container != client.player.getInventory()) continue;
            if (!packingBufferSlots.contains(slot.getContainerSlot())) continue;
            if (!ItemMatcher.isSameItem(slot.getItem(), packingStack)) continue;
            int before = slot.getItem().getCount();
            client.gameMode.handleContainerInput(handler.containerId, uiSlot(handler, slot), 0, ContainerInput.QUICK_MOVE, client.player);
            moved += before;
        }

        if (moved == 0 && countItemInPlayerInv(client, packingStack) >= packingCountBefore + packingMovedCount) {
            send(client, "litematica_container_filler.message.tool_pack_no_matching_stack");
        }
    }

    private Slot findNextPackSource(Minecraft client, List<Slot> containerSlots) {
        Slot fallback = null;
        for (Slot slot : containerSlots) {
            if (!slot.hasItem()) continue;
            if (!slot.mayPickup(client.player)) continue;
            if (packingLastTargetSlot >= 0 && slot.getContainerSlot() <= packingLastTargetSlot) continue;
            if (fallback == null || slot.getContainerSlot() < fallback.getContainerSlot()) {
                fallback = slot;
            }
        }

        if (fallback != null) {
            packingLastTargetSlot = fallback.getContainerSlot();
            return fallback;
        }

        packingLastTargetSlot = -1;
        for (Slot slot : containerSlots) {
            if (slot.hasItem() && slot.mayPickup(client.player)) {
                packingLastTargetSlot = slot.getContainerSlot();
                return slot;
            }
        }
        return null;
    }

    private boolean hasEnoughItemsForSync(Minecraft client, AbstractContainerMenu handler, List<Slot> containerSlots) {
        return computeNeededItemsForSync(client, handler, containerSlots).isEmpty();
    }

    private List<ItemStack> computeNeededItemsForSync(Minecraft client, AbstractContainerMenu handler, List<Slot> containerSlots) {
        Map<StrictItemStackKey, Integer> available = getAvailableInventoryItems(client);
        Map<StrictItemStackKey, Integer> needed = new LinkedHashMap<>();
        Map<StrictItemStackKey, ItemStack> prototypes = new LinkedHashMap<>();

        for (Slot slot : containerSlots) {
            ItemStack target = syncTemplate.getOrDefault(slot.getContainerSlot(), ItemStack.EMPTY);
            ItemStack current = slot.getItem();
            if (target.isEmpty()) continue;
            int currentCount = ItemMatcher.isSameItem(current, target) ? Math.min(current.getCount(), target.getCount()) : 0;
            int missing = target.getCount() - currentCount;
            if (missing > 0) {
                addCount(needed, prototypes, target, missing);
            }
        }

        List<ItemStack> result = new ArrayList<>();
        for (Map.Entry<StrictItemStackKey, Integer> entry : needed.entrySet()) {
            int missing = entry.getValue() - available.getOrDefault(entry.getKey(), 0);
            if (missing > 0) {
                ItemStack stack = prototypes.get(entry.getKey()).copy();
                stack.setCount(missing);
                result.add(stack);
            }
        }
        return result;
    }

    private boolean hasEnoughItemsForSync(Minecraft client, List<ItemStack> needed) {
        Map<StrictItemStackKey, Integer> available = getAvailableInventoryItems(client);
        for (ItemStack stack : needed) {
            if (stack.isEmpty()) continue;
            StrictItemStackKey key = new StrictItemStackKey(stack);
            if (available.getOrDefault(key, 0) < stack.getCount()) {
                return false;
            }
        }
        return true;
    }

    private Map<StrictItemStackKey, Integer> getAvailableInventoryItems(Minecraft client) {
        Map<StrictItemStackKey, Integer> available = new HashMap<>();
        for (int i = 0; i < 36; i++) {
            ItemStack stack = client.player.getInventory().getItem(i);
            if (!stack.isEmpty()) {
                addCount(available, stack, stack.getCount());
            }
        }
        return available;
    }

    private void addCount(Map<StrictItemStackKey, Integer> map, ItemStack stack, int count) {
        StrictItemStackKey key = new StrictItemStackKey(stack);
        map.put(key, map.getOrDefault(key, 0) + count);
    }

    private void addCount(Map<StrictItemStackKey, Integer> counts, Map<StrictItemStackKey, ItemStack> prototypes, ItemStack stack, int count) {
        StrictItemStackKey key = new StrictItemStackKey(stack);
        counts.put(key, counts.getOrDefault(key, 0) + count);
        prototypes.putIfAbsent(key, stack.copy());
    }

    private List<ItemStack> copyNonEmpty(List<ItemStack> stacks) {
        List<ItemStack> copy = new ArrayList<>();
        for (ItemStack stack : stacks) {
            if (!stack.isEmpty()) {
                copy.add(stack.copy());
            }
        }
        return copy;
    }

    private Set<Integer> findShulkersContaining(Minecraft client, List<ItemStack> needed) {
        Set<Integer> slots = new LinkedHashSet<>();
        if (!canOpenShulkerUi()) return slots;

        for (ItemStack req : needed) {
            int amountToFind = req.getCount();
            for (int i = 0; i < 36; i++) {
                if (amountToFind <= 0) break;

                ItemStack shulker = client.player.getInventory().getItem(i);
                if (!isShulkerBox(shulker)) continue;

                ItemContainerContents component = shulker.get(DataComponents.CONTAINER);
                if (component == null) continue;

                for (ItemStack inner : component.allItemsCopyStream().toList()) {
                    if (ItemMatcher.isSameItem(inner, req)) {
                        slots.add(i);
                        amountToFind -= inner.getCount();
                    }
                }
            }
        }
        return slots;
    }

    private Map<Integer, ItemStack> readContainerSlots(AbstractContainerMenu handler, Minecraft client) {
        Map<Integer, ItemStack> items = new LinkedHashMap<>();
        for (Slot slot : getContainerSlots(handler, client)) {
            if (!slot.getItem().isEmpty()) {
                items.put(slot.getContainerSlot(), slot.getItem().copy());
            }
        }
        return items;
    }

    private List<Slot> getContainerSlots(AbstractContainerMenu handler, Minecraft client) {
        List<Slot> slots = new ArrayList<>();
        for (Slot slot : handler.slots) {
            if (slot.container == null || slot.container == client.player.getInventory()) continue;
            if (!slot.isActive()) continue;
            slots.add(slot);
        }
        return slots;
    }

    private Slot findMostCommonPlayerSlot(AbstractContainerMenu handler, Minecraft client, List<Slot> containerSlots) {
        Map<Item, Integer> counts = new HashMap<>();
        Map<Item, Slot> firstSlot = new HashMap<>();

        for (Slot slot : handler.slots) {
            if (slot.container != client.player.getInventory() || !slot.hasItem()) continue;
            ItemStack stack = slot.getItem();
            if (!canAnyContainerSlotReceive(containerSlots, stack)) continue;
            counts.put(stack.getItem(), counts.getOrDefault(stack.getItem(), 0) + 1);
            firstSlot.putIfAbsent(stack.getItem(), slot);
        }

        Item best = null;
        int bestCount = 0;
        boolean tied = false;
        for (Map.Entry<Item, Integer> entry : counts.entrySet()) {
            if (entry.getValue() > bestCount) {
                best = entry.getKey();
                bestCount = entry.getValue();
                tied = false;
            } else if (entry.getValue() == bestCount) {
                tied = true;
            }
        }

        return best == null || tied ? null : firstSlot.get(best);
    }

    private boolean canAnyContainerSlotReceive(List<Slot> slots, ItemStack stack) {
        for (Slot slot : slots) {
            if (canReceive(slot, stack)) return true;
        }
        return false;
    }

    private int findPlayerSlotWithItem(AbstractContainerMenu handler, Minecraft client, ItemStack target) {
        for (Slot slot : handler.slots) {
            if (slot.container == client.player.getInventory() && ItemMatcher.isSameItem(slot.getItem(), target)) {
                return uiSlot(handler, slot);
            }
        }
        return -1;
    }

    private int findEmptyPlayerSlot(Minecraft client) {
        for (int i = 9; i < 36; i++) {
            if (client.player.getInventory().getItem(i).isEmpty()) return i;
        }
        for (int i = 0; i < 9; i++) {
            if (client.player.getInventory().getItem(i).isEmpty()) return i;
        }
        return -1;
    }

    private List<Integer> findEmptyPlayerSlots(Minecraft client, int excludedPlayerSlot) {
        List<Integer> slots = new ArrayList<>();
        for (int i = 9; i < 36; i++) {
            if (i == excludedPlayerSlot) continue;
            if (client.player.getInventory().getItem(i).isEmpty()) slots.add(i);
        }
        for (int i = 0; i < 9; i++) {
            if (i == excludedPlayerSlot) continue;
            if (client.player.getInventory().getItem(i).isEmpty()) slots.add(i);
        }
        return slots;
    }

    private int getPlayerinventoryMenuSlot(AbstractContainerMenu handler, Minecraft client, int playerSlot) {
        for (Slot slot : handler.slots) {
            if (slot.container == client.player.getInventory() && slot.getContainerSlot() == playerSlot) {
                return uiSlot(handler, slot);
            }
        }
        return playerSlot < 9 ? playerSlot + 36 : playerSlot;
    }

    private int countItemInPlayerInv(Minecraft client, ItemStack target) {
        int count = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = client.player.getInventory().getItem(i);
            if (ItemMatcher.isSameItem(stack, target)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private int countEmptyPlayerSlots(Minecraft client) {
        int count = 0;
        for (int i = 0; i < 36; i++) {
            if (client.player.getInventory().getItem(i).isEmpty()) {
                count++;
            }
        }
        return count;
    }

    private Map<StrictItemStackKey, Integer> getPlayerPartialSpaces(Minecraft client) {
        Map<StrictItemStackKey, Integer> spaces = new HashMap<>();
        for (int i = 0; i < 36; i++) {
            ItemStack stack = client.player.getInventory().getItem(i);
            if (!stack.isEmpty()) {
                StrictItemStackKey key = new StrictItemStackKey(stack);
                spaces.put(key, spaces.getOrDefault(key, 0) + Math.max(0, stack.getMaxStackSize() - stack.getCount()));
            }
        }
        return spaces;
    }

    private int findEmptyPlayerUiSlot(AbstractContainerMenu handler, Minecraft client, Set<Integer> usedUiSlots) {
        for (Slot slot : handler.slots) {
            int slotId = uiSlot(handler, slot);
            if (slot.container == client.player.getInventory() && slot.getItem().isEmpty() && !usedUiSlots.contains(slotId)) {
                return slotId;
            }
        }
        return -1;
    }

    private boolean canAbsorb(Minecraft client, ItemStack stack) {
        if (stack.isEmpty()) return true;
        for (int i = 0; i < 36; i++) {
            ItemStack invStack = client.player.getInventory().getItem(i);
            if (invStack.isEmpty()) return true;
            if (ItemMatcher.isSameItem(invStack, stack) && invStack.getCount() < invStack.getMaxStackSize()) {
                return true;
            }
        }
        return false;
    }

    private boolean canAbsorbContainerIntoPlayerInventory(Minecraft client, AbstractContainerMenu handler) {
        Map<StrictItemStackKey, Integer> incoming = new HashMap<>();
        int incomingStacksNeedingEmptySlots = 0;
        for (Slot slot : getContainerSlots(handler, client)) {
            if (!slot.hasItem() || !slot.mayPickup(client.player)) continue;
            ItemStack stack = slot.getItem();
            StrictItemStackKey key = new StrictItemStackKey(stack);
            incoming.put(key, incoming.getOrDefault(key, 0) + stack.getCount());
        }

        if (incoming.isEmpty()) return true;

        int emptySlots = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack invStack = client.player.getInventory().getItem(i);
            if (invStack.isEmpty()) {
                emptySlots++;
                continue;
            }

            StrictItemStackKey key = new StrictItemStackKey(invStack);
            int remaining = incoming.getOrDefault(key, 0);
            if (remaining > 0) {
                remaining -= Math.max(0, invStack.getMaxStackSize() - invStack.getCount());
                if (remaining <= 0) {
                    incoming.remove(key);
                } else {
                    incoming.put(key, remaining);
                }
            }
        }

        for (Map.Entry<StrictItemStackKey, Integer> entry : incoming.entrySet()) {
            ItemStack stack = entry.getKey().stack;
            int maxCount = Math.max(1, stack.getMaxStackSize());
            incomingStacksNeedingEmptySlots += (entry.getValue() + maxCount - 1) / maxCount;
            if (incomingStacksNeedingEmptySlots > emptySlots) {
                return false;
            }
        }
        return true;
    }

    private boolean isolateStackedPackingShulker(Minecraft client, AbstractContainerMenu handler) {
        ItemStack shulker = client.player.getInventory().getItem(packingShulkerSlot);
        if (!isShulkerBox(shulker) || shulker.getCount() <= 1) return true;
        if (!handler.getCarried().isEmpty()) return false;

        int isolatedPlayerSlot = -1;
        for (int playerSlot : findEmptyPlayerSlots(client, packingShulkerSlot)) {
            isolatedPlayerSlot = playerSlot;
            break;
        }
        if (isolatedPlayerSlot < 0) return false;

        int sourceUiSlot = getPlayerinventoryMenuSlot(handler, client, packingShulkerSlot);
        int isolatedUiSlot = getPlayerinventoryMenuSlot(handler, client, isolatedPlayerSlot);
        if (sourceUiSlot < 0 || isolatedUiSlot < 0) return false;

        int syncId = handler.containerId;
        client.gameMode.handleContainerInput(syncId, sourceUiSlot, 0, ContainerInput.PICKUP, client.player);
        client.gameMode.handleContainerInput(syncId, isolatedUiSlot, 1, ContainerInput.PICKUP, client.player);
        client.gameMode.handleContainerInput(syncId, sourceUiSlot, 0, ContainerInput.PICKUP, client.player);

        if (!handler.getCarried().isEmpty()) {
            client.gameMode.handleContainerInput(syncId, sourceUiSlot, 0, ContainerInput.PICKUP, client.player);
            return false;
        }

        ItemStack isolated = client.player.getInventory().getItem(isolatedPlayerSlot);
        if (!isShulkerBox(isolated)) return false;

        packingShulkerSlot = isolatedPlayerSlot;
        return true;
    }

    private int findPackingShulker(Minecraft client, ItemStack stack, int excludedPlayerSlot, int minimumCapacity) {
        int firstAnySpaceShulker = -1;
        for (int i = 0; i < 36; i++) {
            if (i == excludedPlayerSlot) continue;
            ItemStack shulker = client.player.getInventory().getItem(i);
            if (!isShulkerBox(shulker)) continue;
            int capacity = getShulkerCapacityForItem(shulker, stack);
            if (capacity < minimumCapacity) continue;
            if (canShulkerAccept(shulker, stack, true)) return i;
            if (firstAnySpaceShulker < 0 && canShulkerAccept(shulker, stack, false)) {
                firstAnySpaceShulker = i;
            }
        }
        return firstAnySpaceShulker;
    }

    private boolean canShulkerAccept(ItemStack shulker, ItemStack stack, boolean requireMatching) {
        ItemContainerContents component = shulker.get(DataComponents.CONTAINER);
        if (component == null) {
            return !requireMatching;
        }

        List<ItemStack> innerStacks = component.allItemsCopyStream().toList();
        if (innerStacks.isEmpty()) {
            return !requireMatching;
        }

        boolean hasMatching = false;
        boolean hasSpace = innerStacks.size() < 27;
        for (ItemStack inner : innerStacks) {
            if (inner.isEmpty()) {
                hasSpace = true;
            } else if (ItemMatcher.isSameItem(inner, stack)) {
                hasMatching = true;
                if (inner.getCount() < inner.getMaxStackSize()) {
                    hasSpace = true;
                }
            }
        }

        return hasSpace && (!requireMatching || hasMatching);
    }

    private int getShulkerCapacityForItem(ItemStack shulker, ItemStack stack) {
        if (!isShulkerBox(shulker) || stack.isEmpty()) return 0;

        ItemContainerContents component = shulker.get(DataComponents.CONTAINER);
        if (component == null) {
            return 27 * stack.getMaxStackSize();
        }

        List<ItemStack> innerStacks = component.allItemsCopyStream().toList();
        int capacity = Math.max(0, 27 - innerStacks.size()) * stack.getMaxStackSize();
        for (ItemStack inner : innerStacks) {
            if (ItemMatcher.isSameItem(inner, stack)) {
                capacity += Math.max(0, inner.getMaxStackSize() - inner.getCount());
            }
        }
        return capacity;
    }

    private boolean isShulkerBox(ItemStack stack) {
        return stack.getItem() instanceof BlockItem blockItem && blockItem.getBlock() instanceof ShulkerBoxBlock;
    }

    private QuickShulkerOpenMode getQuickShulkerOpenMode() {
        if (Configs.QUICK_SHULKER_OPEN_MODE.getOptionListValue() instanceof QuickShulkerOpenMode mode) {
            return mode;
        }
        return QuickShulkerOpenMode.INVOKE;
    }

    private boolean canUseSyncShulkerExtraction() {
        if (!Configs.ENABLE_SYNC_TOOL_QS_EXTRACTION.getBooleanValue() || !Configs.ENABLE_QS_EXTRACTION.getBooleanValue()) {
            return false;
        }
        return TakeItOutCompat.canRequestStack() || canOpenShulkerUi();
    }

    private boolean canOpenShulkerUi() {
        if (!Configs.ENABLE_QS_EXTRACTION.getBooleanValue()) return false;
        return getQuickShulkerOpenMode() == QuickShulkerOpenMode.SIMULATE_CLICK || DependencyChecker.HAS_QUICK_SHULKER;
    }

    private boolean tryStartSyncTakeItOutFetch(Minecraft client, List<ItemStack> needed) {
        if (!Configs.ENABLE_SYNC_TOOL_QS_EXTRACTION.getBooleanValue() || !Configs.ENABLE_QS_EXTRACTION.getBooleanValue()) return false;
        if (!TakeItOutCompat.canRequestStack()) return false;
        if (syncTakeItOutBlocked) return false;
        if (findEmptyPlayerSlot(client) < 0) return false;

        TakeItOutRequest request = findTakeItOutRequest(client, needed);
        if (request == null || !TakeItOutCompat.requestStack(request.innerSlot(), request.shulkerSlot())) {
            return false;
        }

        syncPendingTakeItOutRequest = request;
        syncTakeItOutWaitTicks = 0;
        send(client, "litematica_container_filler.message.tool_sync_fetching_shulker");
        return true;
    }

    private boolean hasTakeItOutResultArrived(Minecraft client, TakeItOutRequest request) {
        if (request == null || request.requestedStack().isEmpty()) return true;
        if (countItemInPlayerInv(client, request.requestedStack()) > request.countBefore()) return true;

        ItemStack shulker = client.player.getInventory().getItem(request.shulkerSlot());
        if (!isShulkerBox(shulker)) return true;

        ItemContainerContents component = shulker.get(DataComponents.CONTAINER);
        if (component == null) return true;

        List<ItemStack> innerStacks = component.allItemsCopyStream().toList();
        if (request.innerSlot() < 0 || request.innerSlot() >= innerStacks.size()) return true;

        ItemStack inner = innerStacks.get(request.innerSlot());
        return inner.isEmpty() || !ItemMatcher.isSameItem(inner, request.requestedStack());
    }

    private TakeItOutRequest findTakeItOutRequest(Minecraft client, List<ItemStack> needed) {
        for (ItemStack req : needed) {
            for (int shulkerSlot = 0; shulkerSlot < 36; shulkerSlot++) {
                ItemStack shulker = client.player.getInventory().getItem(shulkerSlot);
                if (!isShulkerBox(shulker)) continue;

                ItemContainerContents component = shulker.get(DataComponents.CONTAINER);
                if (component == null) continue;

                List<ItemStack> innerStacks = component.allItemsCopyStream().toList();
                for (int innerSlot = 0; innerSlot < innerStacks.size(); innerSlot++) {
                    ItemStack inner = innerStacks.get(innerSlot);
                    if (ItemMatcher.isSameItem(inner, req)) {
                        return new TakeItOutRequest(shulkerSlot, innerSlot, req.copy(), countItemInPlayerInv(client, req));
                    }
                }
            }
        }
        return null;
    }

    private ContainerClearOutputMode getClearOutputMode() {
        if (Configs.CONTAINER_CLEAR_OUTPUT_MODE.getOptionListValue() instanceof ContainerClearOutputMode mode) {
            return mode;
        }
        return ContainerClearOutputMode.DROP;
    }

    private void directRightClickPlayerSlot(Minecraft client, int playerSlot) {
        if (client.player == null || client.gameMode == null) return;
        if (client.player.containerMenu != client.player.inventoryMenu) return;

        AbstractContainerMenu handler = client.player.containerMenu;
        int uiSlot = getPlayerinventoryMenuSlot(handler, client, playerSlot);
        if (uiSlot >= 0) {
            client.gameMode.handleContainerInput(handler.containerId, uiSlot, 1, ContainerInput.PICKUP, client.player);
            restoreCursorShulkerIfClickWasVanilla(client, handler.containerId, uiSlot);
        }
    }

    private void restoreCursorShulkerIfClickWasVanilla(Minecraft client, int syncId, int uiSlot) {
        if (client.player == null) return;
        AbstractContainerMenu handler = client.player.containerMenu;
        if (handler != client.player.inventoryMenu) return;

        if (isShulkerBox(handler.getCarried())) {
            client.gameMode.handleContainerInput(syncId, uiSlot, 0, ContainerInput.PICKUP, client.player);
        }
    }

    private boolean canReceive(Slot slot, ItemStack stack) {
        if (slot == null || stack == null || stack.isEmpty()) return false;
        ItemStack current = slot.getItem();
        if (!current.isEmpty() && (!ItemMatcher.isSameItem(current, stack) || current.getCount() >= current.getMaxStackSize())) return false;
        return slot.mayPlace(stack);
    }

    private void finish(Minecraft client) {
        if (!currentOperationContinuous) {
            send(client, "litematica_container_filler.message.tool_done");
        }
        reset(client, false, true);
    }

    private void fail(Minecraft client, String messageKey) {
        if (!currentOperationContinuous) {
            send(client, messageKey);
        }
        reset(client, true, true);
    }

    private void failPackingSupport(Minecraft client) {
        Configs.CONTAINER_TOOL_MODE.setOptionListValue(ContainerToolMode.CLEAR);
        Configs.TOOL_ENABLED.setBooleanValue(false);
        fail(client, "litematica_container_filler.message.tool_pack_no_shulker_support");
    }

    private void reset(Minecraft client, boolean closeScreen, boolean keepTemplate) {
        if (closeScreen && client != null && client.player != null && !(client.player.containerMenu instanceof InventoryMenu)) {
            client.player.closeContainer();
        }

        scheduleContinuousCooldown();
        phase = Phase.IDLE;
        mode = null;
        currentPos = null;
        uiWaitTicks = 0;
        openedByTool = false;
        packingShulkerSlot = -1;
        packingStack = ItemStack.EMPTY;
        packingCountBefore = 0;
        packingMovedCount = 0;
        packingCapacity = 0;
        packingLastTargetSlot = -1;
        packingBufferSlots.clear();
        syncNeededToFetch.clear();
        syncPendingShulkers.clear();
        syncActiveShulkerSlot = -1;
        syncTakeItOutWaitTicks = 0;
        syncPendingTakeItOutRequest = null;
        syncTakeItOutBlocked = false;
        currentOperationContinuous = false;
        ClickPacketRateLimiter.setOperationActive(AutoFillerStateMachine.getInstance().isWorking());
        if (!keepTemplate) {
            syncTemplate.clear();
            syncTemplatePos = null;
            syncTemplateBlockId = null;
        }
    }

    private void scheduleContinuousCooldown() {
        if (currentOperationContinuous && currentPos != null && mode != null) {
            continuousLastTarget = currentPos.immutable();
            continuousLastMode = mode;
            continuousSameTargetCooldownTicks = Math.max(0, Configs.TOOL_REPEAT_SAME_CONTAINER_COOLDOWN.getIntegerValue());
        }
    }

    private void send(Minecraft client, String key) {
        if (currentOperationContinuous) return;
        if (client != null && client.player != null) {
            client.player.sendOverlayMessage(Component.translatable(key));
        }
    }

    private void send(Minecraft client, String key, Object... args) {
        if (currentOperationContinuous) return;
        if (client != null && client.player != null) {
            client.player.sendOverlayMessage(Component.translatable(key, args));
        }
    }

    private static final class StrictItemStackKey {
        private final ItemStack stack;

        private StrictItemStackKey(ItemStack stack) {
            this.stack = stack.copy();
            this.stack.setCount(1);
        }

        @Override
        public boolean equals(Object obj) {
            return obj instanceof StrictItemStackKey other && ItemMatcher.isSameItem(this.stack, other.stack);
        }

        @Override
        public int hashCode() {
            return ItemMatcher.matchingHash(this.stack);
        }
    }

    private record TakeItOutRequest(int shulkerSlot, int innerSlot, ItemStack requestedStack, int countBefore) {
    }
}


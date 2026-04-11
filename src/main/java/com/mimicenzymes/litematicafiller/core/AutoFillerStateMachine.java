package com.mimicenzymes.litematicafiller.core;

import com.mimicenzymes.litematicafiller.config.Configs;
import com.mimicenzymes.litematicafiller.dependency.DependencyChecker;
import com.mimicenzymes.litematicafiller.dependency.DummyExtractor;
import com.mimicenzymes.litematicafiller.dependency.IShulkerExtractor;
import com.mimicenzymes.litematicafiller.dependency.QuickShulkerWrapper;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.CrafterMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

public class AutoFillerStateMachine {

    public enum Phase {
        IDLE,
        AWAITING_DATA,
        INSPECTING,
        STASHING,
        GATHERING,
        FILLING,
        RETURNING
    }

    public static class FillTask {
        public final BlockPos targetPos;
        public final Map<Integer, ItemStack> requiredItems;
        public Map<Integer, ItemStack> missingItems;
        public boolean needsInspection;

        public FillTask(BlockPos targetPos, Map<Integer, ItemStack> requiredItems, Map<Integer, ItemStack> missingItems, boolean needsInspection) {
            this.targetPos = targetPos;
            this.requiredItems = requiredItems;
            this.missingItems = missingItems;
            this.needsInspection = needsInspection;
        }
    }

    private static final AutoFillerStateMachine INSTANCE = new AutoFillerStateMachine();
    public static AutoFillerStateMachine getInstance() { return INSTANCE; }

    private final Queue<FillTask> taskQueue = new ConcurrentLinkedQueue<>();
    private FillTask currentTask = null;
    private SlotMapper currentMapper = null;

    private Phase currentPhase = Phase.IDLE;
    private final Deque<Runnable> actionQueue = new LinkedList<>();
    private final Queue<Integer> pendingShulkers = new LinkedList<>();

    private int actionWaitTicks = 0;
    private int watchdogTimer = 0;
    private int uiWaitTimer = 0;
    private int dataWaitTimer = 0;
    private boolean silentlyExtracting = false;
    private boolean yieldTick = false;

    private int activeShulkerSlot = -1;
    private int stashShulkerSlot = -1;
    private int stashItemSlot = -1;

    private final Set<Item> borrowedItems = new HashSet<>();
    private final Map<Item, Integer> stashedItemCounts = new HashMap<>();

    private final Set<Integer> openedShulkerSlots = new LinkedHashSet<>();
    private final Map<Integer, Set<Item>> shulkerMisses = new HashMap<>();
    private final Map<BlockPos, Set<Item>> failedContainers = new ConcurrentHashMap<>();

    private boolean lastContinuousState = false;
    private int tickCounter = 0;
    private int consecutiveFailures = 0;
    private final IShulkerExtractor shulkerExtractor;

    private static java.lang.reflect.Field CACHE_FIELD = null;
    private static java.lang.reflect.Field NBT_QUERY_CACHE_FIELD = null;
    static {
        try {
            CACHE_FIELD = RealContainerCache.class.getDeclaredField("CACHE");
            CACHE_FIELD.setAccessible(true);
            NBT_QUERY_CACHE_FIELD = RealContainerCache.class.getDeclaredField("NBT_QUERY_CACHE");
            NBT_QUERY_CACHE_FIELD.setAccessible(true);
        } catch (Exception ignored) {}
    }

    private AutoFillerStateMachine() {
        this.shulkerExtractor = DependencyChecker.HAS_QUICK_SHULKER ? new QuickShulkerWrapper() : new DummyExtractor();
    }

    private int getDelay(int baseTicks) {
        if (!Configs.ENABLE_SAFETY_DELAY.getBooleanValue()) {
            return 0;
        }
        return baseTicks + Configs.FILL_DELAY.getIntegerValue();
    }

    @SuppressWarnings("unchecked")
    private Map<Integer, ItemStack> getReliableCache(BlockPos pos) {
        try {
            if (CACHE_FIELD != null) {
                Map<BlockPos, Map<Integer, ItemStack>> cache = (Map<BlockPos, Map<Integer, ItemStack>>) CACHE_FIELD.get(null);
                if (cache.containsKey(pos)) return cache.get(pos);
            }
            if (NBT_QUERY_CACHE_FIELD != null) {
                Map<BlockPos, Map<Integer, ItemStack>> nbtCache = (Map<BlockPos, Map<Integer, ItemStack>>) NBT_QUERY_CACHE_FIELD.get(null);
                if (nbtCache.containsKey(pos)) return nbtCache.get(pos);
            }
        } catch (Exception ignored) {}
        return null;
    }

    private Map<Integer, ItemStack> getTrueContainerData(Minecraft client, BlockPos pos) {

        pos = pos.immutable();
        final BlockPos finalPos = pos;

        Map<Integer, ItemStack> verifiedCache = getReliableCache(finalPos);
        if (verifiedCache != null) {
            return verifiedCache;
        }

        if (client.isLocalServer() && client.getSingleplayerServer() != null && client.level != null && client.player != null) {

            ServerPlayer serverPlayer = client.getSingleplayerServer().getPlayerList().getPlayer(client.player.getUUID());
            if (serverPlayer != null) {
                ServerLevel serverWorld = (ServerLevel) serverPlayer.level();

                if (serverWorld != null) {
                    net.minecraft.world.level.block.state.BlockState clientState = client.level.getBlockState(finalPos);
                    final BlockPos[] halves = LitematicaContainerReader.getDoubleContainerHalves(client.level, finalPos, clientState);

                    client.getSingleplayerServer().execute(() -> {
                        net.minecraft.world.level.block.state.BlockState state = serverWorld.getBlockState(finalPos);
                        Map<Integer, ItemStack> inventoryData = null;

                        if (halves != null && state.is(net.minecraft.world.level.block.Blocks.BARREL)) {
                            Map<Integer, ItemStack> right = getSingleBlockEntityInventory(serverWorld, halves[0]);
                            Map<Integer, ItemStack> left = getSingleBlockEntityInventory(serverWorld, halves[1]);
                            if (right != null && left != null) {
                                Map<Integer, ItemStack> combined = new HashMap<>(right);
                                left.forEach((k, v) -> combined.put(k + 27, v));
                                inventoryData = combined;
                            }
                        } else {
                            inventoryData = getSingleBlockEntityInventory(serverWorld, finalPos);
                        }

                        if (inventoryData != null) {
                            if (halves != null) {
                                RealContainerCache.put(halves[0].immutable(), inventoryData);
                                RealContainerCache.put(halves[1].immutable(), inventoryData);
                            } else {
                                RealContainerCache.put(finalPos, inventoryData);
                            }
                        }

                        if (state.getBlock() instanceof net.minecraft.world.level.block.CrafterBlock) {
                            net.minecraft.world.level.block.entity.BlockEntity be = serverWorld.getBlockEntity(finalPos);
                            if (be != null) {
                                net.minecraft.nbt.CompoundTag nbt = be.saveWithoutMetadata(serverWorld.registryAccess());
                                Set<Integer> locks = RealContainerCache.parseDisabledSlots(nbt);
                                RealContainerCache.putLock(finalPos, locks);
                            }
                        }
                    });
                }
            }
        }

        Map<Integer, ItemStack> fallback = RealContainerCache.getCachedItems(pos);

        if (fallback != null && fallback.isEmpty()) {
            return null;
        }

        return fallback;
    }

    private Map<Integer, ItemStack> getSingleBlockEntityInventory(net.minecraft.server.level.ServerLevel world, BlockPos pos) {
        net.minecraft.world.level.block.entity.BlockEntity be = world.getBlockEntity(pos);

        if (be == null) {
            return null;
        }

        net.minecraft.world.Container inv = null;
        net.minecraft.world.level.block.state.BlockState state = world.getBlockState(pos);

        if (state.getBlock() instanceof net.minecraft.world.level.block.ChestBlock chest) {
            inv = net.minecraft.world.level.block.ChestBlock.getContainer(
                    chest,
                    state,
                    world,
                    pos,
                    true
            );
        }

        if (inv == null && be instanceof net.minecraft.world.Container inventory) {
            inv = inventory;
        }

        if (inv != null) {
            Map<Integer, ItemStack> map = new HashMap<>();
            for (int i = 0; i < inv.getContainerSize(); i++) {
                ItemStack stack = inv.getItem(i);
                if (!stack.isEmpty()) {
                    map.put(i, stack.copy());
                }
            }
            return map;
        }

        net.minecraft.nbt.CompoundTag nbt = be.saveWithoutMetadata(world.registryAccess());

        if (nbt != null && nbt.contains("Items")) {
            return RealContainerCache.parseNbtInventory(nbt, world.registryAccess());
        }

        return null;
    }

    private Map<Integer, ItemStack> inventoryToMap(net.minecraft.world.Container inv) {
        Map<Integer, ItemStack> map = new HashMap<>();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack != null && !stack.isEmpty()) {
                map.put(i, stack.copy());
            }
        }
        return map;
    }

    private static int transactionIdCounter = 0;
    private void requestNbtUpdate(Minecraft client, BlockPos pos) {
        if (!client.isLocalServer() && client.player != null && client.player.canUseGameMasterBlocks()) {
            try {
                if (Configs.ENABLE_OP_NBT_QUERY.getBooleanValue() && client.getConnection() != null) {
                    client.getConnection().send(new net.minecraft.network.protocol.game.ServerboundBlockEntityTagQueryPacket(transactionIdCounter++, pos));
                }
            } catch (Exception ignored) {}
        }
    }

    public void addTask(BlockPos pos, Map<Integer, ItemStack> requiredItems) {
        if (requiredItems == null || requiredItems.isEmpty()) {
            return;
        }

        if (failedContainers.containsKey(pos)) return;
        if (currentTask != null && currentTask.targetPos.equals(pos)) return;
        for (FillTask t : taskQueue) {
            if (t.targetPos.equals(pos)) return;
        }

        Minecraft client = Minecraft.getInstance();
        if (client.player == null) return;

        Map<Integer, ItemStack> trueData = getTrueContainerData(client, pos);

        if (trueData == null) {
            taskQueue.add(new FillTask(pos, requiredItems, new HashMap<>(), true));
            requestNbtUpdate(client, pos);
            return;
        }

        boolean needsAction = false;
        Map<Integer, ItemStack> missingItems = new HashMap<>();

        for (int i = 0; i < 54; i++) {
            ItemStack req = requiredItems.getOrDefault(i, ItemStack.EMPTY);
            ItemStack cur = trueData.getOrDefault(i, ItemStack.EMPTY);

            if (req.isEmpty() && cur.isEmpty()) continue;

            if (req.isEmpty() && !cur.isEmpty()) {
                needsAction = true;
            } else if (!req.isEmpty() && cur.isEmpty()) {
                needsAction = true;
                missingItems.put(i, req.copy());
            } else if (!ItemMatcher.isSameItem(req, cur)) {
                needsAction = true;
                missingItems.put(i, req.copy());
            } else if (cur.getCount() < req.getCount()) {
                needsAction = true;
                ItemStack diff = req.copy();
                diff.setCount(req.getCount() - cur.getCount());
                missingItems.put(i, diff);
            } else if (cur.getCount() > req.getCount()) {
                needsAction = true;
            }
        }

        boolean isCrafter = client.level.getBlockState(pos).getBlock() instanceof net.minecraft.world.level.block.CrafterBlock;
        if (isCrafter && LitematicaContainerReader.doesCrafterNeedLocking(pos, client)) {
            needsAction = true;
        }

        if (!needsAction) return;

        taskQueue.add(new FillTask(pos, requiredItems, missingItems, false));
    }

    private boolean checkMaterialsAndPrepare(Minecraft client) {
        if (currentTask.missingItems.isEmpty()) return true;

        int emptySlots = getEmptySlots(client).size();
        if (emptySlots == 0) {
            boolean hasItemsToFill = false;
            for (ItemStack req : currentTask.missingItems.values()) {
                if (countItemInPlayerInv(client, req) > 0) {
                    hasItemsToFill = true;
                    break;
                }
            }
            if (!hasItemsToFill) {
                if (!Configs.AUTO_STASH_ITEMS.getBooleanValue() || findStashAction(client, currentTask.missingItems.values()) == null) {
                    sendFeedback(client, Component.translatable("litematica_container_filler.message.inventory_full_no_stash").getString(), true);
                    return false;
                }
            }
        }

        boolean hasAtLeastOneMaterial = false;
        Set<Item> missingTypes = new LinkedHashSet<>();
        for (ItemStack req : currentTask.missingItems.values()) {
            if (hasItemAnywhere(client, req)) {
                hasAtLeastOneMaterial = true;
            } else {
                missingTypes.add(req.getItem());
            }
        }

        if (!hasAtLeastOneMaterial) {
            failedContainers.put(currentTask.targetPos, missingTypes);
            StringBuilder sb = new StringBuilder();
            int count = 0;
            for (Item item : missingTypes) {
                if (count > 0) sb.append(", ");
                sb.append(item.getName().getString());
                count++;
                if (count >= 3 && missingTypes.size() > 3) {
                    sb.append(Component.translatable("litematica_container_filler.message.etc").getString());
                    break;
                }
            }
            sendFeedback(client, Component.translatable("litematica_container_filler.message.material_shortage", sb.toString()).getString(), true);
            return false;
        }

        sendFeedback(client, Component.translatable("litematica_container_filler.message.task_dispatched").getString(), true);
        return true;
    }

    private void abortTask(Minecraft client, String errorMsgKey) {
        sendFeedback(client, Component.translatable(errorMsgKey).getString(), true);
        actionQueue.add(() -> {
            if (client.player != null && client.player.containerMenu != client.player.inventoryMenu) {
                client.player.closeContainer();
            }
        });
        actionQueue.add(() -> actionWaitTicks = getDelay(1));
        actionQueue.add(this::reset);
    }

    private List<Integer> getEmptySlots(Minecraft client) {
        List<Integer> emptySlots = new ArrayList<>();
        for (int i = 0; i < 36; i++) {
            if (client.player.getInventory().getItem(i).isEmpty()) {
                emptySlots.add(i);
            }
        }
        return emptySlots;
    }

    private int[] findStashAction(Minecraft client, Collection<ItemStack> requiredValues) {
        if (!DependencyChecker.HAS_QUICK_SHULKER || !Configs.ENABLE_QS_EXTRACTION.getBooleanValue()) return null;

        int targetShulker = -1;
        int itemToStash = -1;

        for (int i = 0; i < 36; i++) {
            ItemStack s = client.player.getInventory().getItem(i);
            if (s.getItem() instanceof BlockItem bi && bi.getBlock() instanceof ShulkerBoxBlock) {
                ItemContainerContents c = s.get(DataComponents.CONTAINER);
                long size = c == null ? 0 : c.stream().filter(stack -> !stack.isEmpty()).count();
                if (size < 27) {
                    targetShulker = i;
                    break;
                }
            }
        }

        if (targetShulker == -1) return null;

        for (int i = 0; i < 36; i++) {
            if (i == targetShulker) continue;
            ItemStack s = client.player.getInventory().getItem(i);
            if (s.isEmpty()) continue;
            if (s.getItem() instanceof BlockItem bi && bi.getBlock() instanceof ShulkerBoxBlock) continue;

            boolean isNeeded = false;
            for (ItemStack req : requiredValues) {
                if (ItemMatcher.isSameItem(s, req)) {
                    isNeeded = true; break;
                }
            }
            if (!isNeeded) {
                itemToStash = i;
                break;
            }
        }

        if (itemToStash != -1) {
            return new int[]{targetShulker, itemToStash};
        }
        return null;
    }

    public boolean isSilentlyExtracting() { return silentlyExtracting; }
    public void clearBlacklist() { failedContainers.clear(); }

    public void tick(Minecraft client) {
        if (client.player == null || client.level == null) { reset(); return; }

        boolean currentContinuousState = Configs.CONTINUOUS_FILL.getBooleanValue();
        if (currentContinuousState != lastContinuousState) {
            clearBlacklist();
            if (!currentContinuousState) taskQueue.clear();
            lastContinuousState = currentContinuousState;
        }

        tickCounter++;
        if (!failedContainers.isEmpty() && tickCounter % 10 == 0) {
            failedContainers.entrySet().removeIf(entry -> {
                for (Item item : entry.getValue()) {
                    if (hasItemAnywhere(client, item.getDefaultInstance())) return true;
                }
                return false;
            });
        }

        if (currentTask != null) {
            watchdogTimer++;
            if (watchdogTimer > 150) {
                abortTask(client, "litematica_container_filler.message.timeout_reset");
                return;
            }
        }

        if (actionWaitTicks > 0) { actionWaitTicks--; watchdogTimer = 0; return; }

        if (currentTask == null) {
            if (!taskQueue.isEmpty()) {
                currentTask = taskQueue.poll();
                borrowedItems.clear();
                openedShulkerSlots.clear();
                shulkerMisses.clear();
                consecutiveFailures = 0;

                if (currentTask.needsInspection) {
                    currentPhase = Phase.AWAITING_DATA;
                    dataWaitTimer = 0;
                } else {
                    if (!checkMaterialsAndPrepare(client)) {
                        reset(); return;
                    }
                    checkAndStartGatheringOrFilling(client);
                }
            } else {
                return;
            }
        }

        yieldTick = false;
        while (!actionQueue.isEmpty() && actionWaitTicks <= 0 && !yieldTick) {
            actionQueue.poll().run();
            if (!yieldTick) watchdogTimer = 0;
        }

        if (actionWaitTicks <= 0 && actionQueue.isEmpty() && currentTask != null && !yieldTick) {
            AbstractContainerMenu currentHandler = client.player.containerMenu;
            boolean inGui = currentHandler != client.player.inventoryMenu;

            switch (currentPhase) {
                case AWAITING_DATA:
                    Map<Integer, ItemStack> lateCache = getTrueContainerData(client, currentTask.targetPos);
                    if (lateCache != null) {
                        currentTask.needsInspection = false;

                        boolean needsAction = false;
                        Map<Integer, ItemStack> missingItems = new HashMap<>();

                        for (int i = 0; i < 54; i++) {
                            ItemStack req = currentTask.requiredItems.getOrDefault(i, ItemStack.EMPTY);
                            ItemStack cur = lateCache.getOrDefault(i, ItemStack.EMPTY);

                            if (req.isEmpty() && cur.isEmpty()) continue;
                            if (req.isEmpty() && !cur.isEmpty()) needsAction = true;
                            else if (!req.isEmpty() && cur.isEmpty()) {
                                needsAction = true;
                                missingItems.put(i, req.copy());
                            } else if (!ItemMatcher.isSameItem(req, cur)) {
                                needsAction = true;
                                missingItems.put(i, req.copy());
                            } else if (cur.getCount() < req.getCount()) {
                                needsAction = true;
                                ItemStack diff = req.copy();
                                diff.setCount(req.getCount() - cur.getCount());
                                missingItems.put(i, diff);
                            } else if (cur.getCount() > req.getCount()) {
                                needsAction = true;
                            }
                        }

                        boolean isCrafter = client.level.getBlockState(currentTask.targetPos).getBlock() instanceof net.minecraft.world.level.block.CrafterBlock;
                        if (isCrafter && LitematicaContainerReader.doesCrafterNeedLocking(currentTask.targetPos, client)) {
                            needsAction = true;
                        }

                        if (!needsAction) {
                            sendFeedback(client, Component.translatable("litematica_container_filler.message.already_satisfied").getString(), true);
                            reset();
                            break;
                        }

                        currentTask.missingItems.clear();
                        currentTask.missingItems.putAll(missingItems);

                        if (!checkMaterialsAndPrepare(client)) {
                            reset(); break;
                        }
                        checkAndStartGatheringOrFilling(client);
                    } else {
                        dataWaitTimer++;
                        if (dataWaitTimer > 20) {
                            currentPhase = Phase.INSPECTING;
                        }
                    }
                    break;

                case INSPECTING:
                    if (!inGui) {
                        openTargetContainer(client, currentTask.targetPos);
                    } else {
                        if (!silentlyExtracting) doInspectionPhase(client);
                    }
                    break;

                case STASHING:
                    if (!inGui) {
                        openShulkerBox(client, stashShulkerSlot);
                    } else {
                        if (silentlyExtracting) doStashPhase(client);
                    }
                    break;

                case GATHERING:
                    if (!inGui) {
                        if (pendingShulkers.isEmpty()) {
                            currentPhase = Phase.FILLING;
                        } else {
                            int slot = pendingShulkers.poll();
                            openShulkerBox(client, slot);
                        }
                    } else {
                        if (silentlyExtracting) doShulkerExtractionPhase(client);
                    }
                    break;

                case FILLING:
                    if (!inGui) {
                        openTargetContainer(client, currentTask.targetPos);
                    } else {
                        if (!silentlyExtracting) {
                            if (currentMapper == null) {
                                currentMapper = new SlotMapper(currentHandler, client.player.getInventory());
                            }
                            executeBurstFill(client, currentHandler);
                        }
                    }
                    break;

                case RETURNING:
                    if (!inGui) {
                        if (pendingShulkers.isEmpty()) {
                            reset();
                        } else {
                            int slot = pendingShulkers.poll();
                            openShulkerBox(client, slot);
                        }
                    } else {
                        if (silentlyExtracting) returnBorrowedAndStashedItems(client);
                    }
                    break;

                case IDLE:
                    break;
            }
        }
    }

    private void doInspectionPhase(Minecraft client) {
        actionQueue.add(() -> client.player.closeContainer());
        actionQueue.add(() -> actionWaitTicks = getDelay(1));
        actionQueue.add(() -> {
            currentTask.needsInspection = false;
            Map<Integer, ItemStack> newlyCached = getReliableCache(currentTask.targetPos);
            if (newlyCached == null) newlyCached = new HashMap<>();

            boolean needsAction = false;
            Map<Integer, ItemStack> missingItems = new HashMap<>();

            for (int i = 0; i < 54; i++) {
                ItemStack req = currentTask.requiredItems.getOrDefault(i, ItemStack.EMPTY);
                ItemStack cur = newlyCached.getOrDefault(i, ItemStack.EMPTY);

                if (req.isEmpty() && cur.isEmpty()) continue;
                if (req.isEmpty() && !cur.isEmpty()) needsAction = true;
                else if (!req.isEmpty() && cur.isEmpty()) {
                    needsAction = true;
                    missingItems.put(i, req.copy());
                } else if (!ItemMatcher.isSameItem(req, cur)) {
                    needsAction = true;
                    missingItems.put(i, req.copy());
                } else if (cur.getCount() < req.getCount()) {
                    needsAction = true;
                    ItemStack diff = req.copy();
                    diff.setCount(req.getCount() - cur.getCount());
                    missingItems.put(i, diff);
                } else if (cur.getCount() > req.getCount()) {
                    needsAction = true;
                }
            }

            boolean isCrafter = client.level.getBlockState(currentTask.targetPos).getBlock() instanceof net.minecraft.world.level.block.CrafterBlock;
            if (isCrafter && LitematicaContainerReader.doesCrafterNeedLocking(currentTask.targetPos, client)) {
                needsAction = true;
            }

            if (!needsAction) {
                sendFeedback(client, Component.translatable("litematica_container_filler.message.already_satisfied").getString(), true);
                reset();
            } else {
                currentTask.missingItems.clear();
                currentTask.missingItems.putAll(missingItems);
                if (checkMaterialsAndPrepare(client)) {
                    checkAndStartGatheringOrFilling(client);
                } else {
                    reset();
                }
            }
        });
    }

    private void checkAndStartGatheringOrFilling(Minecraft client) {
        if (currentTask.missingItems.isEmpty()) {
            currentPhase = Phase.FILLING;
            return;
        }

        int emptySlots = getEmptySlots(client).size();
        if (emptySlots == 0 && Configs.AUTO_STASH_ITEMS.getBooleanValue()) {
            int[] stashAction = findStashAction(client, currentTask.requiredItems.values());
            if (stashAction != null) {
                stashShulkerSlot = stashAction[0];
                stashItemSlot = stashAction[1];
                currentPhase = Phase.STASHING;
                return;
            }
        }

        pendingShulkers.clear();
        List<ItemStack> needed = computeNeededToFetch(client);
        if (!needed.isEmpty()) {
            Set<Integer> shulkers = findShulkersContaining(client, needed);
            if (!shulkers.isEmpty()) {
                pendingShulkers.addAll(shulkers);
                currentPhase = Phase.GATHERING;
                return;
            } else {
                if (hasAnyMaterialsToFill(client)) {
                    currentPhase = Phase.FILLING;
                    return;
                }
                abortTask(client, "litematica_container_filler.message.materials_depleted");
                return;
            }
        }

        currentPhase = Phase.FILLING;
    }

    private boolean hasAnyMaterialsToFill(Minecraft client) {
        Map<Integer, ItemStack> trueData = getTrueContainerData(client, currentTask.targetPos);
        if (trueData == null) trueData = new HashMap<>();

        for (int i = 0; i < 54; i++) {
            ItemStack req = currentTask.requiredItems.getOrDefault(i, ItemStack.EMPTY);
            ItemStack cur = trueData.getOrDefault(i, ItemStack.EMPTY);

            if (!req.isEmpty()) {
                int missing = req.getCount() - (ItemMatcher.isSameItem(req, cur) ? cur.getCount() : 0);
                if (missing > 0 && countItemInPlayerInv(client, req) > 0) {
                    return true;
                }
            }
        }
        return false;
    }

    private List<ItemStack> computeNeededToFetch(Minecraft client) {
        Map<Integer, ItemStack> trueData = getTrueContainerData(client, currentTask.targetPos);
        if (trueData == null) trueData = new HashMap<>();

        List<ItemStack> toFetch = new ArrayList<>();
        for (int i = 0; i < 54; i++) {
            ItemStack req = currentTask.requiredItems.getOrDefault(i, ItemStack.EMPTY);
            ItemStack cur = trueData.getOrDefault(i, ItemStack.EMPTY);

            if (!req.isEmpty()) {
                if (cur.isEmpty() || !ItemMatcher.isSameItem(req, cur)) {
                    toFetch.add(req.copy());
                } else if (cur.getCount() < req.getCount()) {
                    ItemStack diff = req.copy();
                    diff.setCount(req.getCount() - cur.getCount());
                    toFetch.add(diff);
                }
            }
        }

        List<ItemStack> consolidated = new ArrayList<>();
        for (ItemStack req : toFetch) {
            boolean found = false;
            for (ItemStack exist : consolidated) {
                if (ItemMatcher.isSameItem(exist, req)) {
                    exist.setCount(exist.getCount() + req.getCount());
                    found = true; break;
                }
            }
            if (!found) consolidated.add(req.copy());
        }

        List<ItemStack> needed = new ArrayList<>();
        for (ItemStack req : consolidated) {
            int inInv = countItemInPlayerInv(client, req);
            if (inInv < req.getCount()) {
                ItemStack diff = req.copy();
                diff.setCount(req.getCount() - inInv);
                needed.add(diff);
            }
        }
        return needed;
    }

    private void doStashPhase(Minecraft client) {
        AbstractContainerMenu h = client.player.containerMenu;
        int uiSlot = stashItemSlot < 9 ? stashItemSlot + 54 : stashItemSlot + 18;

        ItemStack stackToStash = h.slots.get(uiSlot).getItem();
        if (!stackToStash.isEmpty()) {
            Item stashedItem = stackToStash.getItem();
            stashedItemCounts.put(stashedItem, stashedItemCounts.getOrDefault(stashedItem, 0) + stackToStash.getCount());
        }

        client.gameMode.handleInventoryMouseClick(h.containerId, uiSlot, 0, ClickType.QUICK_MOVE, client.player);
        sendFeedback(client, Component.translatable("litematica_container_filler.message.stashing_items").getString(), true);

        actionQueue.add(() -> {
            client.player.closeContainer();
            silentlyExtracting = false;
            activeShulkerSlot = -1;
            stashShulkerSlot = -1;
            stashItemSlot = -1;
        });
        actionQueue.add(() -> actionWaitTicks = getDelay(1));
        actionQueue.add(() -> checkAndStartGatheringOrFilling(client));
    }

    private Set<Integer> findShulkersContaining(Minecraft client, List<ItemStack> needed) {
        Set<Integer> slots = new LinkedHashSet<>();
        if (!DependencyChecker.HAS_QUICK_SHULKER || !Configs.ENABLE_QS_EXTRACTION.getBooleanValue()) return slots;

        for (ItemStack req : needed) {
            int amountToFind = req.getCount();
            for (int i = 0; i < 36; i++) {
                if (amountToFind <= 0) break;
                if (shulkerMisses.containsKey(i) && shulkerMisses.get(i).contains(req.getItem())) continue;

                ItemStack s = client.player.getInventory().getItem(i);
                if (s.getItem() instanceof BlockItem bi && bi.getBlock() instanceof ShulkerBoxBlock) {
                    ItemContainerContents c = s.get(DataComponents.CONTAINER);
                    if (c != null) {
                        for (ItemStack inner : c.stream().toList()) {
                            if (ItemMatcher.isSameItem(inner, req)) {
                                slots.add(i);
                                amountToFind -= inner.getCount();
                            }
                        }
                    }
                }
            }
        }
        return slots;
    }

    private void openTargetContainer(Minecraft client, BlockPos pos) {
        silentlyExtracting = false;
        BlockHitResult hitResult = new BlockHitResult(new Vec3(pos.getX()+0.5, pos.getY()+0.5, pos.getZ()+0.5), Direction.UP, pos, false);
        client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND, hitResult);
        currentMapper = null;
        uiWaitTimer = 0;
        actionQueue.add(this::waitForUi);
        actionQueue.add(() -> actionWaitTicks = getDelay(1));
    }

    private void openShulkerBox(Minecraft client, int slot) {
        silentlyExtracting = true;
        activeShulkerSlot = slot;
        openedShulkerSlots.add(slot);

        if (currentPhase == Phase.STASHING) {
            sendFeedback(client, Component.translatable("litematica_container_filler.message.opening_shulker_stash").getString(), true);
        } else if (currentPhase == Phase.RETURNING) {
            sendFeedback(client, Component.translatable("litematica_container_filler.message.opening_shulker_return").getString(), true);
        } else {
            sendFeedback(client, Component.translatable("litematica_container_filler.message.opening_shulker_extract").getString(), true);
        }

        actionQueue.add(() -> shulkerExtractor.requestOpenShulker(slot));
        uiWaitTimer = 0;
        actionQueue.add(this::waitForUi);
        actionQueue.add(() -> actionWaitTicks = getDelay(1));
    }

    private void doShulkerExtractionPhase(Minecraft client) {
        AbstractContainerMenu h = client.player.containerMenu;
        Set<Integer> usedEmptySlots = new HashSet<>();
        List<ItemStack> needed = computeNeededToFetch(client);

        int remainingEmptySlots = getEmptySlots(client).size();
        Map<Item, Integer> partialSpaces = new HashMap<>();

        for (int i = 0; i < 36; i++) {
            ItemStack invStack = client.player.getInventory().getItem(i);
            if (!invStack.isEmpty()) {
                partialSpaces.put(invStack.getItem(), partialSpaces.getOrDefault(invStack.getItem(), 0) + (invStack.getMaxStackSize() - invStack.getCount()));
            }
        }

        boolean inventoryFull = false;

        for (ItemStack req : needed) {
            if (inventoryFull) break;

            if (remainingEmptySlots <= 0 && partialSpaces.getOrDefault(req.getItem(), 0) <= 0) {
                continue;
            }

            int amountMissing = req.getCount();
            int amountTaken = 0;

            for (int i = 0; i < h.slots.size() - 36; i++) {
                if (amountTaken >= amountMissing) break;

                ItemStack slotStack = h.slots.get(i).getItem();
                if (slotStack.isEmpty() || !ItemMatcher.isSameItem(slotStack, req)) continue;

                int partialSpace = partialSpaces.getOrDefault(req.getItem(), 0);
                if (remainingEmptySlots <= 0 && partialSpace <= 0) {
                    inventoryFull = true;
                    break;
                }

                int amountAvailable = slotStack.getCount();
                int maxWeCanTake = amountAvailable;
                if (remainingEmptySlots <= 0) {
                    maxWeCanTake = Math.min(amountAvailable, partialSpace);
                }

                int amountToTake = Math.min(amountMissing - amountTaken, maxWeCanTake);
                if (amountToTake <= 0) continue;

                if (amountToTake == amountAvailable) {
                    client.gameMode.handleInventoryMouseClick(h.containerId, i, 0, ClickType.QUICK_MOVE, client.player);
                } else {
                    int emptySlot = -1;
                    for (int j = h.slots.size() - 36; j < h.slots.size(); j++) {
                        if (h.slots.get(j).getItem().isEmpty() && !usedEmptySlots.contains(j)) {
                            emptySlot = j; break;
                        }
                    }
                    if (emptySlot != -1) {
                        usedEmptySlots.add(emptySlot);
                        client.gameMode.handleInventoryMouseClick(h.containerId, i, 0, ClickType.PICKUP, client.player);
                        for (int k = 0; k < amountToTake; k++) {
                            client.gameMode.handleInventoryMouseClick(h.containerId, emptySlot, 1, ClickType.PICKUP, client.player);
                        }
                        client.gameMode.handleInventoryMouseClick(h.containerId, i, 0, ClickType.PICKUP, client.player);
                    } else {
                        client.gameMode.handleInventoryMouseClick(h.containerId, i, 0, ClickType.QUICK_MOVE, client.player);
                    }
                }

                borrowedItems.add(req.getItem());
                amountTaken += amountToTake;

                if (amountToTake > partialSpace) {
                    remainingEmptySlots--;
                    int overflow = amountToTake - partialSpace;
                    partialSpaces.put(req.getItem(), req.getMaxStackSize() - overflow);
                } else {
                    partialSpaces.put(req.getItem(), partialSpace - amountToTake);
                }
            }

            if (amountTaken == 0 && (remainingEmptySlots > 0 || partialSpaces.getOrDefault(req.getItem(), 0) > 0)) {
                shulkerMisses.computeIfAbsent(activeShulkerSlot, k -> new HashSet<>()).add(req.getItem());
            }
        }

        sendFeedback(client, Component.translatable("litematica_container_filler.message.extraction_done").getString(), true);
        final boolean forceDump = (remainingEmptySlots <= 0);

        actionQueue.add(() -> {
            client.player.closeContainer();
            silentlyExtracting = false;
            activeShulkerSlot = -1;

            if (forceDump) {
                pendingShulkers.clear();
                currentPhase = Phase.FILLING;
            }
        });
        actionQueue.add(() -> actionWaitTicks = getDelay(1));
    }

    private boolean canAbsorb(Minecraft client, ItemStack stack) {
        if (getEmptySlots(client).size() > 0) return true;
        for (int i = 0; i < 36; i++) {
            ItemStack pStack = client.player.getInventory().getItem(i);
            if (ItemMatcher.isSameItem(pStack, stack) && pStack.getCount() + stack.getCount() <= pStack.getMaxStackSize()) {
                return true;
            }
        }
        return false;
    }

    private void triggerStashOrAbort(Minecraft client) {
        if (Configs.AUTO_STASH_ITEMS.getBooleanValue()) {
            int[] stashAction = findStashAction(client, currentTask.requiredItems.values());
            if (stashAction != null) {
                actionQueue.add(() -> client.player.closeContainer());
                actionQueue.add(() -> actionWaitTicks = getDelay(1));
                actionQueue.add(() -> {
                    stashShulkerSlot = stashAction[0];
                    stashItemSlot = stashAction[1];
                    currentPhase = Phase.STASHING;
                });
                return;
            }
        }
        abortTask(client, "litematica_container_filler.message.inventory_full_cannot_extract");
    }

    private void executeBurstFill(Minecraft client, AbstractContainerMenu handler) {
        int syncId = handler.containerId;
        int delay = Configs.ENABLE_SAFETY_DELAY.getBooleanValue() ? Configs.FILL_DELAY.getIntegerValue() : 0;

        if (!handler.getCarried().isEmpty()) {
            if (!tryPlaceCursorItem(client, handler)) {
                abortTask(client, "litematica_container_filler.message.cursor_stuck");
                return;
            }
            if (delay > 0) { actionWaitTicks = delay; return; }
        }

        if (handler instanceof CrafterMenu crafterHandler && client.screen instanceof AbstractContainerScreen<?> handledScreen) {
            Set<Integer> targetDisabled = LitematicaContainerReader.getDisabledSlots(currentTask.targetPos);
            boolean toggledInThisTick = false;
            for (int i = 0; i < 9; i++) {
                boolean shouldBeDisabled = targetDisabled != null && targetDisabled.contains(i);
                if (shouldBeDisabled != crafterHandler.isSlotDisabled(i)) {
                    if (crafterHandler.getSlot(i).hasItem()) simulateSlotClick(handledScreen, crafterHandler.getSlot(i), i, 0, ClickType.QUICK_MOVE);
                    else simulateSlotClick(handledScreen, crafterHandler.getSlot(i), i, 0, ClickType.PICKUP);
                    toggledInThisTick = true;
                    if (delay > 0) break;
                }
            }
            if (toggledInThisTick) { actionWaitTicks = delay; if (delay > 0) return; }
        }

        int containerSize = handler.slots.size() - 36;
        if (handler instanceof CrafterMenu) containerSize = 9;
        if (containerSize <= 0) { finishTaskAndReturn(client); return; }

        boolean movedAny = false;
        boolean stillNeedsAction = false;

        for (int containerSlot = 0; containerSlot < containerSize; containerSlot++) {
            if (handler instanceof CrafterMenu ch && ch.isSlotDisabled(containerSlot)) continue;

            ItemStack reqStack = currentTask.requiredItems.getOrDefault(containerSlot, ItemStack.EMPTY);
            int uiSlot = currentMapper.getUiSlotForContainer(containerSlot);
            ItemStack curStack = handler.slots.get(uiSlot).getItem();

            if (reqStack.isEmpty() && curStack.isEmpty()) continue;

            boolean isWrong = !curStack.isEmpty() && !ItemMatcher.isSameItem(curStack, reqStack);
            boolean isExcess = !curStack.isEmpty() && ItemMatcher.isSameItem(curStack, reqStack) && curStack.getCount() > reqStack.getCount();

            if (reqStack.isEmpty() || isWrong || isExcess) {
                stillNeedsAction = true;
                if (!canAbsorb(client, curStack)) {
                    triggerStashOrAbort(client);
                    return;
                }
                client.gameMode.handleInventoryMouseClick(syncId, uiSlot, 0, ClickType.QUICK_MOVE, client.player);
                movedAny = true;
                if (delay > 0) break;
                continue;
            }

            int curCount = curStack.isEmpty() ? 0 : curStack.getCount();
            int actualMissing = reqStack.getCount() - curCount;

            if (actualMissing > 0) {
                stillNeedsAction = true;
                ItemStack needed = reqStack.copy();
                needed.setCount(actualMissing);

                int playerSlot = findItemInPlayerInv(client, needed);
                if (playerSlot != -1) {
                    ItemStack sourceStack = client.player.getInventory().getItem(playerSlot);
                    int amountToMove = Math.min(actualMissing, sourceStack.getCount());

                    fillFromPlayerInv(client, syncId, playerSlot, uiSlot, actualMissing);
                    movedAny = true;
                    if (delay > 0) break;
                }
            }
        }

        if (movedAny) {
            actionWaitTicks = delay;
            consecutiveFailures = 0;
            watchdogTimer = 0;
        } else {
            if (stillNeedsAction) {
                if (client.screen instanceof AbstractContainerScreen<?> hs) {
                    RealContainerCache.updateFromScreen(client, hs);
                }
                actionQueue.add(() -> client.player.closeContainer());
                actionQueue.add(() -> actionWaitTicks = getDelay(1));
                actionQueue.add(() -> checkAndStartGatheringOrFilling(client));
            } else {
                finishTaskAndReturn(client);
            }
        }
    }

    private void finishTaskAndReturn(Minecraft client) {
        if (client.screen instanceof AbstractContainerScreen<?> hs) {
            RealContainerCache.updateFromScreen(client, hs);
        }
        sendFeedback(client, Component.translatable("litematica_container_filler.message.fill_completed").getString(), true);

        actionQueue.add(() -> client.player.closeContainer());
        actionQueue.add(() -> actionWaitTicks = getDelay(1));

        actionQueue.add(() -> {
            borrowedItems.removeIf(item -> {
                for (int i = 0; i < 36; i++) {
                    if (client.player.getInventory().getItem(i).is(item)) return false;
                }
                return true;
            });

            stashedItemCounts.entrySet().removeIf(entry -> entry.getValue() <= 0);

            if (!openedShulkerSlots.isEmpty() && (!borrowedItems.isEmpty() || !stashedItemCounts.isEmpty())) {
                pendingShulkers.clear();
                pendingShulkers.addAll(openedShulkerSlots);
                currentPhase = Phase.RETURNING;
            } else {
                reset();
            }
        });
    }

    private void returnBorrowedAndStashedItems(Minecraft client) {
        AbstractContainerMenu h = client.player.containerMenu;
        boolean movedAny = false;

        for (int i = h.slots.size() - 36; i < h.slots.size(); i++) {
            ItemStack s = h.slots.get(i).getItem();
            if (!s.isEmpty() && borrowedItems.contains(s.getItem())) {
                client.gameMode.handleInventoryMouseClick(h.containerId, i, 0, ClickType.QUICK_MOVE, client.player);
                movedAny = true;
            }
        }

        for (int i = 0; i < h.slots.size() - 36; i++) {
            ItemStack s = h.slots.get(i).getItem();
            if (!s.isEmpty() && stashedItemCounts.containsKey(s.getItem())) {
                int neededToRetrieve = stashedItemCounts.get(s.getItem());
                if (neededToRetrieve <= 0) continue;

                int amountInSlot = s.getCount();
                if (amountInSlot <= neededToRetrieve) {
                    client.gameMode.handleInventoryMouseClick(h.containerId, i, 0, ClickType.QUICK_MOVE, client.player);
                    stashedItemCounts.put(s.getItem(), neededToRetrieve - amountInSlot);
                } else {
                    int emptySlot = -1;
                    for (int j = h.slots.size() - 36; j < h.slots.size(); j++) {
                        if (h.slots.get(j).getItem().isEmpty()) {
                            emptySlot = j; break;
                        }
                    }
                    if (emptySlot != -1) {
                        client.gameMode.handleInventoryMouseClick(h.containerId, i, 0, ClickType.PICKUP, client.player);
                        for (int k = 0; k < neededToRetrieve; k++) {
                            client.gameMode.handleInventoryMouseClick(h.containerId, emptySlot, 1, ClickType.PICKUP, client.player);
                        }
                        client.gameMode.handleInventoryMouseClick(h.containerId, i, 0, ClickType.PICKUP, client.player);
                        stashedItemCounts.put(s.getItem(), 0);
                    } else {
                        client.gameMode.handleInventoryMouseClick(h.containerId, i, 0, ClickType.QUICK_MOVE, client.player);
                        stashedItemCounts.put(s.getItem(), neededToRetrieve - amountInSlot);
                    }
                }
                movedAny = true;
            }
        }

        if (movedAny) {
            sendFeedback(client, Component.translatable("litematica_container_filler.message.returning_items").getString(), true);
        }

        actionQueue.add(() -> {
            client.player.closeContainer();
            silentlyExtracting = false;
            activeShulkerSlot = -1;
        });
        actionQueue.add(() -> actionWaitTicks = getDelay(1));
    }

    private void waitForUi() {
        Minecraft client = Minecraft.getInstance();
        if (client.player != null && client.player.containerMenu == client.player.inventoryMenu) {
            uiWaitTimer++;
            if (uiWaitTimer > 20) {
                abortTask(client, "litematica_container_filler.message.container_timeout");
                return;
            }
            actionQueue.addFirst(this::waitForUi);
            yieldTick = true;
        } else {
            uiWaitTimer = 0;
        }
    }

    private void reset() {
        currentTask = null;
        currentMapper = null;
        silentlyExtracting = false;
        yieldTick = false;
        currentPhase = Phase.IDLE;
        actionQueue.clear();
        pendingShulkers.clear();
        actionWaitTicks = 0;
        watchdogTimer = 0;
        uiWaitTimer = 0;
        dataWaitTimer = 0;
        activeShulkerSlot = -1;
        stashShulkerSlot = -1;
        stashItemSlot = -1;
        borrowedItems.clear();
        stashedItemCounts.clear();
        openedShulkerSlots.clear();
        shulkerMisses.clear();
        consecutiveFailures = 0;
    }

    private void sendFeedback(Minecraft client, String text, boolean isActionBar) {
        if (client.player != null) client.player.displayClientMessage(Component.literal(text), isActionBar);
    }

    private boolean hasItemAnywhere(Minecraft client, ItemStack target) {
        for (int i = 0; i < 36; i++) {
            ItemStack s = client.player.getInventory().getItem(i);
            if (ItemMatcher.isSameItem(s, target)) return true;
            if (s.getItem() instanceof BlockItem bi && bi.getBlock() instanceof ShulkerBoxBlock) {
                ItemContainerContents c = s.get(DataComponents.CONTAINER);
                if (c != null) {
                    for (ItemStack inner : c.stream().toList()) {
                        if (ItemMatcher.isSameItem(inner, target)) return true;
                    }
                }
            }
        }
        return false;
    }

    private int countItemInPlayerInv(Minecraft client, ItemStack target) {
        int count = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack s = client.player.getInventory().getItem(i);
            if (ItemMatcher.isSameItem(s, target)) count += s.getCount();
        }
        return count;
    }

    private int findItemInPlayerInv(Minecraft client, ItemStack target) {
        for (int i = 0; i < 36; i++) if (ItemMatcher.isSameItem(client.player.getInventory().getItem(i), target)) return i;
        return -1;
    }

    private boolean tryPlaceCursorItem(Minecraft client, AbstractContainerMenu handler) {
        int empty = findEmptyPlayerSlot(client);
        if (empty != -1) {
            client.gameMode.handleInventoryMouseClick(handler.containerId, currentMapper.getUiSlotForPlayer(empty), 0, ClickType.PICKUP, client.player);
            return true;
        }
        return false;
    }

    private int findEmptyPlayerSlot(Minecraft client) {
        for (int i = 9; i < 36; i++) if (client.player.getInventory().getItem(i).isEmpty()) return i;
        for (int i = 0; i < 9; i++) if (client.player.getInventory().getItem(i).isEmpty()) return i;
        return -1;
    }

    private void fillFromPlayerInv(Minecraft client, int syncId, int playerSlot, int containerSlot, int needed) {
        int uiPlayerSlot = currentMapper.getUiSlotForPlayer(playerSlot);
        ItemStack sourceStack = client.player.getInventory().getItem(playerSlot);
        int countInSlot = sourceStack.getCount();
        int amountToMove = Math.min(needed, countInSlot);

        if (amountToMove == countInSlot) {
            client.gameMode.handleInventoryMouseClick(syncId, uiPlayerSlot, 0, ClickType.PICKUP, client.player);
            client.gameMode.handleInventoryMouseClick(syncId, containerSlot, 0, ClickType.PICKUP, client.player);
            client.gameMode.handleInventoryMouseClick(syncId, uiPlayerSlot, 0, ClickType.PICKUP, client.player);
        } else {
            client.gameMode.handleInventoryMouseClick(syncId, uiPlayerSlot, 0, ClickType.PICKUP, client.player);
            for (int i = 0; i < amountToMove; i++) {
                client.gameMode.handleInventoryMouseClick(syncId, containerSlot, 1, ClickType.PICKUP, client.player);
            }
            client.gameMode.handleInventoryMouseClick(syncId, uiPlayerSlot, 0, ClickType.PICKUP, client.player);
        }
    }

    public BlockPos getCurrentTaskPos() { return currentTask != null ? currentTask.targetPos : null; }
    public boolean isIdle() { return this.currentTask == null && this.actionQueue.isEmpty(); }

    private void simulateSlotClick(AbstractContainerScreen<?> screen, Slot slot, int slotId, int button, ClickType actionType) {
        try {
            java.lang.reflect.Method targetMethod = null;
            Class<?> currClass = screen.getClass();
            while (currClass != null && targetMethod == null) {
                for (java.lang.reflect.Method m : currClass.getDeclaredMethods()) {
                    Class<?>[] params = m.getParameterTypes();
                    if (params.length == 4 && params[0] == Slot.class && params[1] == int.class && params[2] == int.class && params[3] == ClickType.class) {
                        targetMethod = m; break;
                    }
                }
                currClass = currClass.getSuperclass();
            }
            if (targetMethod != null) {
                targetMethod.setAccessible(true);
                targetMethod.invoke(screen, slot, slotId, button, actionType);
            } else {
                Minecraft.getInstance().gameMode.handleInventoryMouseClick(screen.getMenu().containerId, slotId, button, actionType, Minecraft.getInstance().player);
            }
        } catch (Exception e) { e.printStackTrace(); }
    }
}
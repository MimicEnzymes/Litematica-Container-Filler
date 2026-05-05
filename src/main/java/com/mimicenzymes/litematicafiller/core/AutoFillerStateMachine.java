package com.mimicenzymes.litematicafiller.core;

import com.mimicenzymes.litematicafiller.config.Configs;
import com.mimicenzymes.litematicafiller.dependency.DependencyChecker;
import com.mimicenzymes.litematicafiller.dependency.DummyExtractor;
import com.mimicenzymes.litematicafiller.dependency.IShulkerExtractor;
import com.mimicenzymes.litematicafiller.dependency.QuickShulkerWrapper;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.CrafterScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.text.Text;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

public class AutoFillerStateMachine {

    public enum Phase {
        IDLE, AWAITING_DATA, INSPECTING, STASHING, GATHERING, FILLING, RETURNING
    }

    public static class FillTask {
        public final BlockPos targetPos;
        public final Map<Integer, ItemStack> requiredItems;
        public Map<Integer, ItemStack> missingItems;
        public boolean needsInspection;
        public boolean forcedManual;

        public final Map<Integer, Integer> fillLedger = new HashMap<>();

        public FillTask(BlockPos targetPos, Map<Integer, ItemStack> requiredItems, Map<Integer, ItemStack> missingItems, boolean needsInspection, boolean forcedManual) {
            this.targetPos = targetPos;
            this.requiredItems = requiredItems;
            this.missingItems = missingItems;
            this.needsInspection = needsInspection;
            this.forcedManual = forcedManual;
            initLedger();
        }

        public void initLedger() {
            fillLedger.clear();
            for (Map.Entry<Integer, ItemStack> e : missingItems.entrySet()) {
                fillLedger.put(e.getKey(), e.getValue().getCount());
            }
        }
    }

    private static class StrictItemStackKey {
        public final ItemStack stack;
        public StrictItemStackKey(ItemStack stack) { this.stack = stack; }
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof StrictItemStackKey)) return false;
            return ItemMatcher.isSameItem(this.stack, ((StrictItemStackKey) o).stack);
        }
        @Override
        public int hashCode() {
            net.minecraft.text.Text name = stack.get(DataComponentTypes.CUSTOM_NAME);
            return stack.getItem().hashCode() * 31 + (name != null ? name.getString().hashCode() : 0);
        }
    }

    private static final AutoFillerStateMachine INSTANCE = new AutoFillerStateMachine();
    public static AutoFillerStateMachine getInstance() { return INSTANCE; }

    private final Queue<FillTask> taskQueue = new ConcurrentLinkedQueue<>();
    private FillTask currentTask = null;
    private SlotMapper currentMapper = null;
    private ScreenHandler mappedHandler = null;

    private Phase currentPhase = Phase.IDLE;
    private boolean guiOpenedForPhase = false;

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

    private final Set<StrictItemStackKey> borrowedItems = new HashSet<>();
    private final Map<StrictItemStackKey, Integer> stashedItemCounts = new HashMap<>();

    private final Set<Integer> openedShulkerSlots = new LinkedHashSet<>();
    private final Map<Integer, Set<Item>> shulkerMisses = new HashMap<>();
    private final Map<BlockPos, Set<Item>> failedContainers = new ConcurrentHashMap<>();
    private final Set<Integer> blacklistedSlots = new HashSet<>();

    private boolean lastContinuousState = false;
    private int tickCounter = 0;
    private int consecutiveFailures = 0;
    private int movesThisTask = 0;
    private int cursorStuckAttempts = 0;

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

    private void changePhase(Phase newPhase) {
        this.currentPhase = newPhase;
        this.guiOpenedForPhase = false;
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

    private Map<Integer, ItemStack> getTrueContainerData(MinecraftClient client, BlockPos pos) {
        pos = pos.toImmutable();
        final BlockPos finalPos = pos;
        Map<Integer, ItemStack> verifiedCache = getReliableCache(finalPos);
        if (verifiedCache != null) return verifiedCache;

        if (client.isInSingleplayer() && client.getServer() != null && client.world != null && client.player != null) {
            ServerPlayerEntity serverPlayer = client.getServer().getPlayerManager().getPlayer(client.player.getUuid());
            if (serverPlayer != null) {
                ServerWorld serverWorld = (ServerWorld) serverPlayer.getEntityWorld();
                if (serverWorld != null) {
                    net.minecraft.block.BlockState clientState = client.world.getBlockState(finalPos);
                    final BlockPos[] halves = LitematicaContainerReader.getDoubleContainerHalves(client.world, finalPos, clientState);
                    client.getServer().execute(() -> {
                        net.minecraft.block.BlockState state = serverWorld.getBlockState(finalPos);
                        Map<Integer, ItemStack> inventoryData = null;
                        if (halves != null && state.isOf(net.minecraft.block.Blocks.BARREL)) {
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
                                RealContainerCache.put(halves[0].toImmutable(), inventoryData);
                                RealContainerCache.put(halves[1].toImmutable(), inventoryData);
                            } else {
                                RealContainerCache.put(finalPos, inventoryData);
                            }
                        }
                        if (state.getBlock() instanceof net.minecraft.block.CrafterBlock) {
                            net.minecraft.block.entity.BlockEntity be = serverWorld.getBlockEntity(finalPos);
                            if (be != null) {
                                net.minecraft.nbt.NbtCompound nbt = be.createNbt(serverWorld.getRegistryManager());
                                Set<Integer> locks = RealContainerCache.parseDisabledSlots(nbt);
                                RealContainerCache.putLock(finalPos, locks);
                            }
                        }
                    });
                }
            }
        }
        Map<Integer, ItemStack> fallback = RealContainerCache.getCachedItems(pos);
        if (fallback != null && fallback.isEmpty()) return null;
        return fallback;
    }

    private Map<Integer, ItemStack> getSingleBlockEntityInventory(net.minecraft.server.world.ServerWorld world, BlockPos pos) {
        net.minecraft.block.entity.BlockEntity be = world.getBlockEntity(pos);
        if (be == null) return null;
        net.minecraft.inventory.Inventory inv = null;
        net.minecraft.block.BlockState state = world.getBlockState(pos);
        if (state.getBlock() instanceof net.minecraft.block.ChestBlock chest) {
            inv = net.minecraft.block.ChestBlock.getInventory(chest, state, world, pos, true);
        }
        if (inv == null && be instanceof net.minecraft.inventory.Inventory inventory) inv = inventory;
        if (inv != null) {
            Map<Integer, ItemStack> map = new HashMap<>();
            for (int i = 0; i < inv.size(); i++) {
                ItemStack stack = inv.getStack(i);
                if (!stack.isEmpty()) map.put(i, stack.copy());
            }
            return map;
        }
        net.minecraft.nbt.NbtCompound nbt = be.createNbt(world.getRegistryManager());
        if (nbt != null && nbt.contains("Items")) {
            return RealContainerCache.parseNbtInventory(nbt, world.getRegistryManager());
        }
        return null;
    }

    public void addTask(BlockPos pos, Map<Integer, ItemStack> requiredItems) {
        if (requiredItems == null) return;
        if (failedContainers.containsKey(pos)) return;
        if (currentTask != null && currentTask.targetPos.equals(pos)) return;
        if (taskQueue.size() >= 20) return;

        for (FillTask t : taskQueue) {
            if (t.targetPos.equals(pos)) return;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;

        Map<Integer, ItemStack> trueData = getTrueContainerData(client, pos);
        if (trueData == null) {
            taskQueue.add(new FillTask(pos, requiredItems, new HashMap<>(), true, false));
            RealContainerCache.requestContainerData(pos);
            return;
        }

        boolean isCrafter = client.world.getBlockState(pos).getBlock() instanceof net.minecraft.block.CrafterBlock;
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
                if (!isCrafter) needsAction = true;
            }
        }

        if (isCrafter && LitematicaContainerReader.doesCrafterNeedLocking(pos, client)) needsAction = true;
        if (!needsAction) return;

        taskQueue.add(new FillTask(pos, requiredItems, missingItems, false, false));
    }

    public void addManualTask(BlockPos pos, Map<Integer, ItemStack> requiredItems) {
        if (requiredItems == null) return;
        failedContainers.remove(pos);
        RealContainerCache.remove(pos);

        taskQueue.removeIf(t -> t.targetPos.equals(pos));
        taskQueue.add(new FillTask(pos, requiredItems, new HashMap<>(), true, true));
        RealContainerCache.requestContainerData(pos);
    }

    private boolean checkMaterialsAndPrepare(MinecraftClient client) {
        if (currentTask.missingItems.isEmpty()) return true;

        Map<Integer, ItemStack> trueData = getTrueContainerData(client, currentTask.targetPos);
        if (trueData == null) trueData = new HashMap<>();

        Map<StrictItemStackKey, Integer> mobileItemsInContainer = new HashMap<>();
        boolean hasGarbageToExtract = false;
        boolean isCrafter = false;
        if (client.world != null) {
            isCrafter = client.world.getBlockState(currentTask.targetPos).getBlock() instanceof net.minecraft.block.CrafterBlock;
        }

        for (int i = 0; i < 54; i++) {
            ItemStack req = currentTask.requiredItems.getOrDefault(i, ItemStack.EMPTY);
            ItemStack cur = trueData.getOrDefault(i, ItemStack.EMPTY);

            if (!cur.isEmpty()) {
                if (req.isEmpty() || !ItemMatcher.isSameItem(req, cur)) {
                    mobileItemsInContainer.merge(new StrictItemStackKey(cur), cur.getCount(), Integer::sum);
                    hasGarbageToExtract = true;
                } else if (cur.getCount() > req.getCount() && !isCrafter) {
                    mobileItemsInContainer.merge(new StrictItemStackKey(cur), cur.getCount() - req.getCount(), Integer::sum);
                    hasGarbageToExtract = true;
                }
            }
        }

        boolean canFillSomething = false;
        Set<Item> trueMissingTypes = new LinkedHashSet<>();

        boolean isCreativeFill = client.player != null && client.player.isCreative() && Configs.ENABLE_CREATIVE_FILL.getBooleanValue();

        for (int i = 0; i < 54; i++) {
            ItemStack req = currentTask.requiredItems.getOrDefault(i, ItemStack.EMPTY);
            ItemStack cur = trueData.getOrDefault(i, ItemStack.EMPTY);

            int missingAmt = 0;
            if (!req.isEmpty()) {
                if (cur.isEmpty() || !ItemMatcher.isSameItem(req, cur)) {
                    missingAmt = req.getCount();
                } else if (cur.getCount() < req.getCount()) {
                    missingAmt = req.getCount() - cur.getCount();
                }
            }

            if (missingAmt > 0) {
                StrictItemStackKey hash = new StrictItemStackKey(req);
                int mobileAmt = mobileItemsInContainer.getOrDefault(hash, 0);

                if (hasItemAnywhere(client, req) || mobileAmt > 0 || (isCreativeFill && getEmptySlots(client).size() > 0)) {
                    canFillSomething = true;
                    if (mobileAmt > 0) {
                        mobileItemsInContainer.put(hash, mobileAmt - Math.min(missingAmt, mobileAmt));
                    }
                } else {
                    trueMissingTypes.add(req.getItem());
                }
            }
        }

        boolean canExtractSomething = false;
        if (hasGarbageToExtract) {
            if (Configs.DROP_EXTRACTED_ITEMS.getBooleanValue() || getEmptySlots(client).size() > 0) {
                canExtractSomething = true;
            } else {
                for (int i = 0; i < 54; i++) {
                    ItemStack req = currentTask.requiredItems.getOrDefault(i, ItemStack.EMPTY);
                    ItemStack cur = trueData.getOrDefault(i, ItemStack.EMPTY);
                    if (!cur.isEmpty() && (req.isEmpty() || !ItemMatcher.isSameItem(req, cur) || (cur.getCount() > req.getCount() && !isCrafter))) {
                        if (canAbsorb(client, cur)) {
                            canExtractSomething = true;
                            break;
                        }
                    }
                }
            }
        }

        if (!canFillSomething && !canExtractSomething) {
            if (hasGarbageToExtract && !Configs.DROP_EXTRACTED_ITEMS.getBooleanValue() && getEmptySlots(client).size() == 0) {
                if (Configs.AUTO_STASH_ITEMS.getBooleanValue() && findStashAction(client, currentTask.requiredItems.values()) != null) {
                    sendFeedback(client, Text.translatable("litematica_container_filler.message.task_dispatched").getString(), true);
                    return true;
                }
                abortTask(client, "litematica_container_filler.message.inventory_full_no_stash", true, false);
                return false;
            } else if (!trueMissingTypes.isEmpty()) {
                failedContainers.put(currentTask.targetPos, trueMissingTypes);
                StringBuilder sb = new StringBuilder();
                int count = 0;
                for (Item item : trueMissingTypes) {
                    if (count > 0) sb.append(", ");
                    sb.append(item.getName().getString());
                    count++;
                    if (count >= 3 && trueMissingTypes.size() > 3) {
                        sb.append(Text.translatable("litematica_container_filler.message.etc").getString());
                        break;
                    }
                }
                sendFeedback(client, Text.translatable("litematica_container_filler.message.material_shortage", sb.toString()).getString(), true);
                return false;
            } else {
                abortTask(client, "litematica_container_filler.message.cursor_stuck", false, false);
                return false;
            }
        }

        sendFeedback(client, Text.translatable("litematica_container_filler.message.task_dispatched").getString(), true);
        return true;
    }

    private void abortTask(MinecraftClient client, String errorMsgKey, boolean isInventoryFull, boolean isLeaking) {
        aborting = true; // Immediately allow screen opens (inventory, etc.)
        sendFeedback(client, Text.translatable(errorMsgKey).getString(), true);
        if (currentTask != null) {
            if (isLeaking) {
                failedContainers.put(currentTask.targetPos, Collections.singleton(net.minecraft.item.Items.BARRIER));
            } else if (isInventoryFull) {
                failedContainers.put(currentTask.targetPos, Collections.emptySet());
            } else {
                Set<Item> missing = new HashSet<>();
                for (ItemStack req : currentTask.missingItems.values()) {
                    missing.add(req.getItem());
                }
                failedContainers.put(currentTask.targetPos, missing);
            }

            RealContainerCache.remove(currentTask.targetPos);
        }
        actionQueue.add(() -> {
            if (client.player != null && client.player.currentScreenHandler != client.player.playerScreenHandler) {
                client.player.closeHandledScreen();
            }
        });
        actionQueue.add(() -> actionWaitTicks = getDelay(1));
        actionQueue.add(this::reset);
    }

    private List<Integer> getEmptySlots(MinecraftClient client) {
        List<Integer> emptySlots = new ArrayList<>();
        for (int i = 0; i < 36; i++) {
            if (client.player.getInventory().getStack(i).isEmpty() && !blacklistedSlots.contains(i)) {
                emptySlots.add(i);
            }
        }
        return emptySlots;
    }

    private int[] findStashAction(MinecraftClient client, Collection<ItemStack> requiredValues) {
        if (!DependencyChecker.HAS_QUICK_SHULKER || !Configs.ENABLE_QS_EXTRACTION.getBooleanValue()) return null;

        int targetShulker = -1;
        int itemToStash = -1;

        for (int i = 0; i < 36; i++) {
            ItemStack s = client.player.getInventory().getStack(i);
            if (s.getItem() instanceof BlockItem bi && bi.getBlock() instanceof ShulkerBoxBlock) {
                ContainerComponent c = s.get(DataComponentTypes.CONTAINER);
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
            ItemStack s = client.player.getInventory().getStack(i);
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

        if (itemToStash != -1) return new int[]{targetShulker, itemToStash};
        return null;
    }

    public boolean isSilentlyExtracting() { return silentlyExtracting; }
    public void clearBlacklist() { failedContainers.clear(); blacklistedSlots.clear(); }

    public void tick(MinecraftClient client) {
        if (client.player == null || client.world == null) { reset(); return; }

        boolean currentContinuousState = Configs.CONTINUOUS_FILL.getBooleanValue();
        if (currentContinuousState != lastContinuousState) {
            clearBlacklist();
            if (!currentContinuousState) taskQueue.clear();
            lastContinuousState = currentContinuousState;
        }

        tickCounter++;
        if (!failedContainers.isEmpty() && tickCounter % 10 == 0) {
            failedContainers.entrySet().removeIf(entry -> {
                Set<Item> reasons = entry.getValue();
                if (reasons.contains(net.minecraft.item.Items.BARRIER)) return false;

                if (reasons.isEmpty()) return getEmptySlots(client).size() > 0 || !openedShulkerSlots.isEmpty();
                for (Item item : reasons) {
                    if (hasItemAnywhere(client, item.getDefaultStack())) return true;
                }
                return false;
            });
        }

        if (currentTask != null) {
            watchdogTimer++;
            if (watchdogTimer > 150) {
                abortTask(client, "litematica_container_filler.message.timeout_reset", false, false);
                return;
            }
        }

        if (actionWaitTicks > 0) { actionWaitTicks--; return; }

        if (currentTask == null) {
            if (!taskQueue.isEmpty()) {
                currentTask = taskQueue.poll();
                borrowedItems.clear();
                openedShulkerSlots.clear();
                shulkerMisses.clear();
                consecutiveFailures = 0;
                movesThisTask = 0;
                cursorStuckAttempts = 0;

                if (currentTask.forcedManual) {
                    changePhase(Phase.INSPECTING);
                } else if (currentTask.needsInspection) {
                    changePhase(Phase.AWAITING_DATA);
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
            ScreenHandler currentHandler = client.player.currentScreenHandler;
            boolean inGui = currentHandler != client.player.playerScreenHandler;

            switch (currentPhase) {
                case AWAITING_DATA:
                    Map<Integer, ItemStack> lateCache = getTrueContainerData(client, currentTask.targetPos);
                    if (lateCache != null) {
                        currentTask.needsInspection = false;

                        boolean isCrafter = client.world.getBlockState(currentTask.targetPos).getBlock() instanceof net.minecraft.block.CrafterBlock;
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
                                if (!isCrafter) needsAction = true;
                            }
                        }

                        if (isCrafter && LitematicaContainerReader.doesCrafterNeedLocking(currentTask.targetPos, client)) needsAction = true;

                        if (!needsAction) {
                            sendFeedback(client, Text.translatable("litematica_container_filler.message.already_satisfied").getString(), true);
                            reset();
                            break;
                        }

                        currentTask.missingItems.clear();
                        currentTask.missingItems.putAll(missingItems);

                        currentTask.initLedger();

                        if (!checkMaterialsAndPrepare(client)) {
                            reset(); break;
                        }
                        checkAndStartGatheringOrFilling(client);
                    } else {
                        dataWaitTimer++;
                        if (dataWaitTimer > 20) changePhase(Phase.INSPECTING);
                    }
                    break;

                case INSPECTING:
                    if (!inGui) {
                        if (!guiOpenedForPhase) {
                            openTargetContainer(client, currentTask.targetPos);
                            guiOpenedForPhase = true;
                        } else {
                            abortTask(client, "litematica_container_filler.message.user_aborted", false, false);
                        }
                    } else {
                        guiOpenedForPhase = true;
                        if (!silentlyExtracting) doInspectionPhase(client);
                    }
                    break;

                case STASHING:
                    if (!inGui) {
                        if (!guiOpenedForPhase) {
                            openShulkerBox(client, stashShulkerSlot);
                            guiOpenedForPhase = true;
                        } else {
                            abortTask(client, "litematica_container_filler.message.user_aborted", false, false);
                        }
                    } else {
                        guiOpenedForPhase = true;
                        if (silentlyExtracting) doStashPhase(client);
                    }
                    break;

                case GATHERING:
                    if (!inGui) {
                        if (!guiOpenedForPhase) {
                            if (pendingShulkers.isEmpty()) {
                                changePhase(Phase.FILLING);
                            } else {
                                openShulkerBox(client, pendingShulkers.poll());
                                guiOpenedForPhase = true;
                            }
                        } else {
                            abortTask(client, "litematica_container_filler.message.user_aborted", false, false);
                        }
                    } else {
                        guiOpenedForPhase = true;
                        if (silentlyExtracting) doShulkerExtractionPhase(client);
                    }
                    break;

                case FILLING:
                    if (!inGui) {
                        if (!guiOpenedForPhase) {
                            openTargetContainer(client, currentTask.targetPos);
                            guiOpenedForPhase = true;
                        } else {
                            abortTask(client, "litematica_container_filler.message.user_aborted", false, false);
                        }
                    } else {
                        guiOpenedForPhase = true;
                        if (!silentlyExtracting) {
                            if (currentMapper == null || mappedHandler != currentHandler) {
                                currentMapper = new SlotMapper(currentHandler, client.player.getInventory());
                                mappedHandler = currentHandler;
                            }
                            executeBurstFill(client, currentHandler);
                        }
                    }
                    break;

                case RETURNING:
                    if (!inGui) {
                        if (!guiOpenedForPhase) {
                            if (pendingShulkers.isEmpty()) reset();
                            else {
                                openShulkerBox(client, pendingShulkers.poll());
                                guiOpenedForPhase = true;
                            }
                        } else {
                            abortTask(client, "litematica_container_filler.message.user_aborted", false, false);
                        }
                    } else {
                        guiOpenedForPhase = true;
                        if (silentlyExtracting) returnBorrowedAndStashedItems(client);
                    }
                    break;

                case IDLE:
                    break;
            }
        }
    }

    private void doInspectionPhase(MinecraftClient client) {
        actionQueue.add(() -> client.player.closeHandledScreen());
        actionQueue.add(() -> actionWaitTicks = getDelay(1));
        actionQueue.add(() -> {
            currentTask.needsInspection = false;
            Map<Integer, ItemStack> newlyCached = RealContainerCache.getCachedItems(currentTask.targetPos);
            if (newlyCached == null) newlyCached = new HashMap<>();

            boolean isCrafter = client.world.getBlockState(currentTask.targetPos).getBlock() instanceof net.minecraft.block.CrafterBlock;
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
                    if (!isCrafter) needsAction = true;
                }
            }

            if (isCrafter && LitematicaContainerReader.doesCrafterNeedLocking(currentTask.targetPos, client)) {
                needsAction = true;
            }

            if (!needsAction) {
                sendFeedback(client, Text.translatable("litematica_container_filler.message.already_satisfied").getString(), true);
                reset();
            } else {
                currentTask.missingItems.clear();
                currentTask.missingItems.putAll(missingItems);
                currentTask.initLedger();
                if (checkMaterialsAndPrepare(client)) checkAndStartGatheringOrFilling(client);
                else reset();
            }
        });
    }

    private void checkAndStartGatheringOrFilling(MinecraftClient client) {
        boolean isCreativeFill = client.player != null && client.player.isCreative() && Configs.ENABLE_CREATIVE_FILL.getBooleanValue();

        int emptySlots = getEmptySlots(client).size();
        if (emptySlots == 0 && Configs.AUTO_STASH_ITEMS.getBooleanValue()) {
            int[] stashAction = findStashAction(client, currentTask.requiredItems.values());
            if (stashAction != null) {
                stashShulkerSlot = stashAction[0];
                stashItemSlot = stashAction[1];
                changePhase(Phase.STASHING);
                return;
            }
        }

        if (isCreativeFill) {
            currentTask.initLedger();
            changePhase(Phase.FILLING);
            return;
        }

        pendingShulkers.clear();
        List<ItemStack> needed = computeNeededToFetch(client);
        if (!needed.isEmpty()) {
            Set<Integer> shulkers = findShulkersContaining(client, needed);
            if (!shulkers.isEmpty()) {
                pendingShulkers.addAll(shulkers);
                changePhase(Phase.GATHERING);
                return;
            } else {
                if (hasAnyMaterialsToFill(client)) {
                    currentTask.initLedger();
                    changePhase(Phase.FILLING);
                    return;
                }
                abortTask(client, "litematica_container_filler.message.materials_depleted", false, false);
                return;
            }
        }

        currentTask.initLedger();
        changePhase(Phase.FILLING);
    }

    private boolean hasAnyMaterialsToFill(MinecraftClient client) {
        boolean isCreativeFill = client.player != null && client.player.isCreative() && Configs.ENABLE_CREATIVE_FILL.getBooleanValue();

        Map<Integer, ItemStack> trueData = getTrueContainerData(client, currentTask.targetPos);
        if (trueData == null) trueData = new HashMap<>();

        Map<StrictItemStackKey, Integer> mobileItemsInContainer = new HashMap<>();
        boolean hasGarbageToExtract = false;
        boolean isCrafter = false;
        if (client.world != null) {
            isCrafter = client.world.getBlockState(currentTask.targetPos).getBlock() instanceof net.minecraft.block.CrafterBlock;
        }

        for (int i = 0; i < 54; i++) {
            ItemStack req = currentTask.requiredItems.getOrDefault(i, ItemStack.EMPTY);
            ItemStack cur = trueData.getOrDefault(i, ItemStack.EMPTY);

            if (!cur.isEmpty()) {
                if (req.isEmpty() || !ItemMatcher.isSameItem(req, cur)) {
                    mobileItemsInContainer.merge(new StrictItemStackKey(cur), cur.getCount(), Integer::sum);
                    hasGarbageToExtract = true;
                } else if (cur.getCount() > req.getCount() && !isCrafter) {
                    mobileItemsInContainer.merge(new StrictItemStackKey(cur), cur.getCount() - req.getCount(), Integer::sum);
                    hasGarbageToExtract = true;
                }
            }
        }

        for (int i = 0; i < 54; i++) {
            ItemStack req = currentTask.requiredItems.getOrDefault(i, ItemStack.EMPTY);
            ItemStack cur = trueData.getOrDefault(i, ItemStack.EMPTY);

            int missingAmt = 0;
            if (!req.isEmpty()) {
                if (cur.isEmpty() || !ItemMatcher.isSameItem(req, cur)) missingAmt = req.getCount();
                else if (cur.getCount() < req.getCount()) missingAmt = req.getCount() - cur.getCount();
            }

            if (missingAmt > 0) {
                StrictItemStackKey hash = new StrictItemStackKey(req);
                int mobileAmt = mobileItemsInContainer.getOrDefault(hash, 0);

                if (countItemInPlayerInv(client, req) > 0 || mobileAmt > 0 || (isCreativeFill && getEmptySlots(client).size() > 0)) {
                    return true;
                }
            }
        }

        if (hasGarbageToExtract) {
            if (Configs.DROP_EXTRACTED_ITEMS.getBooleanValue() || getEmptySlots(client).size() > 0) return true;
            for (int i = 0; i < 54; i++) {
                ItemStack req = currentTask.requiredItems.getOrDefault(i, ItemStack.EMPTY);
                ItemStack cur = trueData.getOrDefault(i, ItemStack.EMPTY);
                if (!cur.isEmpty() && (req.isEmpty() || !ItemMatcher.isSameItem(req, cur) || (cur.getCount() > req.getCount() && !isCrafter))) {
                    if (canAbsorb(client, cur)) return true;
                }
            }
        }
        return false;
    }

    private List<ItemStack> computeNeededToFetch(MinecraftClient client) {
        Map<Integer, ItemStack> trueData = getTrueContainerData(client, currentTask.targetPos);
        if (trueData == null) trueData = new HashMap<>();

        Map<StrictItemStackKey, Integer> containerRequired = new HashMap<>();
        Map<StrictItemStackKey, ItemStack> reqStacks = new HashMap<>();
        for (ItemStack req : currentTask.requiredItems.values()) {
            if (!req.isEmpty()) {
                StrictItemStackKey hash = new StrictItemStackKey(req);
                containerRequired.merge(hash, req.getCount(), Integer::sum);
                reqStacks.putIfAbsent(hash, req.copy());
            }
        }

        Map<StrictItemStackKey, Integer> containerPresent = new HashMap<>();
        for (ItemStack cur : trueData.values()) {
            if (!cur.isEmpty()) containerPresent.merge(new StrictItemStackKey(cur), cur.getCount(), Integer::sum);
        }

        List<ItemStack> needed = new ArrayList<>();
        for (Map.Entry<StrictItemStackKey, Integer> entry : containerRequired.entrySet()) {
            StrictItemStackKey hash = entry.getKey();
            int reqAmt = entry.getValue();
            int presAmt = containerPresent.getOrDefault(hash, 0);

            if (reqAmt > presAmt) {
                int missingAmt = reqAmt - presAmt;
                ItemStack rep = reqStacks.get(hash);
                int inInv = countItemInPlayerInv(client, rep);
                if (inInv < missingAmt) {
                    ItemStack fetch = rep.copy();
                    fetch.setCount(missingAmt - inInv);
                    needed.add(fetch);
                }
            }
        }
        return needed;
    }

    private void doStashPhase(MinecraftClient client) {
        ScreenHandler h = client.player.currentScreenHandler;
        int uiSlot = stashItemSlot < 9 ? stashItemSlot + 54 : stashItemSlot + 18;

        ItemStack stackToStash = h.slots.get(uiSlot).getStack();
        if (!stackToStash.isEmpty()) {
            StrictItemStackKey key = new StrictItemStackKey(stackToStash);
            stashedItemCounts.put(key, stashedItemCounts.getOrDefault(key, 0) + stackToStash.getCount());
        }

        client.interactionManager.clickSlot(h.syncId, uiSlot, 0, SlotActionType.QUICK_MOVE, client.player);
        sendFeedback(client, Text.translatable("litematica_container_filler.message.stashing_items").getString(), true);

        actionQueue.add(() -> {
            client.player.closeHandledScreen();
            guiOpenedForPhase = false;
            silentlyExtracting = false;
            activeShulkerSlot = -1;
            stashShulkerSlot = -1;
            stashItemSlot = -1;

            consecutiveFailures++;
            if (consecutiveFailures > 5) {
                abortTask(client, "litematica_container_filler.message.inventory_full_cannot_extract", true, false);
            }
        });
        actionQueue.add(() -> actionWaitTicks = getDelay(1));
        actionQueue.add(() -> {
            if (currentTask != null) checkAndStartGatheringOrFilling(client);
        });
    }

    private Set<Integer> findShulkersContaining(MinecraftClient client, List<ItemStack> needed) {
        Set<Integer> slots = new LinkedHashSet<>();
        if (!DependencyChecker.HAS_QUICK_SHULKER || !Configs.ENABLE_QS_EXTRACTION.getBooleanValue()) return slots;

        for (ItemStack req : needed) {
            int amountToFind = req.getCount();
            for (int i = 0; i < 36; i++) {
                if (amountToFind <= 0) break;
                if (shulkerMisses.containsKey(i) && shulkerMisses.get(i).contains(req.getItem())) continue;

                ItemStack s = client.player.getInventory().getStack(i);
                if (s.getItem() instanceof BlockItem bi && bi.getBlock() instanceof ShulkerBoxBlock) {
                    ContainerComponent c = s.get(DataComponentTypes.CONTAINER);
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

    private void openTargetContainer(MinecraftClient client, BlockPos pos) {
        silentlyExtracting = false;
        BlockHitResult hitResult = new BlockHitResult(new Vec3d(pos.getX()+0.5, pos.getY()+0.5, pos.getZ()+0.5), Direction.UP, pos, false);
        client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND, hitResult);
        currentMapper = null;
        uiWaitTimer = 0;
        actionQueue.add(this::waitForUi);
        actionQueue.add(() -> actionWaitTicks = getDelay(1));
    }

    private void openShulkerBox(MinecraftClient client, int slot) {
        silentlyExtracting = true;
        activeShulkerSlot = slot;
        openedShulkerSlots.add(slot);

        if (currentPhase == Phase.STASHING) {
            sendFeedback(client, Text.translatable("litematica_container_filler.message.opening_shulker_stash").getString(), true);
        } else if (currentPhase == Phase.RETURNING) {
            sendFeedback(client, Text.translatable("litematica_container_filler.message.opening_shulker_return").getString(), true);
        } else {
            sendFeedback(client, Text.translatable("litematica_container_filler.message.opening_shulker_extract").getString(), true);
        }

        actionQueue.add(() -> shulkerExtractor.requestOpenShulker(slot));
        uiWaitTimer = 0;
        actionQueue.add(this::waitForUi);
        actionQueue.add(() -> actionWaitTicks = getDelay(1));
    }

    private void doShulkerExtractionPhase(MinecraftClient client) {
        ScreenHandler h = client.player.currentScreenHandler;
        Set<Integer> usedEmptySlots = new HashSet<>();
        List<ItemStack> needed = computeNeededToFetch(client);

        int remainingEmptySlots = getEmptySlots(client).size();
        Map<Item, Integer> partialSpaces = new HashMap<>();

        for (int i = 0; i < 36; i++) {
            ItemStack invStack = client.player.getInventory().getStack(i);
            if (!invStack.isEmpty()) {
                partialSpaces.put(invStack.getItem(), partialSpaces.getOrDefault(invStack.getItem(), 0) + (invStack.getMaxCount() - invStack.getCount()));
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

                ItemStack slotStack = h.slots.get(i).getStack();
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
                    client.interactionManager.clickSlot(h.syncId, i, 0, SlotActionType.QUICK_MOVE, client.player);
                } else {
                    boolean success = false;
                    while (!success) {
                        int emptyUiSlot = -1;
                        int emptyPlayerSlot = -1;
                        for (int j = h.slots.size() - 36; j < h.slots.size(); j++) {
                            int pIdx = j - (h.slots.size() - 36);
                            if (h.slots.get(j).getStack().isEmpty() && !usedEmptySlots.contains(j) && !blacklistedSlots.contains(pIdx)) {
                                emptyUiSlot = j;
                                emptyPlayerSlot = pIdx;
                                break;
                            }
                        }

                        if (emptyUiSlot != -1) {
                            usedEmptySlots.add(emptyUiSlot);

                            client.interactionManager.clickSlot(h.syncId, i, 0, SlotActionType.PICKUP, client.player);
                            for (int k = 0; k < amountToTake; k++) {
                                client.interactionManager.clickSlot(h.syncId, emptyUiSlot, 1, SlotActionType.PICKUP, client.player);
                            }
                            client.interactionManager.clickSlot(h.syncId, i, 0, SlotActionType.PICKUP, client.player);

                            if (h.slots.get(emptyUiSlot).getStack().isEmpty()) {
                                blacklistedSlots.add(emptyPlayerSlot);
                            } else {
                                success = true;
                            }
                        } else {
                            client.interactionManager.clickSlot(h.syncId, i, 0, SlotActionType.QUICK_MOVE, client.player);
                            success = true;
                        }
                    }
                }

                borrowedItems.add(new StrictItemStackKey(req));
                amountTaken += amountToTake;

                if (amountToTake > partialSpace) {
                    remainingEmptySlots--;
                    int overflow = amountToTake - partialSpace;
                    partialSpaces.put(req.getItem(), req.getMaxCount() - overflow);
                } else {
                    partialSpaces.put(req.getItem(), partialSpace - amountToTake);
                }
            }

            if (amountTaken == 0 && (remainingEmptySlots > 0 || partialSpaces.getOrDefault(req.getItem(), 0) > 0)) {
                shulkerMisses.computeIfAbsent(activeShulkerSlot, k -> new HashSet<>()).add(req.getItem());
            }
        }

        sendFeedback(client, Text.translatable("litematica_container_filler.message.extraction_done").getString(), true);
        final boolean forceDump = (remainingEmptySlots <= 0);

        actionQueue.add(() -> {
            client.player.closeHandledScreen();
            guiOpenedForPhase = false;
            silentlyExtracting = false;
            activeShulkerSlot = -1;

            if (forceDump) {
                pendingShulkers.clear();
                changePhase(Phase.FILLING);
            }
        });
        actionQueue.add(() -> actionWaitTicks = getDelay(1));
    }

    private boolean canAbsorb(MinecraftClient client, ItemStack stack) {
        if (getEmptySlots(client).size() > 0) return true;
        for (int i = 0; i < 36; i++) {
            ItemStack pStack = client.player.getInventory().getStack(i);
            if (ItemMatcher.isSameItem(pStack, stack) && pStack.getCount() + stack.getCount() <= pStack.getMaxCount()) {
                return true;
            }
        }
        return false;
    }

    private void triggerStashOrAbort(MinecraftClient client) {
        if (Configs.AUTO_STASH_ITEMS.getBooleanValue()) {
            int[] stashAction = findStashAction(client, currentTask.requiredItems.values());
            if (stashAction != null) {
                actionQueue.add(() -> client.player.closeHandledScreen());
                actionQueue.add(() -> actionWaitTicks = getDelay(1));
                actionQueue.add(() -> {
                    stashShulkerSlot = stashAction[0];
                    stashItemSlot = stashAction[1];
                    changePhase(Phase.STASHING);
                });
                return;
            }
        }
        abortTask(client, "litematica_container_filler.message.inventory_full_cannot_extract", true, false);
    }

    private void executeBurstFill(MinecraftClient client, ScreenHandler handler) {
        int syncId = handler.syncId;
        int delay = Configs.ENABLE_SAFETY_DELAY.getBooleanValue() ? Configs.FILL_DELAY.getIntegerValue() : 0;
        boolean dropExtracted = Configs.DROP_EXTRACTED_ITEMS.getBooleanValue();
        boolean isCreativeFill = client.player != null && client.player.isCreative() && Configs.ENABLE_CREATIVE_FILL.getBooleanValue();

        if (!handler.getCursorStack().isEmpty()) {
            boolean placed = tryPlaceCursorItem(client, handler);
            if (!placed) {
                if (dropExtracted) {
                    client.interactionManager.clickSlot(syncId, -999, 0, SlotActionType.PICKUP, client.player);
                } else {
                    cursorStuckAttempts++;
                    if (cursorStuckAttempts > 5) {
                        abortTask(client, "litematica_container_filler.message.cursor_stuck", true, false);
                        return;
                    }
                }
            } else {
                cursorStuckAttempts++;
                if (cursorStuckAttempts > 5) {
                    abortTask(client, "litematica_container_filler.message.cursor_stuck", true, false);
                    return;
                }
            }
            if (delay > 0) { actionWaitTicks = delay; return; }
        } else {
            cursorStuckAttempts = 0;
        }

        int containerSize = handler.slots.size() - 36;
        if (handler instanceof CrafterScreenHandler) containerSize = 9;
        if (containerSize <= 0) { finishTaskAndReturn(client); return; }

        boolean movedAny = false;
        boolean stillNeedsAction = false;
        boolean swappedAnyInThisPass = false;
        boolean extractedAnyInThisPass = false;

        if (handler instanceof CrafterScreenHandler crafterHandler) {
            HandledScreen<?> handledScreen = client.currentScreen instanceof HandledScreen<?> ? (HandledScreen<?>) client.currentScreen : null;
            Set<Integer> targetDisabled = LitematicaContainerReader.getDisabledSlots(currentTask.targetPos);
            boolean toggledInThisTick = false;

            for (int i = 0; i < 9; i++) {
                boolean shouldBeDisabled = targetDisabled != null && targetDisabled.contains(i);
                if (shouldBeDisabled != crafterHandler.isSlotDisabled(i)) {
                    if (crafterHandler.getSlot(i).hasStack()) {
                        if (dropExtracted) simulateSlotClick(handledScreen, crafterHandler.getSlot(i), i, 1, SlotActionType.THROW);
                        else simulateSlotClick(handledScreen, crafterHandler.getSlot(i), i, 0, SlotActionType.QUICK_MOVE);
                    } else {
                        setCrafterSlotEnabled(client, crafterHandler, i, !shouldBeDisabled);
                    }

                    toggledInThisTick = true;
                    movedAny = true;
                    stillNeedsAction = true;
                    if (delay > 0) break;
                }
            }

            if (toggledInThisTick) {
                if (delay > 0 || handledScreen == null) {
                    actionWaitTicks = Math.max(2, delay);
                    consecutiveFailures = 0;
                    watchdogTimer = 0;
                    return;
                }
            }
        }

        for (int containerSlot = 0; containerSlot < containerSize; containerSlot++) {
            if (handler instanceof CrafterScreenHandler ch && ch.isSlotDisabled(containerSlot)) continue;

            int allowed = currentTask.fillLedger.getOrDefault(containerSlot, 0);
            if (allowed <= 0) continue;

            ItemStack reqStack = currentTask.requiredItems.getOrDefault(containerSlot, ItemStack.EMPTY);
            int uiSlot = currentMapper.getUiSlotForContainer(containerSlot);
            if (uiSlot < 0 || uiSlot >= handler.slots.size()) continue;

            ItemStack curStack = handler.slots.get(uiSlot).getStack();

            if (!reqStack.isEmpty() && !curStack.isEmpty() && !ItemMatcher.isSameItem(reqStack, curStack)) {
                stillNeedsAction = true;
                int playerSlot = findItemInPlayerInv(client, reqStack);
                if (playerSlot != -1) {
                    int uiPlayerSlot = currentMapper.getUiSlotForPlayer(playerSlot);
                    if (uiPlayerSlot < 0 || uiPlayerSlot >= handler.slots.size()) continue;

                    client.interactionManager.clickSlot(syncId, uiPlayerSlot, 0, SlotActionType.PICKUP, client.player);
                    client.interactionManager.clickSlot(syncId, uiSlot, 0, SlotActionType.PICKUP, client.player);

                    if (dropExtracted) client.interactionManager.clickSlot(syncId, -999, 0, SlotActionType.PICKUP, client.player);
                    else client.interactionManager.clickSlot(syncId, uiPlayerSlot, 0, SlotActionType.PICKUP, client.player);

                    currentTask.fillLedger.put(containerSlot, 0);
                    movedAny = true; swappedAnyInThisPass = true;
                    if (delay > 0) break;
                }
            }
        }

        if (!swappedAnyInThisPass || delay == 0) {
            for (int dst = 0; dst < containerSize; dst++) {
                if (handler instanceof CrafterScreenHandler ch && ch.isSlotDisabled(dst)) continue;

                int allowed = currentTask.fillLedger.getOrDefault(dst, 0);
                if (allowed <= 0) continue;

                ItemStack reqStack = currentTask.requiredItems.getOrDefault(dst, ItemStack.EMPTY);
                if (reqStack.isEmpty()) continue;

                int uiDst = currentMapper.getUiSlotForContainer(dst);
                if (uiDst < 0 || uiDst >= handler.slots.size()) continue;

                boolean dstNeedsMore = true;
                int maxAttempts = 100;
                while (dstNeedsMore && maxAttempts-- > 0) {
                    ItemStack curDst = handler.slots.get(uiDst).getStack();
                    if (ItemMatcher.isSameItem(reqStack, curDst) && curDst.getCount() >= reqStack.getCount()) {
                        dstNeedsMore = false; break;
                    }

                    int bestSrc = -1;
                    for (int src = 0; src < containerSize; src++) {
                        if (src == dst) continue;
                        if (handler instanceof CrafterScreenHandler ch && ch.isSlotDisabled(src)) continue;

                        int uiSrc = currentMapper.getUiSlotForContainer(src);
                        if (uiSrc < 0 || uiSrc >= handler.slots.size()) continue;

                        ItemStack srcCur = handler.slots.get(uiSrc).getStack();

                        if (ItemMatcher.isSameItem(srcCur, reqStack)) {
                            ItemStack srcReq = currentTask.requiredItems.getOrDefault(src, ItemStack.EMPTY);
                            if (!ItemMatcher.isSameItem(srcCur, srcReq) || srcCur.getCount() > srcReq.getCount()) {
                                bestSrc = src; break;
                            }
                        }
                    }

                    if (bestSrc != -1) {
                        stillNeedsAction = true;
                        int uiSrc = currentMapper.getUiSlotForContainer(bestSrc);
                        if (uiSrc < 0 || uiSrc >= handler.slots.size()) { dstNeedsMore = false; continue; }

                        client.interactionManager.clickSlot(syncId, uiSrc, 0, SlotActionType.PICKUP, client.player);
                        client.interactionManager.clickSlot(syncId, uiDst, 0, SlotActionType.PICKUP, client.player);
                        client.interactionManager.clickSlot(syncId, uiSrc, 0, SlotActionType.PICKUP, client.player);

                        currentTask.fillLedger.put(dst, 0);
                        movedAny = true; swappedAnyInThisPass = true;
                        if (delay > 0) break;
                    } else {
                        dstNeedsMore = false;
                    }
                }
                if (delay > 0 && movedAny) break;
            }
        }

        if (!swappedAnyInThisPass || delay == 0) {
            for (int containerSlot = 0; containerSlot < containerSize; containerSlot++) {
                if (handler instanceof CrafterScreenHandler ch && ch.isSlotDisabled(containerSlot)) continue;

                ItemStack reqStack = currentTask.requiredItems.getOrDefault(containerSlot, ItemStack.EMPTY);
                int uiSlot = currentMapper.getUiSlotForContainer(containerSlot);
                if (uiSlot < 0 || uiSlot >= handler.slots.size()) continue;

                ItemStack curStack = handler.slots.get(uiSlot).getStack();
                if (curStack.isEmpty()) continue;

                boolean isWrong = !reqStack.isEmpty() && !ItemMatcher.isSameItem(curStack, reqStack);
                boolean isExcess = ItemMatcher.isSameItem(curStack, reqStack) && curStack.getCount() > reqStack.getCount();
                if (handler instanceof CrafterScreenHandler) isExcess = false;

                if (reqStack.isEmpty() || isWrong || isExcess) {
                    stillNeedsAction = true;
                    if (dropExtracted) client.interactionManager.clickSlot(syncId, uiSlot, 1, SlotActionType.THROW, client.player);
                    else {
                        if (!canAbsorb(client, curStack)) {
                            triggerStashOrAbort(client); return;
                        }
                        client.interactionManager.clickSlot(syncId, uiSlot, 0, SlotActionType.QUICK_MOVE, client.player);
                    }
                    movedAny = true; extractedAnyInThisPass = true;
                    if (delay > 0) break;
                }
            }
        }

        boolean printedAny = false;
        if (isCreativeFill && (!swappedAnyInThisPass && !extractedAnyInThisPass)) {
            for (int containerSlot = 0; containerSlot < containerSize; containerSlot++) {
                if (handler instanceof CrafterScreenHandler ch && ch.isSlotDisabled(containerSlot)) continue;

                int allowed = currentTask.fillLedger.getOrDefault(containerSlot, 0);
                if (allowed <= 0) continue;

                ItemStack reqStack = currentTask.requiredItems.getOrDefault(containerSlot, ItemStack.EMPTY);
                if (reqStack.isEmpty()) continue;

                int uiSlot = currentMapper.getUiSlotForContainer(containerSlot);
                if (uiSlot < 0 || uiSlot >= handler.slots.size()) continue;

                ItemStack curStack = handler.slots.get(uiSlot).getStack();
                int curCount = curStack.isEmpty() ? 0 : curStack.getCount();

                int actualMissing = Math.min(reqStack.getCount() - curCount, allowed);

                if (actualMissing > 0 && (curStack.isEmpty() || ItemMatcher.isSameItem(curStack, reqStack))) {

                    int emptySlot = findEmptyPlayerSlot(client);

                    if (emptySlot != -1) {
                        ItemStack createStack = reqStack.copy();
                        createStack.setCount(actualMissing);

                        int syncSlot = emptySlot < 9 ? emptySlot + 36 : emptySlot;
                        int uiPlayerSlot = currentMapper.getUiSlotForPlayer(emptySlot);

                        if (uiPlayerSlot >= 0 && uiPlayerSlot < handler.slots.size()) {
                            client.interactionManager.clickCreativeStack(createStack, syncSlot);

                            client.interactionManager.clickSlot(syncId, uiPlayerSlot, 0, SlotActionType.PICKUP, client.player);

                            client.interactionManager.clickSlot(syncId, uiSlot, 0, SlotActionType.PICKUP, client.player);

                            currentTask.fillLedger.put(containerSlot, allowed - actualMissing);
                            printedAny = true;
                            movedAny = true;

                            if (delay > 0) break;
                        }
                    }
                }
            }
            if (printedAny && delay > 0) {
                actionWaitTicks = Math.max(1, delay);
                consecutiveFailures = 0;
                watchdogTimer = 0;
                return;
            }
        }

        if ((!swappedAnyInThisPass && !extractedAnyInThisPass && !printedAny) || delay == 0) {
            for (int containerSlot = 0; containerSlot < containerSize; containerSlot++) {
                if (handler instanceof CrafterScreenHandler ch && ch.isSlotDisabled(containerSlot)) continue;

                int allowed = currentTask.fillLedger.getOrDefault(containerSlot, 0);
                if (allowed <= 0) continue;

                ItemStack reqStack = currentTask.requiredItems.getOrDefault(containerSlot, ItemStack.EMPTY);
                if (reqStack.isEmpty()) continue;

                int uiSlot = currentMapper.getUiSlotForContainer(containerSlot);
                if (uiSlot < 0 || uiSlot >= handler.slots.size()) continue;

                boolean dstNeedsMore = true;
                int maxAttempts = 100;
                while (dstNeedsMore && maxAttempts-- > 0 && allowed > 0) {
                    ItemStack curStack = handler.slots.get(uiSlot).getStack();
                    int curCount = curStack.isEmpty() ? 0 : curStack.getCount();

                    int actualMissing = Math.min(reqStack.getCount() - curCount, allowed);

                    if (actualMissing > 0 && (curStack.isEmpty() || ItemMatcher.isSameItem(curStack, reqStack))) {
                        stillNeedsAction = true;
                        ItemStack needed = reqStack.copy();
                        needed.setCount(actualMissing);

                        int playerSlot = findItemInPlayerInv(client, needed);

                        if (playerSlot != -1) {
                            int movedAmount = fillFromPlayerInv(client, syncId, playerSlot, uiSlot, actualMissing);

                            allowed -= movedAmount;
                            currentTask.fillLedger.put(containerSlot, allowed);

                            movedAny = true;
                            if (delay > 0) break;
                        } else {
                            dstNeedsMore = false;
                        }
                    } else {
                        dstNeedsMore = false;
                    }
                }
                if (delay > 0 && movedAny) break;
            }
        }

        if (movedAny) {
            actionWaitTicks = delay;
            consecutiveFailures = 0;
            watchdogTimer = 0;

            movesThisTask++;
            if (movesThisTask > 150) {
                abortTask(client, "litematica_container_filler.message.container_leaking", false, true);
                return;
            }
        } else {
            if (stillNeedsAction) {
                boolean allLedgersExhausted = true;
                boolean hasTrackedLedgers = false;
                for (int containerSlot = 0; containerSlot < containerSize; containerSlot++) {
                    ItemStack reqStack = currentTask.requiredItems.getOrDefault(containerSlot, ItemStack.EMPTY);
                    if (!reqStack.isEmpty()) {
                        hasTrackedLedgers = true;
                        if (currentTask.fillLedger.getOrDefault(containerSlot, 0) > 0) {
                            allLedgersExhausted = false;
                            break;
                        }
                    }
                }

                if (hasTrackedLedgers && allLedgersExhausted) {
                    abortTask(client, "litematica_container_filler.message.container_leaking", false, true);
                    return;
                }

                consecutiveFailures++;
                if (consecutiveFailures > 2) {
                    abortTask(client, "litematica_container_filler.message.cursor_stuck", false, false);
                    return;
                }

                RealContainerCache.updateFromHandler(client, handler);

                actionQueue.add(() -> client.player.closeHandledScreen());
                actionQueue.add(() -> actionWaitTicks = getDelay(1));
                actionQueue.add(() -> checkAndStartGatheringOrFilling(client));
            } else {
                finishTaskAndReturn(client);
            }
        }
    }

    private void finishTaskAndReturn(MinecraftClient client) {
        ScreenHandler currentHandler = client.player.currentScreenHandler;
        if (currentHandler != client.player.playerScreenHandler) {
            RealContainerCache.updateFromHandler(client, currentHandler);
        }

        sendFeedback(client, Text.translatable("litematica_container_filler.message.fill_completed").getString(), true);

        actionQueue.add(() -> client.player.closeHandledScreen());
        actionQueue.add(() -> actionWaitTicks = getDelay(1));

        actionQueue.add(() -> {
            borrowedItems.removeIf(key -> {
                for (int i = 0; i < 36; i++) {
                    if (ItemMatcher.isSameItem(client.player.getInventory().getStack(i), key.stack)) return false;
                }
                return true;
            });

            stashedItemCounts.entrySet().removeIf(entry -> entry.getValue() <= 0);

            if (!openedShulkerSlots.isEmpty() && (!borrowedItems.isEmpty() || !stashedItemCounts.isEmpty())) {
                pendingShulkers.clear();
                pendingShulkers.addAll(openedShulkerSlots);
                changePhase(Phase.RETURNING);
            } else {
                reset();
            }
        });
    }

    private void returnBorrowedAndStashedItems(MinecraftClient client) {
        ScreenHandler h = client.player.currentScreenHandler;
        boolean movedAny = false;

        for (int i = h.slots.size() - 36; i < h.slots.size(); i++) {
            ItemStack s = h.slots.get(i).getStack();
            if (!s.isEmpty()) {
                StrictItemStackKey sKey = new StrictItemStackKey(s);
                if (borrowedItems.contains(sKey)) {
                    client.interactionManager.clickSlot(h.syncId, i, 0, SlotActionType.QUICK_MOVE, client.player);
                    movedAny = true;
                }
            }
        }

        for (int i = 0; i < h.slots.size() - 36; i++) {
            ItemStack s = h.slots.get(i).getStack();
            if (!s.isEmpty()) {
                StrictItemStackKey sKey = new StrictItemStackKey(s);
                if (stashedItemCounts.containsKey(sKey)) {
                    int neededToRetrieve = stashedItemCounts.get(sKey);
                    if (neededToRetrieve <= 0) continue;

                    int amountInSlot = s.getCount();
                    if (amountInSlot <= neededToRetrieve) {
                        client.interactionManager.clickSlot(h.syncId, i, 0, SlotActionType.QUICK_MOVE, client.player);
                        stashedItemCounts.put(sKey, neededToRetrieve - amountInSlot);
                    } else {
                        int emptySlot = -1;
                        for (int j = h.slots.size() - 36; j < h.slots.size(); j++) {
                            if (h.slots.get(j).getStack().isEmpty()) {
                                emptySlot = j; break;
                            }
                        }
                        if (emptySlot != -1) {
                            client.interactionManager.clickSlot(h.syncId, i, 0, SlotActionType.PICKUP, client.player);
                            for (int k = 0; k < neededToRetrieve; k++) {
                                client.interactionManager.clickSlot(h.syncId, emptySlot, 1, SlotActionType.PICKUP, client.player);
                            }
                            client.interactionManager.clickSlot(h.syncId, i, 0, SlotActionType.PICKUP, client.player);
                            stashedItemCounts.put(sKey, 0);
                        } else {
                            client.interactionManager.clickSlot(h.syncId, i, 0, SlotActionType.QUICK_MOVE, client.player);
                            stashedItemCounts.put(sKey, neededToRetrieve - amountInSlot);
                        }
                    }
                    movedAny = true;
                }
            }
        }

        if (movedAny) {
            sendFeedback(client, Text.translatable("litematica_container_filler.message.returning_items").getString(), true);
        }

        actionQueue.add(() -> {
            client.player.closeHandledScreen();
            guiOpenedForPhase = false;
            silentlyExtracting = false;
            activeShulkerSlot = -1;
        });
        actionQueue.add(() -> actionWaitTicks = getDelay(1));
    }

    private void waitForUi() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null && client.player.currentScreenHandler == client.player.playerScreenHandler) {
            uiWaitTimer++;
            if (uiWaitTimer > 20) {
                abortTask(client, "litematica_container_filler.message.container_timeout", false, false);
                return;
            }
            actionQueue.addFirst(this::waitForUi);
            yieldTick = true;
        } else {
            uiWaitTimer = 0;
        }
    }

    private void reset() {
        aborting = false;
        currentTask = null;
        currentMapper = null;
        mappedHandler = null;
        silentlyExtracting = false;
        yieldTick = false;
        changePhase(Phase.IDLE);
        actionQueue.clear();
        pendingShulkers.clear();
        actionWaitTicks = 0;
        watchdogTimer = 0;
        movesThisTask = 0;
        consecutiveFailures = 0;
        cursorStuckAttempts = 0;
        uiWaitTimer = 0;
        dataWaitTimer = 0;
        activeShulkerSlot = -1;
        stashShulkerSlot = -1;
        stashItemSlot = -1;
        borrowedItems.clear();
        stashedItemCounts.clear();
        openedShulkerSlots.clear();
        shulkerMisses.clear();
        blacklistedSlots.clear();
    }

    private void sendFeedback(MinecraftClient client, String text, boolean isActionBar) {
        if (client.player != null) client.player.sendMessage(Text.literal(text), isActionBar);
    }

    private boolean hasItemAnywhere(MinecraftClient client, ItemStack target) {
        for (int i = 0; i < 36; i++) {
            ItemStack s = client.player.getInventory().getStack(i);
            if (ItemMatcher.isSameItem(s, target)) return true;
            if (s.getItem() instanceof BlockItem bi && bi.getBlock() instanceof ShulkerBoxBlock) {
                ContainerComponent c = s.get(DataComponentTypes.CONTAINER);
                if (c != null) {
                    for (ItemStack inner : c.stream().toList()) {
                        if (ItemMatcher.isSameItem(inner, target)) return true;
                    }
                }
            }
        }
        return false;
    }

    private int countItemInPlayerInv(MinecraftClient client, ItemStack target) {
        int count = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack s = client.player.getInventory().getStack(i);
            if (ItemMatcher.isSameItem(s, target)) count += s.getCount();
        }
        return count;
    }

    private int findItemInPlayerInv(MinecraftClient client, ItemStack target) {
        for (int i = 0; i < 36; i++) if (ItemMatcher.isSameItem(client.player.getInventory().getStack(i), target)) return i;
        return -1;
    }

    private boolean tryPlaceCursorItem(MinecraftClient client, ScreenHandler handler) {
        int empty = findEmptyPlayerSlot(client);
        if (empty != -1) {
            int uiPlayerSlot = currentMapper.getUiSlotForPlayer(empty);
            if (uiPlayerSlot < 0 || uiPlayerSlot >= handler.slots.size()) return false;

            int before = handler.getCursorStack().getCount();
            client.interactionManager.clickSlot(handler.syncId, uiPlayerSlot, 0, SlotActionType.PICKUP, client.player);

            if (handler.getCursorStack().getCount() == before) {
                blacklistedSlots.add(empty);
                return false;
            }
            return true;
        }
        return false;
    }

    private int findEmptyPlayerSlot(MinecraftClient client) {
        for (int i = 9; i < 36; i++) if (client.player.getInventory().getStack(i).isEmpty() && !blacklistedSlots.contains(i)) return i;
        for (int i = 0; i < 9; i++) if (client.player.getInventory().getStack(i).isEmpty() && !blacklistedSlots.contains(i)) return i;
        return -1;
    }

    private int fillFromPlayerInv(MinecraftClient client, int syncId, int playerSlot, int uiContainerSlot, int needed) {
        int uiPlayerSlot = currentMapper.getUiSlotForPlayer(playerSlot);
        ScreenHandler handler = client.player.currentScreenHandler;

        if (uiPlayerSlot < 0 || uiPlayerSlot >= handler.slots.size() || uiContainerSlot < 0 || uiContainerSlot >= handler.slots.size()) return 0;

        ItemStack sourceStack = client.player.getInventory().getStack(playerSlot);
        int countInSlot = sourceStack.getCount();
        int amountToMove = Math.min(needed, countInSlot);

        if (amountToMove == countInSlot) {
            client.interactionManager.clickSlot(syncId, uiPlayerSlot, 0, SlotActionType.PICKUP, client.player);
            client.interactionManager.clickSlot(syncId, uiContainerSlot, 0, SlotActionType.PICKUP, client.player);
            client.interactionManager.clickSlot(syncId, uiPlayerSlot, 0, SlotActionType.PICKUP, client.player);
        } else {
            client.interactionManager.clickSlot(syncId, uiPlayerSlot, 0, SlotActionType.PICKUP, client.player);
            for (int i = 0; i < amountToMove; i++) {
                client.interactionManager.clickSlot(syncId, uiContainerSlot, 1, SlotActionType.PICKUP, client.player);
            }
            client.interactionManager.clickSlot(syncId, uiPlayerSlot, 0, SlotActionType.PICKUP, client.player);
        }
        return amountToMove;
    }

    public BlockPos getCurrentTaskPos() { return currentTask != null ? currentTask.targetPos : null; }
    public boolean isIdle() { return this.currentTask == null && this.actionQueue.isEmpty(); }

    public boolean isWorking() { return !isIdle(); }

    /**
     * Returns true only when the filler is actively performing automated operations
     * (filling, extracting, inspecting, etc.) - NOT when it's aborting or resetting.
     * Used by ScreenInterceptorMixin to decide whether to block screen opens.
     * This prevents the "can't open inventory after timeout" bug.
     */
    public boolean shouldBlockScreens() {
        return isWorking() && currentPhase != Phase.IDLE && !aborting;
    }

    private boolean aborting = false;

    private void setCrafterSlotEnabled(MinecraftClient client, CrafterScreenHandler handler, int slotId, boolean enabled) {
        handler.setSlotEnabled(slotId, enabled);

        if (client.interactionManager != null) {
            client.interactionManager.slotChangedState(slotId, handler.syncId, enabled);
        }

        Set<Integer> disabledSlots = new HashSet<>();
        for (int i = 0; i < 9; i++) {
            if (handler.isSlotDisabled(i)) {
                disabledSlots.add(i);
            }
        }
        RealContainerCache.putLock(currentTask.targetPos, disabledSlots);
    }

    private void simulateSlotClick(HandledScreen<?> screen, Slot slot, int slotId, int button, SlotActionType actionType) {
        try {
            MinecraftClient client = MinecraftClient.getInstance();

            if (screen == null) {
                client.interactionManager.clickSlot(client.player.currentScreenHandler.syncId, slotId, button, actionType, client.player);
                return;
            }

            java.lang.reflect.Method targetMethod = null;
            Class<?> currClass = screen.getClass();
            while (currClass != null && targetMethod == null) {
                for (java.lang.reflect.Method m : currClass.getDeclaredMethods()) {
                    Class<?>[] params = m.getParameterTypes();
                    if (params.length == 4 && params[0] == Slot.class && params[1] == int.class && params[2] == int.class && params[3] == SlotActionType.class) {
                        targetMethod = m; break;
                    }
                }
                currClass = currClass.getSuperclass();
            }
            if (targetMethod != null) {
                targetMethod.setAccessible(true);
                targetMethod.invoke(screen, slot, slotId, button, actionType);
            } else {
                client.interactionManager.clickSlot(screen.getScreenHandler().syncId, slotId, button, actionType, client.player);
            }
        } catch (Exception e) { e.printStackTrace(); }
    }
}

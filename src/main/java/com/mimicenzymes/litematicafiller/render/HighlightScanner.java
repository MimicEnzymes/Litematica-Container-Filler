package com.mimicenzymes.litematicafiller.render;

import com.mimicenzymes.litematicafiller.config.Configs;
import com.mimicenzymes.litematicafiller.core.AutoFillerStateMachine;
import com.mimicenzymes.litematicafiller.filter.ContainerBlockFilter;
import com.mimicenzymes.litematicafiller.tool.ContainerToolStateMachine;
import com.mimicenzymes.litematicafiller.core.ItemMatcher;
import com.mimicenzymes.litematicafiller.core.LitematicaContainerReader;
import com.mimicenzymes.litematicafiller.core.LitematicaPlacementContainerData;
import com.mimicenzymes.litematicafiller.core.ManualContainerOverrideManager;
import com.mimicenzymes.litematicafiller.core.ManualContainerOverrideState;
import com.mimicenzymes.litematicafiller.core.MaterialReplacer;
import com.mimicenzymes.litematicafiller.core.RealContainerCache;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.BlockPos;
import fi.dy.masa.malilib.util.LayerRange;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class HighlightScanner {
    private static final int NORMAL_UPDATE_INTERVAL_TICKS = 10;
    private static final int IDLE_UPDATE_INTERVAL_TICKS = 20;
    private static final int BOOSTED_UPDATE_INTERVAL_TICKS = 2;
    private static final int BOOST_DURATION_TICKS = 60;
    private static final int MAX_DATA_REQUESTS_PER_TICK = 128;
    private static final long UNKNOWN_REQUEST_INTERVAL_MS = 100L;
    private static final long BARREL_ACTIVE_REQUEST_INTERVAL_MS = 250L;
    private static final long BARREL_SATISFIED_REQUEST_INTERVAL_MS = 1000L;
    private static final long LARGE_BARREL_CONFIRM_REQUEST_INTERVAL_MS = 250L;
    private static final long ACTIVE_REQUEST_INTERVAL_MS = 750L;
    private static final long SATISFIED_REQUEST_INTERVAL_MS = 4000L;
    private static final long EMPTY_SYNC_CONFIRMATION_MS = 5000L;
    private static final Map<BlockPos, HighlightState> HIGHLIGHT_MAP = new ConcurrentHashMap<>();
    private static final Map<BlockPos, Map<Integer, ItemStack>> SCHEMATIC_REQ_CACHE = new ConcurrentHashMap<>();
    private static final Map<BlockPos, Set<Integer>> SCHEMATIC_IGNORED_SLOT_CACHE = new ConcurrentHashMap<>();
    private static final Map<BlockPos, Long> HIGHLIGHT_REQUEST_TIME = new ConcurrentHashMap<>();
    private static final Map<BlockPos, Long> HIGHLIGHT_REQUEST_INTERVALS = new ConcurrentHashMap<>();
    private static final Deque<BlockPos> DATA_REQUEST_QUEUE = new ArrayDeque<>();
    private static final Set<BlockPos> QUEUED_DATA_REQUESTS = new HashSet<>();
    private static final Deque<BlockPos> DIRTY_HIGHLIGHT_QUEUE = new ArrayDeque<>();
    private static final Set<BlockPos> QUEUED_DIRTY_HIGHLIGHTS = new HashSet<>();
    private static final int MAX_DIRTY_HIGHLIGHT_UPDATES_PER_TICK = 64;
    private static volatile int highlightVersion = 0;
    private static HighlightFingerprint highlightFingerprint = HighlightFingerprint.empty();
    private static final ExecutorService INDEX_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "LitematicaFiller-HighlightScanner");
        thread.setDaemon(true);
        return thread;
    });
    private static volatile Set<BlockPos> SCHEMATIC_CONTAINERS = Collections.emptySet();
    private static volatile Map<Long, Set<BlockPos>> SCHEMATIC_CONTAINER_BUCKETS = Collections.emptyMap();
    private static volatile long lastIndexTime = 0;
    private static volatile boolean isIndexing = false;
    private static long lastRenderLayerSignature = Long.MIN_VALUE;
    private static boolean pendingRenderLayerRefresh = false;
    private static long lastRenderLayerRefreshTick = Long.MIN_VALUE;
    private static Map<Long, Set<BlockPos>> cachedNearbyBucketSource = Collections.emptyMap();
    private static List<Set<BlockPos>> cachedNearbyBucketSets = Collections.emptyList();
    private static int cachedNearbyMinX = Integer.MIN_VALUE;
    private static int cachedNearbyMaxX = Integer.MIN_VALUE;
    private static int cachedNearbyMinY = Integer.MIN_VALUE;
    private static int cachedNearbyMaxY = Integer.MIN_VALUE;
    private static int cachedNearbyMinZ = Integer.MIN_VALUE;
    private static int cachedNearbyMaxZ = Integer.MIN_VALUE;

    private static int tickCounter = 0;
    private static int boostedTicks = 0;
    private static int seenGlobalReplacementVersion = -1;
    private static int seenSchematicReplacementVersion = -1;

    public static Map<BlockPos, HighlightState> getHighlights() {
        return HIGHLIGHT_MAP;
    }

    public static int getHighlightVersion() {
        return highlightVersion;
    }

    public static void onContainerDataChanged(BlockPos pos) {
        if (pos != null) {
            HIGHLIGHT_REQUEST_TIME.remove(pos);
            HIGHLIGHT_REQUEST_INTERVALS.remove(pos);
            DATA_REQUEST_QUEUE.remove(pos);
            QUEUED_DATA_REQUESTS.remove(pos);
        }
        triggerBoost(BOOST_DURATION_TICKS);
    }

    public static void onManualOverrideChanged(BlockPos pos, ManualContainerOverrideState state) {
        if (pos == null) return;

        if (state == ManualContainerOverrideState.AUTO) {
            BlockPos key = pos.immutable();
            RealContainerCache.remove(key);
            HIGHLIGHT_REQUEST_TIME.remove(key);
            HIGHLIGHT_REQUEST_INTERVALS.remove(key);
            DATA_REQUEST_QUEUE.remove(key);
            QUEUED_DATA_REQUESTS.remove(key);
            if (HIGHLIGHT_MAP.remove(key) != null) {
                highlightFingerprint = computeHighlightFingerprint(HIGHLIGHT_MAP);
                highlightVersion++;
            }
            triggerBoost(BOOST_DURATION_TICKS);
        } else {
            triggerBoost(BOOST_DURATION_TICKS);
        }
    }

    public static void onManualOverridesCleared() {
        boolean changed = false;
        for (Map.Entry<BlockPos, HighlightState> entry : HIGHLIGHT_MAP.entrySet()) {
            HighlightState state = entry.getValue();
            if ((state == HighlightState.MANUAL_COMPLETED || state == HighlightState.MANUAL_NEEDS_FILL) &&
                    HIGHLIGHT_MAP.remove(entry.getKey(), state)) {
                changed = true;
            }
        }
        if (changed) {
            highlightFingerprint = computeHighlightFingerprint(HIGHLIGHT_MAP);
            highlightVersion++;
        }
        triggerBoost(BOOST_DURATION_TICKS);
    }

    public static void onMaterialReplacementChanged() {
        seenGlobalReplacementVersion = MaterialReplacer.getGlobalReplacementVersion();
        seenSchematicReplacementVersion = MaterialReplacer.getSchematicReplacementVersion();
        SCHEMATIC_REQ_CACHE.clear();
        SCHEMATIC_IGNORED_SLOT_CACHE.clear();
        LitematicaPlacementContainerData.clear();
        HIGHLIGHT_REQUEST_TIME.clear();
        HIGHLIGHT_REQUEST_INTERVALS.clear();
        DATA_REQUEST_QUEUE.clear();
        QUEUED_DATA_REQUESTS.clear();
        DIRTY_HIGHLIGHT_QUEUE.clear();
        QUEUED_DIRTY_HIGHLIGHTS.clear();
        triggerBoost(BOOST_DURATION_TICKS);
    }

    public record ContainerSnapshot(List<BlockPos> positions, int nextCursor, int totalCount) {
    }

    public static ContainerSnapshot getNearbySchematicContainersSnapshot(BlockPos center, int radius, int cursor, int limit) {
        ensureSchematicContainerIndex();
        if (limit <= 0) return new ContainerSnapshot(Collections.emptyList(), 0, 0);

        Map<Long, Set<BlockPos>> buckets = SCHEMATIC_CONTAINER_BUCKETS;
        if (radius <= 0 || buckets.isEmpty()) {
            Collection<BlockPos> indexed = SCHEMATIC_CONTAINERS;
            return collectSnapshot(indexed, cursor, limit);
        }

        List<Set<BlockPos>> bucketSets = getNearbyBucketSets(buckets, center, radius);
        int totalCount = 0;
        for (Set<BlockPos> bucket : bucketSets) {
            totalCount += bucket.size();
        }

        if (totalCount == 0) {
            return new ContainerSnapshot(Collections.emptyList(), 0, 0);
        }

        int start = Math.floorMod(cursor, totalCount);
        int targetCount = Math.min(limit, totalCount);
        List<BlockPos> snapshot = new ArrayList<>(targetCount);

        int index = 0;
        for (Set<BlockPos> bucket : bucketSets) {
            for (BlockPos pos : bucket) {
                if (index++ >= start) {
                    snapshot.add(pos);
                    if (snapshot.size() >= targetCount) {
                        return new ContainerSnapshot(snapshot, (start + snapshot.size()) % totalCount, totalCount);
                    }
                }
            }
        }

        for (Set<BlockPos> bucket : bucketSets) {
            for (BlockPos pos : bucket) {
                snapshot.add(pos);
                if (snapshot.size() >= targetCount) break;
            }
            if (snapshot.size() >= targetCount) break;
        }

        return new ContainerSnapshot(snapshot, (start + snapshot.size()) % totalCount, totalCount);
    }

    private static ContainerSnapshot collectSnapshot(Collection<BlockPos> positions, int cursor, int limit) {
        int totalCount = positions.size();
        if (totalCount == 0) return new ContainerSnapshot(Collections.emptyList(), 0, 0);

        int start = Math.floorMod(cursor, totalCount);
        int targetCount = Math.min(limit, totalCount);
        List<BlockPos> snapshot = new ArrayList<>(targetCount);

        int index = 0;
        for (BlockPos pos : positions) {
            if (index++ >= start) {
                snapshot.add(pos);
                if (snapshot.size() >= targetCount) {
                    return new ContainerSnapshot(snapshot, (start + snapshot.size()) % totalCount, totalCount);
                }
            }
        }

        for (BlockPos pos : positions) {
            snapshot.add(pos);
            if (snapshot.size() >= targetCount) break;
        }

        return new ContainerSnapshot(snapshot, (start + snapshot.size()) % totalCount, totalCount);
    }

    public static boolean hasSchematicContainerIndex() {
        return !SCHEMATIC_CONTAINERS.isEmpty();
    }

    public static void ensureSchematicContainerIndex() {
        long now = System.currentTimeMillis();
        if (now - lastIndexTime <= 5000) return;
        startIndexingIfIdle(now);
    }

    public static void clearCache() {
        clearHighlights();
        SCHEMATIC_REQ_CACHE.clear();
        SCHEMATIC_IGNORED_SLOT_CACHE.clear();
        HIGHLIGHT_REQUEST_TIME.clear();
        HIGHLIGHT_REQUEST_INTERVALS.clear();
        DATA_REQUEST_QUEUE.clear();
        QUEUED_DATA_REQUESTS.clear();
        DIRTY_HIGHLIGHT_QUEUE.clear();
        QUEUED_DIRTY_HIGHLIGHTS.clear();
        LitematicaPlacementContainerData.clear();
        ManualContainerOverrideManager.clearForCurrentContext();
        SCHEMATIC_CONTAINERS = Collections.emptySet();
        SCHEMATIC_CONTAINER_BUCKETS = Collections.emptyMap();
        invalidateNearbyBucketCache();
        lastIndexTime = 0;
        lastRenderLayerSignature = Long.MIN_VALUE;
        pendingRenderLayerRefresh = false;
        lastRenderLayerRefreshTick = Long.MIN_VALUE;
        boostedTicks = 0;
    }

    public static void onPlacementChanged() {
        lastIndexTime = 0;
        SCHEMATIC_CONTAINERS = Collections.emptySet();
        SCHEMATIC_REQ_CACHE.clear();
        SCHEMATIC_IGNORED_SLOT_CACHE.clear();
        HIGHLIGHT_REQUEST_TIME.clear();
        HIGHLIGHT_REQUEST_INTERVALS.clear();
        DATA_REQUEST_QUEUE.clear();
        QUEUED_DATA_REQUESTS.clear();
        DIRTY_HIGHLIGHT_QUEUE.clear();
        QUEUED_DIRTY_HIGHLIGHTS.clear();
        LitematicaPlacementContainerData.clear();
        ManualContainerOverrideManager.clearForCurrentContext();
        triggerBoost(BOOST_DURATION_TICKS);
        SCHEMATIC_CONTAINER_BUCKETS = Collections.emptyMap();
        invalidateNearbyBucketCache();
        lastRenderLayerSignature = Long.MIN_VALUE;
        pendingRenderLayerRefresh = false;
        lastRenderLayerRefreshTick = Long.MIN_VALUE;
    }

    private static Map<Integer, ItemStack> getCachedSchematicReq(BlockPos pos, Minecraft client) {
        Map<Integer, ItemStack> req = SCHEMATIC_REQ_CACHE.get(pos);
        if (req == null) {
            req = LitematicaContainerReader.getRequiredItems(pos, client.level.registryAccess());
            if (req != null) {
                SCHEMATIC_REQ_CACHE.put(pos, req);
            }
        }
        return req;
    }

    public static Map<Integer, ItemStack> getCachedSchematicRequirement(BlockPos pos, Minecraft client) {
        return getCachedSchematicReq(pos, client);
    }

    private static Set<Integer> getCachedIgnoredSlots(BlockPos pos, Minecraft client) {
        return SCHEMATIC_IGNORED_SLOT_CACHE.computeIfAbsent(pos.immutable(),
                ignored -> LitematicaContainerReader.getIgnoredSlots(pos, client.level.registryAccess()));
    }

    public static void tick(Minecraft client) {
        tickCounter++;
        if (!Configs.ENABLE_MOD.getBooleanValue() || !Configs.HIGHLIGHT_CONTAINERS.getBooleanValue()) {
            clearHighlights();
            DATA_REQUEST_QUEUE.clear();
            QUEUED_DATA_REQUESTS.clear();
            DIRTY_HIGHLIGHT_QUEUE.clear();
            QUEUED_DIRTY_HIGHLIGHTS.clear();
            HIGHLIGHT_REQUEST_TIME.clear();
            HIGHLIGHT_REQUEST_INTERVALS.clear();
            return;
        }

        if (client.player == null) return;

        var schematicWorld = fi.dy.masa.litematica.world.SchematicWorldHandler.getSchematicWorld();
        if (schematicWorld == null) {
            clearHighlights();
            if (!SCHEMATIC_REQ_CACHE.isEmpty()) SCHEMATIC_REQ_CACHE.clear();
            if (!SCHEMATIC_IGNORED_SLOT_CACHE.isEmpty()) SCHEMATIC_IGNORED_SLOT_CACHE.clear();
            if (!HIGHLIGHT_REQUEST_TIME.isEmpty()) HIGHLIGHT_REQUEST_TIME.clear();
            if (!SCHEMATIC_CONTAINERS.isEmpty()) SCHEMATIC_CONTAINERS = Collections.emptySet();
            if (!SCHEMATIC_CONTAINER_BUCKETS.isEmpty()) SCHEMATIC_CONTAINER_BUCKETS = Collections.emptyMap();
            return;
        }
        syncGlobalReplacementVersion();

        BlockPos currentCenter = client.player.blockPosition();

        long now = System.currentTimeMillis();
        boolean modOperating = AutoFillerStateMachine.getInstance().isWorking() || ContainerToolStateMachine.getInstance().isWorking();
        boolean syncLayer = Configs.SYNC_LITE_LAYER.getBooleanValue();
        LayerRange renderLayerRange = syncLayer ? fi.dy.masa.litematica.data.DataManager.getRenderLayerRange() : null;
        processDirtyHighlights(client, schematicWorld, renderLayerRange, now);

        boolean userHandledScreenOpen = client.screen instanceof AbstractContainerScreen<?> && !modOperating;
        if (userHandledScreenOpen) {
            pumpDataRequests(now);
            return;
        }

        pumpDataRequests(now);

        startIndexingIfIdle(now);

        boolean layerChanged = updateRenderLayerSignature(syncLayer);
        if (layerChanged) {
            pendingRenderLayerRefresh = true;
            triggerBoost(BOOST_DURATION_TICKS);
        }

        boolean fillWorkEnabled = Configs.WORKING_STATE.getBooleanValue();
        int updateInterval = boostedTicks > 0
                ? BOOSTED_UPDATE_INTERVAL_TICKS
                : (modOperating || fillWorkEnabled ? NORMAL_UPDATE_INTERVAL_TICKS : IDLE_UPDATE_INTERVAL_TICKS);
        boolean layerRefreshDue = pendingRenderLayerRefresh &&
                (lastRenderLayerRefreshTick == Long.MIN_VALUE ||
                        tickCounter - lastRenderLayerRefreshTick >= BOOSTED_UPDATE_INTERVAL_TICKS);
        if (!layerRefreshDue && tickCounter % updateInterval != 0) {
            if (boostedTicks > 0) boostedTicks--;
            return;
        }
        if (pendingRenderLayerRefresh) {
            pendingRenderLayerRefresh = false;
            lastRenderLayerRefreshTick = tickCounter;
        }

        int currentRadius = Configs.RENDER_RADIUS.getIntegerValue();
        double radiusSq = currentRadius * currentRadius;
        boolean hasManualOverrides = ManualContainerOverrideManager.hasOverrides();

        HighlightBuild nextHighlights = new HighlightBuild();

        for (BlockPos pos : getNearbyHighlightCandidates(currentCenter, currentRadius)) {
            if (currentRadius > 0 && pos.distSqr(currentCenter) > radiusSq) continue;

            HighlightState type = evaluateHighlightForPosition(client, schematicWorld, pos, renderLayerRange,
                    hasManualOverrides, now);
            if (type != null) {
                nextHighlights.put(pos, type);
            }
        }

        replaceHighlightsIfChanged(nextHighlights);
        if (boostedTicks > 0) {
            boostedTicks--;
        }
    }

    private static void syncGlobalReplacementVersion() {
        int globalVersion = MaterialReplacer.getGlobalReplacementVersion();
        int schematicVersion = MaterialReplacer.getSchematicReplacementVersion();
        if (globalVersion != seenGlobalReplacementVersion || schematicVersion != seenSchematicReplacementVersion) {
            onMaterialReplacementChanged();
        }
    }

    private static void processDirtyHighlights(Minecraft client,
                                               net.minecraft.world.level.Level schematicWorld,
                                               LayerRange renderLayerRange,
                                               long now) {
        Set<BlockPos> changed = RealContainerCache.drainChangedPositions();
        for (BlockPos changedPos : changed) {
            if (changedPos == null) continue;
            BlockPos key = changedPos.immutable();
            if (QUEUED_DIRTY_HIGHLIGHTS.add(key)) {
                DIRTY_HIGHLIGHT_QUEUE.offer(key);
            }
        }
        if (DIRTY_HIGHLIGHT_QUEUE.isEmpty()) return;

        boolean hasManualOverrides = ManualContainerOverrideManager.hasOverrides();
        int processed = 0;
        boolean anyHighlightChanged = false;
        Set<BlockPos> processedRenderPositions = new HashSet<>();
        while (processed < MAX_DIRTY_HIGHLIGHT_UPDATES_PER_TICK && !DIRTY_HIGHLIGHT_QUEUE.isEmpty()) {
            BlockPos changedPos = DIRTY_HIGHLIGHT_QUEUE.poll();
            QUEUED_DIRTY_HIGHLIGHTS.remove(changedPos);
            if (changedPos == null) continue;
            BlockPos renderPos = getRenderPositionForChangedContainer(schematicWorld, changedPos);
            if (renderPos == null) renderPos = changedPos;
            renderPos = renderPos.immutable();
            if (!processedRenderPositions.add(renderPos)) continue;

            HighlightState previous = HIGHLIGHT_MAP.get(renderPos);
            HighlightState next = evaluateHighlightForPosition(client, schematicWorld, renderPos, renderLayerRange,
                    hasManualOverrides, now);

            boolean changedHighlight;
            if (next == null) {
                changedHighlight = HIGHLIGHT_MAP.remove(renderPos) != null;
            } else {
                changedHighlight = previous != next;
                if (changedHighlight) {
                    HIGHLIGHT_MAP.put(renderPos.immutable(), next);
                }
            }

            if (changedHighlight) {
                anyHighlightChanged = true;
            }

            processed++;
        }

        if (anyHighlightChanged) {
            highlightFingerprint = computeHighlightFingerprint(HIGHLIGHT_MAP);
            highlightVersion++;
        }
    }

    private static BlockPos getRenderPositionForChangedContainer(net.minecraft.world.level.Level schematicWorld, BlockPos pos) {
        BlockState state = schematicWorld.getBlockState(pos);
        if (state == null || state.isAir()) return pos;

        BlockPos[] halves = LitematicaContainerReader.getRenderContainerHalves(schematicWorld, pos, state);
        return halves != null ? halves[0] : pos;
    }

    private static HighlightState evaluateHighlightForPosition(Minecraft client,
                                                               net.minecraft.world.level.Level schematicWorld,
                                                               BlockPos pos,
                                                               LayerRange renderLayerRange,
                                                               boolean hasManualOverrides,
                                                               long now) {
        BlockState state = schematicWorld.getBlockState(pos);
        boolean schematicContainer = state != null && !state.isAir() && state.hasBlockEntity() &&
                ContainerBlockFilter.isAllowedForSchematicFill(state, schematicWorld, pos);
        ManualContainerOverrideState manualState = hasManualOverrides
                ? ManualContainerOverrideManager.get(pos)
                : ManualContainerOverrideState.AUTO;
        boolean manualCompleted = manualState == ManualContainerOverrideState.COMPLETED;
        boolean manualNeedsFill = manualState == ManualContainerOverrideState.NEEDS_FILL;
        boolean manualMarked = manualCompleted || manualNeedsFill;

        if (renderLayerRange != null && schematicContainer && !renderLayerRange.isPositionWithinRange(pos)) return null;
        if (!schematicContainer && !manualMarked) return null;

        if (!schematicContainer) {
            BlockState realState = client.level.getBlockState(pos);
            if (!ContainerBlockFilter.isAllowedForSchematicFill(realState, client.level, pos)) return null;
            return manualCompleted ? HighlightState.MANUAL_COMPLETED : HighlightState.MANUAL_NEEDS_FILL;
        }

        BlockPos checkPos = pos;
        BlockPos[] halves = LitematicaContainerReader.getRenderContainerHalves(schematicWorld, pos, state);
        if (halves != null) checkPos = halves[0];
        if (!checkPos.equals(pos)) return null;
        queueLargeBarrelConfirmationIfNeeded(client, schematicWorld, checkPos, state, now);

        Map<Integer, ItemStack> required = getCachedSchematicReq(checkPos, client);
        boolean isCrafter = state.getBlock() instanceof net.minecraft.world.level.block.CrafterBlock;
        boolean crafterNeedsLocking = false;

        boolean hasRequiredItems = required != null && !required.isEmpty();
        boolean hasJob = hasRequiredItems;
        boolean shouldCheckEmptySchematicContainer = Configs.HIGHLIGHT_EMPTY_SCHEMATIC_CONTAINERS.getBooleanValue();
        if (isCrafter) {
            Set<Integer> schematicLocks = LitematicaContainerReader.getDisabledSlots(checkPos);
            crafterNeedsLocking = LitematicaContainerReader.doesCrafterNeedLocking(checkPos, client);
            hasJob = hasJob || !schematicLocks.isEmpty() || crafterNeedsLocking;
        }

        if (manualCompleted || manualNeedsFill) hasJob = true;

        if (!hasJob && !shouldCheckEmptySchematicContainer) return null;

        if (isRealContainerAreaLoaded(client, checkPos, halves)) {
            if (isRealContainerMissing(client, checkPos, halves)) {
                observeRealContainerStates(client, checkPos, halves);
                clearRealContainerCache(checkPos, halves);
                return Configs.HIGHLIGHT_UNPLACED_CONTAINERS.getBooleanValue() && hasJob
                        ? HighlightState.UNPLACED
                        : null;
            }

            observeRealContainerStates(client, checkPos, halves);
            Map<Integer, ItemStack> cached = RealContainerCache.getCachedItems(checkPos);
            HighlightState type;

            if (manualCompleted) {
                type = HighlightState.MANUAL_COMPLETED;
            } else if (manualNeedsFill) {
                type = HighlightState.MANUAL_NEEDS_FILL;
            } else if (cached == null) {
                type = HighlightState.UNKNOWN;
                queueHighlightRefresh(checkPos, UNKNOWN_REQUEST_INTERVAL_MS, now);
            } else {
                Set<Integer> ignoredSlots = getCachedIgnoredSlots(checkPos, client);
                type = evaluateState(cached, required, ignoredSlots, isCrafter, crafterNeedsLocking);
                queueHighlightRefresh(checkPos, requestIntervalFor(type, state), now);
            }

            if (!hasJob && type == HighlightState.SATISFIED) return null;
            if (hasJob || type != HighlightState.UNKNOWN) {
                return type;
            }
            return null;
        }

        if (!hasJob) return null;
        if (manualCompleted) return HighlightState.MANUAL_COMPLETED;
        if (manualNeedsFill) return HighlightState.MANUAL_NEEDS_FILL;
        return HighlightState.UNKNOWN;
    }

    private static boolean isRealContainerMissing(Minecraft client, BlockPos checkPos, BlockPos[] schematicHalves) {
        if (schematicHalves == null) {
            return isRealContainerMissingAt(client, checkPos);
        }

        for (BlockPos half : schematicHalves) {
            if (isRealContainerMissingAt(client, half)) return true;
        }

        return false;
    }

    private static boolean isRealContainerAreaLoaded(Minecraft client, BlockPos checkPos, BlockPos[] schematicHalves) {
        if (schematicHalves == null) return client.level.isLoaded(checkPos);

        for (BlockPos half : schematicHalves) {
            if (!client.level.isLoaded(half)) return false;
        }

        return true;
    }

    private static boolean isRealContainerMissingAt(Minecraft client, BlockPos pos) {
        BlockState realState = client.level.getBlockState(pos);
        return realState == null || realState.isAir() || !realState.hasBlockEntity() ||
                !ContainerBlockFilter.isAllowedForSchematicFill(realState, client.level, pos);
    }

    private static void clearRealContainerCache(BlockPos checkPos, BlockPos[] schematicHalves) {
        RealContainerCache.remove(checkPos);
        if (schematicHalves == null) return;

        for (BlockPos half : schematicHalves) {
            RealContainerCache.remove(half);
        }
    }

    private static void observeRealContainerStates(Minecraft client, BlockPos checkPos, BlockPos[] schematicHalves) {
        if (schematicHalves == null) {
            RealContainerCache.observeBlockState(checkPos, client.level.getBlockState(checkPos));
            return;
        }

        for (BlockPos half : schematicHalves) {
            RealContainerCache.observeBlockState(half, client.level.getBlockState(half));
        }
    }

    private static void triggerBoost(int ticks) {
        boostedTicks = Math.max(boostedTicks, ticks);
    }

    private static boolean updateRenderLayerSignature(boolean syncLayer) {
        long signature = syncLayer ? computeRenderLayerSignature() : Long.MIN_VALUE + 1L;
        if (signature == lastRenderLayerSignature) {
            return false;
        }

        boolean changed = lastRenderLayerSignature != Long.MIN_VALUE;
        lastRenderLayerSignature = signature;
        return changed;
    }

    private static long computeRenderLayerSignature() {
        try {
            LayerRange range = fi.dy.masa.litematica.data.DataManager.getRenderLayerRange();
            if (range == null) return 0L;

            long value = 0x6a09e667f3bcc909L;
            value = mix64(value ^ range.getLayerMode().ordinal());
            value = mix64(value ^ ((long) range.getAxis().ordinal() << 8));
            value = mix64(value ^ ((long) range.getLayerSingle() << 16));
            value = mix64(value ^ ((long) range.getLayerAbove() << 24));
            value = mix64(value ^ ((long) range.getLayerBelow() << 32));
            value = mix64(value ^ ((long) range.getLayerRangeMin() << 40));
            value = mix64(value ^ ((long) range.getLayerRangeMax() << 48));
            value = mix64(value ^ (range.getMoveLayerRangeMin() ? 0x100000001b3L : 0L));
            value = mix64(value ^ (range.getMoveLayerRangeMax() ? 0x9e3779b97f4a7c15L : 0L));
            return value;
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    private static long mix64(long value) {
        value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
        value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
        return value ^ (value >>> 31);
    }

    private static synchronized void startIndexingIfIdle(long now) {
        if (isIndexing || now - lastIndexTime <= 5000) return;

        isIndexing = true;
        CompletableFuture.runAsync(() -> {
            try {
                Set<BlockPos> found = LitematicaPlacementContainerData.rebuildIndex();
                if (!found.equals(SCHEMATIC_CONTAINERS)) {
                    SCHEMATIC_REQ_CACHE.clear();
                    SCHEMATIC_IGNORED_SLOT_CACHE.clear();
                }
                SCHEMATIC_CONTAINERS = found;
                SCHEMATIC_CONTAINER_BUCKETS = buildContainerBuckets(found);
            } catch (Exception e) {} finally {
                lastIndexTime = System.currentTimeMillis();
                invalidateNearbyBucketCache();
                isIndexing = false;
            }
        }, INDEX_EXECUTOR);
    }

    private static void clearHighlights() {
        if (HIGHLIGHT_MAP.isEmpty()) return;

        HIGHLIGHT_MAP.clear();
        highlightFingerprint = HighlightFingerprint.empty();
        highlightVersion++;
    }

    private static void replaceHighlightsIfChanged(HighlightBuild nextHighlights) {
        HighlightFingerprint nextFingerprint = nextHighlights.fingerprint();
        if (highlightFingerprint.equals(nextFingerprint)) {
            return;
        }

        HIGHLIGHT_MAP.clear();
        HIGHLIGHT_MAP.putAll(nextHighlights.states);
        highlightFingerprint = nextFingerprint;
        highlightVersion++;
    }

    private static HighlightFingerprint computeHighlightFingerprint(Map<BlockPos, HighlightState> highlights) {
        HighlightBuild build = new HighlightBuild();
        for (Map.Entry<BlockPos, HighlightState> entry : highlights.entrySet()) {
            build.put(entry.getKey(), entry.getValue());
        }
        return build.fingerprint();
    }

    private static long requestIntervalFor(HighlightState type, BlockState state) {
        if (state != null && state.is(net.minecraft.world.level.block.Blocks.BARREL)) {
            return type == HighlightState.SATISFIED ? BARREL_SATISFIED_REQUEST_INTERVAL_MS : BARREL_ACTIVE_REQUEST_INTERVAL_MS;
        }
        if (type == HighlightState.SATISFIED) return SATISFIED_REQUEST_INTERVAL_MS;
        return ACTIVE_REQUEST_INTERVAL_MS;
    }

    private static void queueLargeBarrelConfirmationIfNeeded(Minecraft client,
                                                             net.minecraft.world.level.Level schematicWorld,
                                                             BlockPos pos,
                                                             BlockState state,
                                                             long now) {
        BlockPos[] schematicPair = LitematicaContainerReader.getLargeBarrelConfirmationPair(schematicWorld, pos, state);
        if (schematicPair == null) return;

        BlockState realState = client.level.getBlockState(pos);
        BlockPos[] realPair = LitematicaContainerReader.getLargeBarrelConfirmationPair(client.level, pos, realState);
        BlockPos[] requestPair = realPair != null ? realPair : schematicPair;
        for (BlockPos half : requestPair) {
            queueHighlightRefresh(half, LARGE_BARREL_CONFIRM_REQUEST_INTERVAL_MS, now);
        }
    }

    private static void queueHighlightRefresh(BlockPos pos, long minIntervalMs, long now) {
        BlockPos key = pos.immutable();
        if (now - HIGHLIGHT_REQUEST_TIME.getOrDefault(key, 0L) < minIntervalMs) return;

        HIGHLIGHT_REQUEST_INTERVALS.put(key, minIntervalMs);
        if (QUEUED_DATA_REQUESTS.add(key)) {
            if (minIntervalMs <= UNKNOWN_REQUEST_INTERVAL_MS || minIntervalMs == LARGE_BARREL_CONFIRM_REQUEST_INTERVAL_MS) {
                DATA_REQUEST_QUEUE.offerFirst(key);
            } else {
                DATA_REQUEST_QUEUE.offerLast(key);
            }
        }
    }

    private static void pumpDataRequests(long now) {
        int sent = 0;

        while (sent < MAX_DATA_REQUESTS_PER_TICK && !DATA_REQUEST_QUEUE.isEmpty()) {
            BlockPos key = DATA_REQUEST_QUEUE.poll();
            QUEUED_DATA_REQUESTS.remove(key);

            long minIntervalMs = HIGHLIGHT_REQUEST_INTERVALS.getOrDefault(key, ACTIVE_REQUEST_INTERVAL_MS);
            if (now - HIGHLIGHT_REQUEST_TIME.getOrDefault(key, 0L) < minIntervalMs) {
                continue;
            }

            HIGHLIGHT_REQUEST_TIME.put(key, now);
            RealContainerCache.requestContainerData(key, minIntervalMs, true);
            sent++;
        }
    }

    private static Iterable<BlockPos> getNearbySchematicContainers(BlockPos center, int radius) {
        Map<Long, Set<BlockPos>> buckets = SCHEMATIC_CONTAINER_BUCKETS;
        if (radius <= 0 || buckets.isEmpty()) {
            Collection<BlockPos> indexed = SCHEMATIC_CONTAINERS;
            return indexed;
        }

        List<Set<BlockPos>> bucketSets = getNearbyBucketSets(buckets, center, radius);
        return () -> new Iterator<>() {
            private int bucketIndex = 0;
            private Iterator<BlockPos> current = Collections.emptyIterator();

            @Override
            public boolean hasNext() {
                advance();
                return current.hasNext();
            }

            @Override
            public BlockPos next() {
                advance();
                if (!current.hasNext()) {
                    throw new NoSuchElementException();
                }
                return current.next();
            }

            private void advance() {
                while (!current.hasNext() && bucketIndex < bucketSets.size()) {
                    current = bucketSets.get(bucketIndex++).iterator();
                }
            }
        };
    }

    private static List<Set<BlockPos>> getNearbyBucketSets(Map<Long, Set<BlockPos>> buckets, BlockPos center, int radius) {
        int minX = (center.getX() - radius) >> 4;
        int maxX = (center.getX() + radius) >> 4;
        int minY = (center.getY() - radius) >> 4;
        int maxY = (center.getY() + radius) >> 4;
        int minZ = (center.getZ() - radius) >> 4;
        int maxZ = (center.getZ() + radius) >> 4;

        if (buckets == cachedNearbyBucketSource &&
                minX == cachedNearbyMinX && maxX == cachedNearbyMaxX &&
                minY == cachedNearbyMinY && maxY == cachedNearbyMaxY &&
                minZ == cachedNearbyMinZ && maxZ == cachedNearbyMaxZ) {
            return cachedNearbyBucketSets;
        }

        List<Set<BlockPos>> bucketSets = new ArrayList<>();
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    Set<BlockPos> bucket = buckets.get(packBucketKey(x, y, z));
                    if (bucket == null || bucket.isEmpty()) continue;
                    bucketSets.add(bucket);
                }
            }
        }

        cachedNearbyBucketSource = buckets;
        cachedNearbyBucketSets = bucketSets;
        cachedNearbyMinX = minX;
        cachedNearbyMaxX = maxX;
        cachedNearbyMinY = minY;
        cachedNearbyMaxY = maxY;
        cachedNearbyMinZ = minZ;
        cachedNearbyMaxZ = maxZ;
        return bucketSets;
    }

    private static void invalidateNearbyBucketCache() {
        cachedNearbyBucketSource = Collections.emptyMap();
        cachedNearbyBucketSets = Collections.emptyList();
        cachedNearbyMinX = Integer.MIN_VALUE;
        cachedNearbyMaxX = Integer.MIN_VALUE;
        cachedNearbyMinY = Integer.MIN_VALUE;
        cachedNearbyMaxY = Integer.MIN_VALUE;
        cachedNearbyMinZ = Integer.MIN_VALUE;
        cachedNearbyMaxZ = Integer.MIN_VALUE;
    }

    private static Iterable<BlockPos> getNearbyHighlightCandidates(BlockPos center, int radius) {
        Iterable<BlockPos> schematicCandidates = getNearbySchematicContainers(center, radius);
        double radiusSq = radius * radius;
        return () -> new Iterator<>() {
            private final Iterator<BlockPos> schematicIterator = schematicCandidates.iterator();
            private final Iterator<BlockPos> manualIterator = ManualContainerOverrideManager.iterateCurrentContextPositions().iterator();
            private BlockPos nextManual;
            private boolean schematicDone;
            private boolean manualPrepared;

            @Override
            public boolean hasNext() {
                if (!schematicDone && schematicIterator.hasNext()) {
                    return true;
                }
                schematicDone = true;
                prepareManual();
                return nextManual != null;
            }

            @Override
            public BlockPos next() {
                if (!schematicDone && schematicIterator.hasNext()) {
                    return schematicIterator.next();
                }
                schematicDone = true;
                prepareManual();
                if (nextManual == null) {
                    throw new NoSuchElementException();
                }
                BlockPos result = nextManual;
                nextManual = null;
                manualPrepared = false;
                return result;
            }

            private void prepareManual() {
                if (manualPrepared) return;
                manualPrepared = true;

                while (manualIterator.hasNext()) {
                    BlockPos pos = manualIterator.next();
                    if (radius > 0 && pos.distSqr(center) > radiusSq) continue;
                    if (SCHEMATIC_CONTAINERS.contains(pos)) continue;
                    nextManual = pos.immutable();
                    return;
                }

                nextManual = null;
            }
        };
    }

    private static Map<Long, Set<BlockPos>> buildContainerBuckets(Set<BlockPos> containers) {
        Map<Long, Set<BlockPos>> buckets = new HashMap<>();

        for (BlockPos pos : containers) {
            buckets.computeIfAbsent(packBucketKey(pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4), ignored -> new HashSet<>()).add(pos);
        }

        return buckets;
    }

    private static HighlightState evaluateState(Map<Integer, ItemStack> realItems, Map<Integer, ItemStack> required, Set<Integer> ignoredSlots, boolean isCrafter, boolean crafterNeedsLocking) {
        if (realItems == null) return HighlightState.UNKNOWN;

        int maxSlot = isCrafter ? 9 : 54;
        boolean hasAnyReal = false;
        boolean hasWrong = false;
        boolean hasExtra = false;
        boolean hasPartial = false;

        for (int i = 0; i < maxSlot; i++) {
            if (ignoredSlots != null && ignoredSlots.contains(i)) continue;

            ItemStack real = realItems.getOrDefault(i, ItemStack.EMPTY);
            ItemStack req = (required != null) ? required.getOrDefault(i, ItemStack.EMPTY) : ItemStack.EMPTY;

            if (!real.isEmpty()) hasAnyReal = true;

            if (req.isEmpty() && !real.isEmpty()) hasExtra = true;
            else if (!req.isEmpty() && real.isEmpty()) hasPartial = true;
            else if (!req.isEmpty() && !real.isEmpty()) {
                if (!ItemMatcher.isSameItem(req, real)) hasWrong = true;
                else {
                    if (real.getCount() > req.getCount()) hasExtra = true;
                    else if (real.getCount() < req.getCount()) hasPartial = true;
                }
            }

            if (hasWrong) return HighlightState.WRONG_ITEM;
        }

        if (crafterNeedsLocking) hasPartial = true;

        if (hasPartial) return hasAnyReal ? HighlightState.PARTIAL : HighlightState.UNFILLED;
        if (hasExtra) return HighlightState.OVERFILLED;

        return HighlightState.SATISFIED;
    }

    private static long highlightEntryHash(BlockPos pos, HighlightState state) {
        long value = pos.asLong();
        value ^= ((long) state.ordinal() + 0x9e3779b97f4a7c15L) * 0xbf58476d1ce4e5b9L;
        return mix64(value);
    }

    private static final class HighlightBuild {
        private final Map<BlockPos, HighlightState> states = new HashMap<>();
        private int count = 0;
        private long sum = 0L;
        private long xor = 0L;

        private void put(BlockPos pos, HighlightState state) {
            if (pos == null || state == null) return;
            BlockPos key = pos.immutable();
            HighlightState previous = states.put(key, state);
            if (previous != null) {
                remove(key, previous);
            }
            add(key, state);
        }

        private void add(BlockPos pos, HighlightState state) {
            long hash = highlightEntryHash(pos, state);
            count++;
            sum += hash;
            xor ^= Long.rotateLeft(hash, (int) (hash & 63L));
        }

        private void remove(BlockPos pos, HighlightState state) {
            long hash = highlightEntryHash(pos, state);
            count--;
            sum -= hash;
            xor ^= Long.rotateLeft(hash, (int) (hash & 63L));
        }

        private HighlightFingerprint fingerprint() {
            return new HighlightFingerprint(count, sum, xor);
        }
    }

    private record HighlightFingerprint(int count, long sum, long xor) {
        static HighlightFingerprint empty() {
            return new HighlightFingerprint(0, 0L, 0L);
        }
    }

    private static long packBucketKey(int x, int y, int z) {
        return (((long) x & 0x3FFFFFL) << 42)
                | (((long) z & 0x3FFFFFL) << 20)
                | ((long) y & 0xFFFFFL);
    }
}

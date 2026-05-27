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
import com.mimicenzymes.litematicafiller.core.RealContainerCache;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
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
    private static volatile int highlightVersion = 0;
    private static final ExecutorService INDEX_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "LitematicaFiller-HighlightScanner");
        thread.setDaemon(true);
        return thread;
    });
    private static volatile Set<BlockPos> SCHEMATIC_CONTAINERS = Collections.emptySet();
    private static volatile Map<BucketKey, Set<BlockPos>> SCHEMATIC_CONTAINER_BUCKETS = Collections.emptyMap();
    private static volatile long lastIndexTime = 0;
    private static volatile boolean isIndexing = false;
    private static long lastRenderLayerSignature = Long.MIN_VALUE;
    private static boolean pendingRenderLayerRefresh = false;
    private static long lastRenderLayerRefreshTick = Long.MIN_VALUE;

    private static int tickCounter = 0;
    private static int boostedTicks = 0;

    public static Map<BlockPos, HighlightState> getHighlights() {
        return HIGHLIGHT_MAP;
    }

    public static int getHighlightVersion() {
        return highlightVersion;
    }

    public static void onManualOverrideChanged(BlockPos pos, ManualContainerOverrideState state) {
        if (pos == null) return;

        if (state == ManualContainerOverrideState.AUTO) {
            BlockPos key = pos.toImmutable();
            RealContainerCache.remove(key);
            HIGHLIGHT_REQUEST_TIME.remove(key);
            HIGHLIGHT_REQUEST_INTERVALS.remove(key);
            DATA_REQUEST_QUEUE.remove(key);
            QUEUED_DATA_REQUESTS.remove(key);
            if (HIGHLIGHT_MAP.remove(key) != null) {
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
        if (changed) highlightVersion++;
        triggerBoost(BOOST_DURATION_TICKS);
    }

    public record ContainerSnapshot(List<BlockPos> positions, int nextCursor, int totalCount) {
    }

    public static ContainerSnapshot getNearbySchematicContainersSnapshot(BlockPos center, int radius, int cursor, int limit) {
        ensureSchematicContainerIndex();
        if (limit <= 0) return new ContainerSnapshot(Collections.emptyList(), 0, 0);

        Map<BucketKey, Set<BlockPos>> buckets = SCHEMATIC_CONTAINER_BUCKETS;
        if (radius <= 0 || buckets.isEmpty()) {
            Collection<BlockPos> indexed = SCHEMATIC_CONTAINERS;
            return collectSnapshot(indexed, cursor, limit);
        }

        int minX = (center.getX() - radius) >> 4;
        int maxX = (center.getX() + radius) >> 4;
        int minY = (center.getY() - radius) >> 4;
        int maxY = (center.getY() + radius) >> 4;
        int minZ = (center.getZ() - radius) >> 4;
        int maxZ = (center.getZ() + radius) >> 4;
        List<Set<BlockPos>> bucketSets = new ArrayList<>();
        int totalCount = 0;

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    Set<BlockPos> bucket = buckets.get(new BucketKey(x, y, z));
                    if (bucket == null || bucket.isEmpty()) continue;
                    bucketSets.add(bucket);
                    totalCount += bucket.size();
                }
            }
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
        LitematicaPlacementContainerData.clear();
        ManualContainerOverrideManager.clearForCurrentContext();
        SCHEMATIC_CONTAINERS = Collections.emptySet();
        SCHEMATIC_CONTAINER_BUCKETS = Collections.emptyMap();
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
        LitematicaPlacementContainerData.clear();
        ManualContainerOverrideManager.clearForCurrentContext();
        triggerBoost(BOOST_DURATION_TICKS);
        SCHEMATIC_CONTAINER_BUCKETS = Collections.emptyMap();
        lastRenderLayerSignature = Long.MIN_VALUE;
        pendingRenderLayerRefresh = false;
        lastRenderLayerRefreshTick = Long.MIN_VALUE;
    }

    private static Map<Integer, ItemStack> getCachedSchematicReq(BlockPos pos, MinecraftClient client) {
        Map<Integer, ItemStack> req = SCHEMATIC_REQ_CACHE.get(pos);
        if (req == null) {
            req = LitematicaContainerReader.getRequiredItems(pos, client.world.getRegistryManager());
            if (req != null) {
                SCHEMATIC_REQ_CACHE.put(pos, req);
            }
        }
        return req;
    }

    public static Map<Integer, ItemStack> getCachedSchematicRequirement(BlockPos pos, MinecraftClient client) {
        return getCachedSchematicReq(pos, client);
    }

    private static Set<Integer> getCachedIgnoredSlots(BlockPos pos, MinecraftClient client) {
        return SCHEMATIC_IGNORED_SLOT_CACHE.computeIfAbsent(pos.toImmutable(),
                ignored -> LitematicaContainerReader.getIgnoredSlots(pos, client.world.getRegistryManager()));
    }

    public static void tick(MinecraftClient client) {
        tickCounter++;
        if (!Configs.ENABLE_MOD.getBooleanValue() || !Configs.HIGHLIGHT_CONTAINERS.getBooleanValue()) {
            clearHighlights();
            DATA_REQUEST_QUEUE.clear();
            QUEUED_DATA_REQUESTS.clear();
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

        BlockPos currentCenter = client.player.getBlockPos();

        long now = System.currentTimeMillis();
        boolean modOperating = AutoFillerStateMachine.getInstance().isWorking() || ContainerToolStateMachine.getInstance().isWorking();
        boolean userHandledScreenOpen = client.currentScreen instanceof HandledScreen<?> && !modOperating;
        if (userHandledScreenOpen) {
            return;
        }

        pumpDataRequests(now);

        startIndexingIfIdle(now);

        boolean syncLayer = Configs.SYNC_LITE_LAYER.getBooleanValue();
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

        boolean hideCompleted = Configs.HIDE_COMPLETED_CONTAINERS.getBooleanValue();

        int currentRadius = Configs.RENDER_RADIUS.getIntegerValue();
        double radiusSq = currentRadius * currentRadius;

        Map<BlockPos, HighlightState> nextMap = new HashMap<>();

        for (BlockPos pos : getNearbyHighlightCandidates(currentCenter, currentRadius)) {
            if (currentRadius > 0 && pos.getSquaredDistance(currentCenter) > radiusSq) continue;

            BlockState state = schematicWorld.getBlockState(pos);
            boolean schematicContainer = state != null && !state.isAir() && state.hasBlockEntity() &&
                    ContainerBlockFilter.isAllowedForSchematicFill(state, schematicWorld, pos);
            boolean manualMarked = ManualContainerOverrideManager.isCompleted(pos) || ManualContainerOverrideManager.isNeedsFill(pos);

            if (syncLayer && schematicContainer && !fi.dy.masa.litematica.data.DataManager.getRenderLayerRange().isPositionWithinRange(pos)) continue;
            if (!schematicContainer && !manualMarked) continue;

            if (!schematicContainer) {
                BlockState realState = client.world.getBlockState(pos);
                if (!ContainerBlockFilter.isAllowedForSchematicFill(realState, client.world, pos)) continue;
                nextMap.put(pos.toImmutable(), ManualContainerOverrideManager.isCompleted(pos)
                        ? HighlightState.MANUAL_COMPLETED
                        : HighlightState.MANUAL_NEEDS_FILL);
                continue;
            }

            BlockPos checkPos = pos;
            BlockPos[] halves = LitematicaContainerReader.getRenderContainerHalves(schematicWorld, pos, state);
            if (halves != null) checkPos = halves[0];
            if (!checkPos.equals(pos)) continue;
            boolean manualCompleted = ManualContainerOverrideManager.isCompleted(checkPos);

            Map<Integer, ItemStack> required = getCachedSchematicReq(checkPos, client);
            Set<Integer> ignoredSlots = getCachedIgnoredSlots(checkPos, client);
            boolean isCrafter = state.getBlock() instanceof net.minecraft.block.CrafterBlock;
            boolean manualNeedsFill = ManualContainerOverrideManager.isNeedsFill(checkPos);

            boolean hasRequiredItems = required != null && !required.isEmpty();
            boolean hasJob = hasRequiredItems;
            boolean shouldCheckEmptySchematicContainer = Configs.HIGHLIGHT_EMPTY_SCHEMATIC_CONTAINERS.getBooleanValue();
            if (isCrafter) {
                Set<Integer> schematicLocks = LitematicaContainerReader.getDisabledSlots(checkPos);
                hasJob = hasJob || !schematicLocks.isEmpty() || LitematicaContainerReader.doesCrafterNeedLocking(checkPos, client);
            }

            if (manualCompleted || manualNeedsFill) hasJob = true;

            if (!hasJob && !shouldCheckEmptySchematicContainer) continue;

            if (isRealContainerAreaLoaded(client, checkPos, halves)) {
                if (isRealContainerMissing(client, checkPos, halves)) {
                    observeRealContainerStates(client, checkPos, halves);
                    clearRealContainerCache(checkPos, halves);
                    if (Configs.HIGHLIGHT_UNPLACED_CONTAINERS.getBooleanValue() && hasJob) {
                        nextMap.put(pos.toImmutable(), HighlightState.UNPLACED);
                    }
                    continue;
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
                    type = evaluateState(cached, required, ignoredSlots, isCrafter, checkPos, client);
                    queueHighlightRefresh(checkPos, requestIntervalFor(type), now);
                }

                if (!hasJob && type == HighlightState.SATISFIED) {
                    continue;
                }

                if (hasJob || type != HighlightState.UNKNOWN) {
                    if (hideCompleted && type == HighlightState.SATISFIED) continue;
                    nextMap.put(pos.toImmutable(), type);
                }
            } else {
                if (hasJob) {
                    if (manualCompleted) {
                        nextMap.put(pos.toImmutable(), HighlightState.MANUAL_COMPLETED);
                    } else if (manualNeedsFill || !hideCompleted) {
                        nextMap.put(pos.toImmutable(), manualNeedsFill ? HighlightState.MANUAL_NEEDS_FILL : HighlightState.UNKNOWN);
                    }
                }
            }
        }

        replaceHighlightsIfChanged(nextMap);
        if (boostedTicks > 0) {
            boostedTicks--;
        }
    }

    private static boolean isRealContainerMissing(MinecraftClient client, BlockPos checkPos, BlockPos[] schematicHalves) {
        if (schematicHalves == null) {
            return isRealContainerMissingAt(client, checkPos);
        }

        for (BlockPos half : schematicHalves) {
            if (isRealContainerMissingAt(client, half)) return true;
        }

        return false;
    }

    private static boolean isRealContainerAreaLoaded(MinecraftClient client, BlockPos checkPos, BlockPos[] schematicHalves) {
        if (schematicHalves == null) return client.world.isChunkLoaded(checkPos);

        for (BlockPos half : schematicHalves) {
            if (!client.world.isChunkLoaded(half)) return false;
        }

        return true;
    }

    private static boolean isRealContainerMissingAt(MinecraftClient client, BlockPos pos) {
        BlockState realState = client.world.getBlockState(pos);
        return realState == null || realState.isAir() || !realState.hasBlockEntity() ||
                !ContainerBlockFilter.isAllowedForSchematicFill(realState, client.world, pos);
    }

    private static void clearRealContainerCache(BlockPos checkPos, BlockPos[] schematicHalves) {
        RealContainerCache.remove(checkPos);
        if (schematicHalves == null) return;

        for (BlockPos half : schematicHalves) {
            RealContainerCache.remove(half);
        }
    }

    private static void observeRealContainerStates(MinecraftClient client, BlockPos checkPos, BlockPos[] schematicHalves) {
        if (schematicHalves == null) {
            RealContainerCache.observeBlockState(checkPos, client.world.getBlockState(checkPos));
            return;
        }

        for (BlockPos half : schematicHalves) {
            RealContainerCache.observeBlockState(half, client.world.getBlockState(half));
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
                isIndexing = false;
            }
        }, INDEX_EXECUTOR);
    }

    private static void clearHighlights() {
        if (HIGHLIGHT_MAP.isEmpty()) return;

        HIGHLIGHT_MAP.clear();
        highlightVersion++;
    }

    private static void replaceHighlightsIfChanged(Map<BlockPos, HighlightState> nextMap) {
        if (HIGHLIGHT_MAP.equals(nextMap)) return;

        HIGHLIGHT_MAP.clear();
        HIGHLIGHT_MAP.putAll(nextMap);
        highlightVersion++;
    }

    private static long requestIntervalFor(HighlightState type) {
        return type == HighlightState.SATISFIED ? SATISFIED_REQUEST_INTERVAL_MS : ACTIVE_REQUEST_INTERVAL_MS;
    }

    private static void queueHighlightRefresh(BlockPos pos, long minIntervalMs, long now) {
        BlockPos key = pos.toImmutable();
        if (now - HIGHLIGHT_REQUEST_TIME.getOrDefault(key, 0L) < minIntervalMs) return;

        HIGHLIGHT_REQUEST_INTERVALS.put(key, minIntervalMs);
        if (QUEUED_DATA_REQUESTS.add(key)) {
            if (minIntervalMs <= UNKNOWN_REQUEST_INTERVAL_MS) {
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
        Map<BucketKey, Set<BlockPos>> buckets = SCHEMATIC_CONTAINER_BUCKETS;
        if (radius <= 0 || buckets.isEmpty()) {
            Collection<BlockPos> indexed = SCHEMATIC_CONTAINERS;
            return indexed;
        }

        int minX = (center.getX() - radius) >> 4;
        int maxX = (center.getX() + radius) >> 4;
        int minY = (center.getY() - radius) >> 4;
        int maxY = (center.getY() + radius) >> 4;
        int minZ = (center.getZ() - radius) >> 4;
        int maxZ = (center.getZ() + radius) >> 4;
        return () -> new Iterator<>() {
            private int x = minX;
            private int y = minY;
            private int z = minZ;
            private Iterator<BlockPos> current = Collections.emptyIterator();
            private boolean finished = false;

            @Override
            public boolean hasNext() {
                advance();
                return !finished && current.hasNext();
            }

            @Override
            public BlockPos next() {
                advance();
                if (finished || !current.hasNext()) {
                    throw new NoSuchElementException();
                }
                return current.next();
            }

            private void advance() {
                while (!finished && !current.hasNext()) {
                    if (x > maxX) {
                        finished = true;
                        return;
                    }

                    Set<BlockPos> bucket = buckets.get(new BucketKey(x, y, z));
                    current = bucket == null ? Collections.emptyIterator() : bucket.iterator();
                    z++;
                    if (z > maxZ) {
                        z = minZ;
                        y++;
                        if (y > maxY) {
                            y = minY;
                            x++;
                        }
                    }
                }
            }
        };
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
                    if (radius > 0 && pos.getSquaredDistance(center) > radiusSq) continue;
                    if (SCHEMATIC_CONTAINERS.contains(pos)) continue;
                    nextManual = pos.toImmutable();
                    return;
                }

                nextManual = null;
            }
        };
    }

    private static Map<BucketKey, Set<BlockPos>> buildContainerBuckets(Set<BlockPos> containers) {
        Map<BucketKey, Set<BlockPos>> buckets = new HashMap<>();

        for (BlockPos pos : containers) {
            buckets.computeIfAbsent(BucketKey.from(pos), ignored -> new HashSet<>()).add(pos);
        }

        return buckets;
    }

    private static HighlightState evaluateState(Map<Integer, ItemStack> realItems, Map<Integer, ItemStack> required, Set<Integer> ignoredSlots, boolean isCrafter, BlockPos pos, MinecraftClient client) {
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
        }

        if (isCrafter && LitematicaContainerReader.doesCrafterNeedLocking(pos, client)) hasPartial = true;

        if (hasWrong) return HighlightState.WRONG_ITEM;
        if (hasPartial) return hasAnyReal ? HighlightState.PARTIAL : HighlightState.UNFILLED;
        if (hasExtra) return HighlightState.OVERFILLED;

        return HighlightState.SATISFIED;
    }

    private record BucketKey(int x, int y, int z) {
        static BucketKey from(BlockPos pos) {
            return new BucketKey(pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4);
        }
    }

}

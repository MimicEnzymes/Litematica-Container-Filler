package com.mimicenzymes.litematicafiller.render;

import com.mimicenzymes.litematicafiller.config.Configs;
import com.mimicenzymes.litematicafiller.core.AutoFillerStateMachine;
import com.mimicenzymes.litematicafiller.filter.ContainerBlockFilter;
import com.mimicenzymes.litematicafiller.tool.ContainerToolStateMachine;
import com.mimicenzymes.litematicafiller.core.ItemMatcher;
import com.mimicenzymes.litematicafiller.core.LitematicaContainerReader;
import com.mimicenzymes.litematicafiller.core.LitematicaPlacementContainerData;
import com.mimicenzymes.litematicafiller.core.RealContainerCache;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.util.math.BlockPos;

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

    private static int tickCounter = 0;
    private static int boostedTicks = 0;

    public static Map<BlockPos, HighlightState> getHighlights() {
        return HIGHLIGHT_MAP;
    }

    public static int getHighlightVersion() {
        return highlightVersion;
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
        SCHEMATIC_CONTAINERS = Collections.emptySet();
        SCHEMATIC_CONTAINER_BUCKETS = Collections.emptyMap();
        lastIndexTime = 0;
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
        triggerBoost(BOOST_DURATION_TICKS);
        SCHEMATIC_CONTAINER_BUCKETS = Collections.emptyMap();
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

        boolean fillWorkEnabled = Configs.WORKING_STATE.getBooleanValue();
        int updateInterval = boostedTicks > 0
                ? BOOSTED_UPDATE_INTERVAL_TICKS
                : (modOperating || fillWorkEnabled ? NORMAL_UPDATE_INTERVAL_TICKS : IDLE_UPDATE_INTERVAL_TICKS);
        if (tickCounter % updateInterval != 0) {
            if (boostedTicks > 0) boostedTicks--;
            return;
        }

        boolean hideCompleted = Configs.HIDE_COMPLETED_CONTAINERS.getBooleanValue();
        boolean syncLayer = Configs.SYNC_LITE_LAYER.getBooleanValue();

        int currentRadius = Configs.RENDER_RADIUS.getIntegerValue();
        double radiusSq = currentRadius * currentRadius;

        Map<BlockPos, HighlightState> nextMap = new HashMap<>();

        for (BlockPos pos : getNearbySchematicContainers(currentCenter, currentRadius)) {
            if (currentRadius > 0 && pos.getSquaredDistance(currentCenter) > radiusSq) continue;

            if (syncLayer && !fi.dy.masa.litematica.data.DataManager.getRenderLayerRange().isPositionWithinRange(pos)) continue;

            BlockState state = schematicWorld.getBlockState(pos);
            if (state == null || state.isAir() || !state.hasBlockEntity()) continue;
            if (!ContainerBlockFilter.isAllowedForSchematicFill(state, schematicWorld, pos)) continue;

            BlockPos checkPos = pos;
            BlockPos[] halves = LitematicaContainerReader.getRenderContainerHalves(schematicWorld, pos, state);
            if (halves != null) checkPos = halves[0];
            if (!checkPos.equals(pos)) continue;

            Map<Integer, ItemStack> required = getCachedSchematicReq(checkPos, client);
            Set<Integer> ignoredSlots = getCachedIgnoredSlots(checkPos, client);
            boolean isCrafter = state.getBlock() instanceof net.minecraft.block.CrafterBlock;

            boolean hasRequiredItems = required != null && !required.isEmpty();
            boolean hasJob = hasRequiredItems;
            boolean shouldCheckEmptySchematicContainer = Configs.HIGHLIGHT_EMPTY_SCHEMATIC_CONTAINERS.getBooleanValue();
            if (isCrafter) {
                Set<Integer> schematicLocks = LitematicaContainerReader.getDisabledSlots(checkPos);
                hasJob = hasJob || !schematicLocks.isEmpty() || LitematicaContainerReader.doesCrafterNeedLocking(checkPos, client);
            }

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

                if (cached == null) {
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
                if (hasJob && !hideCompleted) {
                    nextMap.put(pos.toImmutable(), HighlightState.UNKNOWN);
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

    private static synchronized void startIndexingIfIdle(long now) {
        if (isIndexing || now - lastIndexTime <= 5000) return;

        isIndexing = true;
        CompletableFuture.runAsync(() -> {
            try {
                Set<BlockPos> found = LitematicaPlacementContainerData.rebuildIndex();
                if (found.isEmpty()) {
                    found = extractAllContainersFromSchematic();
                }
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

    private static Set<BlockPos> extractAllContainersFromSchematic() {
        Set<BlockPos> newSet = new HashSet<>();
        try {
            Object manager = fi.dy.masa.litematica.data.DataManager.getSchematicPlacementManager();
            Collection<?> all = (Collection<?>) manager.getClass().getMethod("getAllSchematicsPlacements").invoke(manager);
            if (all != null) {
                var schematicWorld = fi.dy.masa.litematica.world.SchematicWorldHandler.getSchematicWorld();

                for (Object p : all) {
                    Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
                    boolean enabled = true;
                    try { enabled = (boolean) p.getClass().getMethod("isEnabled").invoke(p); } catch (Exception e) {}
                    if (!enabled) continue;

                    BlockPos origin = null;
                    try {
                        for (java.lang.reflect.Method m : p.getClass().getMethods()) {
                            if (m.getParameterCount() == 0 && m.getReturnType() == BlockPos.class) {
                                String name = m.getName().toLowerCase();
                                if (name.contains("origin") || name.contains("pos")) {
                                    origin = (BlockPos) m.invoke(p);
                                    break;
                                }
                            }
                        }
                    } catch (Exception e) {}
                    if (origin == null) origin = BlockPos.ORIGIN;

                    List<NbtCompound> nbts = new ArrayList<>();
                    extractNbts(p, nbts, visited, 0);

                    for (NbtCompound nbt : nbts) {
                        if (nbt.contains("x") && nbt.contains("y") && nbt.contains("z")) {
                            int nx = getInt(nbt.get("x"));
                            int ny = getInt(nbt.get("y"));
                            int nz = getInt(nbt.get("z"));

                            BlockPos directPos = new BlockPos(nx, ny, nz);
                            BlockPos offsetPos = origin.add(nx, ny, nz);

                            BlockPos worldPos = null;

                            if (schematicWorld != null) {
                                if (schematicWorld.getBlockState(offsetPos).hasBlockEntity()) {
                                    worldPos = offsetPos;
                                } else if (schematicWorld.getBlockState(directPos).hasBlockEntity()) {
                                    worldPos = directPos;
                                }
                            }

                            if (worldPos == null) {
                                double distDirect = directPos.getSquaredDistance(origin);
                                double distOffset = offsetPos.getSquaredDistance(origin);
                                worldPos = (distDirect < distOffset) ? directPos : offsetPos;
                            }

                            newSet.add(worldPos);
                        }
                    }
                }
            }
        } catch (Exception e) {}
        return newSet;
    }

    private static void extractNbts(Object obj, List<NbtCompound> results, Set<Object> visited, int depth) {
        if (obj == null || depth > 100 || !visited.add(obj)) return;

        if (obj instanceof NbtCompound c) {
            if (c.contains("x") && c.contains("y") && c.contains("z") && (c.contains("Items") || c.contains("id"))) {
                results.add(c);
            }
            for (String key : c.getKeys()) {
                NbtElement el = c.get(key);
                if (el instanceof NbtCompound child) extractNbts(child, results, visited, depth + 1);
                else if (el instanceof NbtList list) {
                    for (int i = 0; i < list.size(); i++) extractNbts(list.get(i), results, visited, depth + 1);
                }
            }
            return;
        }

        if (obj instanceof net.minecraft.block.entity.BlockEntity be) {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.world != null) {
                try {
                    NbtCompound c = be.createNbt(client.world.getRegistryManager());
                    if (c != null && c.contains("x")) results.add(c);
                } catch (Exception ignored) {}
            }
            return;
        }

        if (obj instanceof Map<?, ?> map) {
            for (Object val : map.values()) extractNbts(val, results, visited, depth + 1);
            return;
        }
        if (obj instanceof Iterable<?> iter) {
            for (Object val : iter) extractNbts(val, results, visited, depth + 1);
            return;
        }
        if (obj.getClass().isArray() && !obj.getClass().getComponentType().isPrimitive()) {
            for (Object val : (Object[]) obj) extractNbts(val, results, visited, depth + 1);
            return;
        }

        String pkg = obj.getClass().getPackage() != null ? obj.getClass().getPackage().getName() : "";
        if (!pkg.startsWith("fi.dy.masa")) return;

        Class<?> clazz = obj.getClass();
        while (clazz != null && clazz != Object.class) {
            for (java.lang.reflect.Field f : clazz.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) continue;
                String fname = f.getName().toLowerCase();
                if (fname.contains("parent") || fname.contains("screen") || fname.contains("gui") || fname.contains("client") || fname.contains("world") || fname.contains("manager")) continue;
                try {
                    f.setAccessible(true);
                    extractNbts(f.get(obj), results, visited, depth + 1);
                } catch (Exception ignored) {}
            }
            clazz = clazz.getSuperclass();
        }
    }

    private static int getInt(NbtElement elem) {
        if (elem instanceof net.minecraft.nbt.AbstractNbtNumber num) return num.intValue();
        return 0;
    }

    private record BucketKey(int x, int y, int z) {
        static BucketKey from(BlockPos pos) {
            return new BucketKey(pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4);
        }
    }

}

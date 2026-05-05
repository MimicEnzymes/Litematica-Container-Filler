package com.mimicenzymes.litematicafiller.render;

import com.mimicenzymes.litematicafiller.config.Configs;
import com.mimicenzymes.litematicafiller.core.ItemMatcher;
import com.mimicenzymes.litematicafiller.core.LitematicaContainerReader;
import com.mimicenzymes.litematicafiller.core.RealContainerCache;
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
    private static final int BOOSTED_UPDATE_INTERVAL_TICKS = 2;
    private static final int BOOST_DURATION_TICKS = 60;
    private static final int MAX_DATA_REQUESTS_PER_TICK = 128;
    private static final long UNKNOWN_REQUEST_INTERVAL_MS = 100L;
    private static final long ACTIVE_REQUEST_INTERVAL_MS = 750L;
    private static final long SATISFIED_REQUEST_INTERVAL_MS = 4000L;
    private static final long EMPTY_SYNC_CONFIRMATION_MS = 5000L;

    private static final Map<BlockPos, HighlightState> HIGHLIGHT_MAP = new ConcurrentHashMap<>();
    private static final Map<BlockPos, Map<Integer, ItemStack>> SCHEMATIC_REQ_CACHE = new ConcurrentHashMap<>();
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
    private static long lastIndexTime = 0;
    private static boolean isIndexing = false;

    private static int tickCounter = 0;
    private static int boostedTicks = 0;

    public static Map<BlockPos, HighlightState> getHighlights() {
        return HIGHLIGHT_MAP;
    }

    public static int getHighlightVersion() {
        return highlightVersion;
    }

    public static void clearCache() {
        clearHighlights();
        SCHEMATIC_REQ_CACHE.clear();
        HIGHLIGHT_REQUEST_TIME.clear();
        HIGHLIGHT_REQUEST_INTERVALS.clear();
        DATA_REQUEST_QUEUE.clear();
        QUEUED_DATA_REQUESTS.clear();
        SCHEMATIC_CONTAINERS = Collections.emptySet();
        SCHEMATIC_CONTAINER_BUCKETS = Collections.emptyMap();
        lastIndexTime = 0;
        boostedTicks = 0;
    }

    public static void onPlacementChanged() {
        lastIndexTime = 0;
        SCHEMATIC_CONTAINERS = Collections.emptySet();
        SCHEMATIC_REQ_CACHE.clear();
        HIGHLIGHT_REQUEST_TIME.clear();
        HIGHLIGHT_REQUEST_INTERVALS.clear();
        DATA_REQUEST_QUEUE.clear();
        QUEUED_DATA_REQUESTS.clear();
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

    public static void tick(MinecraftClient client) {
        tickCounter++;
        if (!Configs.ENABLE_MOD.getBooleanValue() || !Configs.HIGHLIGHT_CONTAINERS.getBooleanValue()) {
            clearHighlights();
            return;
        }

        if (client.player == null) return;

        var schematicWorld = fi.dy.masa.litematica.world.SchematicWorldHandler.getSchematicWorld();
        if (schematicWorld == null) {
            clearHighlights();
            if (!SCHEMATIC_REQ_CACHE.isEmpty()) SCHEMATIC_REQ_CACHE.clear();
            if (!HIGHLIGHT_REQUEST_TIME.isEmpty()) HIGHLIGHT_REQUEST_TIME.clear();
            if (!SCHEMATIC_CONTAINERS.isEmpty()) SCHEMATIC_CONTAINERS = Collections.emptySet();
            if (!SCHEMATIC_CONTAINER_BUCKETS.isEmpty()) SCHEMATIC_CONTAINER_BUCKETS = Collections.emptyMap();
            return;
        }

        BlockPos currentCenter = client.player.getBlockPos();

        long now = System.currentTimeMillis();
        pumpDataRequests(now);

        if (!isIndexing && (now - lastIndexTime > 5000 || SCHEMATIC_CONTAINERS.isEmpty())) {
            isIndexing = true;
            CompletableFuture.runAsync(() -> {
                try {
                    Set<BlockPos> found = extractAllContainersFromSchematic();
                    if (!found.equals(SCHEMATIC_CONTAINERS)) {
                        SCHEMATIC_REQ_CACHE.clear();
                    }
                    SCHEMATIC_CONTAINERS = found;
                    SCHEMATIC_CONTAINER_BUCKETS = buildContainerBuckets(found);
                } catch (Exception e) {} finally {
                    lastIndexTime = System.currentTimeMillis();
                    isIndexing = false;
                }
            }, INDEX_EXECUTOR);
        }

        int updateInterval = boostedTicks > 0 ? BOOSTED_UPDATE_INTERVAL_TICKS : NORMAL_UPDATE_INTERVAL_TICKS;
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

            BlockPos checkPos = pos;
            BlockPos[] halves = LitematicaContainerReader.getDoubleContainerHalves(schematicWorld, pos, state);
            if (halves != null) checkPos = halves[0];

            Map<Integer, ItemStack> required = getCachedSchematicReq(checkPos, client);
            boolean isCrafter = state.getBlock() instanceof net.minecraft.block.CrafterBlock;

            boolean hasJob = (required != null && !required.isEmpty());
            if (!hasJob && isCrafter) {
                Set<Integer> schematicLocks = LitematicaContainerReader.getDisabledSlots(checkPos);
                hasJob = !schematicLocks.isEmpty();
            }

            if (!hasJob) continue;

            if (client.world.isChunkLoaded(checkPos)) {
                Map<Integer, ItemStack> cached = RealContainerCache.getCachedItems(checkPos);
                HighlightState type;

                if (cached == null) {
                    type = HighlightState.UNKNOWN;
                    queueHighlightRefresh(checkPos, UNKNOWN_REQUEST_INTERVAL_MS, now);
                } else {
                    type = evaluateState(cached, required, isCrafter, checkPos, client);
                    queueHighlightRefresh(checkPos, requestIntervalFor(type), now);
                }

                if (!(hideCompleted && type == HighlightState.SATISFIED)) {
                    nextMap.put(pos.toImmutable(), type);
                }
            } else {
                if (!hideCompleted) {
                    nextMap.put(pos.toImmutable(), HighlightState.UNKNOWN);
                }
            }
        }

        replaceHighlightsIfChanged(nextMap);
        if (boostedTicks > 0) {
            boostedTicks--;
        }
    }

    private static void triggerBoost(int ticks) {
        boostedTicks = Math.max(boostedTicks, ticks);
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

    private static Collection<BlockPos> getNearbySchematicContainers(BlockPos center, int radius) {
        Map<BucketKey, Set<BlockPos>> buckets = SCHEMATIC_CONTAINER_BUCKETS;
        if (radius <= 0 || buckets.isEmpty()) return SCHEMATIC_CONTAINERS;

        int minX = (center.getX() - radius) >> 4;
        int maxX = (center.getX() + radius) >> 4;
        int minZ = (center.getZ() - radius) >> 4;
        int maxZ = (center.getZ() + radius) >> 4;
        List<BlockPos> nearby = new ArrayList<>();

        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                Set<BlockPos> bucket = buckets.get(new BucketKey(x, z));
                if (bucket != null) {
                    nearby.addAll(bucket);
                }
            }
        }

        return nearby;
    }

    private static Map<BucketKey, Set<BlockPos>> buildContainerBuckets(Set<BlockPos> containers) {
        Map<BucketKey, Set<BlockPos>> buckets = new HashMap<>();

        for (BlockPos pos : containers) {
            buckets.computeIfAbsent(BucketKey.from(pos), ignored -> new HashSet<>()).add(pos);
        }

        return buckets;
    }

    private static HighlightState evaluateState(Map<Integer, ItemStack> realItems, Map<Integer, ItemStack> required, boolean isCrafter, BlockPos pos, MinecraftClient client) {
        if (realItems == null) return HighlightState.UNKNOWN;

        int maxSlot = isCrafter ? 9 : 54;
        boolean hasAnyReal = false;
        boolean hasWrong = false;
        boolean hasExtra = false;
        boolean hasPartial = false;

        for (int i = 0; i < maxSlot; i++) {
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

    private record BucketKey(int x, int z) {
        static BucketKey from(BlockPos pos) {
            return new BucketKey(pos.getX() >> 4, pos.getZ() >> 4);
        }
    }
}

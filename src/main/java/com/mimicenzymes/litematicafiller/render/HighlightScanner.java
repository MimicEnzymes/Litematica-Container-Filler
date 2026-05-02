package com.mimicenzymes.litematicafiller.render;

import net.minecraft.network.chat.Component;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.nbt.NumericTag;


import com.mimicenzymes.litematicafiller.config.Configs;
import com.mimicenzymes.litematicafiller.core.ItemMatcher;
import com.mimicenzymes.litematicafiller.core.LitematicaContainerReader;
import com.mimicenzymes.litematicafiller.core.RealContainerCache;








import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class HighlightScanner {
    private static final Map<BlockPos, HighlightState> HIGHLIGHT_MAP = new ConcurrentHashMap<>();
    private static final Map<BlockPos, Map<Integer, ItemStack>> SCHEMATIC_REQ_CACHE = new ConcurrentHashMap<>();
    private static final ExecutorService INDEX_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "LitematicaFiller-HighlightScanner");
        thread.setDaemon(true);
        return thread;
    });
    private static volatile Set<BlockPos> SCHEMATIC_CONTAINERS = Collections.emptySet();
    private static long lastIndexTime = 0;
    private static boolean isIndexing = false;

    private static int tickCounter = 0;

    public static Map<BlockPos, HighlightState> getHighlights() {
        return HIGHLIGHT_MAP;
    }

    public static void clearCache() {
        SCHEMATIC_REQ_CACHE.clear();
        SCHEMATIC_CONTAINERS = Collections.emptySet();
        lastIndexTime = 0;
    }

    public static void onPlacementChanged() {
        lastIndexTime = 0;
        SCHEMATIC_CONTAINERS = Collections.emptySet();
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

    public static void tick(Minecraft client) {
        tickCounter++;
        if (!Configs.ENABLE_MOD.getBooleanValue() || !Configs.HIGHLIGHT_CONTAINERS.getBooleanValue()) {
            if (!HIGHLIGHT_MAP.isEmpty()) HIGHLIGHT_MAP.clear();
            return;
        }

        if (client.player == null) return;

        var schematicWorld = fi.dy.masa.litematica.world.SchematicWorldHandler.getSchematicWorld();
        if (schematicWorld == null) {
            if (!HIGHLIGHT_MAP.isEmpty()) HIGHLIGHT_MAP.clear();
            if (!SCHEMATIC_REQ_CACHE.isEmpty()) SCHEMATIC_REQ_CACHE.clear();
            if (!SCHEMATIC_CONTAINERS.isEmpty()) SCHEMATIC_CONTAINERS = Collections.emptySet();
            return;
        }

        if (tickCounter % 200 == 0) SCHEMATIC_REQ_CACHE.clear();

        long now = System.currentTimeMillis();
        if (!isIndexing && (now - lastIndexTime > 5000 || SCHEMATIC_CONTAINERS.isEmpty())) {
            isIndexing = true;
            CompletableFuture.runAsync(() -> {
                try {
                    Set<BlockPos> found = extractAllContainersFromSchematic();
                    SCHEMATIC_CONTAINERS = found;
                } catch (Exception e) {} finally {
                    lastIndexTime = System.currentTimeMillis();
                    isIndexing = false;
                }
            }, INDEX_EXECUTOR);
        }

        boolean hideCompleted = Configs.HIDE_COMPLETED_CONTAINERS.getBooleanValue();
        boolean syncLayer = Configs.SYNC_LITE_LAYER.getBooleanValue();

        BlockPos currentCenter = client.player.blockPosition();
        int currentRadius = Configs.RENDER_RADIUS.getIntegerValue();
        double radiusSq = currentRadius * currentRadius;

        Map<BlockPos, HighlightState> nextMap = new HashMap<>();

        for (BlockPos pos : SCHEMATIC_CONTAINERS) {
            if (currentRadius > 0 && pos.distSqr(currentCenter) > radiusSq) continue;

            if (syncLayer && !fi.dy.masa.litematica.data.DataManager.getRenderLayerRange().isPositionWithinRange(pos)) continue;

            BlockState state = schematicWorld.getBlockState(pos);
            if (state == null || state.isAir() || !state.hasBlockEntity()) continue;

            BlockPos checkPos = pos;
            BlockPos[] halves = LitematicaContainerReader.getDoubleContainerHalves(schematicWorld, pos, state);
            if (halves != null) checkPos = halves[0];

            Map<Integer, ItemStack> required = getCachedSchematicReq(checkPos, client);
            boolean isCrafter = state.getBlock() instanceof net.minecraft.world.level.block.CrafterBlock;

            boolean hasJob = (required != null && !required.isEmpty());
            if (!hasJob && isCrafter) {
                Set<Integer> schematicLocks = LitematicaContainerReader.getDisabledSlots(checkPos);
                hasJob = !schematicLocks.isEmpty();
            }

            if (!hasJob) continue;

            if (client.level.hasChunkAt(checkPos)) {
                Map<Integer, ItemStack> cached = RealContainerCache.getCachedItems(checkPos);
                HighlightState type;

                if (cached == null) {
                    type = HighlightState.UNKNOWN;
                    RealContainerCache.requestContainerData(checkPos);
                } else {
                    type = evaluateState(cached, required, isCrafter, checkPos, client);
                }

                if (!(hideCompleted && type == HighlightState.SATISFIED)) {
                    nextMap.put(pos.immutable(), type);
                }
            } else {
                if (!hideCompleted) {
                    nextMap.put(pos.immutable(), HighlightState.UNKNOWN);
                }
            }
        }

        HIGHLIGHT_MAP.clear();
        HIGHLIGHT_MAP.putAll(nextMap);
    }

    private static HighlightState evaluateState(Map<Integer, ItemStack> realItems, Map<Integer, ItemStack> required, boolean isCrafter, BlockPos pos, Minecraft client) {
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
                Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
                var schematicWorld = fi.dy.masa.litematica.world.SchematicWorldHandler.getSchematicWorld();

                for (Object p : all) {
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
                    if (origin == null) origin = BlockPos.ZERO;

                    List<CompoundTag> nbts = new ArrayList<>();
                    extractNbts(p, nbts, visited, 0);

                    for (CompoundTag nbt : nbts) {
                        if (nbt.contains("x") && nbt.contains("y") && nbt.contains("z")) {
                            int nx = getInt(nbt.get("x"));
                            int ny = getInt(nbt.get("y"));
                            int nz = getInt(nbt.get("z"));

                            BlockPos directPos = new BlockPos(nx, ny, nz);
                            BlockPos offsetPos = origin.offset(nx, ny, nz);

                            BlockPos worldPos = null;

                            if (schematicWorld != null) {
                                if (schematicWorld.getBlockState(offsetPos).hasBlockEntity()) {
                                    worldPos = offsetPos;
                                } else if (schematicWorld.getBlockState(directPos).hasBlockEntity()) {
                                    worldPos = directPos;
                                }
                            }

                            if (worldPos == null) {
                                double distDirect = directPos.distSqr(origin);
                                double distOffset = offsetPos.distSqr(origin);
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

    private static void extractNbts(Object obj, List<CompoundTag> results, Set<Object> visited, int depth) {
        if (obj == null || depth > 100 || !visited.add(obj)) return;

        if (obj instanceof CompoundTag c) {
            if (c.contains("x") && c.contains("y") && c.contains("z") && (c.contains("Items") || c.contains("id"))) {
                results.add(c);
            }
            for (String key : c.keySet()) {
                Tag el = c.get(key);
                if (el instanceof CompoundTag child) extractNbts(child, results, visited, depth + 1);
                else if (el instanceof ListTag list) {
                    for (int i = 0; i < list.size(); i++) extractNbts(list.get(i), results, visited, depth + 1);
                }
            }
            return;
        }

        if (obj instanceof net.minecraft.world.level.block.entity.BlockEntity be) {
            Minecraft client = Minecraft.getInstance();
            if (client.level != null) {
                try {
                    CompoundTag c = be.saveWithoutMetadata(client.level.registryAccess());
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

    private static int getInt(Tag elem) {
        if (elem instanceof net.minecraft.nbt.NumericTag num) return num.intValue();
        return 0;
    }
}

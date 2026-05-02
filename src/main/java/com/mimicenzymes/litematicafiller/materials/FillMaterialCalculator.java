package com.mimicenzymes.litematicafiller.materials;

import com.mimicenzymes.litematicafiller.core.ItemMatcher;
import com.mimicenzymes.litematicafiller.core.LitematicaContainerReader;
import com.mimicenzymes.litematicafiller.core.MaterialReplacer;
import com.mimicenzymes.litematicafiller.core.RealContainerCache;
import com.mimicenzymes.litematicafiller.mixin.MaterialListPlacementAccessor;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.gui.GuiMaterialList;
import fi.dy.masa.litematica.materials.MaterialListBase;
import fi.dy.masa.litematica.materials.MaterialListEntry;
import fi.dy.masa.litematica.materials.MaterialListPlacement;
import fi.dy.masa.litematica.materials.IMaterialList;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacementManager;
import fi.dy.masa.litematica.schematic.placement.SubRegionPlacement.RequiredEnabled;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.selection.Box;
import fi.dy.masa.litematica.util.BlockInfoListType;
import fi.dy.masa.malilib.util.LayerRange;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.util.Identifier;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class FillMaterialCalculator {
    public static boolean isFillMode = false;
    public static int listMode = 0;

    public static volatile boolean hasMissingData = false;

    private static final Map<SchematicPlacement, List<NbtContext>> PLACEMENT_NBT_CACHE = new IdentityHashMap<>();
    public static class ItemStackKey {
        public final Item item;
        public final String customName;
        public final int containerHash;

        public ItemStackKey(ItemStack stack) {
            this.item = stack.getItem();
            net.minecraft.text.Text name = stack.get(DataComponentTypes.CUSTOM_NAME);
            this.customName = name != null ? name.getString() : "";
            this.containerHash = computeContainerHash(stack);
        }

        private static int computeContainerHash(ItemStack stack) {
            if (!(stack.getItem() instanceof BlockItem blockItem) ||
                !(blockItem.getBlock() instanceof ShulkerBoxBlock)) {
                return 0;
            }

            ContainerComponent container = stack.get(DataComponentTypes.CONTAINER);
            if (container == null) return 0;

            int hash = 0;
            int slot = 0;
            for (ItemStack contained : container.iterateNonEmpty()) {
                hash = 31 * hash + Registries.ITEM.getId(contained.getItem()).hashCode();
                hash = 31 * hash + contained.getCount();
                hash = 31 * hash + slot;
                net.minecraft.text.Text cName = contained.get(DataComponentTypes.CUSTOM_NAME);
                if (cName != null) hash = 31 * hash + cName.getString().hashCode();
                slot++;
            }
            return hash;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof ItemStackKey)) return false;
            ItemStackKey that = (ItemStackKey) o;
            return item.equals(that.item) && customName.equals(that.customName) && containerHash == that.containerHash;
        }

        @Override
        public int hashCode() {
            int result = 31 * item.hashCode() + customName.hashCode();
            return 31 * result + containerHash;
        }
    }

    private static class ItemStats {
        int totalAll = 0, missingAll = 0, availableAll = 0, mismatchAll = 0;
        int totalLayer = 0, missingLayer = 0, availableLayer = 0, mismatchLayer = 0;
        ItemStack representative = ItemStack.EMPTY;
    }

    private static class NbtContext {
        final BlockPos worldPos;
        final SchematicPlacement placement;
        final Map<Integer, ItemStack> parsedRequired;

        NbtContext(BlockPos worldPos, SchematicPlacement placement, Map<Integer, ItemStack> parsedRequired) {
            this.worldPos = worldPos;
            this.placement = placement;
            this.parsedRequired = parsedRequired;
        }
    }

    private static class SchematicSource {
        final LitematicaSchematic schematic;
        final Set<String> regions;

        SchematicSource(LitematicaSchematic schematic, Set<String> regions) {
            this.schematic = schematic;
            this.regions = regions;
        }
    }

    private static final Map<ItemStackKey, ItemStats> itemStatsCache = new HashMap<>();

    public static void calculate(Object input, boolean silent) {
        calculate(input, silent, null);
    }

    public static void calculate(Object input, boolean silent, List<MaterialListEntry> fallbackEntries) {
        itemStatsCache.clear();

        if (!silent) {
            PLACEMENT_NBT_CACHE.clear();
        }

        int foundContainersAll = 0, foundContainersLayer = 0;
        boolean waitingForData = false;

        MinecraftClient client = MinecraftClient.getInstance();

        List<SchematicPlacement> placementsToScan = new ArrayList<>();
        List<SchematicSource> schematicSourcesToScan = new ArrayList<>();

        MaterialListBase sourceMaterialList = null;

        if (input instanceof SchematicPlacement sp) {
            placementsToScan.add(sp);
        } else if (input instanceof Collection<?> coll) {
            for (Object obj : coll) {
                if (obj instanceof SchematicPlacement sp) placementsToScan.add(sp);
            }
        } else {
            MaterialListBase matList = extractMaterialList(input);
            sourceMaterialList = matList;

            if (matList != null) {
                if (matList instanceof MaterialListPlacement mlp) {
                    SchematicPlacement sp = ((MaterialListPlacementAccessor) mlp).getPlacement();
                    if (sp != null) {
                        placementsToScan.add(sp);
                        schematicSourcesToScan.add(new SchematicSource(sp.getSchematic(), null));
                    }
                } else {
                    SchematicSource source = extractSchematicSource(matList);
                    if (source != null) schematicSourcesToScan.add(source);
                }
            }
        }

        if (placementsToScan.isEmpty()) {
            SchematicPlacementManager manager = DataManager.getSchematicPlacementManager();
            if (manager != null) {
                for (SchematicPlacement p : manager.getAllSchematicsPlacements()) {
                    if (p.isEnabled()) placementsToScan.add(p);
                }
            }
        }

        if (placementsToScan.isEmpty()) {
            if (calculateFromSchematicContainers(schematicSourcesToScan)) {
                hasMissingData = false;
            } else if (calculateFromMaterialListContainers(sourceMaterialList, fallbackEntries)) {
                hasMissingData = false;
            }
            return;
        }

        Map<BlockPos, NbtContext> nbtMap = new HashMap<>();
        var schematicWorld = SchematicWorldHandler.getSchematicWorld();

        for (SchematicPlacement placement : placementsToScan) {
            List<NbtContext> cachedCtx = PLACEMENT_NBT_CACHE.get(placement);

            if (cachedCtx == null) {
                LitematicaSchematic schematic = placement.getSchematic();
                if (schematic == null) continue;

                int totalTEs = 0;
                for (String regionName : schematic.getAreaPositions().keySet()) {
                    Map<BlockPos, NbtCompound> teMap = schematic.getBlockEntityMapForRegion(regionName);
                    if (teMap != null) totalTEs += teMap.size();
                }

                if (totalTEs == 0) {
                    PLACEMENT_NBT_CACHE.put(placement, Collections.emptyList());
                    continue;
                }

                Map<BlockPos, NbtContext> bestMap = new HashMap<>();

                if (schematicWorld != null) {
                    for (Box box : placement.getSubRegionBoxes(RequiredEnabled.PLACEMENT_ENABLED).values()) {
                        BlockPos p1 = box.getPos1();
                        BlockPos p2 = box.getPos2();
                        int minX = Math.min(p1.getX(), p2.getX());
                        int maxX = Math.max(p1.getX(), p2.getX());
                        int minY = Math.min(p1.getY(), p2.getY());
                        int maxY = Math.max(p1.getY(), p2.getY());
                        int minZ = Math.min(p1.getZ(), p2.getZ());
                        int maxZ = Math.max(p1.getZ(), p2.getZ());

                        for (int x = minX; x <= maxX; x++) {
                            for (int y = minY; y <= maxY; y++) {
                                for (int z = minZ; z <= maxZ; z++) {
                                    BlockPos worldPos = new BlockPos(x, y, z);
                                    BlockState state = schematicWorld.getBlockState(worldPos);

                                    if (state != null && state.hasBlockEntity()) {
                                        BlockEntity be = schematicWorld.getBlockEntity(worldPos);
                                        if (be != null) {
                                            NbtCompound nbt = be.createNbt(client.world.getRegistryManager());
                                            if (nbt != null && nbt.contains("Items")) {
                                                Map<Integer, ItemStack> parsedReq = RealContainerCache.parseNbtInventory(nbt, client.world.getRegistryManager());
                                                MaterialReplacer.replaceInMap(parsedReq);

                                                NbtContext existing = bestMap.get(worldPos);
                                                if (existing == null || getItemsCount(nbt) > getItemsCount(existing.parsedRequired)) {
                                                    bestMap.put(worldPos, new NbtContext(worldPos, placement, parsedReq));
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                cachedCtx = new ArrayList<>(bestMap.values());
                PLACEMENT_NBT_CACHE.put(placement, cachedCtx);
            }

            for (NbtContext ctx : cachedCtx) {
                nbtMap.put(ctx.worldPos, ctx);
            }
        }

        if (nbtMap.isEmpty()) {
            if (calculateFromSchematicContainers(schematicSourcesToScan)) {
                hasMissingData = false;
            } else if (calculateFromMaterialListContainers(sourceMaterialList, fallbackEntries)) {
                hasMissingData = false;
            }
            return;
        }

        Set<BlockPos> globalVisited = new java.util.HashSet<>();

        LayerRange layerRange = DataManager.getRenderLayerRange();

        for (Map.Entry<BlockPos, NbtContext> entry : nbtMap.entrySet()) {
            BlockPos pos = entry.getKey();
            NbtContext ctx = entry.getValue();

            if (globalVisited.contains(pos)) continue;

            boolean inLayer = true;
            if (layerRange != null) {
                inLayer = layerRange.isPositionWithinRange(pos);
            }

            Map<Integer, ItemStack> required = new HashMap<>();
            for (Map.Entry<Integer, ItemStack> e : ctx.parsedRequired.entrySet()) {
                required.put(e.getKey(), e.getValue().copy());
            }

            BlockPos mainPos = pos;
            BlockState realState = client.world.getBlockState(pos);
            BlockState schState = schematicWorld != null ? schematicWorld.getBlockState(pos) : null;

            boolean useSch = !realState.hasBlockEntity() && schState != null && schState.hasBlockEntity();
            BlockState stateForHalves = useSch ? schState : realState;
            net.minecraft.world.World worldForHalves = useSch ? (net.minecraft.world.World) (Object) schematicWorld : client.world;

            BlockPos[] halves = null;
            if (stateForHalves.hasBlockEntity()) {
                halves = LitematicaContainerReader.getDoubleContainerHalves(worldForHalves, pos, stateForHalves);
                if (halves != null) {
                    mainPos = halves[0];
                    globalVisited.add(halves[0]);
                    globalVisited.add(halves[1]);

                    boolean isPrimary = pos.equals(halves[0]);
                    BlockPos otherPos = isPrimary ? halves[1] : halves[0];

                    if (nbtMap.containsKey(otherPos)) {
                        Map<Integer, ItemStack> otherReq = nbtMap.get(otherPos).parsedRequired;

                        if (isPrimary) {
                            for (Map.Entry<Integer, ItemStack> e : otherReq.entrySet()) {
                                required.put(e.getKey() + 27, e.getValue().copy());
                            }
                        } else {
                            Map<Integer, ItemStack> shifted = new HashMap<>();
                            for (Map.Entry<Integer, ItemStack> e : required.entrySet()) {
                                shifted.put(e.getKey() + 27, e.getValue());
                            }
                            for (Map.Entry<Integer, ItemStack> e : otherReq.entrySet()) {
                                shifted.put(e.getKey(), e.getValue().copy());
                            }
                            required = shifted;
                        }
                    } else {
                        if (!isPrimary) {
                            Map<Integer, ItemStack> shifted = new HashMap<>();
                            for (Map.Entry<Integer, ItemStack> e : required.entrySet()) {
                                shifted.put(e.getKey() + 27, e.getValue());
                            }
                            required = shifted;
                        }
                    }
                } else {
                    globalVisited.add(pos);
                }
            } else {
                globalVisited.add(pos);
            }

            if (required.isEmpty()) continue;

            foundContainersAll++;
            if (inLayer) foundContainersLayer++;

            Map<Integer, ItemStack> realItems = RealContainerCache.getCachedItems(mainPos);

            if (realItems == null && halves != null) {
                BlockPos otherHalf = pos.equals(halves[0]) ? halves[1] : halves[0];
                realItems = RealContainerCache.getCachedItems(otherHalf);
                if (realItems != null) mainPos = otherHalf;
            }

            if (realItems == null) {
                if (client.world != null) {
                    waitingForData = true;
                    RealContainerCache.requestContainerData(mainPos);

                    if (halves != null) {
                        BlockPos otherHalf = pos.equals(halves[0]) ? halves[1] : halves[0];
                        RealContainerCache.requestContainerData(otherHalf);
                    }
                }
                realItems = new HashMap<>();
            }

            Map<ItemStackKey, Integer> reqAgg = new HashMap<>();
            Map<ItemStackKey, Integer> realAgg = new HashMap<>();

            for (ItemStack s : required.values()) {
                ItemStackKey key = new ItemStackKey(s);
                reqAgg.merge(key, s.getCount(), Integer::sum);
                ItemStats stats = itemStatsCache.computeIfAbsent(key, k -> new ItemStats());
                if (stats.representative.isEmpty()) stats.representative = s.copy();
            }

            for (ItemStack s : realItems.values()) {
                ItemStackKey key = new ItemStackKey(s);
                realAgg.merge(key, s.getCount(), Integer::sum);
            }

            for (Map.Entry<ItemStackKey, Integer> e : reqAgg.entrySet()) {
                ItemStackKey key = e.getKey();
                int req = e.getValue();
                int real = realAgg.getOrDefault(key, 0);

                ItemStats stats = itemStatsCache.get(key);

                int matched = Math.min(req, real);

                stats.totalAll += req;
                stats.availableAll += real;
                stats.missingAll += Math.max(0, req - matched);

                if (inLayer) {
                    stats.totalLayer += req;
                    stats.availableLayer += real;
                    stats.missingLayer += Math.max(0, req - matched);
                }
            }

            for (Map.Entry<ItemStackKey, Integer> e : realAgg.entrySet()) {
                ItemStackKey key = e.getKey();
                int real = e.getValue();
                int req = reqAgg.getOrDefault(key, 0);

                if (real > req) {
                    ItemStats stats = itemStatsCache.computeIfAbsent(key, k -> new ItemStats());
                    if (stats.representative.isEmpty()) stats.representative = new ItemStack(key.item);

                    stats.mismatchAll += (real - req);
                    if (inLayer) {
                        stats.mismatchLayer += (real - req);
                    }
                }
            }
        }

        hasMissingData = waitingForData;
    }

    private static MaterialListBase extractMaterialList(Object input) {
        if (input instanceof MaterialListBase mlb) {
            return mlb;
        }
        if (input instanceof GuiMaterialList gui) {
            return gui.getMaterialList();
        }
        try {
            if (input != null && input.getClass().getSimpleName().contains("GuiMaterialList")) {
                java.lang.reflect.Method m = input.getClass().getMethod("getMaterialList");
                Object result = m.invoke(input);
                if (result instanceof MaterialListBase mlb) return mlb;
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static int getItemsCount(NbtCompound nbt) {
        if (nbt != null && nbt.contains("Items")) {
            NbtElement el = nbt.get("Items");
            if (el instanceof NbtList list) {
                return list.size();
            }
        }
        return 0;
    }

    private static int getItemsCount(Map<Integer, ItemStack> parsedMap) {
        return parsedMap != null ? parsedMap.size() : 0;
    }

    private static SchematicSource extractSchematicSource(Object input) {
        if (input == null) return null;

        LitematicaSchematic schematic = null;
        Set<String> regions = null;
        Class<?> current = input.getClass();

        while (current != null) {
            for (java.lang.reflect.Field field : current.getDeclaredFields()) {
                try {
                    field.setAccessible(true);
                    Object value = field.get(input);

                    if (schematic == null && value instanceof LitematicaSchematic litematic) {
                        schematic = litematic;
                    }

                    if (regions == null && "regions".equals(field.getName()) && value instanceof Collection<?> collection) {
                        Set<String> readRegions = new LinkedHashSet<>();
                        for (Object region : collection) {
                            if (region instanceof String name) readRegions.add(name);
                        }
                        if (!readRegions.isEmpty()) regions = readRegions;
                    }
                } catch (Exception ignored) {}
            }

            current = current.getSuperclass();
        }

        return schematic != null ? new SchematicSource(schematic, regions) : null;
    }

    private static boolean calculateFromSchematicContainers(Collection<SchematicSource> sources) {
        if (sources == null || sources.isEmpty()) return false;

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null) return false;

        boolean foundContainers = false;

        for (SchematicSource source : sources) {
            if (source == null || source.schematic == null) continue;

            LitematicaSchematic schematic = getSchematicWithBlockEntities(source);
            if (schematic == null) continue;

            for (String regionName : getSchematicRegions(schematic, source.regions)) {
                Map<BlockPos, NbtCompound> teMap = schematic.getBlockEntityMapForRegion(regionName);
                if (teMap == null || teMap.isEmpty()) continue;

                for (NbtCompound nbt : teMap.values()) {
                    if (nbt == null || !nbt.contains("Items")) continue;

                    Map<Integer, ItemStack> parsed = RealContainerCache.parseNbtInventory(nbt, client.world.getRegistryManager());
                    if (parsed.isEmpty()) continue;

                    MaterialReplacer.replaceInMap(parsed);
                    addSchematicContainerItems(parsed);
                    foundContainers = true;
                }
            }
        }

        return foundContainers;
    }

    private static LitematicaSchematic getSchematicWithBlockEntities(SchematicSource source) {
        LitematicaSchematic schematic = source.schematic;
        if (hasBlockEntityData(schematic, source.regions)) return schematic;

        try {
            LitematicaSchematic loaded = reloadSchematicFromFile(schematic.getFile());
            if (loaded != null && hasBlockEntityData(loaded, source.regions)) return loaded;
        } catch (Throwable ignored) {}

        return schematic;
    }

    private static LitematicaSchematic reloadSchematicFromFile(Object fileObject) {
        try {
            if (fileObject instanceof Path file) {
                if (!Files.isRegularFile(file)) return null;

                Path parent = file.getParent();
                Path fileName = file.getFileName();
                if (parent == null || fileName == null) return null;

                return invokeCreateFromFile(Path.class, parent, fileName.toString());
            }

            if (fileObject instanceof File file) {
                if (!file.isFile()) return null;

                File parent = file.getParentFile();
                if (parent == null) return null;

                return invokeCreateFromFile(File.class, parent, file.getName());
            }
        } catch (Throwable ignored) {}

        return null;
    }

    private static LitematicaSchematic invokeCreateFromFile(Class<?> directoryType, Object directory, String fileName) throws Exception {
        java.lang.reflect.Method method = LitematicaSchematic.class.getMethod("createFromFile", directoryType, String.class);
        Object result = method.invoke(null, directory, fileName);
        return result instanceof LitematicaSchematic schematic ? schematic : null;
    }

    private static boolean hasBlockEntityData(LitematicaSchematic schematic, Set<String> selectedRegions) {
        if (schematic == null) return false;

        for (String regionName : getSchematicRegions(schematic, selectedRegions)) {
            Map<BlockPos, NbtCompound> teMap = schematic.getBlockEntityMapForRegion(regionName);
            if (teMap != null && !teMap.isEmpty()) return true;
        }

        return false;
    }

    private static Collection<String> getSchematicRegions(LitematicaSchematic schematic, Set<String> selectedRegions) {
        if (schematic == null) return Collections.emptyList();

        Set<String> allRegions = schematic.getAreaPositions().keySet();
        if (selectedRegions == null || selectedRegions.isEmpty()) return allRegions;

        List<String> regions = new ArrayList<>();
        for (String regionName : selectedRegions) {
            if (allRegions.contains(regionName)) regions.add(regionName);
        }

        return regions;
    }

    private static void addSchematicContainerItems(Map<Integer, ItemStack> required) {
        for (ItemStack s : required.values()) {
            if (s == null || s.isEmpty()) continue;

            ItemStackKey key = new ItemStackKey(s);
            ItemStats stats = itemStatsCache.computeIfAbsent(key, k -> new ItemStats());
            if (stats.representative.isEmpty()) stats.representative = s.copy();

            int count = s.getCount();
            stats.totalAll += count;
            stats.missingAll += count;
            stats.totalLayer += count;
            stats.missingLayer += count;
        }
    }

    private static boolean calculateFromMaterialListContainers(MaterialListBase materialList, List<MaterialListEntry> fallbackEntries) {
        List<MaterialListEntry> entries = fallbackEntries != null ? fallbackEntries : readMaterialListEntries(materialList);
        if (entries.isEmpty()) return false;

        boolean foundContainers = false;

        for (MaterialListEntry entry : entries) {
            if (entry == null || entry.getStack().isEmpty()) continue;

            ContainerComponent container = entry.getStack().get(DataComponentTypes.CONTAINER);
            if (container == null) continue;

            foundContainers = true;

            int totalMultiplier = Math.max(0, entry.getCountTotal());
            int missingMultiplier = Math.max(0, entry.getCountMissing());
            int availableMultiplier = Math.max(0, entry.getCountAvailable());
            int mismatchMultiplier = Math.max(0, entry.getCountMismatched());

            for (ItemStack contained : container.iterateNonEmpty()) {
                if (contained.isEmpty()) continue;

                ItemStack stack = MaterialReplacer.replaceSingleStack(contained.copy());
                if (stack.isEmpty()) continue;

                int count = stack.getCount();
                addMaterialListContainerItem(
                        stack,
                        count * totalMultiplier,
                        count * missingMultiplier,
                        count * availableMultiplier,
                        count * mismatchMultiplier
                );
            }
        }

        return foundContainers;
    }

    @SuppressWarnings("unchecked")
    private static List<MaterialListEntry> readMaterialListEntries(MaterialListBase materialList) {
        if (materialList == null) return Collections.emptyList();

        for (String fieldName : List.of("materialListAll", "materialListPreFiltered")) {
            try {
                java.lang.reflect.Field field = MaterialListBase.class.getDeclaredField(fieldName);
                field.setAccessible(true);
                Object value = field.get(materialList);
                if (value instanceof List<?> list) {
                    return (List<MaterialListEntry>) list;
                }
            } catch (Exception ignored) {}
        }

        return Collections.emptyList();
    }

    private static void addMaterialListContainerItem(ItemStack stack, int total, int missing, int available, int mismatch) {
        if (total == 0 && missing == 0 && available == 0 && mismatch == 0) return;

        ItemStackKey key = new ItemStackKey(stack);
        ItemStats stats = itemStatsCache.computeIfAbsent(key, k -> new ItemStats());
        if (stats.representative.isEmpty()) {
            stats.representative = stack.copy();
            stats.representative.setCount(1);
        }

        stats.totalAll += total;
        stats.missingAll += missing;
        stats.availableAll += available;
        stats.mismatchAll += mismatch;

        stats.totalLayer += total;
        stats.missingLayer += missing;
        stats.availableLayer += available;
        stats.mismatchLayer += mismatch;
    }

    private static boolean isItemIgnored(MaterialListBase materialList, Item item) {
        return false;
    }

    public static List<MaterialListEntry> getCustomMaterialList(Object materialListObj) {
        boolean limitToLayer = true;

        if (materialListObj instanceof IMaterialList iMatList) {
            BlockInfoListType type = iMatList.getMaterialListType();
            if (type != null) {
                limitToLayer = type.name().contains("LAYER");
            }
        }

        List<MaterialListEntry> list = new ArrayList<>();

        for (Map.Entry<ItemStackKey, ItemStats> entry : itemStatsCache.entrySet()) {
            ItemStats stats = entry.getValue();

            int total = limitToLayer ? stats.totalLayer : stats.totalAll;
            int missing = limitToLayer ? stats.missingLayer : stats.missingAll;
            int available = limitToLayer ? stats.availableLayer : stats.availableAll;
            int mismatch = limitToLayer ? stats.mismatchLayer : stats.mismatchAll;

            if (total == 0 && missing == 0 && available == 0 && mismatch == 0) continue;

            ItemStack stack = stats.representative;
            if (stack.isEmpty()) stack = new ItemStack(entry.getKey().item);

            MaterialListEntry matEntry = new MaterialListEntry(stack, total, missing, available, 0);
            list.add(matEntry);
        }

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null && !list.isEmpty()) {
            fi.dy.masa.litematica.materials.MaterialListUtils.updateAvailableCounts(list, client.player);
        }

        return list;
    }

    public static List<MaterialListEntry> mergeLists(List<MaterialListEntry> vanillaList, List<MaterialListEntry> containerList) {
        Map<ItemStackKey, MaterialListEntry> mergedMap = new java.util.LinkedHashMap<>();

        for (MaterialListEntry v : vanillaList) {
            mergedMap.put(new ItemStackKey(v.getStack()), v);
        }

        for (MaterialListEntry c : containerList) {
            ItemStackKey key = new ItemStackKey(c.getStack());
            MaterialListEntry v = mergedMap.get(key);

            if (v != null) {
                MaterialListEntry combined = new MaterialListEntry(
                        c.getStack(),
                        v.getCountTotal() + c.getCountTotal(),
                        v.getCountMissing() + c.getCountMissing(),
                        v.getCountAvailable(),
                        v.getCountMismatched() + c.getCountMismatched()
                );
                mergedMap.put(key, combined);
            } else {
                mergedMap.put(key, c);
            }
        }

        return new ArrayList<>(mergedMap.values());
    }
}

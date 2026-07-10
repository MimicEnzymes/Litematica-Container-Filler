package com.mimicenzymes.litematicafiller.core;

import com.mojang.logging.LogUtils;
import com.mimicenzymes.litematicafiller.materials.FillMaterialCalculator;
import com.mimicenzymes.litematicafiller.render.HighlightScanner;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.selection.Box;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacementManager;
import fi.dy.masa.litematica.schematic.placement.SubRegionPlacement;
import fi.dy.masa.litematica.schematic.placement.SubRegionPlacement.RequiredEnabled;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;

public final class SchematicPlacementChangeWatcher {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final long EMPTY_SIGNATURE = 0x6a09e667f3bcc909L;

    private static ClientLevel lastLevel = null;
    private static ResourceKey<Level> lastDimension = null;
    private static UUID lastPlayerUuid = null;
    private static long lastSignature = EMPTY_SIGNATURE;
    private static boolean initialized = false;

    private SchematicPlacementChangeWatcher() {
    }

    public static void tick(Minecraft client) {
        if (client == null || client.level == null) {
            reset();
            return;
        }

        if (contextChanged(client)) {
            updateContext(client);
            initialized = false;
            lastSignature = EMPTY_SIGNATURE;
            LOGGER.info("[LCF diagnostics] placement context changed; {}", describeCurrentPlacements());
            refreshPlacementCaches();
        }

        long signature = computeCurrentSignature();
        if (!initialized) {
            lastSignature = signature;
            initialized = true;
            LOGGER.info("[LCF diagnostics] placement watcher initialized signature={} {}", Long.toUnsignedString(signature), describeCurrentPlacements());
            return;
        }

        if (signature != lastSignature) {
            long previousSignature = lastSignature;
            lastSignature = signature;
            LOGGER.info("[LCF diagnostics] placement signature changed {} -> {}; {}", Long.toUnsignedString(previousSignature), Long.toUnsignedString(signature), describeCurrentPlacements());
            refreshPlacementCaches();
        }
    }

    public static void reset() {
        lastLevel = null;
        lastDimension = null;
        lastPlayerUuid = null;
        lastSignature = EMPTY_SIGNATURE;
        initialized = false;
    }

    private static boolean contextChanged(Minecraft client) {
        ResourceKey<Level> dimension = client.level.dimension();
        UUID playerUuid = client.player == null ? null : client.player.getUUID();
        return client.level != lastLevel
                || (lastDimension != null && !lastDimension.equals(dimension))
                || (lastDimension == null && dimension != null)
                || (lastPlayerUuid != null && !lastPlayerUuid.equals(playerUuid))
                || (lastPlayerUuid == null && playerUuid != null);
    }

    private static void updateContext(Minecraft client) {
        lastLevel = client.level;
        lastDimension = client.level.dimension();
        lastPlayerUuid = client.player == null ? null : client.player.getUUID();
    }

    private static void refreshPlacementCaches() {
        HighlightScanner.onPlacementChanged();
        FillMaterialCalculator.requestMaterialReplacementRefresh();
    }

    private static long computeCurrentSignature() {
        try {
            SchematicPlacementManager manager = DataManager.getSchematicPlacementManager();
            if (manager == null) return EMPTY_SIGNATURE;

            List<SignatureEntry> entries = new ArrayList<>();
            for (SchematicPlacement placement : manager.getAllSchematicsPlacements()) {
                if (placement == null) continue;
                entries.add(snapshotPlacement(placement));
            }

            entries.sort(Comparator.comparing(SignatureEntry::key));

            long signature = mix64(EMPTY_SIGNATURE ^ entries.size());
            for (SignatureEntry entry : entries) {
                signature = mix64(signature ^ stringHash(entry.key()));
                signature = mix64(signature ^ entry.hash());
            }
            return signature;
        } catch (Throwable ignored) {
            return EMPTY_SIGNATURE;
        }
    }

    private static SignatureEntry snapshotPlacement(SchematicPlacement placement) {
        String key = placementKey(placement);
        long hash = stringHash(key);

        hash = mix64(hash ^ booleanHash(placement.isEnabled()));
        hash = mix64(hash ^ booleanHash(placement.isRenderingEnabled()));
        hash = mix64(hash ^ blockPosHash(placement.getOrigin()));
        hash = mix64(hash ^ enumHash(placement.getRotation()));
        hash = mix64(hash ^ enumHash(placement.getMirror()));

        List<SignatureEntry> subRegions = new ArrayList<>();
        for (SubRegionPlacement subRegion : placement.getAllSubRegionsPlacements()) {
            if (subRegion == null) continue;
            subRegions.add(snapshotSubRegion(subRegion));
        }
        subRegions.sort(Comparator.comparing(SignatureEntry::key));

        hash = mix64(hash ^ subRegions.size());
        for (SignatureEntry subRegion : subRegions) {
            hash = mix64(hash ^ stringHash(subRegion.key()));
            hash = mix64(hash ^ subRegion.hash());
        }

        List<SignatureEntry> boxes = snapshotPlacementBoxes(placement);
        hash = mix64(hash ^ boxes.size());
        for (SignatureEntry box : boxes) {
            hash = mix64(hash ^ stringHash(box.key()));
            hash = mix64(hash ^ box.hash());
        }

        return new SignatureEntry(key, hash);
    }

    private static SignatureEntry snapshotSubRegion(SubRegionPlacement subRegion) {
        String key = safeString(subRegion.getName());
        long hash = stringHash(key);
        hash = mix64(hash ^ blockPosHash(subRegion.getPos()));
        hash = mix64(hash ^ enumHash(subRegion.getRotation()));
        hash = mix64(hash ^ enumHash(subRegion.getMirror()));
        hash = mix64(hash ^ booleanHash(subRegion.isEnabled()));
        hash = mix64(hash ^ booleanHash(subRegion.isRenderingEnabled()));
        hash = mix64(hash ^ booleanHash(subRegion.ignoreEntities()));
        return new SignatureEntry(key, hash);
    }

    private static List<SignatureEntry> snapshotPlacementBoxes(SchematicPlacement placement) {
        List<SignatureEntry> boxes = new ArrayList<>();
        try {
            Map<String, Box> subRegionBoxes = placement.getSubRegionBoxes(RequiredEnabled.PLACEMENT_ENABLED);
            if (subRegionBoxes == null || subRegionBoxes.isEmpty()) return boxes;

            for (Map.Entry<String, Box> entry : subRegionBoxes.entrySet()) {
                Box box = entry.getValue();
                if (box == null) continue;

                String key = safeString(entry.getKey()) + ":" + safeString(box.getName());
                long hash = stringHash(key);
                hash = mix64(hash ^ blockPosHash(box.getPos1()));
                hash = mix64(hash ^ blockPosHash(box.getPos2()));
                hash = mix64(hash ^ enumHash(box.getSelectedCorner()));
                boxes.add(new SignatureEntry(key, hash));
            }
        } catch (Throwable ignored) {
        }

        boxes.sort(Comparator.comparing(SignatureEntry::key));
        return boxes;
    }

    private static String placementKey(SchematicPlacement placement) {
        UUID hashId = placement.getHashId();
        if (hashId != null) return "id:" + hashId;

        Path file = placement.getSchematicFile();
        if (file != null) return "file:" + file.toAbsolutePath().normalize();

        return "name:" + safeString(placement.getName());
    }

    private static long blockPosHash(BlockPos pos) {
        return pos == null ? 0L : mix64(pos.asLong());
    }

    private static long enumHash(Enum<?> value) {
        return value == null ? 0L : mix64(value.ordinal() + 1L);
    }

    private static long booleanHash(boolean value) {
        return value ? 0x9e3779b97f4a7c15L : 0x3c6ef372fe94f82aL;
    }

    private static long stringHash(String value) {
        long hash = 0xcbf29ce484222325L;
        for (int i = 0; i < value.length(); i++) {
            hash ^= value.charAt(i);
            hash *= 0x100000001b3L;
        }
        return mix64(hash);
    }

    private static String safeString(String value) {
        return value == null ? "" : value;
    }

    private static String describeCurrentPlacements() {
        try {
            SchematicPlacementManager manager = DataManager.getSchematicPlacementManager();
            if (manager == null) return "placements=<no manager>";

            List<SchematicPlacement> placements = manager.getAllSchematicsPlacements();
            if (placements == null || placements.isEmpty()) return "placements=0";

            StringBuilder builder = new StringBuilder("placements=").append(placements.size());
            int limit = Math.min(placements.size(), 3);
            for (int i = 0; i < limit; i++) {
                SchematicPlacement placement = placements.get(i);
                if (placement == null) continue;

                builder.append(" [")
                        .append(safeString(placement.getName()))
                        .append(" origin=").append(placement.getOrigin())
                        .append(" enabled=").append(placement.isEnabled())
                        .append(" render=").append(placement.isRenderingEnabled());

                try {
                    Map<String, Box> boxes = placement.getSubRegionBoxes(RequiredEnabled.PLACEMENT_ENABLED);
                    builder.append(" boxes=").append(boxes == null ? 0 : boxes.size());
                    if (boxes != null && !boxes.isEmpty()) {
                        Map.Entry<String, Box> first = boxes.entrySet().iterator().next();
                        Box box = first.getValue();
                        if (box != null) {
                            builder.append(" firstBox=")
                                    .append(first.getKey())
                                    .append(':')
                                    .append(box.getPos1())
                                    .append("..")
                                    .append(box.getPos2());
                        }
                    }
                } catch (Throwable t) {
                    builder.append(" boxes=<error>");
                }

                builder.append(']');
            }
            return builder.toString();
        } catch (Throwable t) {
            return "placements=<error " + t.getClass().getSimpleName() + ">";
        }
    }

    private static long mix64(long value) {
        value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
        value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
        return value ^ (value >>> 31);
    }

    private record SignatureEntry(String key, long hash) {
    }
}

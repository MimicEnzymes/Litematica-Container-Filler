package com.mimicenzymes.litematicafiller.core;

import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;

import java.nio.file.Path;
import java.util.UUID;

public final class SchematicMaterialReplacementContext {
    private static final ThreadLocal<String> ACTIVE_KEY = new ThreadLocal<>();

    private SchematicMaterialReplacementContext() {
    }

    public static String getActiveKey() {
        return ACTIVE_KEY.get();
    }

    public static void push(String schematicKey) {
        if (schematicKey == null || schematicKey.isBlank()) {
            ACTIVE_KEY.remove();
        } else {
            ACTIVE_KEY.set(schematicKey);
        }
    }

    public static void clear() {
        ACTIVE_KEY.remove();
    }

    public static String keyForPlacement(SchematicPlacement placement) {
        if (placement == null) return null;

        UUID hash = placement.getHashId();
        if (hash != null) {
            return "placement:" + hash;
        }

        Path file = placement.getSchematicFile();
        if (file != null) {
            return "placement-file:" + file.toAbsolutePath().normalize() + "@" + placement.getOrigin();
        }

        return "placement-name:" + placement.getName() + "@" + placement.getOrigin();
    }

    public static String keyForSchematic(LitematicaSchematic schematic) {
        if (schematic == null) return null;

        Path file = schematic.getFile();
        if (file != null) {
            return "schematic-file:" + file.toAbsolutePath().normalize();
        }

        try {
            String name = schematic.getMetadata().getName();
            if (name != null && !name.isBlank()) {
                return "schematic-name:" + name;
            }
        } catch (Throwable ignored) {
        }

        return null;
    }
}

package com.mimicenzymes.litematicafiller.core;

import net.minecraft.client.MinecraftClient;
import net.minecraft.registry.RegistryKey;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.concurrent.ConcurrentHashMap;

public class ManualContainerOverrideManager {
    private static final Map<Key, ManualContainerOverrideState> OVERRIDES = new ConcurrentHashMap<>();

    public static ManualContainerOverrideState get(BlockPos pos) {
        Key key = key(pos);
        if (key == null) return ManualContainerOverrideState.AUTO;
        return OVERRIDES.getOrDefault(key, ManualContainerOverrideState.AUTO);
    }

    public static boolean isCompleted(BlockPos pos) {
        return get(pos) == ManualContainerOverrideState.COMPLETED;
    }

    public static boolean isNeedsFill(BlockPos pos) {
        return get(pos) == ManualContainerOverrideState.NEEDS_FILL;
    }

    public static ManualContainerOverrideState cycle(BlockPos pos) {
        Key key = key(pos);
        if (key == null) return ManualContainerOverrideState.AUTO;

        ManualContainerOverrideState next = switch (OVERRIDES.getOrDefault(key, ManualContainerOverrideState.AUTO)) {
            case AUTO -> ManualContainerOverrideState.COMPLETED;
            case COMPLETED -> ManualContainerOverrideState.NEEDS_FILL;
            case NEEDS_FILL -> ManualContainerOverrideState.AUTO;
        };

        if (next == ManualContainerOverrideState.AUTO) {
            OVERRIDES.remove(key);
        } else {
            OVERRIDES.put(key, next);
        }

        return next;
    }

    public static int clearAll() {
        int count = OVERRIDES.size();
        OVERRIDES.clear();
        return count;
    }

    public static Set<BlockPos> getCurrentContextPositions() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null) return Set.of();

        RegistryKey<World> dimension = client.world.getRegistryKey();
        return OVERRIDES.entrySet().stream()
                .filter(entry -> entry.getKey().dimension != null && entry.getKey().dimension.equals(dimension))
                .filter(entry -> entry.getValue() != ManualContainerOverrideState.AUTO)
                .map(entry -> entry.getKey().pos)
                .collect(Collectors.toSet());
    }

    public static void clearForCurrentContext() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null) {
            clearAll();
            return;
        }

        RegistryKey<World> dimension = client.world.getRegistryKey();
        OVERRIDES.keySet().removeIf(key -> key.dimension == null || key.dimension.equals(dimension));
    }

    private static Key key(BlockPos pos) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null || pos == null) return null;
        return new Key(client.world.getRegistryKey(), pos.toImmutable());
    }

    private record Key(RegistryKey<World> dimension, BlockPos pos) {
    }
}

package com.mimicenzymes.litematicafiller.filter;

import com.mimicenzymes.litematicafiller.config.Configs;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.CrafterBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;

import java.util.List;

public class ContainerBlockFilter {
    public static boolean isAllowed(BlockState state) {
        return isAllowedForSchematicFill(state);
    }

    public static boolean isAllowedForSchematicFill(BlockState state) {
        return isAllowedForSchematicFill(state, null, null);
    }

    public static boolean isAllowedForSchematicFill(BlockState state, net.minecraft.world.level.Level world, net.minecraft.core.BlockPos pos) {
        return isContainerLike(state, world, pos) && (!appliesToSchematicFill() || matchesFilter(state));
    }

    public static boolean isAllowedForTools(BlockState state) {
        return isAllowedForTools(state, null, null);
    }

    public static boolean isAllowedForTools(BlockState state, net.minecraft.world.level.Level world, net.minecraft.core.BlockPos pos) {
        return isContainerLike(state, world, pos) && (!appliesToTools() || matchesFilter(state));
    }

    public static boolean isContainerLike(BlockState state, net.minecraft.world.level.Level world, net.minecraft.core.BlockPos pos) {
        if (state == null || state.isAir()) return false;
        if (state.getBlock() instanceof ChestBlock ||
                state.getBlock() instanceof BarrelBlock ||
                state.getBlock() instanceof CrafterBlock ||
                state.is(Blocks.HOPPER) ||
                state.is(Blocks.DISPENSER) ||
                state.is(Blocks.DROPPER) ||
                state.is(Blocks.FURNACE) ||
                state.is(Blocks.BLAST_FURNACE) ||
                state.is(Blocks.SMOKER) ||
                state.is(Blocks.BREWING_STAND) ||
                state.is(Blocks.ENDER_CHEST) ||
                state.getBlock() instanceof net.minecraft.world.level.block.ShulkerBoxBlock) {
            return true;
        }

        if (!state.hasBlockEntity()) return false;
        if (world != null && pos != null) {
            BlockEntity blockEntity = world.getBlockEntity(pos);
            if (blockEntity instanceof net.minecraft.world.Container) {
                return true;
            }
        }

        return looksLikeContainerId(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
    }

    public static boolean isContainerLike(BlockState state, net.minecraft.world.level.Level world) {
        return isContainerLike(state, world, null);
    }

    public static boolean isContainerLike(BlockState state) {
        Minecraft client = Minecraft.getInstance();
        return isContainerLike(state, client == null ? null : client.level, null);
    }

    private static boolean matchesFilter(BlockState state) {
        if (state == null || state.isAir()) return false;
        ContainerFilterMode mode = ContainerFilterMode.DISABLED;
        if (Configs.CONTAINER_FILTER_MODE.getOptionListValue() instanceof ContainerFilterMode configuredMode) {
            mode = configuredMode;
        }
        if (mode == ContainerFilterMode.DISABLED) return true;

        Identifier id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        String blockId = id.toString();
        boolean listed = matchesAny(blockId, Configs.CONTAINER_FILTER_LIST.getStrings());
        return mode == ContainerFilterMode.WHITELIST ? listed : !listed;
    }

    private static boolean appliesToSchematicFill() {
        ContainerFilterScope scope = getScope();
        return scope == ContainerFilterScope.SCHEMATIC_FILL || scope == ContainerFilterScope.BOTH;
    }

    private static boolean appliesToTools() {
        ContainerFilterScope scope = getScope();
        return scope == ContainerFilterScope.TOOLS || scope == ContainerFilterScope.BOTH;
    }

    private static ContainerFilterScope getScope() {
        if (Configs.CONTAINER_FILTER_SCOPE.getOptionListValue() instanceof ContainerFilterScope scope) {
            return scope;
        }
        return ContainerFilterScope.BOTH;
    }

    private static boolean matchesAny(String blockId, List<String> patterns) {
        for (String raw : patterns) {
            if (raw == null) continue;
            String pattern = raw.trim();
            if (pattern.isEmpty()) continue;
            if (matches(blockId, pattern)) return true;
        }
        return false;
    }

    private static boolean matches(String value, String pattern) {
        if ("*".equals(pattern)) return true;
        int wildcard = pattern.indexOf('*');
        if (wildcard < 0) return value.equals(pattern);

        String prefix = pattern.substring(0, wildcard);
        String suffix = pattern.substring(wildcard + 1);
        return value.startsWith(prefix) && value.endsWith(suffix);
    }

    private static boolean looksLikeContainerId(String blockId) {
        return blockId.endsWith("_chest") ||
                blockId.endsWith("_barrel") ||
                blockId.endsWith("_shulker_box") ||
                blockId.endsWith("_furnace") ||
                blockId.contains("chest") ||
                blockId.contains("barrel") ||
                blockId.contains("container") ||
                blockId.contains("storage") ||
                blockId.contains("drawer") ||
                blockId.contains("crate");
    }
}


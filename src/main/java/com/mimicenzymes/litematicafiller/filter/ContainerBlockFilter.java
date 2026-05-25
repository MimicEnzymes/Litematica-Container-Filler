package com.mimicenzymes.litematicafiller.filter;

import com.mimicenzymes.litematicafiller.config.Configs;
import net.minecraft.block.BarrelBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.CrafterBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.util.List;

public class ContainerBlockFilter {
    public static boolean isAllowed(BlockState state) {
        return isAllowedForSchematicFill(state);
    }

    public static boolean isAllowedForSchematicFill(BlockState state) {
        return isAllowedForSchematicFill(state, null, null);
    }

    public static boolean isAllowedForSchematicFill(BlockState state, net.minecraft.world.World world, net.minecraft.util.math.BlockPos pos) {
        return isContainerLike(state, world, pos) && (!appliesToSchematicFill() || matchesFilter(state));
    }

    public static boolean isAllowedForTools(BlockState state) {
        return isAllowedForTools(state, null, null);
    }

    public static boolean isAllowedForTools(BlockState state, net.minecraft.world.World world, net.minecraft.util.math.BlockPos pos) {
        return isContainerLike(state, world, pos) && (!appliesToTools() || matchesFilter(state));
    }

    public static boolean isContainerLike(BlockState state, net.minecraft.world.World world, net.minecraft.util.math.BlockPos pos) {
        if (state == null || state.isAir()) return false;
        if (state.getBlock() instanceof ChestBlock ||
                state.getBlock() instanceof BarrelBlock ||
                state.getBlock() instanceof CrafterBlock ||
                state.isOf(Blocks.HOPPER) ||
                state.isOf(Blocks.DISPENSER) ||
                state.isOf(Blocks.DROPPER) ||
                state.isOf(Blocks.FURNACE) ||
                state.isOf(Blocks.BLAST_FURNACE) ||
                state.isOf(Blocks.SMOKER) ||
                state.isOf(Blocks.BREWING_STAND) ||
                state.isOf(Blocks.ENDER_CHEST) ||
                state.getBlock() instanceof net.minecraft.block.ShulkerBoxBlock) {
            return true;
        }

        if (!state.hasBlockEntity()) return false;
        if (world != null && pos != null) {
            BlockEntity blockEntity = world.getBlockEntity(pos);
            if (blockEntity instanceof net.minecraft.inventory.Inventory) {
                return true;
            }
        }

        return looksLikeContainerId(Registries.BLOCK.getId(state.getBlock()).toString());
    }

    public static boolean isContainerLike(BlockState state, net.minecraft.world.World world) {
        return isContainerLike(state, world, null);
    }

    public static boolean isContainerLike(BlockState state) {
        MinecraftClient client = MinecraftClient.getInstance();
        return isContainerLike(state, client == null ? null : client.world, null);
    }

    private static boolean matchesFilter(BlockState state) {
        if (state == null || state.isAir()) return false;
        ContainerFilterMode mode = ContainerFilterMode.DISABLED;
        if (Configs.CONTAINER_FILTER_MODE.getOptionListValue() instanceof ContainerFilterMode configuredMode) {
            mode = configuredMode;
        }
        if (mode == ContainerFilterMode.DISABLED) return true;

        Identifier id = Registries.BLOCK.getId(state.getBlock());
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

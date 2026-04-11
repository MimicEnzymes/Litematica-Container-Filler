package com.mimicenzymes.litematicafiller.core;

import com.mimicenzymes.litematicafiller.config.Configs;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

public class AreaScanner {
    private static final Map<BlockPos, Long> ATTEMPT_COOLDOWNS = new HashMap<>();

    public static void executeScan(Minecraft mc, boolean isSilentPrinter) {
        if (mc.player == null || mc.level == null) return;

        var schematicWorld = SchematicWorldHandler.getSchematicWorld();
        if (schematicWorld == null) {
            if (!isSilentPrinter) mc.player.sendOverlayMessage(Component.translatable("litematica_container_filler.message.no_schematic_world"));
            return;
        }

        BlockPos center = mc.player.blockPosition();
        int r = Configs.FILL_RADIUS.getIntegerValue();
        boolean syncLayer = Configs.SYNC_LITE_LAYER.getBooleanValue();
        int count = 0;
        long now = System.currentTimeMillis();

        for (int x = -r; x <= r; x++) {
            for (int y = -r; y <= r; y++) {
                for (int z = -r; z <= r; z++) {
                    BlockPos pos = center.offset(x, y, z);

                    if (syncLayer && !fi.dy.masa.litematica.data.DataManager.getRenderLayerRange().isPositionWithinRange(pos)) continue;

                    BlockState state = schematicWorld.getBlockState(pos);
                    if (state.isAir() || !state.hasBlockEntity()) continue;

                    // 统一处理大箱子与Carpet大木桶的坐标归集
                    BlockPos[] halves = LitematicaContainerReader.getDoubleContainerHalves(schematicWorld, pos, state);
                    if (halves != null) {
                        pos = halves[0]; // 永远重定向到主容器坐标
                    }

                    if (isSilentPrinter && ATTEMPT_COOLDOWNS.containsKey(pos) && now - ATTEMPT_COOLDOWNS.get(pos) < 5000) {
                        continue;
                    }

                    Map<Integer, ItemStack> required = LitematicaContainerReader.getRequiredItems(pos, mc.level.registryAccess());

                    boolean isCrafter = state.getBlock() instanceof net.minecraft.world.level.block.CrafterBlock;
                    boolean needsLocking = isCrafter && LitematicaContainerReader.doesCrafterNeedLocking(pos, mc);
                    boolean hasItems = required != null && !required.isEmpty() && !RealContainerCache.isSatisfied(pos, required);

                    if (!hasItems && !needsLocking) continue;

                    AutoFillerStateMachine.getInstance().addTask(pos, required == null ? new HashMap<>() : required);
                    ATTEMPT_COOLDOWNS.put(pos, now);
                    count++;
                }
            }
        }

        if (!isSilentPrinter) {
            if (count > 0) {
                mc.player.sendOverlayMessage(Component.translatable("litematica_container_filler.message.scan_start", count));
            } else {
                mc.player.sendOverlayMessage(Component.translatable("litematica_container_filler.message.no_requirements"));
            }
        }
    }
}
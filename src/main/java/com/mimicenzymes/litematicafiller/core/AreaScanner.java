package com.mimicenzymes.litematicafiller.core;

import com.mimicenzymes.litematicafiller.config.Configs;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class AreaScanner {
    private static final long ATTEMPT_COOLDOWN_MS = 5000L;
    private static final long ATTEMPT_RETENTION_MS = 60000L;
    private static final Map<BlockPos, Long> ATTEMPT_COOLDOWNS = new HashMap<>();

    private static class PendingTask {
        final BlockPos pos;
        final Map<Integer, ItemStack> required;
        final double distSq;

        PendingTask(BlockPos pos, Map<Integer, ItemStack> required, double distSq) {
            this.pos = pos;
            this.required = required;
            this.distSq = distSq;
        }
    }

    public static void executeScan(MinecraftClient mc, boolean isSilentPrinter) {
        if (mc.player == null || mc.world == null) return;

        var schematicWorld = SchematicWorldHandler.getSchematicWorld();
        if (schematicWorld == null) {
            if (!isSilentPrinter) mc.player.sendMessage(Text.translatable("litematica_container_filler.message.no_schematic_world"), true);
            return;
        }

        BlockPos center = mc.player.getBlockPos();
        int r = Configs.FILL_RADIUS.getIntegerValue();
        boolean syncLayer = Configs.SYNC_LITE_LAYER.getBooleanValue();
        long now = System.currentTimeMillis();
        ATTEMPT_COOLDOWNS.entrySet().removeIf(entry -> now - entry.getValue() > ATTEMPT_RETENTION_MS);

        int maxTasks = isSilentPrinter ? 15 : 40;

        List<PendingTask> pendingTasks = new ArrayList<>();
        Set<BlockPos> processedPositions = new HashSet<>();

        double reach = mc.player.getBlockInteractionRange();
        double reachSq = (reach + 0.5) * (reach + 0.5);
        Vec3d eyePos = mc.player.getEyePos();

        if (r == 0) {
            for (BlockPos rawPos : com.mimicenzymes.litematicafiller.render.HighlightScanner.getHighlights().keySet()) {
                collectPendingTask(mc, schematicWorld, center, eyePos, reachSq, now, isSilentPrinter, processedPositions, pendingTasks, rawPos);
            }
        } else {
            for (int x = -r; x <= r; x++) {
                for (int y = -r; y <= r; y++) {
                    for (int z = -r; z <= r; z++) {
                        BlockPos rawPos = center.add(x, y, z);

                        if (syncLayer && !fi.dy.masa.litematica.data.DataManager.getRenderLayerRange().isPositionWithinRange(rawPos)) continue;

                        collectPendingTask(mc, schematicWorld, center, eyePos, reachSq, now, isSilentPrinter, processedPositions, pendingTasks, rawPos);
                    }
                }
            }
        }

        pendingTasks.sort(Comparator.comparingDouble(t -> t.distSq));

        int count = 0;
        for (PendingTask task : pendingTasks) {
            AutoFillerStateMachine.getInstance().addTask(task.pos, task.required);
            ATTEMPT_COOLDOWNS.put(task.pos, now);
            count++;

            if (count >= maxTasks) break;
        }

        if (!isSilentPrinter) {
            if (count > 0) {
                mc.player.sendMessage(Text.translatable("litematica_container_filler.message.scan_start", count), true);
            } else {
                mc.player.sendMessage(Text.translatable("litematica_container_filler.message.no_requirements"), true);
            }
        }
    }

    private static void collectPendingTask(MinecraftClient mc,
                                           net.minecraft.world.World schematicWorld,
                                           BlockPos center,
                                           Vec3d eyePos,
                                           double reachSq,
                                           long now,
                                           boolean isSilentPrinter,
                                           Set<BlockPos> processedPositions,
                                           List<PendingTask> pendingTasks,
                                           BlockPos rawPos) {
        BlockState state = schematicWorld.getBlockState(rawPos);
        if (state == null || state.isAir() || !state.hasBlockEntity()) return;

        BlockPos[] halves = LitematicaContainerReader.getDoubleContainerHalves(schematicWorld, rawPos, state);
        BlockPos taskPos = halves != null ? halves[0] : rawPos;

        if (!processedPositions.add(taskPos)) return;
        if (eyePos.squaredDistanceTo(Vec3d.ofCenter(taskPos)) > reachSq) return;

        Long lastAttempt = ATTEMPT_COOLDOWNS.get(taskPos);
        if (isSilentPrinter && lastAttempt != null && now - lastAttempt < ATTEMPT_COOLDOWN_MS) {
            return;
        }

        Map<Integer, ItemStack> required = LitematicaContainerReader.getRequiredItems(taskPos, mc.world.getRegistryManager());
        boolean isCrafter = state.getBlock() instanceof net.minecraft.block.CrafterBlock;
        boolean needsLocking = isCrafter && LitematicaContainerReader.doesCrafterNeedLocking(taskPos, mc);
        boolean hasItems = required != null && !required.isEmpty() && !RealContainerCache.isSatisfied(taskPos, required);

        if (!hasItems && !needsLocking) return;

        pendingTasks.add(new PendingTask(taskPos, required == null ? new HashMap<>() : required, taskPos.getSquaredDistance(center)));
    }
}

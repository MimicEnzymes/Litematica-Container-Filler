package com.mimicenzymes.litematicafiller.core;

import com.mimicenzymes.litematicafiller.config.Configs;
import com.mimicenzymes.litematicafiller.filter.ContainerBlockFilter;
import com.mimicenzymes.litematicafiller.render.HighlightScanner;
import com.mimicenzymes.litematicafiller.render.HighlightState;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.minecraft.network.chat.Component;

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
    private static final int SILENT_CANDIDATE_BUDGET = 384;
    private static final int PASS_THROUGH_CANDIDATE_BUDGET = 96;
    private static final int MANUAL_CANDIDATE_BUDGET = 2048;
    private static int scanCursor = 0;

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

    public static void executeScan(Minecraft mc, boolean isSilentPrinter) {
        executeScan(mc, isSilentPrinter, false);
    }

    public static void executeScan(Minecraft mc, boolean isSilentPrinter, boolean passThroughScan) {
        if (mc.player == null || mc.level == null) return;

        var schematicWorld = SchematicWorldHandler.getSchematicWorld();
        if (schematicWorld == null) {
            if (!isSilentPrinter) mc.player.sendOverlayMessage(Component.translatable("litematica_container_filler.message.no_schematic_world"));
            return;
        }

        BlockPos center = mc.player.blockPosition();
        int r = Configs.FILL_RADIUS.getIntegerValue();
        boolean syncLayer = Configs.SYNC_LITE_LAYER.getBooleanValue();
        long now = System.currentTimeMillis();
        ATTEMPT_COOLDOWNS.entrySet().removeIf(entry -> now - entry.getValue() > ATTEMPT_RETENTION_MS);

        int maxTasks = passThroughScan ? 4 : (isSilentPrinter ? 15 : 40);

        double reach = mc.player.blockInteractionRange();
        double reachSq = InteractionTargeting.squaredInteractionRange(reach);
        Vec3 eyePos = mc.player.getEyePosition();
        int interactionCandidateRadius = (int) Math.ceil(reach + 2.0);
        int effectiveCandidateRadius = r > 0 ? Math.min(r, interactionCandidateRadius) : interactionCandidateRadius;

        List<PendingTask> pendingTasks = new ArrayList<>();
        Set<BlockPos> processedPositions = new HashSet<>();
        int maxCandidates = passThroughScan ? PASS_THROUGH_CANDIDATE_BUDGET : (isSilentPrinter ? SILENT_CANDIDATE_BUDGET : MANUAL_CANDIDATE_BUDGET);
        collectCandidates(mc, schematicWorld, center, r, effectiveCandidateRadius, maxCandidates, syncLayer,
                eyePos, reachSq, now, isSilentPrinter, passThroughScan, processedPositions, pendingTasks);

        pendingTasks.sort(Comparator.comparingDouble(t -> t.distSq));

        int count = 0;
        for (PendingTask task : pendingTasks) {
            if (AutoFillerStateMachine.getInstance().addTask(task.pos, task.required, passThroughScan)) {
                ATTEMPT_COOLDOWNS.put(task.pos, now);
                count++;
            }

            if (count >= maxTasks) break;
        }

        if (!isSilentPrinter) {
            if (count > 0) {
                mc.player.sendOverlayMessage(Component.translatable("litematica_container_filler.message.scan_start", count));
            } else {
                mc.player.sendOverlayMessage(Component.translatable("litematica_container_filler.message.no_requirements"));
            }
        }
    }

    private static void collectCandidates(Minecraft mc,
                                          net.minecraft.world.level.Level schematicWorld,
                                          BlockPos center,
                                          int fillRadius,
                                          int candidateRadius,
                                          int maxCandidates,
                                          boolean syncLayer,
                                          Vec3 eyePos,
                                          double reachSq,
                                          long now,
                                          boolean isSilentPrinter,
                                          boolean passThroughScan,
                                          Set<BlockPos> processedPositions,
                                          List<PendingTask> pendingTasks) {
        double candidateRadiusSq = (double) candidateRadius * candidateRadius;
        double fillRadiusSq = (double) fillRadius * fillRadius;
        int processedCandidates = 0;

        for (BlockPos pos : HighlightScanner.getHighlights().keySet()) {
            if (pos.distSqr(center) > candidateRadiusSq) continue;
            if (!collectCandidate(mc, schematicWorld, center, fillRadius, fillRadiusSq, syncLayer, eyePos, reachSq, now,
                    isSilentPrinter, passThroughScan, processedPositions, pendingTasks, pos)) {
                continue;
            }
            if (++processedCandidates >= maxCandidates) return;
        }

        HighlightScanner.ContainerSnapshot snapshot = HighlightScanner.getNearbySchematicContainersSnapshot(center, candidateRadius, scanCursor, maxCandidates);
        scanCursor = snapshot.nextCursor();
        for (BlockPos pos : snapshot.positions()) {
            if (collectCandidate(mc, schematicWorld, center, fillRadius, fillRadiusSq, syncLayer, eyePos, reachSq, now,
                    isSilentPrinter, passThroughScan, processedPositions, pendingTasks, pos)) {
                if (++processedCandidates >= maxCandidates) return;
            }
        }
    }

    private static boolean collectCandidate(Minecraft mc,
                                            net.minecraft.world.level.Level schematicWorld,
                                            BlockPos center,
                                            int fillRadius,
                                            double fillRadiusSq,
                                            boolean syncLayer,
                                            Vec3 eyePos,
                                            double reachSq,
                                            long now,
                                            boolean isSilentPrinter,
                                            boolean passThroughScan,
                                            Set<BlockPos> processedPositions,
                                            List<PendingTask> pendingTasks,
                                            BlockPos rawPos) {
        if (fillRadius > 0 && rawPos.distSqr(center) > fillRadiusSq) return false;
        if (syncLayer && !fi.dy.masa.litematica.data.DataManager.getRenderLayerRange().isPositionWithinRange(rawPos)) return false;

        collectPendingTask(mc, schematicWorld, center, eyePos, reachSq, now, isSilentPrinter, passThroughScan, processedPositions, pendingTasks, rawPos);
        return true;
    }

    private static void collectPendingTask(Minecraft mc,
                                           net.minecraft.world.level.Level schematicWorld,
                                           BlockPos center,
                                           Vec3 eyePos,
                                           double reachSq,
                                           long now,
                                           boolean isSilentPrinter,
                                           boolean passThroughScan,
                                           Set<BlockPos> processedPositions,
                                           List<PendingTask> pendingTasks,
                                           BlockPos rawPos) {
        BlockState state = schematicWorld.getBlockState(rawPos);
        if (state == null || state.isAir() || !state.hasBlockEntity()) return;
        if (!ContainerBlockFilter.isAllowedForSchematicFill(state, schematicWorld, rawPos)) return;

        BlockPos[] halves = LitematicaContainerReader.getDoubleContainerHalves(schematicWorld, rawPos, state);
        BlockPos taskPos = halves != null ? halves[0] : rawPos;

        if (!processedPositions.add(taskPos)) return;
        if (ManualContainerOverrideManager.isCompleted(taskPos)) return;
        HighlightState highlightState = HighlightScanner.getHighlights().get(taskPos);
        if (highlightState == HighlightState.SATISFIED || highlightState == HighlightState.MANUAL_COMPLETED) return;
        if (InteractionTargeting.squaredDistanceToBlock(eyePos, taskPos) > reachSq) return;
        if (isLoadedRealContainerMissing(mc, taskPos, halves)) return;

        Long lastAttempt = ATTEMPT_COOLDOWNS.get(taskPos);
        long cooldownMs = passThroughScan ? 1200L : ATTEMPT_COOLDOWN_MS;
        if (isSilentPrinter && lastAttempt != null && now - lastAttempt < cooldownMs) {
            return;
        }

        Map<Integer, ItemStack> required = HighlightScanner.getCachedSchematicRequirement(taskPos, mc);
        boolean isCrafter = state.getBlock() instanceof net.minecraft.world.level.block.CrafterBlock;
        boolean needsLocking = isCrafter && LitematicaContainerReader.doesCrafterNeedLocking(taskPos, mc);
        boolean manualNeedsFill = ManualContainerOverrideManager.isNeedsFill(taskPos);
        boolean hasItems = required != null && !required.isEmpty() && (manualNeedsFill || !RealContainerCache.isSatisfied(taskPos, required));

        if (!hasItems && !needsLocking) return;

        pendingTasks.add(new PendingTask(taskPos, required == null ? new HashMap<>() : required, taskPos.distSqr(center)));
    }

    private static boolean isLoadedRealContainerMissing(Minecraft mc, BlockPos taskPos, BlockPos[] schematicHalves) {
        if (schematicHalves == null) {
            return mc.level.isLoaded(taskPos) &&
                    !ContainerBlockFilter.isAllowedForSchematicFill(mc.level.getBlockState(taskPos), mc.level, taskPos);
        }

        for (BlockPos half : schematicHalves) {
            if (mc.level.isLoaded(half) &&
                    !ContainerBlockFilter.isAllowedForSchematicFill(mc.level.getBlockState(half), mc.level, half)) {
                return true;
            }
        }

        return false;
    }

    public static void clearAttemptCooldown(BlockPos pos) {
        if (pos != null) ATTEMPT_COOLDOWNS.remove(pos);
    }
}

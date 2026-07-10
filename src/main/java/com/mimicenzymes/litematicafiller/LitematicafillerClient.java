package com.mimicenzymes.litematicafiller;

import com.mimicenzymes.litematicafiller.config.Configs;
import com.mimicenzymes.litematicafiller.gui.GuiConfigs;
import com.mimicenzymes.litematicafiller.core.*;
import com.mimicenzymes.litematicafiller.network.ClickPacketRateLimiter;
import com.mimicenzymes.litematicafiller.network.ServuxSyncHandler;
import com.mimicenzymes.litematicafiller.network.TakeItOutCompat;
import com.mimicenzymes.litematicafiller.tool.ContainerToolStateMachine;

import fi.dy.masa.malilib.event.InitializationHandler;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceKey;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;

import java.util.UUID;

public class LitematicafillerClient implements ClientModInitializer {
    private static boolean isGuiAutoRegistered = false;
    private static int workerTickTimer = 0;
    private static ClientLevel lastWorld = null;
    private static LocalPlayer lastPlayer = null;
    private static ResourceKey<Level> lastDimension = null;
    private static UUID lastPlayerUuid = null;
    private static boolean lastPlayerAlive = false;

    @Override
    public void onInitializeClient() {
        ServuxSyncHandler.registerPayloads();
        TakeItOutCompat.registerPayload();

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (!isGuiAutoRegistered) {
                boolean isTitleScreen = client.gui.screen() != null && client.gui.screen().getClass().getSimpleName().equals("TitleScreen");
                boolean isInWorld = client.player != null;
                if (isTitleScreen || isInWorld) {
                    try { new GuiConfigs(null); } catch (Exception e) {}
                    isGuiAutoRegistered = true;
                }
            }

            if (!com.mimicenzymes.litematicafiller.config.Configs.ENABLE_MOD.getBooleanValue()) {
                stopActiveWorkForDisabledMod(client);
                ClickPacketRateLimiter.reset();
                updateFillProtectionSnapshot(client);
                return;
            }

            handleFillStateProtection(client);

            if (client.level != null) {
                AutoFillerStateMachine filler = AutoFillerStateMachine.getInstance();
                ContainerToolStateMachine tool = ContainerToolStateMachine.getInstance();
                boolean highlightEnabled = Configs.HIGHLIGHT_CONTAINERS.getBooleanValue();
                boolean workEnabled = Configs.WORKING_STATE.getBooleanValue();
                boolean fillerActive = filler.isWorking() || workEnabled;
                boolean toolActive = tool.isWorking() || Configs.TOOL_ENABLED.getBooleanValue();
                boolean needsContainerData = RealContainerCache.hasActiveConsumers();

                if (Configs.RATE_LIMIT_CLICK_PACKETS.getBooleanValue() || ClickPacketRateLimiter.hasPendingPackets()) {
                    ClickPacketRateLimiter.tick(client);
                }
                if (handleClickPacketOverflow(client, filler, tool)) {
                    updateFillProtectionSnapshot(client);
                    return;
                }
                if (fillerActive || !filler.isIdle()) {
                    filler.tick(client);
                } else {
                    ClickPacketRateLimiter.setOperationActive(false);
                }
                if (toolActive) {
                    tool.tick(client);
                }
                if (handleClickPacketOverflow(client, filler, tool)) {
                    updateFillProtectionSnapshot(client);
                    return;
                }
                if (needsContainerData) {
                    LitematicaChangeListener.tick(client);
                    RealContainerCache.tick(client);
                }
                if (highlightEnabled) {
                    ContainerHighlighter.tick(client);
                }

                boolean passThroughScan = isPlayerMovingFast(client);
                if (workEnabled && (filler.canQueueMoreTasks() || passThroughScan)) {
                    workerTickTimer++;
                    int scanInterval = getWorkerScanInterval(client, filler);
                    if (workerTickTimer >= scanInterval) {
                        workerTickTimer = 0;
                        AreaScanner.executeScan(client, true, passThroughScan);
                    }
                } else {
                    workerTickTimer = 0;
                }
            }
        });

        LevelRenderEvents.AFTER_OPAQUE_TERRAIN.register(context -> {
            if (com.mimicenzymes.litematicafiller.config.Configs.ENABLE_MOD.getBooleanValue()
                    && (Configs.HIGHLIGHT_CONTAINERS.getBooleanValue() || com.mimicenzymes.litematicafiller.render.HighlightScanner.hasMaterialFocus())) {
                ContainerHighlighter.onRender(context);
            }
        });
        InitializationHandler.getInstance().registerInitializationHandler(new InitHandler());
    }

    private static boolean handleClickPacketOverflow(net.minecraft.client.Minecraft client,
                                                     AutoFillerStateMachine filler,
                                                     ContainerToolStateMachine tool) {
        if (!ClickPacketRateLimiter.consumeOverflowed()) {
            return false;
        }

        Configs.WORKING_STATE.setBooleanValue(false);
        filler.emergencyStop(client);
        if (tool.isWorking()) {
            tool.stopForDisabledMod(client);
        }
        ClickPacketRateLimiter.reset();
        workerTickTimer = 0;
        if (client.player != null) {
            client.player.sendOverlayMessage(Component.translatable("litematica_container_filler.message.click_packet_queue_overflow"));
        }
        return true;
    }

    private static void stopActiveWorkForDisabledMod(net.minecraft.client.Minecraft client) {
        AutoFillerStateMachine filler = AutoFillerStateMachine.getInstance();
        if (Configs.WORKING_STATE.getBooleanValue() || !filler.isIdle()) {
            Configs.WORKING_STATE.setBooleanValue(false);
            filler.emergencyStop(client);
            workerTickTimer = 0;
        }

        ContainerToolStateMachine tool = ContainerToolStateMachine.getInstance();
        if (tool.isWorking()) {
            tool.stopForDisabledMod(client);
        }
    }

    private static boolean isPlayerMovingFast(net.minecraft.client.Minecraft client) {
        if (client.player == null) return false;
        double vx = client.player.getDeltaMovement().x;
        double vz = client.player.getDeltaMovement().z;
        return vx * vx + vz * vz > 0.04D;
    }

    private static int getWorkerScanInterval(net.minecraft.client.Minecraft client, AutoFillerStateMachine filler) {
        if (isPlayerMovingFast(client)) return 2;
        return filler.isIdle() ? 5 : 12;
    }

    private static void handleFillStateProtection(net.minecraft.client.Minecraft client) {
        boolean shouldStop = false;
        if (Configs.ENABLE_FILL_STATE_PROTECTION.getBooleanValue() && Configs.WORKING_STATE.getBooleanValue()) {
            if (client.level == null || client.player == null) {
                shouldStop = lastWorld != null || lastPlayerUuid != null;
            } else {
                ResourceKey<Level> currentDimension = client.level.dimension();
                UUID currentPlayerUuid = client.player.getUUID();
                boolean currentPlayerAlive = client.player.isAlive();
                shouldStop =
                        (lastWorld != null && client.level != lastWorld) ||
                        (lastPlayer != null && client.player != lastPlayer) ||
                        (lastDimension != null && !lastDimension.equals(currentDimension)) ||
                        (lastPlayerUuid != null && !lastPlayerUuid.equals(currentPlayerUuid)) ||
                        (lastPlayerAlive && !currentPlayerAlive);
            }
        }

        if (shouldStop) {
            Configs.WORKING_STATE.setBooleanValue(false);
            AutoFillerStateMachine.getInstance().emergencyStop(client);
            if (client.player != null) {
                client.player.sendOverlayMessage(Component.translatable("litematica_container_filler.message.fill_state_protected"));
            }
            workerTickTimer = 0;
        }

        updateFillProtectionSnapshot(client);
    }

    private static void updateFillProtectionSnapshot(net.minecraft.client.Minecraft client) {
        lastWorld = client.level;
        lastPlayer = client.player;
        lastDimension = client.level == null ? null : client.level.dimension();
        lastPlayerUuid = client.player == null ? null : client.player.getUUID();
        lastPlayerAlive = client.player != null && client.player.isAlive();
    }
}

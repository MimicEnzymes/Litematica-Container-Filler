package com.mimicenzymes.litematicafiller;

import com.mimicenzymes.litematicafiller.config.Configs;
import com.mimicenzymes.litematicafiller.gui.GuiConfigs;
import com.mimicenzymes.litematicafiller.core.*;
import com.mimicenzymes.litematicafiller.tool.ContainerToolStateMachine;
import com.mimicenzymes.litematicafiller.network.ClickPacketRateLimiter;
import com.mimicenzymes.litematicafiller.network.ServuxSyncHandler;
import com.mimicenzymes.litematicafiller.network.TakeItOutCompat;

import fi.dy.masa.malilib.event.InitializationHandler;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.registry.RegistryKey;
import net.minecraft.text.Text;
import net.minecraft.world.World;

import java.util.UUID;

public class LitematicaContainerFillerClient implements ClientModInitializer {
    private static boolean isGuiAutoRegistered = false;
    private static int workerTickTimer = 0;
    private static ClientWorld lastWorld = null;
    private static ClientPlayerEntity lastPlayer = null;
    private static RegistryKey<World> lastDimension = null;
    private static UUID lastPlayerUuid = null;
    private static boolean lastPlayerAlive = false;

    @Override
    public void onInitializeClient() {
        ServuxSyncHandler.registerPayloads();
        TakeItOutCompat.registerPayload();

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (!isGuiAutoRegistered) {
                boolean isTitleScreen = client.currentScreen != null && client.currentScreen.getClass().getSimpleName().equals("TitleScreen");
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

            if (client.world != null) {
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

        WorldRenderEvents.AFTER_ENTITIES.register(context -> {
            if (Configs.ENABLE_MOD.getBooleanValue() &&
                    (Configs.HIGHLIGHT_CONTAINERS.getBooleanValue() || com.mimicenzymes.litematicafiller.render.HighlightScanner.hasMaterialFocus())) {
                ContainerHighlighter.onRender(context);
            }
        });
        InitializationHandler.getInstance().registerInitializationHandler(new InitHandler());
    }

    private static boolean handleClickPacketOverflow(MinecraftClient client, AutoFillerStateMachine filler, ContainerToolStateMachine tool) {
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
            client.player.sendMessage(Text.translatable("litematica_container_filler.message.click_packet_queue_overflow"), true);
        }
        return true;
    }

    private static void stopActiveWorkForDisabledMod(MinecraftClient client) {
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

    private static boolean isPlayerMovingFast(net.minecraft.client.MinecraftClient client) {
        if (client.player == null) return false;
        double vx = client.player.getVelocity().x;
        double vz = client.player.getVelocity().z;
        return vx * vx + vz * vz > 0.04D;
    }

    private static int getWorkerScanInterval(net.minecraft.client.MinecraftClient client, AutoFillerStateMachine filler) {
        if (isPlayerMovingFast(client)) return 2;
        return filler.isIdle() ? 5 : 12;
    }

    private static void handleFillStateProtection(MinecraftClient client) {
        boolean shouldStop = false;
        if (Configs.ENABLE_FILL_STATE_PROTECTION.getBooleanValue() && Configs.WORKING_STATE.getBooleanValue()) {
            if (client.world == null || client.player == null) {
                shouldStop = lastWorld != null || lastPlayerUuid != null;
            } else {
                RegistryKey<World> currentDimension = client.world.getRegistryKey();
                UUID currentPlayerUuid = client.player.getUuid();
                boolean currentPlayerAlive = client.player.isAlive();
                shouldStop =
                        (lastWorld != null && client.world != lastWorld) ||
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
                client.player.sendMessage(Text.translatable("litematica_container_filler.message.fill_state_protected"), true);
            }
            workerTickTimer = 0;
        }

        updateFillProtectionSnapshot(client);
    }

    private static void updateFillProtectionSnapshot(MinecraftClient client) {
        lastWorld = client.world;
        lastPlayer = client.player;
        lastDimension = client.world == null ? null : client.world.getRegistryKey();
        lastPlayerUuid = client.player == null ? null : client.player.getUuid();
        lastPlayerAlive = client.player != null && client.player.isAlive();
    }
}

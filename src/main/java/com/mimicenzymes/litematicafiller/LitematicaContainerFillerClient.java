package com.mimicenzymes.litematicafiller;

import com.mimicenzymes.litematicafiller.config.Configs;
import com.mimicenzymes.litematicafiller.config.GuiConfigs;
import com.mimicenzymes.litematicafiller.core.*;
import com.mimicenzymes.litematicafiller.network.ServuxSyncHandler;
import com.mimicenzymes.litematicafiller.network.TakeItOutCompat;

import fi.dy.masa.malilib.event.InitializationHandler;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;

public class LitematicaContainerFillerClient implements ClientModInitializer {
    private static boolean isGuiAutoRegistered = false;
    private static int workerTickTimer = 0;

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
                return;
            }

            if (client.world != null) {
                AutoFillerStateMachine.getInstance().tick(client);
                LitematicaChangeListener.tick(client);
                RealContainerCache.tick(client);

                ContainerHighlighter.tick(client);

                if (Configs.WORKING_STATE.getBooleanValue() && AutoFillerStateMachine.getInstance().isIdle()) {
                    workerTickTimer++;
                    int scanInterval = isPlayerMovingFast(client) ? 16 : 6;
                    if (workerTickTimer >= scanInterval) {
                        workerTickTimer = 0;
                        AreaScanner.executeScan(client, true);
                    }
                } else {
                    workerTickTimer = 0;
                }
            }
        });

        WorldRenderEvents.AFTER_ENTITIES.register(context -> {
            if (com.mimicenzymes.litematicafiller.config.Configs.ENABLE_MOD.getBooleanValue()) {
                ContainerHighlighter.onRender(context);
            }
        });
        InitializationHandler.getInstance().registerInitializationHandler(new InitHandler());
    }

    private static boolean isPlayerMovingFast(net.minecraft.client.MinecraftClient client) {
        if (client.player == null) return false;
        double vx = client.player.getVelocity().x;
        double vz = client.player.getVelocity().z;
        return vx * vx + vz * vz > 0.04D;
    }
}

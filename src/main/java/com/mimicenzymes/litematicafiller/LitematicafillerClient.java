package com.mimicenzymes.litematicafiller;

import com.mimicenzymes.litematicafiller.config.ConfigHandler;
import com.mimicenzymes.litematicafiller.config.Configs;
import com.mimicenzymes.litematicafiller.config.GuiConfigs;
import com.mimicenzymes.litematicafiller.core.*;
import com.mimicenzymes.litematicafiller.input.InputHandler;
import com.mimicenzymes.litematicafiller.network.ServuxSyncHandler;

import fi.dy.masa.malilib.config.ConfigManager;
import fi.dy.masa.malilib.event.InitializationHandler;
import fi.dy.masa.malilib.event.InputEventHandler;
import fi.dy.masa.malilib.interfaces.IInitializationHandler;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import java.util.HashMap;
import java.util.Map;

public class LitematicafillerClient implements ClientModInitializer {
    public static final String MOD_ID = "litematica_container_filler";

    private static boolean isGuiAutoRegistered = false;
    private static int printerTickTimer = 0;
    //防鬼畜
    private static final Map<BlockPos, Long> CROSSHAIR_COOLDOWNS = new HashMap<>();

    @Override
    public void onInitializeClient() {
        ServuxSyncHandler.registerPayloads();

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (!isGuiAutoRegistered) {
                boolean isTitleScreen = client.screen != null && client.screen.getClass().getSimpleName().equals("TitleScreen");
                boolean isInWorld = client.player != null;
                if (isTitleScreen || isInWorld) {
                    try { new GuiConfigs(null); } catch (Exception e) {}
                    isGuiAutoRegistered = true;
                }
            }

            if (!com.mimicenzymes.litematicafiller.config.Configs.ENABLE_MOD.getBooleanValue()) {
                return;
            }

            if (client.level != null) {
                AutoFillerStateMachine.getInstance().tick(client);
                LitematicaChangeListener.tick(client);
                RealContainerCache.tick(client);

                ContainerHighlighter.tick(client);

                if (Configs.CONTINUOUS_FILL.getBooleanValue() && AutoFillerStateMachine.getInstance().isIdle()) {
                    printerTickTimer++;
                    if (printerTickTimer >= 10) {
                        printerTickTimer = 0;

                        if (Configs.AREA_MODE.getBooleanValue()) {
                            AreaScanner.executeScan(client, true);
                        } else {
                            if (client.hitResult != null && client.hitResult.getType() == HitResult.Type.BLOCK) {
                                BlockHitResult bhr = (BlockHitResult) client.hitResult;
                                BlockPos pos = bhr.getBlockPos();
                                long now = System.currentTimeMillis();

                                if (!CROSSHAIR_COOLDOWNS.containsKey(pos) || now - CROSSHAIR_COOLDOWNS.get(pos) >= 5000) {
                                    var schWorld = fi.dy.masa.litematica.world.SchematicWorldHandler.getSchematicWorld();

                                    if (schWorld != null && schWorld.getBlockState(pos).hasBlockEntity()) {
                                        Map<Integer, ItemStack> required = LitematicaContainerReader.getRequiredItems(pos, client.level.registryAccess());

                                        boolean isSatisfied = RealContainerCache.isSatisfied(pos, required);
                                        boolean isCrafter = client.level.getBlockState(pos).getBlock() instanceof net.minecraft.world.level.block.CrafterBlock;
                                        boolean needsLocking = isCrafter && LitematicaContainerReader.doesCrafterNeedLocking(pos, client);

                                        if (!isSatisfied || needsLocking) {
                                            AutoFillerStateMachine.getInstance().addTask(pos, required == null ? new java.util.HashMap<>() : required);
                                            CROSSHAIR_COOLDOWNS.put(pos, now);
                                        }
                                    }
                                }
                            }
                        }
                    }
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

    private static class InitHandler implements IInitializationHandler {
        @Override
        public void registerModHandlers() {
            ConfigHandler configHandler = new ConfigHandler();
            configHandler.load();
            ConfigManager.getInstance().registerConfigHandler(LitematicafillerClient.MOD_ID, configHandler);
            InputEventHandler.getKeybindManager().registerKeybindProvider(InputHandler.getInstance());
        }
    }
}
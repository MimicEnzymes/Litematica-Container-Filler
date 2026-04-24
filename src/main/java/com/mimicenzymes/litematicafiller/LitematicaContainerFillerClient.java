package com.mimicenzymes.litematicafiller;

import com.mimicenzymes.litematicafiller.config.Configs;
import com.mimicenzymes.litematicafiller.config.GuiConfigs;
import com.mimicenzymes.litematicafiller.core.*;
import com.mimicenzymes.litematicafiller.network.ServuxSyncHandler;

import fi.dy.masa.malilib.event.InitializationHandler;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.item.ItemStack;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;

import java.util.HashMap;
import java.util.Map;

public class LitematicaContainerFillerClient implements ClientModInitializer {
    private static boolean isGuiAutoRegistered = false;
    private static int printerTickTimer = 0;
    private static final long CROSSHAIR_COOLDOWN_MS = 5000L;
    private static final Map<BlockPos, Long> CROSSHAIR_COOLDOWNS = new HashMap<>();

    @Override
    public void onInitializeClient() {
        ServuxSyncHandler.registerPayloads();

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

                if (Configs.CONTINUOUS_FILL.getBooleanValue() && AutoFillerStateMachine.getInstance().isIdle()) {
                    printerTickTimer++;
                    if (printerTickTimer >= 10) {
                        printerTickTimer = 0;

                        if (Configs.AREA_MODE.getBooleanValue()) {
                            AreaScanner.executeScan(client, true);
                        } else {
                            if (client.crosshairTarget != null && client.crosshairTarget.getType() == HitResult.Type.BLOCK) {
                                BlockHitResult bhr = (BlockHitResult) client.crosshairTarget;
                                BlockPos pos = bhr.getBlockPos();
                                long now = System.currentTimeMillis();

                                if (!CROSSHAIR_COOLDOWNS.containsKey(pos) || now - CROSSHAIR_COOLDOWNS.get(pos) >= CROSSHAIR_COOLDOWN_MS) {
                                    var schWorld = fi.dy.masa.litematica.world.SchematicWorldHandler.getSchematicWorld();

                                    if (schWorld != null && schWorld.getBlockState(pos).hasBlockEntity()) {
                                        Map<Integer, ItemStack> required = LitematicaContainerReader.getRequiredItems(pos, client.world.getRegistryManager());

                                        boolean isSatisfied = RealContainerCache.isSatisfied(pos, required);
                                        boolean isCrafter = client.world.getBlockState(pos).getBlock() instanceof net.minecraft.block.CrafterBlock;
                                        boolean needsLocking = isCrafter && LitematicaContainerReader.doesCrafterNeedLocking(pos, client);

                                        if (!isSatisfied || needsLocking) {
                                            AutoFillerStateMachine.getInstance().addTask(pos, required == null ? new java.util.HashMap<>() : required);
                                            // 清理过期条目后再插入，避免长时间运行时 Map 无上限增长
                                            CROSSHAIR_COOLDOWNS.entrySet().removeIf(e -> now - e.getValue() >= CROSSHAIR_COOLDOWN_MS);
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
}
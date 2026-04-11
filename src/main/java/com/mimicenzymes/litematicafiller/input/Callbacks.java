package com.mimicenzymes.litematicafiller.input;

import com.mimicenzymes.litematicafiller.config.Configs;
import com.mimicenzymes.litematicafiller.config.GuiConfigs;
import com.mimicenzymes.litematicafiller.config.Hotkeys;
import com.mimicenzymes.litematicafiller.core.AreaScanner;
import com.mimicenzymes.litematicafiller.core.AutoFillerStateMachine;
import com.mimicenzymes.litematicafiller.core.RealContainerCache;
import com.mimicenzymes.litematicafiller.core.LitematicaContainerReader;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.hotkeys.IHotkeyCallback;
import fi.dy.masa.malilib.hotkeys.IKeybind;
import fi.dy.masa.malilib.hotkeys.KeyAction;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

public class Callbacks implements IHotkeyCallback {
    private static final Callbacks INSTANCE = new Callbacks();
    public static Callbacks getInstance() { return INSTANCE; }

    @Override
    public boolean onKeyAction(KeyAction action, IKeybind key) {
        Minecraft mc = Minecraft.getInstance();
        if (action != KeyAction.PRESS) return false;

        if (key == Hotkeys.OPEN_CONFIG_GUI.getKeybind()) {
            GuiBase.openGui(new GuiConfigs(null));
            return true;
        }

        if (!Configs.ENABLE_MOD.getBooleanValue()) {
            return false;
        }

        if (mc.player == null) return false;

        if (key == Hotkeys.FILL_CONTAINER.getKeybind()) {
            AutoFillerStateMachine.getInstance().clearBlacklist();
            executeFill(mc);
            return true;
        } else if (key == Hotkeys.TOGGLE_CONTINUOUS.getKeybind()) {
            boolean state = !Configs.CONTINUOUS_FILL.getBooleanValue();
            Configs.CONTINUOUS_FILL.setBooleanValue(state);
            mc.player.displayClientMessage(Component.translatable(state ? "litematica_container_filler.message.continuous_on" : "litematica_container_filler.message.continuous_off"), true);
            return true;
        } else if (key == Hotkeys.TOGGLE_MODE.getKeybind()) {
            boolean state = !Configs.AREA_MODE.getBooleanValue();
            Configs.AREA_MODE.setBooleanValue(state);
            mc.player.displayClientMessage(Component.translatable(state ? "litematica_container_filler.message.mode_area" : "litematica_container_filler.message.mode_single"), true);
            return true;
        }

        return false;
    }

    private void executeFill(Minecraft mc) {
        if (Configs.AREA_MODE.getBooleanValue()) {
            AreaScanner.executeScan(mc, false);
        } else {
            if (mc.hitResult != null && mc.hitResult.getType() == HitResult.Type.BLOCK) {
                BlockHitResult bhr = (BlockHitResult) mc.hitResult;
                BlockPos pos = bhr.getBlockPos();

                var schWorld = fi.dy.masa.litematica.world.SchematicWorldHandler.getSchematicWorld();
                if (schWorld == null || !schWorld.getBlockState(pos).hasBlockEntity()) {
                    mc.player.displayClientMessage(Component.translatable("litematica_container_filler.message.no_requirements"), true);
                    return;
                }

                Map<Integer, ItemStack> required = LitematicaContainerReader.getRequiredItems(pos, mc.level.registryAccess());
                boolean isCrafter = schWorld.getBlockState(pos).getBlock() instanceof net.minecraft.world.level.block.CrafterBlock;

                boolean needsLocking = isCrafter && LitematicaContainerReader.doesCrafterNeedLocking(pos, mc);

                if (required != null || needsLocking) {
                    Map<Integer, ItemStack> taskReq = required == null ? new HashMap<>() : required;

                    if (RealContainerCache.isSatisfied(pos, taskReq) && !needsLocking) {
                        mc.player.displayClientMessage(Component.translatable("litematica_container_filler.message.already_satisfied"), true);
                        return;
                    }

                    AutoFillerStateMachine.getInstance().addTask(pos, taskReq);
                }
            } else {
                mc.player.displayClientMessage(Component.translatable("litematica_container_filler.message.target_invalid"), true);
            }
        }
    }
}
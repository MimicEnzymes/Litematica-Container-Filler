package com.mimicenzymes.litematicafiller.input;

import com.mimicenzymes.litematicafiller.config.Configs;
import com.mimicenzymes.litematicafiller.config.GuiConfigs;
import com.mimicenzymes.litematicafiller.config.Hotkeys;
import com.mimicenzymes.litematicafiller.core.AutoFillerStateMachine;
import com.mimicenzymes.litematicafiller.core.RealContainerCache;
import com.mimicenzymes.litematicafiller.core.LitematicaContainerReader;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.hotkeys.IHotkeyCallback;
import fi.dy.masa.malilib.hotkeys.IKeybind;
import fi.dy.masa.malilib.hotkeys.KeyAction;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.text.Text;
import java.util.HashMap;
import java.util.Map;

public class Callbacks implements IHotkeyCallback {
    private static final Callbacks INSTANCE = new Callbacks();
    public static Callbacks getInstance() { return INSTANCE; }

    @Override
    public boolean onKeyAction(KeyAction action, IKeybind key) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (action != KeyAction.PRESS) return false;

        if (key == Hotkeys.OPEN_CONFIG_GUI.getKeybind()) {
            GuiBase.openGui(new GuiConfigs(null));
            return true;
        }

        if (key == Configs.WORKING_STATE.getKeybind()) {
            Configs.WORKING_STATE.toggleBooleanValue();
            String messageKey = Configs.WORKING_STATE.getBooleanValue()
                    ? "litematica_container_filler.message.continuous_on"
                    : "litematica_container_filler.message.continuous_off";
            if (mc.player != null) {
                mc.player.sendMessage(Text.translatable(messageKey), true);
            }
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
        }

        return false;
    }

    private void executeFill(MinecraftClient mc) {
        if (mc.crosshairTarget != null && mc.crosshairTarget.getType() == HitResult.Type.BLOCK) {
            BlockHitResult bhr = (BlockHitResult) mc.crosshairTarget;
            BlockPos pos = bhr.getBlockPos();

            var schWorld = fi.dy.masa.litematica.world.SchematicWorldHandler.getSchematicWorld();
            if (schWorld == null || !schWorld.getBlockState(pos).hasBlockEntity()) {
                mc.player.sendMessage(Text.translatable("litematica_container_filler.message.no_requirements"), true);
                return;
            }

            Map<Integer, ItemStack> required = LitematicaContainerReader.getRequiredItems(pos, mc.world.getRegistryManager());
            boolean isCrafter = schWorld.getBlockState(pos).getBlock() instanceof net.minecraft.block.CrafterBlock;

            boolean needsLocking = isCrafter && LitematicaContainerReader.doesCrafterNeedLocking(pos, mc);

            if (required != null || needsLocking) {
                Map<Integer, ItemStack> taskReq = required == null ? new HashMap<>() : required;

                RealContainerCache.remove(pos);

                AutoFillerStateMachine.getInstance().addManualTask(pos, taskReq);
            }
        } else {
            mc.player.sendMessage(Text.translatable("litematica_container_filler.message.target_invalid"), true);
        }
    }
}

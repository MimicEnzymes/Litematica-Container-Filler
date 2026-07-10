package com.mimicenzymes.litematicafiller.input;

import com.mimicenzymes.litematicafiller.config.Configs;
import com.mimicenzymes.litematicafiller.gui.GuiConfigs;
import com.mimicenzymes.litematicafiller.config.Hotkeys;
import com.mimicenzymes.litematicafiller.core.AutoFillerStateMachine;
import com.mimicenzymes.litematicafiller.core.ManualContainerOverrideManager;
import com.mimicenzymes.litematicafiller.core.ManualContainerOverrideState;
import com.mimicenzymes.litematicafiller.tool.ContainerToolStateMachine;
import com.mimicenzymes.litematicafiller.filter.ContainerBlockFilter;
import com.mimicenzymes.litematicafiller.core.RealContainerCache;
import com.mimicenzymes.litematicafiller.core.LitematicaContainerReader;
import com.mimicenzymes.litematicafiller.render.HighlightScanner;
import fi.dy.masa.malilib.config.options.ConfigBooleanHotkeyed;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.hotkeys.IHotkeyCallback;
import fi.dy.masa.malilib.hotkeys.IKeybind;
import fi.dy.masa.malilib.hotkeys.KeyAction;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import java.util.HashMap;
import java.util.Map;

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

        ConfigBooleanHotkeyed toggleConfig = getBooleanHotkeyConfig(key);
        if (toggleConfig != null) {
            toggleBooleanConfig(mc, toggleConfig);
            return true;
        }

        if (!Configs.ENABLE_MOD.getBooleanValue()) {
            return false;
        }

        if (key == Configs.WORKING_STATE.getKeybind()) {
            Configs.WORKING_STATE.toggleBooleanValue();
            String messageKey = Configs.WORKING_STATE.getBooleanValue()
                    ? "litematica_container_filler.message.continuous_on"
                    : "litematica_container_filler.message.continuous_off";
            if (mc.player != null) {
                mc.player.sendOverlayMessage(Component.translatable(messageKey));
            }
            return true;
        }

        if (mc.player == null) return false;

        if (key == Hotkeys.FILL_CONTAINER.getKeybind()) {
            AutoFillerStateMachine.getInstance().clearBlacklist();
            executeFill(mc);
            return true;
        }

        if (key == Hotkeys.TOOL_TRIGGER.getKeybind()) {
            ContainerToolStateMachine.getInstance().triggerCurrent(mc);
            return true;
        }

        if (key == Hotkeys.TOOL_SWITCH_MODE.getKeybind()) {
            ContainerToolStateMachine.getInstance().switchMode(mc);
            return true;
        }

        if (key == Hotkeys.TOOL_SWITCH_PREVIOUS.getKeybind()) {
            ContainerToolStateMachine.getInstance().switchMode(mc, false);
            return true;
        }

        if (key == Hotkeys.TOOL_CLOSE_ALL.getKeybind()) {
            AutoFillerStateMachine.getInstance().emergencyStop(mc);
            Configs.WORKING_STATE.setBooleanValue(false);
            ContainerToolStateMachine.getInstance().closeAll(mc);
            return true;
        }

        if (key == Hotkeys.CYCLE_MANUAL_OVERRIDE.getKeybind()) {
            cycleManualOverride(mc);
            return true;
        }

        if (key == Hotkeys.CLEAR_MANUAL_OVERRIDES.getKeybind()) {
            int count = ManualContainerOverrideManager.clearAll();
            HighlightScanner.onManualOverridesCleared();
            mc.player.sendOverlayMessage(Component.translatable("litematica_container_filler.message.manual_override_cleared", count));
            return true;
        }

        return false;
    }

    private ConfigBooleanHotkeyed getBooleanHotkeyConfig(IKeybind key) {
        for (ConfigBooleanHotkeyed config : Configs.BOOLEAN_HOTKEY_OPTIONS) {
            if (key == config.getKeybind()) {
                return config;
            }
        }
        return null;
    }

    private void toggleBooleanConfig(Minecraft mc, ConfigBooleanHotkeyed config) {
        config.toggleBooleanValue();
        Configs.saveToFile();
        if (mc.player != null) {
            String value = Component.translatable(config.getBooleanValue()
                    ? "litematica_container_filler.gui.value.on"
                    : "litematica_container_filler.gui.value.off").getString();
            mc.player.sendOverlayMessage(Component.literal(Component.translatable(config.getName()).getString() + ": " + value));
        }
    }

    private void executeFill(Minecraft mc) {
        if (mc.hitResult != null && mc.hitResult.getType() == HitResult.Type.BLOCK) {
            BlockHitResult bhr = (BlockHitResult) mc.hitResult;
            BlockPos pos = bhr.getBlockPos();

            var schWorld = fi.dy.masa.litematica.world.SchematicWorldHandler.getSchematicWorld();
            if (schWorld == null || !schWorld.getBlockState(pos).hasBlockEntity()) {
                mc.player.sendOverlayMessage(Component.translatable("litematica_container_filler.message.no_requirements"));
                return;
            }
            if (!ContainerBlockFilter.isAllowedForSchematicFill(schWorld.getBlockState(pos), schWorld, pos)) {
                mc.player.sendOverlayMessage(Component.translatable("litematica_container_filler.message.container_filtered"));
                return;
            }
            if (!ContainerBlockFilter.isAllowedForSchematicFill(mc.level.getBlockState(pos), mc.level, pos)) {
                mc.player.sendOverlayMessage(Component.translatable("litematica_container_filler.message.target_invalid"));
                RealContainerCache.remove(pos);
                return;
            }

            Map<Integer, ItemStack> required = LitematicaContainerReader.getRequiredItems(pos, mc.level.registryAccess());
            boolean isCrafter = schWorld.getBlockState(pos).getBlock() instanceof net.minecraft.world.level.block.CrafterBlock;

            boolean needsLocking = isCrafter && LitematicaContainerReader.doesCrafterNeedLocking(pos, mc);

            if (required != null || needsLocking) {
                Map<Integer, ItemStack> taskReq = required == null ? new HashMap<>() : required;

                RealContainerCache.remove(pos);

                AutoFillerStateMachine.getInstance().addManualTask(pos, taskReq);
            }
        } else {
            mc.player.sendOverlayMessage(Component.translatable("litematica_container_filler.message.target_invalid"));
        }
    }

    private void cycleManualOverride(Minecraft mc) {
        if (mc.hitResult == null || mc.hitResult.getType() != HitResult.Type.BLOCK) {
            mc.player.sendOverlayMessage(Component.translatable("litematica_container_filler.message.target_invalid"));
            return;
        }

            BlockPos pos = ((BlockHitResult) mc.hitResult).getBlockPos();
        var schWorld = fi.dy.masa.litematica.world.SchematicWorldHandler.getSchematicWorld();
        boolean schematicContainer = schWorld != null && ContainerBlockFilter.isAllowedForSchematicFill(schWorld.getBlockState(pos), schWorld, pos);
        boolean realContainer = mc.level != null && ContainerBlockFilter.isAllowedForSchematicFill(mc.level.getBlockState(pos), mc.level, pos);
        if (!schematicContainer && !realContainer) {
            mc.player.sendOverlayMessage(Component.translatable("litematica_container_filler.message.no_requirements"));
            return;
        }
        pos = normalizeManualOverridePos(mc, schWorld, pos, schematicContainer);

        ManualContainerOverrideState state = ManualContainerOverrideManager.cycle(pos);
        HighlightScanner.onManualOverrideChanged(pos, state);
        String stateKey = switch (state) {
            case COMPLETED -> "litematica_container_filler.message.manual_override_completed";
            case NEEDS_FILL -> "litematica_container_filler.message.manual_override_needs_fill";
            case AUTO -> "litematica_container_filler.message.manual_override_auto";
        };
        mc.player.sendOverlayMessage(Component.translatable(stateKey));
    }

    private BlockPos normalizeManualOverridePos(Minecraft mc, net.minecraft.world.level.Level schematicWorld, BlockPos pos, boolean schematicContainer) {
        if (schematicContainer && schematicWorld != null) {
            var state = LitematicaContainerReader.getSchematicBlockState(pos, schematicWorld);
            BlockPos[] halves = LitematicaContainerReader.getRenderContainerHalvesForSchematic(pos, state, schematicWorld);
            if (halves != null) return halves[0];
        }

        if (mc.level != null) {
            var state = mc.level.getBlockState(pos);
            BlockPos[] halves = LitematicaContainerReader.getRenderContainerHalves(mc.level, pos, state);
            if (halves != null) return halves[0];
        }

        return pos.immutable();
    }

}

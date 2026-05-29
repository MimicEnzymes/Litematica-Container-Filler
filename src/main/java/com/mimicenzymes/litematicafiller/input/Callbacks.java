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
                mc.player.sendMessage(Text.translatable(messageKey), true);
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
            mc.player.sendMessage(Text.translatable("litematica_container_filler.message.manual_override_cleared", count), true);
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

    private void toggleBooleanConfig(MinecraftClient mc, ConfigBooleanHotkeyed config) {
        config.toggleBooleanValue();
        Configs.saveToFile();
        if (mc.player != null) {
            String value = Text.translatable(config.getBooleanValue()
                    ? "litematica_container_filler.gui.value.on"
                    : "litematica_container_filler.gui.value.off").getString();
            mc.player.sendMessage(Text.literal(Text.translatable(config.getName()).getString() + ": " + value), true);
        }
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
            if (!ContainerBlockFilter.isAllowedForSchematicFill(schWorld.getBlockState(pos), schWorld, pos)) {
                mc.player.sendMessage(Text.translatable("litematica_container_filler.message.container_filtered"), true);
                return;
            }
            if (!ContainerBlockFilter.isAllowedForSchematicFill(mc.world.getBlockState(pos), mc.world, pos)) {
                mc.player.sendMessage(Text.translatable("litematica_container_filler.message.target_invalid"), true);
                RealContainerCache.remove(pos);
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

    private void cycleManualOverride(MinecraftClient mc) {
        if (mc.crosshairTarget == null || mc.crosshairTarget.getType() != HitResult.Type.BLOCK) {
            mc.player.sendMessage(Text.translatable("litematica_container_filler.message.target_invalid"), true);
            return;
        }

        BlockPos pos = ((BlockHitResult) mc.crosshairTarget).getBlockPos();
        var schWorld = fi.dy.masa.litematica.world.SchematicWorldHandler.getSchematicWorld();
        boolean schematicContainer = schWorld != null && ContainerBlockFilter.isAllowedForSchematicFill(schWorld.getBlockState(pos), schWorld, pos);
        boolean realContainer = mc.world != null && ContainerBlockFilter.isAllowedForSchematicFill(mc.world.getBlockState(pos), mc.world, pos);
        if (!schematicContainer && !realContainer) {
            mc.player.sendMessage(Text.translatable("litematica_container_filler.message.no_requirements"), true);
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
        mc.player.sendMessage(Text.translatable(stateKey), true);
    }

    private BlockPos normalizeManualOverridePos(MinecraftClient mc, net.minecraft.world.World schematicWorld, BlockPos pos, boolean schematicContainer) {
        if (schematicContainer && schematicWorld != null) {
            var state = schematicWorld.getBlockState(pos);
            BlockPos[] halves = LitematicaContainerReader.getRenderContainerHalves(schematicWorld, pos, state);
            if (halves != null) return halves[0];
        }

        if (mc.world != null) {
            var state = mc.world.getBlockState(pos);
            BlockPos[] halves = LitematicaContainerReader.getRenderContainerHalves(mc.world, pos, state);
            if (halves != null) return halves[0];
        }

        return pos.toImmutable();
    }

}

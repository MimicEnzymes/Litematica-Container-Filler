package com.mimicenzymes.litematicafiller.config;

import com.google.common.collect.ImmutableList;
import fi.dy.masa.malilib.config.options.ConfigHotkey;
import java.util.List;

public class Hotkeys {
    public static final ConfigHotkey OPEN_CONFIG_GUI    = new ConfigHotkey("litematica_container_filler.hotkey.name.openConfigGui", "L,C", "litematica_container_filler.hotkey.comment.openConfigGui");
    public static final ConfigHotkey FILL_CONTAINER     = new ConfigHotkey("litematica_container_filler.hotkey.name.fillContainer", "V", "litematica_container_filler.hotkey.comment.fillContainer");
    public static final ConfigHotkey TOOL_TRIGGER        = new ConfigHotkey("litematica_container_filler.hotkey.name.toolTrigger", "", "litematica_container_filler.hotkey.comment.toolTrigger");
    public static final ConfigHotkey TOOL_SWITCH_MODE    = new ConfigHotkey("litematica_container_filler.hotkey.name.toolSwitchMode", "", "litematica_container_filler.hotkey.comment.toolSwitchMode");
    public static final ConfigHotkey TOOL_SWITCH_PREVIOUS = new ConfigHotkey("litematica_container_filler.hotkey.name.toolSwitchPrevious", "", "litematica_container_filler.hotkey.comment.toolSwitchPrevious");
    public static final ConfigHotkey TOOL_CLOSE_ALL      = new ConfigHotkey("litematica_container_filler.hotkey.name.toolCloseAll", "", "litematica_container_filler.hotkey.comment.toolCloseAll");
    public static final ConfigHotkey CYCLE_MANUAL_OVERRIDE = new ConfigHotkey("litematica_container_filler.hotkey.name.cycleManualOverride", "", "litematica_container_filler.hotkey.comment.cycleManualOverride");
    public static final ConfigHotkey CLEAR_MANUAL_OVERRIDES = new ConfigHotkey("litematica_container_filler.hotkey.name.clearManualOverrides", "", "litematica_container_filler.hotkey.comment.clearManualOverrides");

    public static final List<ConfigHotkey> HOTKEY_LIST = ImmutableList.of(
            OPEN_CONFIG_GUI,
            FILL_CONTAINER,
            TOOL_TRIGGER,
            TOOL_SWITCH_MODE,
            TOOL_SWITCH_PREVIOUS,
            TOOL_CLOSE_ALL,
            CYCLE_MANUAL_OVERRIDE,
            CLEAR_MANUAL_OVERRIDES
    );
}

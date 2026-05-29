package com.mimicenzymes.litematicafiller.input;

import com.mimicenzymes.litematicafiller.config.Hotkeys;
import com.mimicenzymes.litematicafiller.config.Configs;
import fi.dy.masa.malilib.config.options.ConfigBooleanHotkeyed;
import fi.dy.masa.malilib.hotkeys.IHotkey;
import fi.dy.masa.malilib.hotkeys.IKeybindManager;
import fi.dy.masa.malilib.hotkeys.IKeybindProvider;
import fi.dy.masa.malilib.hotkeys.IKeyboardInputHandler;

public class InputHandler implements IKeybindProvider, IKeyboardInputHandler {
    private static final InputHandler INSTANCE = new InputHandler();
    public static InputHandler getInstance() { return INSTANCE; }

    private InputHandler() {
        Hotkeys.OPEN_CONFIG_GUI.getKeybind().setCallback(Callbacks.getInstance());
        Hotkeys.FILL_CONTAINER.getKeybind().setCallback(Callbacks.getInstance());
        Hotkeys.TOOL_TRIGGER.getKeybind().setCallback(Callbacks.getInstance());
        Hotkeys.TOOL_SWITCH_MODE.getKeybind().setCallback(Callbacks.getInstance());
        Hotkeys.TOOL_SWITCH_PREVIOUS.getKeybind().setCallback(Callbacks.getInstance());
        Hotkeys.TOOL_CLOSE_ALL.getKeybind().setCallback(Callbacks.getInstance());
        Hotkeys.CYCLE_MANUAL_OVERRIDE.getKeybind().setCallback(Callbacks.getInstance());
        Hotkeys.CLEAR_MANUAL_OVERRIDES.getKeybind().setCallback(Callbacks.getInstance());
    }

    @Override
    public void addKeysToMap(IKeybindManager manager) {
        for (IHotkey hotkey : Hotkeys.HOTKEY_LIST) {
            manager.addKeybindToMap(hotkey.getKeybind());
        }
        Configs.WORKING_STATE.getKeybind().setCallback(Callbacks.getInstance());
        manager.addKeybindToMap(Configs.WORKING_STATE.getKeybind());
        for (ConfigBooleanHotkeyed config : Configs.BOOLEAN_HOTKEY_OPTIONS) {
            config.getKeybind().setCallback(Callbacks.getInstance());
            manager.addKeybindToMap(config.getKeybind());
        }
    }

    @Override
    public void addHotkeys(IKeybindManager manager) {}
}

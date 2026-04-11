package com.mimicenzymes.litematicafiller.config;

import com.mimicenzymes.litematicafiller.input.InputHandler;
import fi.dy.masa.malilib.config.ConfigUtils;
import fi.dy.masa.malilib.config.IConfigHandler;
import fi.dy.masa.malilib.event.InputEventHandler;
import fi.dy.masa.malilib.util.JsonUtils;
import net.fabricmc.loader.api.FabricLoader;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.File;

public class ConfigHandler implements IConfigHandler {
    private static final String CONFIG_FILE_NAME = "litematica_container_filler.json";

    @Override
    public void load() {
        File file = new File(FabricLoader.getInstance().getConfigDir().toFile(), CONFIG_FILE_NAME);
        if (file.exists() && file.canRead()) {
            JsonElement element = JsonUtils.parseJsonFile(file.toPath());
            if (element != null && element.isJsonObject()) {
                JsonObject root = element.getAsJsonObject();
                ConfigUtils.readConfigBase(root, "Features", Configs.OPTIONS);
                ConfigUtils.readConfigBase(root, "Hotkeys", Hotkeys.HOTKEY_LIST);
            }
        }
    }

    @Override
    public void save() {
        File dir = FabricLoader.getInstance().getConfigDir().toFile();
        if ((dir.exists() && dir.isDirectory()) || dir.mkdirs()) {
            JsonObject root = new JsonObject();
            ConfigUtils.writeConfigBase(root, "Features", Configs.OPTIONS);
            ConfigUtils.writeConfigBase(root, "Hotkeys", Hotkeys.HOTKEY_LIST);
            JsonUtils.writeJsonToFile(root, new File(dir, CONFIG_FILE_NAME).toPath());
        }

        InputHandler.getInstance().addKeysToMap(InputEventHandler.getKeybindManager());
    }
}
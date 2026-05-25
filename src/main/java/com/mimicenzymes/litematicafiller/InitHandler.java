package com.mimicenzymes.litematicafiller;

import com.mimicenzymes.litematicafiller.config.Configs;
import com.mimicenzymes.litematicafiller.input.InputHandler;
import fi.dy.masa.malilib.config.ConfigManager;
import fi.dy.masa.malilib.event.InputEventHandler;
import fi.dy.masa.malilib.interfaces.IInitializationHandler;

public class InitHandler implements IInitializationHandler {

    @Override
    public void registerModHandlers() {
        Configs configs = new Configs();
        Configs.init();
        ConfigManager.getInstance().registerConfigHandler(Reference.MOD_ID, configs);
    }
}

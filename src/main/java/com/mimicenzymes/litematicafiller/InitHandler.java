package com.mimicenzymes.litematicafiller;

import com.mimicenzymes.litematicafiller.config.Configs;
import fi.dy.masa.malilib.interfaces.IInitializationHandler;

public class InitHandler implements IInitializationHandler {

    @Override
    public void registerModHandlers() {
        Configs.init();
    }
}

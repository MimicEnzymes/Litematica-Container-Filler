package com.mimicenzymes.litematicafiller.dependency;

import net.fabricmc.loader.api.FabricLoader;

public class DependencyChecker {
    public static final boolean HAS_QUICK_SHULKER = FabricLoader.getInstance().isModLoaded("quickshulker");
}

package com.mimicenzymes.litematicafiller.tool;

import com.mimicenzymes.litematicafiller.config.Configs;
import com.mimicenzymes.litematicafiller.config.QuickShulkerOpenMode;
import com.mimicenzymes.litematicafiller.dependency.DependencyChecker;
import fi.dy.masa.malilib.config.IConfigOptionListEntry;
import fi.dy.masa.malilib.util.StringUtils;

public enum ContainerToolMode implements IConfigOptionListEntry {
    CLEAR("clear", "litematica_container_filler.config.option.containerToolMode.clear"),
    FILL_FULL("fill_full", "litematica_container_filler.config.option.containerToolMode.fillFull"),
    COPY("copy", "litematica_container_filler.config.option.containerToolMode.copy"),
    PACK("pack", "litematica_container_filler.config.option.containerToolMode.pack");

    private final String configString;
    private final String translationKey;

    ContainerToolMode(String configString, String translationKey) {
        this.configString = configString;
        this.translationKey = translationKey;
    }

    @Override
    public String getStringValue() {
        return this.configString;
    }

    @Override
    public String getDisplayName() {
        return StringUtils.translate(this.translationKey);
    }

    @Override
    public IConfigOptionListEntry cycle(boolean forward) {
        ContainerToolMode[] values = values();
        int index = this.ordinal();

        for (int i = 0; i < values.length; i++) {
            index += forward ? 1 : -1;
            if (index < 0) {
                index = values.length - 1;
            } else if (index >= values.length) {
                index = 0;
            }

            if (values[index].isAvailable()) {
                return values[index];
            }
        }

        return CLEAR;
    }

    @Override
    public IConfigOptionListEntry fromString(String value) {
        for (ContainerToolMode mode : values()) {
            if (mode.configString.equalsIgnoreCase(value)) {
                return mode;
            }
        }
        return CLEAR;
    }

    public boolean isAvailable() {
        return this != PACK || isPackingAvailable();
    }

    public static boolean isPackingAvailable() {
        if (!Configs.ENABLE_QS_EXTRACTION.getBooleanValue()) {
            return false;
        }
        if (DependencyChecker.HAS_QUICK_SHULKER) {
            return true;
        }
        return Configs.QUICK_SHULKER_OPEN_MODE.getOptionListValue() == QuickShulkerOpenMode.SIMULATE_CLICK;
    }
}


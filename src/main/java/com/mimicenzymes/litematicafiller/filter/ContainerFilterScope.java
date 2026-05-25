package com.mimicenzymes.litematicafiller.filter;

import fi.dy.masa.malilib.config.IConfigOptionListEntry;
import fi.dy.masa.malilib.util.StringUtils;

public enum ContainerFilterScope implements IConfigOptionListEntry {
    SCHEMATIC_FILL("schematic_fill", "litematica_container_filler.config.option.containerFilterScope.schematicFill"),
    TOOLS("tools", "litematica_container_filler.config.option.containerFilterScope.tools"),
    BOTH("both", "litematica_container_filler.config.option.containerFilterScope.both");

    private final String configString;
    private final String translationKey;

    ContainerFilterScope(String configString, String translationKey) {
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
        ContainerFilterScope[] values = values();
        int index = this.ordinal() + (forward ? 1 : -1);
        if (index < 0) {
            index = values.length - 1;
        } else if (index >= values.length) {
            index = 0;
        }
        return values[index];
    }

    @Override
    public IConfigOptionListEntry fromString(String value) {
        for (ContainerFilterScope scope : values()) {
            if (scope.configString.equalsIgnoreCase(value)) {
                return scope;
            }
        }
        return BOTH;
    }
}


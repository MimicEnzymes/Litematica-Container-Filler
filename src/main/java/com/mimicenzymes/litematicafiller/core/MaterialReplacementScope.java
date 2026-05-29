package com.mimicenzymes.litematicafiller.core;

public enum MaterialReplacementScope {
    SCHEMATIC,
    GLOBAL;

    public MaterialReplacementScope next() {
        return this == SCHEMATIC ? GLOBAL : SCHEMATIC;
    }
}


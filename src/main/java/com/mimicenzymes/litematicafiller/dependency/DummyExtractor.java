package com.mimicenzymes.litematicafiller.dependency;

public class DummyExtractor implements IShulkerExtractor {
    @Override
    public boolean requestOpenShulker(int playerSlotIndex) {
        // Dependency is not installed, so reject the request.
        return false;
    }
}

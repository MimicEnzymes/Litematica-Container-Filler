package com.mimicenzymes.litematicafiller.dependency;

public class DummyExtractor implements IShulkerExtractor {
    @Override
    public boolean requestOpenShulker(int playerSlotIndex) {
        // 娌℃湁瀹夎鍓嶇疆锛岀洿鎺ユ嫆缁濊姹?
        return false; 
    }
}

package com.mimicenzymes.litematicafiller.dependency;

import fi.dy.masa.litematica.world.SchematicWorldHandler;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Map;

public class TechUtilsDeceiver {

    public static void forceTechUtilsUpdate() {
        Minecraft.getInstance().execute(() -> {
            try {
                boolean refreshed = false;
                for (Method m : SchematicWorldHandler.class.getMethods()) {
                    String name = m.getName().toLowerCase();
                    if (name.equals("createschematicworld") || name.equals("recreateschematicworld") || name.equals("reloadschematicworld")) {
                        if (m.getParameterCount() == 0) {
                            m.invoke(null); refreshed = true; break;
                        } else if (m.getParameterCount() == 1 && m.getParameterTypes()[0] == boolean.class) {
                            m.invoke(null, false); refreshed = true; break;
                        }
                    }
                }
                if (FabricLoader.getInstance().isModLoaded("techutils")) {
                    String[] techUtilsTargets = {
                            "me.velizarbg.techutils.client.litematica.SchematicVerifier",
                            "me.velizarbg.techutils.client.litematica.InventoryScreenOverlay",
                            "me.velizarbg.techutils.client.litematica.LitematicaInventoryFeature",
                            "me.velizarbg.techutils.feature.litematica.LitematicaInventoryFeature",
                            "me.velizarbg.techutils.feature.litematica.InventoryScreenOverlay",
                            "me.velizarbg.techutils.litematica.Verifier",
                            "me.velizarbg.techutils.litematica.InventoryVerifier",
                            "me.velizarbg.techutils.litematica.ExpectedInventory"
                    };

                    for (String clsName : techUtilsTargets) {
                        try {
                            Class<?> clazz = Class.forName(clsName);
                            for (Method m : clazz.getDeclaredMethods()) {
                                if (m.getParameterCount() == 0 && (m.getName().toLowerCase().contains("clear") || m.getName().toLowerCase().contains("reset"))) {
                                    m.setAccessible(true);
                                    m.invoke(null);
                                }
                            }

                            for (Field f : clazz.getDeclaredFields()) {
                                f.setAccessible(true);
                                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                                    Object staticVal = f.get(null);
                                    if (staticVal instanceof Map) ((Map<?,?>) staticVal).clear();
                                    else if (staticVal instanceof Collection) ((Collection<?>) staticVal).clear();
                                    else if (staticVal != null && clazz.isInstance(staticVal)) {
                                        for (Field instF : clazz.getDeclaredFields()) {
                                            instF.setAccessible(true);
                                            Object instVal = instF.get(staticVal);
                                            if (instVal instanceof Map) ((Map<?,?>) instVal).clear();
                                            else if (instVal instanceof Collection) ((Collection<?>) instVal).clear();
                                        }
                                    }
                                }
                            }
                        } catch (Throwable ignored) {}
                    }
                }

            } catch (Exception e) {
            }
        });
    }
}

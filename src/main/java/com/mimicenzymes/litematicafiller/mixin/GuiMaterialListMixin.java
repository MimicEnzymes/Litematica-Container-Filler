package com.mimicenzymes.litematicafiller.mixin;

import com.mimicenzymes.litematicafiller.config.Configs;
import com.mimicenzymes.litematicafiller.materials.FillMaterialCalculator;
import fi.dy.masa.litematica.gui.GuiMaterialList;
import fi.dy.masa.litematica.materials.MaterialListBase;
import fi.dy.masa.litematica.materials.MaterialListEntry;
import fi.dy.masa.litematica.materials.IMaterialList;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.util.StringUtils;
import net.minecraft.client.MinecraftClient;
import com.google.common.collect.ImmutableList;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

@Mixin(value = GuiMaterialList.class, remap = false)
public abstract class GuiMaterialListMixin extends GuiBase {

    @Shadow @Final private MaterialListBase materialList;

    @Unique private static java.lang.reflect.Field mimic_materialListAllField = null;
    @Unique private static boolean mimic_reflectionInit = false;

    @Unique private boolean mimic_isMonitorRunning = false;
    @Unique private boolean mimic_needsCalculation = true;
    @Unique private List<MaterialListEntry> mimic_cachedVanillaList = null;
    @Unique private ImmutableList<MaterialListEntry> mimic_lastInjectedRef = null;
    @Unique private boolean mimic_isInjecting = false;

    @Inject(method = "initGui", at = @At("RETURN"))
    private void onInitGui(CallbackInfo ci) {
        if (!Configs.ENABLE_MOD.getBooleanValue()) return;

        mimic_addToggleButton();

        if (mimic_isInjecting) return;

        mimic_needsCalculation = true;

        if (FillMaterialCalculator.listMode != 0) {
            mimic_injectSilently();
        }

        if (!mimic_isMonitorRunning) {
            mimic_isMonitorRunning = true;
            Thread monitor = new Thread(() -> {
                int tickCount = 0;
                while (MinecraftClient.getInstance().currentScreen == this) {
                    try {
                        Thread.sleep(50);
                        tickCount++;

                        if (FillMaterialCalculator.listMode != 0) {
                            final boolean forceRefresh = (tickCount % 10 == 0) && FillMaterialCalculator.hasMissingData;

                            MinecraftClient.getInstance().execute(() -> {
                                if (MinecraftClient.getInstance().currentScreen == this) {
                                    if (forceRefresh) mimic_needsCalculation = true;
                                    mimic_injectSilently();
                                }
                            });
                        }
                    } catch (Exception e) {}
                }
                mimic_isMonitorRunning = false;
            });
            monitor.setDaemon(true);
            monitor.setName("LitematicaFiller-InjectionWatchdog");
            monitor.start();
        }
    }

    @Unique
    private void mimic_addToggleButton() {
        String text = StringUtils.translate("litematica_container_filler.gui.button.mode_blocks_only");
        if (FillMaterialCalculator.listMode == 1) text = StringUtils.translate("litematica_container_filler.gui.button.mode_containers_only");
        else if (FillMaterialCalculator.listMode == 2) text = StringUtils.translate("litematica_container_filler.gui.button.mode_both");

        int maxX = 10;
        int targetY = 26;

        try {
            java.util.List<?> buttons = null;
            for (java.lang.reflect.Field f : GuiBase.class.getDeclaredFields()) {
                if (f.getType() == java.util.List.class && f.getName().toLowerCase().contains("button")) {
                    f.setAccessible(true);
                    buttons = (java.util.List<?>) f.get(this);
                    break;
                }
            }
            if (buttons != null) {
                for (Object btnObj : buttons) {
                    if (btnObj instanceof fi.dy.masa.malilib.gui.button.ButtonBase btn) {
                        if (btn.getY() < 50) {
                            int rightEdge = btn.getX() + btn.getWidth();
                            if (rightEdge > maxX) {
                                maxX = rightEdge;
                                targetY = btn.getY();
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {}

        ButtonGeneric toggleBtn = new ButtonGeneric(maxX + 1, targetY, 80, 20, text);

        this.addButton(toggleBtn, (button, mouseButton) -> {
            FillMaterialCalculator.listMode = (FillMaterialCalculator.listMode + 1) % 3;
            FillMaterialCalculator.isFillMode = (FillMaterialCalculator.listMode != 0);
            mimic_needsCalculation = true;
            mimic_lastInjectedRef = null;

            mimic_injectViaApi();
        });
    }

    @Unique
    private void mimic_injectViaApi() {
        try {
            if (!(materialList instanceof IMaterialList iMatList)) return;
            if (mimic_cachedVanillaList == null) {
                ImmutableList<MaterialListEntry> current = mimic_readMaterialListAll();
                if (current != null) mimic_cachedVanillaList = new ArrayList<>(current);
            }
            if (mimic_cachedVanillaList == null) return;

            FillMaterialCalculator.calculate(this, true, mimic_cachedVanillaList);
            mimic_needsCalculation = false;

            List<MaterialListEntry> targetList = mimic_buildTargetList();

            mimic_isInjecting = true;
            try {
                iMatList.setMaterialListEntries(targetList);
            } finally {
                mimic_isInjecting = false;
            }

            ImmutableList<MaterialListEntry> newRef = mimic_readMaterialListAll();
            mimic_lastInjectedRef = newRef;

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Unique
    @SuppressWarnings("unchecked")
    private void mimic_injectSilently() {
        try {
            ImmutableList<MaterialListEntry> currentRef = mimic_readMaterialListAll();
            if (currentRef == null) return;

            if (currentRef != mimic_lastInjectedRef) {
                mimic_cachedVanillaList = new ArrayList<>(currentRef);
                mimic_needsCalculation = true;
            }

            if (mimic_cachedVanillaList == null) return;

            if (mimic_needsCalculation) {
                FillMaterialCalculator.calculate(this, true, mimic_cachedVanillaList);
                mimic_needsCalculation = false;
            } else if (!FillMaterialCalculator.hasMissingData) {
                return;
            }

            List<MaterialListEntry> targetList = mimic_buildTargetList();

            int scroll = mimic_getScrollPosition();

            mimic_writeMaterialListAll(ImmutableList.copyOf(targetList));

            materialList.refreshPreFilteredList();
            materialList.updateCounts();

            mimic_lastInjectedRef = mimic_readMaterialListAll();

            mimic_refreshWidget();

            mimic_setScrollPosition(scroll);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Unique
    private List<MaterialListEntry> mimic_buildTargetList() {
        if (FillMaterialCalculator.listMode == 1) {
            return FillMaterialCalculator.getCustomMaterialList(materialList);
        } else if (FillMaterialCalculator.listMode == 2) {
            return FillMaterialCalculator.mergeLists(
                    mimic_cachedVanillaList,
                    FillMaterialCalculator.getCustomMaterialList(materialList));
        } else {
            return new ArrayList<>(mimic_cachedVanillaList);
        }
    }

    @Unique
    private static void mimic_ensureReflection() {
        if (mimic_reflectionInit) return;
        mimic_reflectionInit = true;
        try {
            mimic_materialListAllField = MaterialListBase.class.getDeclaredField("materialListAll");
            mimic_materialListAllField.setAccessible(true);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Unique
    @SuppressWarnings("unchecked")
    private ImmutableList<MaterialListEntry> mimic_readMaterialListAll() {
        mimic_ensureReflection();
        if (mimic_materialListAllField == null) return null;
        try {
            return (ImmutableList<MaterialListEntry>) mimic_materialListAllField.get(materialList);
        } catch (Exception e) {
            return null;
        }
    }

    @Unique
    private void mimic_writeMaterialListAll(ImmutableList<MaterialListEntry> list) {
        mimic_ensureReflection();
        if (mimic_materialListAllField == null) return;
        try {
            mimic_materialListAllField.set(materialList, list);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Unique
    private void mimic_refreshWidget() {
        try {
            Object widget = mimic_getWidget();
            if (widget != null) {
                widget.getClass().getMethod("refreshEntries").invoke(widget);
            }
        } catch (Exception ignored) {}
    }

    @Unique
    private Object mimic_getWidget() {
        try {
            Class<?> curr = this.getClass();
            while (curr != null) {
                for (java.lang.reflect.Field f : curr.getDeclaredFields()) {
                    if (f.getName().equals("m_widget") || f.getName().equals("widget")) {
                        f.setAccessible(true);
                        return f.get(this);
                    }
                }
                curr = curr.getSuperclass();
            }
        } catch (Exception ignored) {}
        return null;
    }

    @Unique
    private int mimic_getScrollPosition() {
        try {
            Object widget = mimic_getWidget();
            if (widget != null) {
                Object scrollbar = widget.getClass().getMethod("getScrollbar").invoke(widget);
                return (int) scrollbar.getClass().getMethod("getValue").invoke(scrollbar);
            }
        } catch (Exception ignored) {}
        return 0;
    }

    @Unique
    private void mimic_setScrollPosition(int scroll) {
        if (scroll <= 0) return;
        try {
            Object widget = mimic_getWidget();
            if (widget != null) {
                Object scrollbar = widget.getClass().getMethod("getScrollbar").invoke(widget);
                scrollbar.getClass().getMethod("setValue", int.class).invoke(scrollbar, scroll);
            }
        } catch (Exception ignored) {}
    }
}

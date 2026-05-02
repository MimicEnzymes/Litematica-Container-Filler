package com.mimicenzymes.litematicafiller.mixin;

import com.google.common.collect.ImmutableList;
import com.mimicenzymes.litematicafiller.materials.FillMaterialCalculator;
import fi.dy.masa.litematica.gui.GuiMaterialList;
import fi.dy.masa.litematica.materials.MaterialListBase;
import fi.dy.masa.litematica.materials.MaterialListEntry;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.util.StringUtils;
import net.minecraft.client.Minecraft;
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
    @Unique private static java.lang.reflect.Field cachedMaterialListAllField = null;
    @Unique private static boolean reflectionInitialized = false;

    @Unique private boolean mimic_isMonitorRunning = false;
    @Unique private boolean mimic_needsCalculation = true;
    @Unique private List<MaterialListEntry> mimic_cachedVanillaList = null;
    @Unique private List<MaterialListEntry> mimic_lastInjectedList = null;
    @Unique private boolean mimic_isInjecting = false;
    @Unique private long mimic_lastLabelTotal = -1;
    @Unique private long mimic_lastLabelMissing = -1;

    @Inject(method = "initGui", at = @At("RETURN"))
    private void onInitGui(CallbackInfo ci) {
        if (!com.mimicenzymes.litematicafiller.config.Configs.ENABLE_MOD.getBooleanValue()) return;
        mimic_addToggleButton();
        if (mimic_isInjecting) return;
        mimic_needsCalculation = true;
        if (!mimic_isMonitorRunning) {
            mimic_isMonitorRunning = true;
            Thread monitor = new Thread(() -> {
                int tickCount = 0;
                while (Minecraft.getInstance().screen == this) {
                    try {
                        Thread.sleep(50);
                        tickCount++;

                        if (FillMaterialCalculator.listMode != 0) {
                            final boolean forceRefresh = (tickCount % 10 == 0) && FillMaterialCalculator.hasMissingData;

                            Minecraft.getInstance().execute(() -> {
                                if (Minecraft.getInstance().screen == this) {
                                    if (forceRefresh) mimic_needsCalculation = true;
                                    mimic_injectContainerData();
                                    mimic_refreshLabelsIfNeeded();
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

        if (FillMaterialCalculator.listMode != 0) {
            mimic_injectContainerData();
            mimic_isInjecting = true;
            try {
                this.initGui();
            } finally {
                mimic_isInjecting = false;
            }
        } else if (mimic_cachedVanillaList != null && mimic_lastInjectedList != null) {
            mimic_directSetMaterialList(materialList, mimic_cachedVanillaList);
            mimic_lastInjectedList = null;
            mimic_refreshWidget();
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
            this.initGui();
        });
    }

    @Unique
    private static void mimic_initReflection() {
        if (reflectionInitialized) return;
        reflectionInitialized = true;
        try {
            for (java.lang.reflect.Field f : MaterialListBase.class.getDeclaredFields()) {
                if (f.getType() == ImmutableList.class) {
                    f.setAccessible(true);
                    cachedMaterialListAllField = f;
                    break;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Unique
    private static void mimic_directSetMaterialList(MaterialListBase list, List<MaterialListEntry> entries) {
        mimic_initReflection();
        if (cachedMaterialListAllField != null) {
            try {
                cachedMaterialListAllField.set(list, ImmutableList.copyOf(entries));
                list.refreshPreFilteredList();
                list.updateCounts();
                return;
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        list.setMaterialListEntries(entries);
    }

    @Unique
    private void mimic_injectContainerData() {
        try {
            MaterialListBase list = this.materialList;
            if (list == null) return;

            List<MaterialListEntry> currentBaseList = list.getMaterialsAll();

            if (currentBaseList != mimic_lastInjectedList) {
                mimic_cachedVanillaList = new ArrayList<>(currentBaseList);
                mimic_needsCalculation = true;
            }

            if (mimic_cachedVanillaList == null) return;

            if (mimic_needsCalculation) {
                FillMaterialCalculator.calculate(this, true, mimic_cachedVanillaList);
                mimic_needsCalculation = false;
            } else if (!FillMaterialCalculator.hasMissingData) {
                return;
            }

            List<MaterialListEntry> targetList;

            if (FillMaterialCalculator.listMode == 1) {
                targetList = FillMaterialCalculator.getCustomMaterialList(list);
            } else if (FillMaterialCalculator.listMode == 2) {
                targetList = FillMaterialCalculator.mergeLists(
                        mimic_cachedVanillaList,
                        FillMaterialCalculator.getCustomMaterialList(list)
                );
            } else {
                targetList = new ArrayList<>(mimic_cachedVanillaList);
            }

            int scroll = mimic_getScrollPosition();

            mimic_directSetMaterialList(list, targetList);

            mimic_lastInjectedList = list.getMaterialsAll();

            mimic_refreshWidget();

            mimic_setScrollPosition(scroll);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Unique
    private void mimic_refreshLabelsIfNeeded() {
        try {
            long newTotal = materialList.getCountTotal();
            long newMissing = materialList.getCountMissing();
            if (newTotal == mimic_lastLabelTotal && newMissing == mimic_lastLabelMissing) return;

            if (this.getFocused() != null) return;

            mimic_lastLabelTotal = newTotal;
            mimic_lastLabelMissing = newMissing;

            mimic_isInjecting = true;
            try {
                this.initGui();
            } finally {
                mimic_isInjecting = false;
            }
        } catch (Exception ignored) {}
    }

    @Unique
    private void mimic_refreshWidget() {
        try {
            Object widget = mimic_getListWidget();
            if (widget != null) {
                widget.getClass().getMethod("refreshEntries").invoke(widget);
            }
        } catch (Exception ignored) {}
    }

    @Unique
    private int mimic_getScrollPosition() {
        try {
            Object widget = mimic_getListWidget();
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
            Object widget = mimic_getListWidget();
            if (widget != null) {
                Object scrollbar = widget.getClass().getMethod("getScrollbar").invoke(widget);
                scrollbar.getClass().getMethod("setValue", int.class).invoke(scrollbar, scroll);
            }
        } catch (Exception ignored) {}
    }

    @Unique
    private static java.lang.reflect.Method cachedGetListWidgetMethod = null;
    @Unique
    private static boolean listWidgetMethodResolved = false;

    @Unique
    private Object mimic_getListWidget() {
        try {
            if (!listWidgetMethodResolved) {
                listWidgetMethodResolved = true;
                cachedGetListWidgetMethod = GuiMaterialList.class.getMethod("getListWidget");
                cachedGetListWidgetMethod.setAccessible(true);
            }
            if (cachedGetListWidgetMethod != null) {
                return cachedGetListWidgetMethod.invoke(this);
            }
        } catch (NoSuchMethodException e) {
            try {
                Class<?> cls = GuiMaterialList.class;
                while (cls != null) {
                    try {
                        cachedGetListWidgetMethod = cls.getDeclaredMethod("getListWidget");
                        cachedGetListWidgetMethod.setAccessible(true);
                        return cachedGetListWidgetMethod.invoke(this);
                    } catch (NoSuchMethodException ignored) {}
                    cls = cls.getSuperclass();
                }
            } catch (Exception ignored) {}
        } catch (Exception ignored) {}
        return null;
    }
}

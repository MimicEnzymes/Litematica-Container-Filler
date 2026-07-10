package com.mimicenzymes.litematicafiller.core;

import com.mimicenzymes.litematicafiller.config.Configs;
import com.mimicenzymes.litematicafiller.gui.GuiItemReplacementPicker;
import com.mimicenzymes.litematicafiller.materials.FillMaterialCalculator;
import com.mimicenzymes.litematicafiller.materials.MaterialListReplacementRefresh;
import com.mimicenzymes.litematicafiller.render.HighlightScanner;
import fi.dy.masa.litematica.materials.MaterialListBase;
import fi.dy.masa.litematica.materials.MaterialListPlacement;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.Message;
import fi.dy.masa.malilib.util.InfoUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

public final class MaterialReplacementUi {
    private MaterialReplacementUi() {
    }

    public static void open(Screen parent, ItemStack source) {
        if (source == null || source.isEmpty()) return;

        ItemStack sourceCopy = source.copy();
        sourceCopy.setCount(1);

        String schematicKey = findSchematicKey(parent);
        GuiBase.openGui(new GuiItemReplacementPicker(parent, sourceCopy, schematicKey));
    }

    public static void addReplacementRule(ItemStack source, ItemStack target, MaterialReplacementScope scope, String schematicKey) {
        if (source == null || source.isEmpty() || target == null) return;

        ItemStack sourceCopy = source.copy();
        sourceCopy.setCount(1);
        ItemStack targetCopy = target.copy();
        targetCopy.setCount(1);

        if (scope == MaterialReplacementScope.SCHEMATIC && schematicKey != null && !schematicKey.isBlank()) {
            MaterialReplacer.setSchematicReplacementRule(schematicKey, sourceCopy, targetCopy);
        } else {
            String sourceRule = MaterialReplacer.stackToExactRule(sourceCopy);
            String targetRule = targetCopy.getItem() == Items.AIR ? MaterialReplacer.itemToLegacyRule(Items.AIR) : MaterialReplacer.stackToExactRule(targetCopy);
            String rule = sourceRule + "->" + targetRule;

            List<String> rules = new ArrayList<>(Configs.MATERIAL_REPLACEMENTS.getStrings());
            rules.removeIf(existing -> MaterialReplacer.ruleSourceMatches(existing, sourceCopy));
            rules.add(rule);

            Configs.MATERIAL_REPLACEMENTS.setStrings(rules);
            Configs.saveToFile();
        }

        onReplacementRulesChanged();

        InfoUtils.showGuiOrInGameMessage(
                Message.MessageType.SUCCESS,
                "litematica_container_filler.message.material_replacement_added",
                sourceCopy.getHoverName().getString(),
                targetCopy.getItem() == Items.AIR ? "minecraft:air" : targetCopy.getHoverName().getString()
        );
    }

    public static void resetReplacementRule(ItemStack source, MaterialReplacementScope scope, String schematicKey) {
        if (source == null || source.isEmpty()) return;

        if (scope == MaterialReplacementScope.SCHEMATIC && schematicKey != null && !schematicKey.isBlank()) {
            MaterialReplacer.removeSchematicReplacementRule(schematicKey, source);
        } else {
            MaterialReplacer.removeReplacementRule(source);
        }

        onReplacementRulesChanged();
        InfoUtils.showGuiOrInGameMessage(
                Message.MessageType.SUCCESS,
                "litematica_container_filler.message.material_replacement_reset",
                source.getHoverName().getString()
        );
    }

    public static void clearCurrentSchematicReplacementRules(Screen screen) {
        String schematicKey = findSchematicKey(screen);
        if (schematicKey == null || schematicKey.isBlank()) return;

        MaterialReplacer.clearSchematicReplacementRules(schematicKey);
        onReplacementRulesChanged();
        InfoUtils.showGuiOrInGameMessage(Message.MessageType.SUCCESS,
                "litematica_container_filler.message.material_replacement_schematic_cleared");
    }

    public static void onReplacementRulesChanged() {
        FillMaterialCalculator.requestMaterialReplacementRefresh();
        HighlightScanner.onMaterialReplacementChanged();
        refreshParentList();
    }

    public static String findSchematicKey(Screen screen) {
        MaterialListBase materialList = findMaterialList(screen);
        return findSchematicKey(materialList);
    }

    public static String findSchematicKey(MaterialListBase materialList) {
        if (materialList == null) return null;

        if (materialList instanceof MaterialListPlacement placementList) {
            try {
                Field field = MaterialListPlacement.class.getDeclaredField("placement");
                field.setAccessible(true);
                Object placement = field.get(placementList);
                if (placement instanceof SchematicPlacement schematicPlacement) {
                    return SchematicMaterialReplacementContext.keyForPlacement(schematicPlacement);
                }
            } catch (Throwable ignored) {
            }
        }

        try {
            Field field = materialList.getClass().getDeclaredField("schematic");
            field.setAccessible(true);
            Object schematic = field.get(materialList);
            if (schematic instanceof LitematicaSchematic litematicaSchematic) {
                return SchematicMaterialReplacementContext.keyForSchematic(litematicaSchematic);
            }
        } catch (Throwable ignored) {
        }

        return null;
    }

    private static MaterialListBase findMaterialList(Screen screen) {
        if (screen == null) return null;

        try {
            Object result = screen.getClass().getMethod("getMaterialList").invoke(screen);
            if (result instanceof MaterialListBase materialList) return materialList;
        } catch (Throwable ignored) {
        }

        try {
            Field field = screen.getClass().getDeclaredField("materialList");
            field.setAccessible(true);
            Object result = field.get(screen);
            if (result instanceof MaterialListBase materialList) return materialList;
        } catch (Throwable ignored) {
        }

        return null;
    }

    public static void refreshParentList() {
        Screen screen = Minecraft.getInstance().gui.screen();
        if (screen instanceof MaterialListReplacementRefresh refresh) {
            refresh.lcf$refreshMaterialReplacementList();
        } else if (screen instanceof GuiItemReplacementPicker picker) {
            Screen parent = picker.getParentScreen();
            if (parent instanceof MaterialListReplacementRefresh refresh) {
                refresh.lcf$refreshMaterialReplacementList();
            }
        }
    }
}


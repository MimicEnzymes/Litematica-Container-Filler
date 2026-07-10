package com.mimicenzymes.litematicafiller.mixin;

import com.mimicenzymes.litematicafiller.config.Configs;
import com.mimicenzymes.litematicafiller.core.MaterialReplacer;
import com.mimicenzymes.litematicafiller.core.SchematicMaterialReplacementContext;
import com.mimicenzymes.litematicafiller.core.SchematicPlacementChangeWatcher;
import com.mimicenzymes.litematicafiller.materials.FillMaterialCalculator;
import com.mimicenzymes.litematicafiller.render.HighlightScanner;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacementManager;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

@Mixin(value = SchematicPlacementManager.class, remap = false)
public abstract class SchematicPlacementManagerMixin {
    @Shadow public abstract List<SchematicPlacement> getAllPlacementsOfSchematic(LitematicaSchematic schematic);

    @Inject(method = "removeSchematicPlacement(Lfi/dy/masa/litematica/schematic/placement/SchematicPlacement;Z)Z",
            at = @At("RETURN"),
            require = 0)
    private void lcf$clearRemovedPlacementRules(SchematicPlacement placement, boolean removeFromJson,
                                                CallbackInfoReturnable<Boolean> cir) {
        if (!Configs.ENABLE_MOD.getBooleanValue()) return;
        if (cir.getReturnValueZ()) {
            lcf$clearRulesForPlacement(placement);
            lcf$refreshPlacementHighlights();
        }
    }

    @Inject(method = "removeAllPlacementsOfSchematic", at = @At("HEAD"), require = 0)
    private void lcf$clearRemovedSchematicRules(LitematicaSchematic schematic, CallbackInfo ci) {
        if (!Configs.ENABLE_MOD.getBooleanValue()) return;
        for (SchematicPlacement placement : this.getAllPlacementsOfSchematic(schematic)) {
            lcf$clearRulesForPlacement(placement);
        }
    }

    @Inject(method = "removeAllPlacementsOfSchematic", at = @At("RETURN"), require = 0)
    private void lcf$refreshHighlightsAfterRemovingSchematic(LitematicaSchematic schematic, CallbackInfo ci) {
        if (!Configs.ENABLE_MOD.getBooleanValue()) return;
        lcf$refreshPlacementHighlights();
    }

    @Inject(method = "addSchematicPlacement", at = @At("RETURN"), require = 0)
    private void lcf$refreshHighlightsAfterPlacementAdded(SchematicPlacement placement, boolean printMessages, CallbackInfo ci) {
        if (!Configs.ENABLE_MOD.getBooleanValue()) return;
        lcf$refreshPlacementHighlights();
    }

    @Inject(method = "onPostPlacementChange", at = @At("RETURN"), require = 0)
    private void lcf$refreshHighlightsAfterPlacementChanged(SchematicPlacement placement, CallbackInfo ci) {
        if (!Configs.ENABLE_MOD.getBooleanValue()) return;
        lcf$refreshPlacementHighlights();
    }

    @Inject(method = "setPositionOfCurrentSelectionToRayTrace", at = @At("RETURN"), require = 0)
    private void lcf$refreshHighlightsAfterRayTraceMove(Minecraft mc, double maxDistance, CallbackInfo ci) {
        if (!Configs.ENABLE_MOD.getBooleanValue()) return;
        lcf$refreshPlacementHighlightsIfChanged(mc);
    }

    @Inject(method = "setPositionOfCurrentSelectionTo", at = @At("RETURN"), require = 0)
    private void lcf$refreshHighlightsAfterSelectionMove(BlockPos pos, Minecraft mc, CallbackInfo ci) {
        if (!Configs.ENABLE_MOD.getBooleanValue()) return;
        lcf$refreshPlacementHighlightsIfChanged(mc);
    }

    @Inject(method = "nudgePositionOfCurrentSelection", at = @At("RETURN"), require = 0)
    private void lcf$refreshHighlightsAfterSelectionNudge(Direction direction, int amount, CallbackInfo ci) {
        if (!Configs.ENABLE_MOD.getBooleanValue()) return;
        lcf$refreshPlacementHighlightsIfChanged(Minecraft.getInstance());
    }

    private static void lcf$clearRulesForPlacement(SchematicPlacement placement) {
        String key = SchematicMaterialReplacementContext.keyForPlacement(placement);
        if (key == null || key.isBlank()) return;

        MaterialReplacer.clearSchematicReplacementRules(key);
        FillMaterialCalculator.requestMaterialReplacementRefresh();
    }

    private static void lcf$refreshPlacementHighlights() {
        HighlightScanner.onPlacementChanged();
        FillMaterialCalculator.requestMaterialReplacementRefresh();
    }

    private static void lcf$refreshPlacementHighlightsIfChanged(Minecraft mc) {
        SchematicPlacementChangeWatcher.tick(mc != null ? mc : Minecraft.getInstance());
    }
}


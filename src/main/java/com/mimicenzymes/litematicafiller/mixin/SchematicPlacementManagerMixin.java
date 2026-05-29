package com.mimicenzymes.litematicafiller.mixin;

import com.mimicenzymes.litematicafiller.core.MaterialReplacer;
import com.mimicenzymes.litematicafiller.core.SchematicMaterialReplacementContext;
import com.mimicenzymes.litematicafiller.materials.FillMaterialCalculator;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacementManager;
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
        if (cir.getReturnValueZ()) {
            lcf$clearRulesForPlacement(placement);
        }
    }

    @Inject(method = "removeAllPlacementsOfSchematic", at = @At("HEAD"), require = 0)
    private void lcf$clearRemovedSchematicRules(LitematicaSchematic schematic, CallbackInfo ci) {
        for (SchematicPlacement placement : this.getAllPlacementsOfSchematic(schematic)) {
            lcf$clearRulesForPlacement(placement);
        }
    }

    private static void lcf$clearRulesForPlacement(SchematicPlacement placement) {
        String key = SchematicMaterialReplacementContext.keyForPlacement(placement);
        if (key == null || key.isBlank()) return;

        MaterialReplacer.clearSchematicReplacementRules(key);
        FillMaterialCalculator.requestMaterialReplacementRefresh();
    }
}

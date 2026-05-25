package com.mimicenzymes.litematicafiller.mixin;

import fi.dy.masa.malilib.gui.interfaces.IMessageConsumer;
import fi.dy.masa.malilib.interfaces.IStringConsumer;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = fi.dy.masa.litematica.schematic.placement.SchematicPlacement.class, remap = false)
public class SchematicPlacementMixin {
    @Inject(method = "setOrigin", at = @At("RETURN"))
    private void onOriginChanged(BlockPos origin, IStringConsumer feedback, CallbackInfoReturnable<Boolean> cir) {
        if (!com.mimicenzymes.litematicafiller.config.Configs.ENABLE_MOD.getBooleanValue()) return;
        com.mimicenzymes.litematicafiller.render.HighlightScanner.onPlacementChanged();
    }
    @Inject(method = "setRotation", at = @At("RETURN"))
    private void onRotationChanged(Rotation rotation, IMessageConsumer feedback, CallbackInfoReturnable<Boolean> cir) {
        if (!com.mimicenzymes.litematicafiller.config.Configs.ENABLE_MOD.getBooleanValue()) return;
        com.mimicenzymes.litematicafiller.render.HighlightScanner.onPlacementChanged();
    }
    @Inject(method = "setMirror", at = @At("RETURN"))
    private void onMirrorChanged(Mirror mirror, IMessageConsumer feedback, CallbackInfoReturnable<Boolean> cir) {
        if (!com.mimicenzymes.litematicafiller.config.Configs.ENABLE_MOD.getBooleanValue()) return;
        com.mimicenzymes.litematicafiller.render.HighlightScanner.onPlacementChanged();
    }
    @Inject(method = "toggleEnabled", at = @At("RETURN"))
    private void onToggleEnabled(CallbackInfo ci) {
        if (!com.mimicenzymes.litematicafiller.config.Configs.ENABLE_MOD.getBooleanValue()) return;
        com.mimicenzymes.litematicafiller.render.HighlightScanner.onPlacementChanged();
    }
    @Inject(method = "setEnabled", at = @At("RETURN"))
    private void onSetEnabled(boolean enabled, CallbackInfo ci) {
        if (!com.mimicenzymes.litematicafiller.config.Configs.ENABLE_MOD.getBooleanValue()) return;
        com.mimicenzymes.litematicafiller.render.HighlightScanner.onPlacementChanged();
    }
}

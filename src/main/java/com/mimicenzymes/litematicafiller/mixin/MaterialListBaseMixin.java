package com.mimicenzymes.litematicafiller.mixin;

import com.mimicenzymes.litematicafiller.config.Configs;
import fi.dy.masa.litematica.materials.MaterialListBase;
import fi.dy.masa.litematica.materials.MaterialListEntry;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@Mixin(value = MaterialListBase.class, remap = false)
public abstract class MaterialListBaseMixin {

    @Shadow @Final protected List<MaterialListEntry> materialListPreFiltered;

    @Unique
    private final Set<MimicPreciseIgnoredKey> mimic_preciselyIgnored = new HashSet<>();

    @Inject(method = "ignoreEntry", at = @At("HEAD"), cancellable = true)
    private void mimic_ignoreEntryPrecisely(MaterialListEntry entry, CallbackInfo ci) {
        if (!Configs.ENABLE_MOD.getBooleanValue()) return;
        if (entry != null) {
            this.mimic_preciselyIgnored.add(new MimicPreciseIgnoredKey(entry.getStack()));
            this.mimic_applyPreciseIgnoreFilter();
            ((MaterialListBase) (Object) this).recreateFilteredList();
            ci.cancel();
        }
    }

    @Inject(method = "clearIgnored", at = @At("HEAD"))
    private void mimic_clearPreciseIgnored(CallbackInfo ci) {
        if (!Configs.ENABLE_MOD.getBooleanValue()) return;
        this.mimic_preciselyIgnored.clear();
    }

    @Inject(method = "refreshPreFilteredList", at = @At("TAIL"))
    private void mimic_refreshPreciseIgnored(CallbackInfo ci) {
        if (!Configs.ENABLE_MOD.getBooleanValue()) return;
        this.mimic_applyPreciseIgnoreFilter();
    }

    @Unique
    private void mimic_applyPreciseIgnoreFilter() {
        if (!Configs.ENABLE_MOD.getBooleanValue()) return;
        if (this.mimic_preciselyIgnored.isEmpty()) {
            return;
        }

        this.materialListPreFiltered.removeIf(entry ->
                entry != null &&
                !entry.getStack().isEmpty() &&
                this.mimic_preciselyIgnored.contains(new MimicPreciseIgnoredKey(entry.getStack())));
    }

    @Unique
    private static final class MimicPreciseIgnoredKey {
        private final ItemStack stack;

        private MimicPreciseIgnoredKey(ItemStack stack) {
            this.stack = stack.copy();
            this.stack.setCount(1);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof MimicPreciseIgnoredKey other)) {
                return false;
            }

            return this.stack.getItem() == other.stack.getItem() &&
                   Objects.equals(this.stack.getComponents(), other.stack.getComponents());
        }

        @Override
        public int hashCode() {
            return 31 * this.stack.getItem().hashCode() + this.stack.getComponents().hashCode();
        }
    }
}

package com.mimicenzymes.litematicafiller.core;

import com.mimicenzymes.litematicafiller.config.Configs;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public class MaterialReplacer {

    private static class ItemRule {
        final Item item;
        final String name;

        ItemRule(Item item, String name) {
            this.item = item;
            this.name = name;
        }

        boolean matches(ItemStack stack) {
            if (stack.getItem() != this.item) return false;

            Text customName = stack.get(DataComponentTypes.CUSTOM_NAME);

            if (this.name == null) {
                return customName == null;
            } else {
                if (customName == null) return false;
                return customName.getString().contains(this.name);
            }
        }
    }

    private static class Replacement {
        final ItemRule source;
        final ItemRule target;

        Replacement(ItemRule source, ItemRule target) {
            this.source = source;
            this.target = target;
        }
    }

    private static final List<Replacement> REPLACEMENTS = new ArrayList<>();
    private static int lastHash = -1;

    public static void checkReload() {
        List<String> strings = Configs.MATERIAL_REPLACEMENTS.getStrings();
        int currentHash = strings.hashCode();

        if (currentHash != lastHash) {
            REPLACEMENTS.clear();
            for (String rule : strings) {
                if (rule == null || !rule.contains("->")) continue;
                String[] parts = rule.split("->", 2);
                if (parts.length != 2) continue;

                ItemRule source = parseRule(parts[0].trim());
                ItemRule target = parseRule(parts[1].trim());

                if (source.item != net.minecraft.item.Items.AIR) {
                    REPLACEMENTS.add(new Replacement(source, target));
                }
            }
            lastHash = currentHash;
        }
    }

    private static ItemRule parseRule(String str) {
        String idStr = str;
        String nameStr = null;

        if (str.contains("#")) {
            String[] parts = str.split("#", 2);
            idStr = parts[0].trim();
            nameStr = parts[1].trim();
            if (nameStr.isEmpty()) nameStr = null;
        }

        if (!idStr.contains(":")) {
            idStr = "minecraft:" + idStr;
        }

        Identifier id = Identifier.tryParse(idStr);
        Item item = net.minecraft.item.Items.AIR;
        if (id != null && Registries.ITEM.containsId(id)) {
            item = Registries.ITEM.get(id);
        }

        return new ItemRule(item, nameStr);
    }

    public static ItemStack replaceSingleStack(ItemStack original) {
        if (original == null || original.isEmpty()) return original;
        checkReload();
        if (REPLACEMENTS.isEmpty()) return original;

        for (Replacement rep : REPLACEMENTS) {
            if (rep.source.matches(original)) {
                if (rep.target.item == net.minecraft.item.Items.AIR) {
                    return ItemStack.EMPTY;
                }

                ItemStack newStack = new ItemStack(rep.target.item, original.getCount());

                if (rep.target.name != null) {
                    newStack.set(DataComponentTypes.CUSTOM_NAME, Text.literal(rep.target.name));
                }

                return newStack;
            }
        }
        return original;
    }

    public static boolean isIgnored(ItemStack original) {
        if (original == null || original.isEmpty()) return false;
        checkReload();
        if (REPLACEMENTS.isEmpty()) return false;

        for (Replacement rep : REPLACEMENTS) {
            if (rep.source.matches(original) && rep.target.item == net.minecraft.item.Items.AIR) {
                return true;
            }
        }
        return false;
    }

    public static void replaceInMap(Map<Integer, ItemStack> inventory) {
        if (inventory == null || inventory.isEmpty()) return;
        checkReload();
        if (REPLACEMENTS.isEmpty()) return;
        Iterator<Map.Entry<Integer, ItemStack>> iterator = inventory.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Integer, ItemStack> entry = iterator.next();
            ItemStack replaced = replaceSingleStack(entry.getValue());
            if (replaced != entry.getValue()) {
                if (replaced == null || replaced.isEmpty()) {
                    iterator.remove();
                } else {
                    entry.setValue(replaced);
                }
            }
        }
    }

    public static void replaceInNbtList(net.minecraft.nbt.NbtList itemsList, net.minecraft.registry.RegistryWrapper.WrapperLookup registries) {
        for (int i = 0; i < itemsList.size(); i++) {
            if (itemsList.get(i) instanceof net.minecraft.nbt.NbtCompound itemTag) {
                ItemStack original = ItemStack.OPTIONAL_CODEC.parse(registries.getOps(net.minecraft.nbt.NbtOps.INSTANCE), itemTag).resultOrPartial().orElse(ItemStack.EMPTY);
                if (!original.isEmpty()) {
                    ItemStack replaced = replaceSingleStack(original);
                    if (replaced != original) {
                        if (replaced == null || replaced.isEmpty()) {
                            itemsList.remove(i);
                            i--;
                            continue;
                        }
                        net.minecraft.nbt.NbtElement newTag = ItemStack.OPTIONAL_CODEC.encodeStart(registries.getOps(net.minecraft.nbt.NbtOps.INSTANCE), replaced).resultOrPartial().orElse(null);
                        if (newTag instanceof net.minecraft.nbt.NbtCompound newCompound) {
                            if (itemTag.contains("Slot")) {
                                newCompound.put("Slot", itemTag.get("Slot"));
                            }
                            itemsList.set(i, newCompound);
                        }
                    }
                }
            }
        }
    }
}

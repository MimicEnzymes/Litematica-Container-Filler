package com.mimicenzymes.litematicafiller.core;

import com.mimicenzymes.litematicafiller.config.Configs;
import com.mojang.serialization.DynamicOps;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.TagParser;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class MaterialReplacer {
    private static final String STACK_PREFIX = "stack64:";

    private static class ItemRule {
        final Item item;
        final String name;
        final ItemStack exactStack;

        ItemRule(Item item, String name, ItemStack exactStack) {
            this.item = item;
            this.name = name;
            this.exactStack = exactStack == null || exactStack.isEmpty() ? ItemStack.EMPTY : normalizeStack(exactStack);
        }

        boolean matches(ItemStack stack) {
            if (stack == null || stack.isEmpty()) return false;

            if (!this.exactStack.isEmpty()) {
                return ItemStack.isSameItemSameComponents(normalizeStack(stack), this.exactStack);
            }

            if (stack.getItem() != this.item) return false;

            Component customName = stack.get(DataComponents.CUSTOM_NAME);

            if (this.name == null) {
                return customName == null;
            } else {
                if (customName == null) return false;
                return customName.getString().contains(this.name);
            }
        }

        ItemStack createStack(int count) {
            if (this.item == Items.AIR) return ItemStack.EMPTY;

            ItemStack stack = !this.exactStack.isEmpty() ? this.exactStack.copy() : new ItemStack(this.item);
            stack.setCount(count);

            if (this.exactStack.isEmpty() && this.name != null) {
                stack.set(DataComponents.CUSTOM_NAME, Component.literal(this.name));
            }

            return stack;
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
    private static final Map<String, List<String>> SCHEMATIC_RULE_STRINGS = new HashMap<>();
    private static final Map<String, List<Replacement>> SCHEMATIC_REPLACEMENTS = new HashMap<>();
    private static int lastHash = -1;

    public static void checkReload() {
        List<String> strings = Configs.MATERIAL_REPLACEMENTS.getStrings();
        int currentHash = strings.hashCode();

        if (currentHash != lastHash) {
            REPLACEMENTS.clear();
            REPLACEMENTS.addAll(parseReplacements(strings));
            lastHash = currentHash;
        }
    }

    private static ItemRule parseRule(String str) {
        if (str.startsWith(STACK_PREFIX)) {
            ItemStack stack = decodeStack(str.substring(STACK_PREFIX.length()));
            if (!stack.isEmpty()) {
                return new ItemRule(stack.getItem(), null, stack);
            }
        }

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
        Item item = Items.AIR;
        if (id != null && BuiltInRegistries.ITEM.containsKey(id)) {
            item = BuiltInRegistries.ITEM.getValue(id);
        }

        return new ItemRule(item, nameStr, ItemStack.EMPTY);
    }

    public static ItemStack replaceSingleStack(ItemStack original) {
        return replaceSingleStack(original, SchematicMaterialReplacementContext.getActiveKey());
    }

    public static ItemStack replaceSingleStack(ItemStack original, String schematicKey) {
        if (original == null || original.isEmpty()) return original;
        checkReload();
        if (!hasReplacementRules(schematicKey)) return original;

        return replaceSingleStackLoaded(original, schematicKey);
    }

    private static Replacement findReplacement(ItemStack original) {
        return findReplacement(original, SchematicMaterialReplacementContext.getActiveKey());
    }

    private static Replacement findReplacement(ItemStack original, String schematicKey) {
        if (original == null || original.isEmpty()) return null;

        if (schematicKey != null) {
            List<Replacement> local = SCHEMATIC_REPLACEMENTS.get(schematicKey);
            if (local != null) {
                for (Replacement rep : local) {
                    if (rep.source.matches(original)) {
                        return rep;
                    }
                }
            }
        }

        for (Replacement rep : REPLACEMENTS) {
            if (rep.source.matches(original)) {
                return rep;
            }
        }

        return null;
    }

    public static boolean isIgnored(ItemStack original) {
        return isIgnored(original, SchematicMaterialReplacementContext.getActiveKey());
    }

    public static boolean isIgnored(ItemStack original, String schematicKey) {
        if (original == null || original.isEmpty()) return false;
        checkReload();
        if (!hasReplacementRules(schematicKey)) return false;

        Replacement replacement = findReplacement(original, schematicKey);
        return replacement != null && replacement.target.item == Items.AIR;
    }

    public static void replaceInMap(Map<Integer, ItemStack> inventory) {
        replaceInMap(inventory, SchematicMaterialReplacementContext.getActiveKey());
    }

    public static void replaceInMap(Map<Integer, ItemStack> inventory, String schematicKey) {
        if (inventory == null || inventory.isEmpty()) return;
        checkReload();
        if (!hasReplacementRules(schematicKey)) return;
        Iterator<Map.Entry<Integer, ItemStack>> iterator = inventory.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Integer, ItemStack> entry = iterator.next();
            ItemStack original = entry.getValue();
            ItemStack replaced = replaceSingleStack(original, schematicKey);
            if (replaced != original && !ItemStack.matches(replaced, original)) {
                if (replaced == null || replaced.isEmpty()) {
                    iterator.remove();
                } else {
                    entry.setValue(replaced);
                }
            }
        }
    }

    public static void replaceInListTag(net.minecraft.nbt.ListTag itemsList, net.minecraft.core.HolderLookup.Provider registries) {
        String schematicKey = SchematicMaterialReplacementContext.getActiveKey();
        checkReload();
        if (!hasReplacementRules(schematicKey)) return;

        for (int i = 0; i < itemsList.size(); i++) {
            if (itemsList.get(i) instanceof net.minecraft.nbt.CompoundTag itemTag) {
                DynamicOps<Tag> ops = registries.createSerializationContext(net.minecraft.nbt.NbtOps.INSTANCE);
                ItemStack original = ItemStack.OPTIONAL_CODEC.parse(ops, itemTag).resultOrPartial().orElse(ItemStack.EMPTY);
                if (!original.isEmpty()) {
                    ItemStack replaced = replaceSingleStackLoaded(original, schematicKey);
                    if (replaced != original && !ItemStack.matches(replaced, original)) {
                        if (replaced == null || replaced.isEmpty()) {
                            itemsList.remove(i);
                            i--;
                            continue;
                        }
                        net.minecraft.nbt.Tag newTag = ItemStack.OPTIONAL_CODEC.encodeStart(ops, replaced).resultOrPartial().orElse(null);
                        if (newTag instanceof net.minecraft.nbt.CompoundTag newCompound) {
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

    private static boolean hasReplacementRules(String schematicKey) {
        if (!REPLACEMENTS.isEmpty()) return true;
        if (schematicKey == null) return false;

        List<Replacement> local = SCHEMATIC_REPLACEMENTS.get(schematicKey);
        return local != null && !local.isEmpty();
    }

    private static ItemStack replaceSingleStackLoaded(ItemStack original, String schematicKey) {
        Replacement replacement = findReplacement(original, schematicKey);
        return replacement != null ? replacement.target.createStack(original.getCount()) : original;
    }

    public static Optional<ItemStack> getReplacementTarget(ItemStack source) {
        return getReplacementTarget(source, SchematicMaterialReplacementContext.getActiveKey());
    }

    public static Optional<ItemStack> getReplacementTarget(ItemStack source, String schematicKey) {
        if (source == null || source.isEmpty()) return Optional.empty();
        checkReload();
        if (!hasReplacementRules(schematicKey)) return Optional.empty();

        Replacement replacement = findReplacement(source, schematicKey);
        if (replacement != null) return Optional.of(replacement.target.createStack(1));

        return Optional.empty();
    }

    public static Optional<ItemStack> getGlobalReplacementTarget(ItemStack source) {
        if (source == null || source.isEmpty()) return Optional.empty();
        checkReload();

        for (Replacement rep : REPLACEMENTS) {
            if (rep.source.matches(source)) {
                return Optional.of(rep.target.createStack(1));
            }
        }

        return Optional.empty();
    }

    public static Optional<ItemStack> getSchematicReplacementTarget(String schematicKey, ItemStack source) {
        if (schematicKey == null || schematicKey.isBlank() || source == null || source.isEmpty()) return Optional.empty();
        checkReload();

        List<Replacement> replacements = SCHEMATIC_REPLACEMENTS.get(schematicKey);
        if (replacements == null) return Optional.empty();

        for (Replacement rep : replacements) {
            if (rep.source.matches(source)) {
                return Optional.of(rep.target.createStack(1));
            }
        }

        return Optional.empty();
    }

    public static boolean hasReplacement(ItemStack source) {
        return getReplacementTarget(source).isPresent();
    }

    public static boolean hasSchematicReplacement(String schematicKey, ItemStack source) {
        return getSchematicReplacementTarget(schematicKey, source).isPresent();
    }

    public static void removeReplacementRule(ItemStack source) {
        if (source == null || source.isEmpty()) return;

        List<String> rules = new ArrayList<>(Configs.MATERIAL_REPLACEMENTS.getStrings());
        rules.removeIf(rule -> rule != null && ruleSourceMatches(rule, source));
        Configs.MATERIAL_REPLACEMENTS.setStrings(rules);
        Configs.saveToFile();
        lastHash = -1;
    }

    public static void setSchematicReplacementRule(String schematicKey, ItemStack source, ItemStack target) {
        if (schematicKey == null || schematicKey.isBlank() || source == null || source.isEmpty() || target == null) return;

        ItemStack sourceCopy = normalizeStack(source);
        ItemStack targetCopy = normalizeStack(target);
        String sourceRule = stackToExactRule(sourceCopy);
        String targetRule = targetCopy.getItem() == Items.AIR ? itemToLegacyRule(Items.AIR) : stackToExactRule(targetCopy);
        String rule = sourceRule + "->" + targetRule;

        List<String> rules = new ArrayList<>(SCHEMATIC_RULE_STRINGS.getOrDefault(schematicKey, List.of()));
        rules.removeIf(existing -> ruleSourceMatches(existing, sourceCopy));
        rules.add(rule);
        setSchematicRules(schematicKey, rules);
    }

    public static void removeSchematicReplacementRule(String schematicKey, ItemStack source) {
        if (schematicKey == null || schematicKey.isBlank() || source == null || source.isEmpty()) return;

        List<String> rules = new ArrayList<>(SCHEMATIC_RULE_STRINGS.getOrDefault(schematicKey, List.of()));
        rules.removeIf(existing -> ruleSourceMatches(existing, source));
        setSchematicRules(schematicKey, rules);
    }

    public static void clearSchematicReplacementRules(String schematicKey) {
        if (schematicKey == null || schematicKey.isBlank()) return;
        SCHEMATIC_RULE_STRINGS.remove(schematicKey);
        SCHEMATIC_REPLACEMENTS.remove(schematicKey);
    }

    public static void clearAllSchematicReplacementRules() {
        SCHEMATIC_RULE_STRINGS.clear();
        SCHEMATIC_REPLACEMENTS.clear();
    }

    private static void setSchematicRules(String schematicKey, List<String> rules) {
        if (rules == null || rules.isEmpty()) {
            SCHEMATIC_RULE_STRINGS.remove(schematicKey);
            SCHEMATIC_REPLACEMENTS.remove(schematicKey);
            return;
        }

        SCHEMATIC_RULE_STRINGS.put(schematicKey, List.copyOf(rules));
        SCHEMATIC_REPLACEMENTS.put(schematicKey, parseReplacements(rules));
    }

    private static List<Replacement> parseReplacements(List<String> strings) {
        List<Replacement> replacements = new ArrayList<>();
        for (String rule : strings) {
            if (rule == null || !rule.contains("->")) continue;
            String[] parts = rule.split("->", 2);
            if (parts.length != 2) continue;

            ItemRule source = parseRule(parts[0].trim());
            ItemRule target = parseRule(parts[1].trim());

            if (source.item != Items.AIR) {
                replacements.add(new Replacement(source, target));
            }
        }
        return replacements;
    }

    public static String stackToExactRule(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return itemToLegacyRule(Items.AIR);

        if (stack.get(DataComponents.CUSTOM_NAME) == null && stack.getComponentsPatch().isEmpty()) {
            return stackToLegacyRule(stack);
        }

        ItemStack normalized = normalizeStack(stack);
        DynamicOps<Tag> ops = getNbtOps();
        if (ops != null) {
            Tag encoded = ItemStack.OPTIONAL_CODEC.encodeStart(ops, normalized).resultOrPartial().orElse(null);
            if (encoded != null) {
                String payload = Base64.getUrlEncoder().withoutPadding()
                        .encodeToString(encoded.toString().getBytes(StandardCharsets.UTF_8));
                return STACK_PREFIX + payload;
            }
        }

        if (normalized.get(DataComponents.CUSTOM_NAME) != null) {
            return stackToLegacyRule(normalized);
        }

        if (!normalized.getComponentsPatch().isEmpty()) {
            return itemToLegacyRule(normalized.getItem());
        }

        return stackToLegacyRule(normalized);
    }

    public static String stackToLegacyRule(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return itemToLegacyRule(Items.AIR);

        String id = itemToLegacyRule(stack.getItem());
        Component customName = stack.get(DataComponents.CUSTOM_NAME);
        if (customName == null || customName.getString().isBlank()) {
            return id;
        }
        return id + "#" + customName.getString().replace("->", "").trim();
    }

    public static String itemToLegacyRule(Item item) {
        Identifier id = BuiltInRegistries.ITEM.getKey(item);
        return id != null ? id.toString() : "minecraft:air";
    }

    public static boolean ruleSourceMatches(String rule, ItemStack source) {
        if (rule == null || source == null || source.isEmpty() || !rule.contains("->")) return false;

        String[] parts = rule.split("->", 2);
        if (parts.length != 2) return false;

        return parseRule(parts[0].trim()).matches(source);
    }

    private static ItemStack normalizeStack(ItemStack stack) {
        ItemStack copy = stack.copy();
        copy.setCount(1);
        return copy;
    }

    private static ItemStack decodeStack(String payload) {
        DynamicOps<Tag> ops = getNbtOps();
        if (ops == null || payload == null || payload.isBlank()) return ItemStack.EMPTY;

        try {
            String nbtText = new String(Base64.getUrlDecoder().decode(payload), StandardCharsets.UTF_8);
            CompoundTag nbt = TagParser.parseCompoundFully(nbtText);
            return ItemStack.OPTIONAL_CODEC.parse(ops, nbt).resultOrPartial().orElse(ItemStack.EMPTY);
        } catch (Exception ignored) {
            return ItemStack.EMPTY;
        }
    }

    private static DynamicOps<Tag> getNbtOps() {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.level == null) return null;
        return client.level.registryAccess().createSerializationContext(NbtOps.INSTANCE);
    }
}

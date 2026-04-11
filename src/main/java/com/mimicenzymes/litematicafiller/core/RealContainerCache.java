package com.mimicenzymes.litematicafiller.core;

import com.mimicenzymes.litematicafiller.config.Configs;
import com.mimicenzymes.litematicafiller.network.ServuxSyncHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class RealContainerCache {
    private static final Map<BlockPos, Map<Integer, ItemStack>> CACHE = new ConcurrentHashMap<>();
    private static final Map<BlockPos, Set<Integer>> LOCK_CACHE = new ConcurrentHashMap<>();
    private static BlockPos lastLookedPos = null;

    private static final Map<BlockPos, Map<Integer, ItemStack>> NBT_QUERY_CACHE = new ConcurrentHashMap<>();
    private static final Map<Integer, BlockPos> PENDING_NBT_REQUESTS = new ConcurrentHashMap<>();
    private static final Map<BlockPos, Long> LAST_REQUEST_TIME = new ConcurrentHashMap<>();
    private static int transactionCounter = 10000;

    public static void tick(Minecraft client) {
        if (client.level == null || client.player == null) return;

        if (client.screen == null && client.hitResult instanceof BlockHitResult bhr) {
            lastLookedPos = bhr.getBlockPos();
        }

        if (client.screen instanceof AbstractContainerScreen<?> screen) {
            updateFromHandler(client, screen.getMenu());
        }
    }

    public static void updateFromScreen(Minecraft client, AbstractContainerScreen<?> screen) {
        if (screen != null) {
            updateFromHandler(client, screen.getMenu());
        }
    }

    public static void updateFromHandler(Minecraft client, AbstractContainerMenu handler) {
        BlockPos pos = AutoFillerStateMachine.getInstance().getCurrentTaskPos();
        if (pos == null) pos = lastLookedPos;
        if (pos == null || handler == null) return;

        Map<Integer, ItemStack> items = new HashMap<>();

        for (Slot slot : handler.slots) {
            if (slot.container != null && slot.container != client.player.getInventory()) {
                if (handler instanceof net.minecraft.world.inventory.CrafterMenu && slot.getContainerSlot() == 9) {
                    continue;
                }
                if (!slot.getItem().isEmpty()) items.put(slot.getContainerSlot(), slot.getItem().copy());
            }
        }

        BlockState state = client.level.getBlockState(pos);
        BlockPos[] halves = LitematicaContainerReader.getDoubleContainerHalves(client.level, pos, state);

        if (halves != null) {
            CACHE.put(halves[0].immutable(), items);
            CACHE.put(halves[1].immutable(), items);
        } else {
            CACHE.put(pos.immutable(), items);
        }

        if (handler instanceof net.minecraft.world.inventory.CrafterMenu crafterHandler) {
            Set<Integer> locks = new HashSet<>();
            for (int i = 0; i < 9; i++) {
                if (crafterHandler.isSlotDisabled(i)) locks.add(i);
            }
            LOCK_CACHE.put(pos.immutable(), locks);
        }
    }

    public static Map<Integer, ItemStack> getCachedItems(BlockPos pos) {
        if (CACHE.containsKey(pos)) return CACHE.get(pos);

        var schematicWorld = fi.dy.masa.litematica.world.SchematicWorldHandler.getSchematicWorld();
        if (schematicWorld != null) {
            BlockState state = schematicWorld.getBlockState(pos);
            BlockPos[] halves = LitematicaContainerReader.getDoubleContainerHalves(schematicWorld, pos, state);
            if (halves != null) {
                // 优先读取 Servux 数据，没有则回退到 OP NBT 数据
                Map<Integer, ItemStack> right = ServuxSyncHandler.getCachedData(halves[0]);
                if (right == null) right = NBT_QUERY_CACHE.get(halves[0]);

                Map<Integer, ItemStack> left = ServuxSyncHandler.getCachedData(halves[1]);
                if (left == null) left = NBT_QUERY_CACHE.get(halves[1]);

                if (right != null || left != null) {
                    Map<Integer, ItemStack> combined = new HashMap<>();
                    if (right != null) combined.putAll(right);
                    if (left != null) {
                        left.forEach((k, v) -> combined.put(k + 27, v));
                    }
                    return combined;
                }
                return null;
            }
        }

        Map<Integer, ItemStack> servuxData = ServuxSyncHandler.getCachedData(pos);
        if (servuxData != null) return servuxData;

        return NBT_QUERY_CACHE.get(pos);
    }

    public static void requestContainerData(BlockPos pos) {
        long now = System.currentTimeMillis();
        if (now - LAST_REQUEST_TIME.getOrDefault(pos, 0L) < 2000) return;
        LAST_REQUEST_TIME.put(pos, now);

        boolean isDouble = false;
        BlockPos[] halves = null;
        var schematicWorld = fi.dy.masa.litematica.world.SchematicWorldHandler.getSchematicWorld();
        if (schematicWorld != null) {
            BlockState state = schematicWorld.getBlockState(pos);
            halves = LitematicaContainerReader.getDoubleContainerHalves(schematicWorld, pos, state);
            if (halves != null) isDouble = true;
        }

        if (Configs.ENABLE_DATA_SYNC.getBooleanValue()) {
            if (isDouble) {
                boolean s1 = ServuxSyncHandler.requestData(halves[0]);
                boolean s2 = ServuxSyncHandler.requestData(halves[1]);
                if (s1 || s2) return;
            } else {
                if (ServuxSyncHandler.requestData(pos)) return;
            }
        }

        if (!Configs.ENABLE_OP_NBT_QUERY.getBooleanValue()) return;
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.getConnection() == null) return;

        if (isDouble) {
            int id1 = transactionCounter++;
            PENDING_NBT_REQUESTS.put(id1, halves[0]);
            client.getConnection().send(new net.minecraft.network.protocol.game.ServerboundBlockEntityTagQueryPacket(id1, halves[0]));

            int id2 = transactionCounter++;
            PENDING_NBT_REQUESTS.put(id2, halves[1]);
            client.getConnection().send(new net.minecraft.network.protocol.game.ServerboundBlockEntityTagQueryPacket(id2, halves[1]));
            return;
        }

        int id = transactionCounter++;
        PENDING_NBT_REQUESTS.put(id, pos);
        client.getConnection().send(new net.minecraft.network.protocol.game.ServerboundBlockEntityTagQueryPacket(id, pos));
    }

    public static void handleNbtResponse(int transactionId, CompoundTag nbt) {
        BlockPos pos = PENDING_NBT_REQUESTS.remove(transactionId);
        if (pos != null && nbt != null) {
            Minecraft client = Minecraft.getInstance();
            if (client.level != null) {
                Map<Integer, ItemStack> items = new HashMap<>();
                if (nbt.contains("Items")) {
                    items = parseNbtInventory(nbt, client.level.registryAccess());
                }
                NBT_QUERY_CACHE.put(pos.immutable(), items);

                if (nbt.contains("disabled_slots")) {
                    LOCK_CACHE.put(pos.immutable(), parseDisabledSlots(nbt));
                }
            }
        }
    }

    public static Set<Integer> getCachedLocks(BlockPos pos) { return LOCK_CACHE.get(pos); }

    public static void putLock(BlockPos pos, Set<Integer> locks) {
        if (pos == null || locks == null) return;
        LOCK_CACHE.put(pos.immutable(), locks);
    }

    public static boolean isSatisfied(BlockPos pos, Map<Integer, ItemStack> required) {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null) return false;

        BlockState state = client.level.getBlockState(pos);
        boolean isCrafter = state.getBlock() instanceof net.minecraft.world.level.block.CrafterBlock;
        if (isCrafter && LitematicaContainerReader.doesCrafterNeedLocking(pos, client)) {
            return false;
        }

        Map<Integer, ItemStack> realItems = getCachedItems(pos);
        if (realItems != null) {
            return checkMapStrict(realItems, required, isCrafter);
        }
        return false;
    }

    private static boolean checkMapStrict(Map<Integer, ItemStack> realItems, Map<Integer, ItemStack> required, boolean isCrafter) {
        if (realItems == null) return false;
        int maxSlot = isCrafter ? 9 : 54;

        for (int i = 0; i < maxSlot; i++) {
            ItemStack real = realItems.getOrDefault(i, ItemStack.EMPTY);
            ItemStack req = (required != null) ? required.getOrDefault(i, ItemStack.EMPTY) : ItemStack.EMPTY;
            if (real.isEmpty() && req.isEmpty()) continue;
            if (real.isEmpty() != req.isEmpty() || !ItemMatcher.isSameItem(real, req) || real.getCount() != req.getCount()) {
                return false;
            }
        }
        return true;
    }

    public static Map<Integer, ItemStack> parseNbtInventory(CompoundTag nbt, HolderLookup.Provider registries) {
        Map<Integer, ItemStack> items = new HashMap<>();
        Tag itemsElem = nbt.get("Items");
        if (itemsElem instanceof ListTag list) {
            for (int i = 0; i < list.size(); i++) {
                Tag itemElem = list.get(i);
                if (itemElem instanceof CompoundTag itemTag) {
                    int slot = 0;
                    if (itemTag.contains("Slot")) {
                        try { slot = Integer.parseInt(itemTag.get("Slot").toString().replaceAll("[^0-9]", "")) & 255; } catch (Exception ignored) {}
                    }

                    ItemStack stack = ItemStack.EMPTY;
                    try {
                        stack = ItemStack.OPTIONAL_CODEC.parse(registries.createSerializationContext(NbtOps.INSTANCE), itemTag).resultOrPartial().orElse(ItemStack.EMPTY);
                    } catch (Exception ignored) {}

                    if (stack.isEmpty() && itemTag.contains("id")) {
                        String idStr = itemTag.get("id").toString().replace("\"", "");
                        net.minecraft.resources.Identifier id = net.minecraft.resources.Identifier.tryParse(idStr);
                        if (id != null) {
                            net.minecraft.world.item.Item item = net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(id);
                            if (item != null && item != net.minecraft.world.item.Items.AIR) {
                                int count = 1;
                                try {
                                    if (itemTag.contains("Count")) count = Integer.parseInt(itemTag.get("Count").toString().replaceAll("[^0-9]", ""));
                                    else if (itemTag.contains("count")) count = Integer.parseInt(itemTag.get("count").toString().replaceAll("[^0-9]", ""));
                                } catch (Exception ignored) {}
                                stack = new ItemStack(item, count);
                            }
                        }
                    }

                    if (!stack.isEmpty()) items.put(slot, stack);
                }
            }
        }
        return items;
    }

    public static Set<Integer> parseDisabledSlots(CompoundTag nbt) {
        Set<Integer> disabledSlots = new HashSet<>();
        if (nbt != null && nbt.contains("disabled_slots")) {
            Tag elem = nbt.get("disabled_slots");
            if (elem instanceof ListTag list) {
                for (int i = 0; i < list.size(); i++) {
                    if (list.get(i) instanceof net.minecraft.nbt.NumericTag num) {
                        disabledSlots.add(num.intValue());
                    }
                }
            }
            else if (elem instanceof net.minecraft.nbt.IntArrayTag intArray) {
                for (int val : intArray.getAsIntArray()) {
                    disabledSlots.add(val);
                }
            }
        }
        return disabledSlots;
    }

    public static void clear() {
        CACHE.clear();
        LOCK_CACHE.clear();
        NBT_QUERY_CACHE.clear();
        PENDING_NBT_REQUESTS.clear();
        LAST_REQUEST_TIME.clear();
        ServuxSyncHandler.INDEPENDENT_CACHE.clear();
    }

    public static void put(BlockPos pos, Map<Integer, ItemStack> items) {
        if (pos == null || items == null) return;
        CACHE.put(pos.immutable(), items);
    }
}
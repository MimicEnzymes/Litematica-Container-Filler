package com.mimicenzymes.litematicafiller.network;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

public record ServuxResponsePayload(BlockPos pos, Map<Integer, ItemStack> items) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<ServuxResponsePayload> ID = new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("servux", "hud_data_sync"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ServuxResponsePayload> CODEC = StreamCodec.ofMember(
            (value, buf) -> {
            },
            buf -> {
                Map<Integer, ItemStack> parsedItems = new HashMap<>();
                BlockPos parsedPos = null;

                try {

                    parsedPos = buf.readBlockPos();
                    int size = buf.readVarInt();

                    for (int i = 0; i < size; i++) {
                        int slot = buf.readVarInt();
                        ItemStack stack = ItemStack.STREAM_CODEC.decode(buf);
                        if (!stack.isEmpty()) {
                            parsedItems.put(slot, stack);
                        }
                    }
                } catch (Exception e) {
                    System.err.println("[LitematicaFiller] 脱离 MiniHUD 独立解析 Servux 数据失败，协议不匹配: " + e.getMessage());
                }

                return new ServuxResponsePayload(parsedPos != null ? parsedPos : BlockPos.ZERO, parsedItems);
            }
    );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}
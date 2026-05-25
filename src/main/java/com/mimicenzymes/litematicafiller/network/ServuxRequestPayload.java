package com.mimicenzymes.litematicafiller.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.core.BlockPos;

public record ServuxRequestPayload(int action, BlockPos pos) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<ServuxRequestPayload> ID = new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("servux", "hud_data_request"));

    public static final StreamCodec<FriendlyByteBuf, ServuxRequestPayload> CODEC = StreamCodec.ofMember(
            (value, buf) -> {
                buf.writeVarInt(value.action());
                buf.writeBlockPos(value.pos());
            },
            buf -> new ServuxRequestPayload(buf.readVarInt(), buf.readBlockPos())
    );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}

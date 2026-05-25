package com.mimicenzymes.litematicafiller.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

public record TakeItOutPayload(int slot, int shulker) implements CustomPayload {
    public static final CustomPayload.Id<TakeItOutPayload> ID = new CustomPayload.Id<>(Identifier.of("takeitout", "getstack"));

    public static final PacketCodec<RegistryByteBuf, TakeItOutPayload> CODEC = PacketCodec.tuple(
            PacketCodecs.INTEGER,
            TakeItOutPayload::slot,
            PacketCodecs.INTEGER,
            TakeItOutPayload::shulker,
            TakeItOutPayload::new
    );

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }
}

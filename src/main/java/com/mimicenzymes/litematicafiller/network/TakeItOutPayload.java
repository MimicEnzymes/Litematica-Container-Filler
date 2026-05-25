package com.mimicenzymes.litematicafiller.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record TakeItOutPayload(int slot, int shulker) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<TakeItOutPayload> ID = new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("takeitout", "getstack"));

    public static final StreamCodec<RegistryFriendlyByteBuf, TakeItOutPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.INT,
            TakeItOutPayload::slot,
            ByteBufCodecs.INT,
            TakeItOutPayload::shulker,
            TakeItOutPayload::new
    );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}


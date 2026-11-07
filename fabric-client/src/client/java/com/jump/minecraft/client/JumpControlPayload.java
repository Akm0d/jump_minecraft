package com.jump.minecraft.client;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

record JumpControlPayload(byte action) implements CustomPacketPayload {
    static final byte VERSION = 1;
    static final byte TOGGLE_MODE = 1;
    static final Type<JumpControlPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath("jump", "control"));
    static final StreamCodec<FriendlyByteBuf, JumpControlPayload> CODEC = StreamCodec.of(
            (buffer, payload) -> {
                buffer.writeByte(VERSION);
                buffer.writeByte(payload.action);
            },
            buffer -> {
                byte version = buffer.readByte();
                byte action = buffer.readByte();
                if (version != VERSION || action != TOGGLE_MODE) {
                    throw new IllegalArgumentException("Unsupported JUMP control payload");
                }
                return new JumpControlPayload(action);
            });

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

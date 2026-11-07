package com.jump.minecraft.client;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

record JumpModePayload(byte mode) implements CustomPacketPayload {
    static final byte VERSION = 1;
    static final byte MINECRAFT = 0;
    static final byte FLIGHT = 1;
    static final byte FLIGHT_DESKTOP = 2;
    static final Type<JumpModePayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath("jump", "mode"));
    static final StreamCodec<FriendlyByteBuf, JumpModePayload> CODEC = StreamCodec.of(
            (buffer, payload) -> {
                buffer.writeByte(VERSION);
                buffer.writeByte(payload.mode);
            },
            buffer -> {
                byte version = buffer.readByte();
                byte mode = buffer.readByte();
                if (version != VERSION || mode < MINECRAFT || mode > FLIGHT_DESKTOP) {
                    throw new IllegalArgumentException("Unsupported JUMP mode payload");
                }
                return new JumpModePayload(mode);
            });

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

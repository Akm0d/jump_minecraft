package com.jump.minecraft.client;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

record JumpDesktopInputPayload(float forward, float right, float mouseX, float lookPitch, boolean jump)
        implements CustomPacketPayload {
    static final byte VERSION = 1;
    static final Type<JumpDesktopInputPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath("jump", "input"));
    static final StreamCodec<FriendlyByteBuf, JumpDesktopInputPayload> CODEC = StreamCodec.of(
            (buffer, payload) -> {
                buffer.writeByte(VERSION);
                buffer.writeFloat(payload.forward);
                buffer.writeFloat(payload.right);
                buffer.writeFloat(payload.mouseX);
                buffer.writeFloat(payload.lookPitch);
                buffer.writeByte(payload.jump ? 1 : 0);
            },
            buffer -> {
                byte version = buffer.readByte();
                if (version != VERSION) throw new IllegalArgumentException("Unsupported JUMP input payload");
                return new JumpDesktopInputPayload(
                        buffer.readFloat(), buffer.readFloat(), buffer.readFloat(), buffer.readFloat(),
                        buffer.readByte() != 0);
            });

    static JumpDesktopInputPayload neutral() {
        return new JumpDesktopInputPayload(0, 0, 0, 0, false);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

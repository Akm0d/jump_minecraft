package com.jump.minecraft;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

record JumpDesktopInput(double forward, double right, double mouseX, double lookPitch, boolean jump) {
    static final String CHANNEL = "jump:input";
    static final byte VERSION = 1;
    private static final int WIRE_SIZE = 18;

    JumpDesktopInput {
        requireBounded(forward, 1.0, "forward");
        requireBounded(right, 1.0, "right");
        requireBounded(mouseX, 1_000.0, "mouseX");
        requireBounded(lookPitch, 85.0, "lookPitch");
    }

    static JumpDesktopInput decode(byte[] bytes) {
        if (bytes == null || bytes.length != WIRE_SIZE || bytes[0] != VERSION) {
            throw new IllegalArgumentException("unsupported JUMP desktop input payload");
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        buffer.get();
        return new JumpDesktopInput(
                buffer.getFloat(), buffer.getFloat(), buffer.getFloat(), buffer.getFloat(), buffer.get() != 0);
    }

    static JumpDesktopInput neutral() {
        return new JumpDesktopInput(0.0, 0.0, 0.0, 0.0, false);
    }

    private static void requireBounded(double value, double bound, String name) {
        if (!Double.isFinite(value) || Math.abs(value) > bound) {
            throw new IllegalArgumentException(name + " is outside the permitted range");
        }
    }
}

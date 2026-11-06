package com.jump.minecraft;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ProtocolV2Tests {
    @Test
    void decodesSourceVerifiedLayoutAndEveryLifecycleState() {
        for (FlightState state : FlightState.values()) {
            byte[] fixture = frame(1_793_980_123_456_789L + state.ordinal(), state, "{\"notice\":true}");
            FlightFrame decoded = JumpProtocol.decode(fixture, fixture.length);
            assertEquals(state, decoded.state(), "v2 state " + state);
            assertEquals(1.25, decoded.position().x(), 1.0e-6, "v2 position");
            assertEquals(-2.5, decoded.position().y(), 1.0e-6, "v2 position");
            assertEquals(1.0, decoded.forward().z(), 1.0e-6, "v2 forward");
            assertEquals(1.0, decoded.up().y(), 1.0e-6, "v2 up");
            assertTrue(decoded.hasTracking(), "v2 tracking");
            assertEquals(47.5, decoded.speed(), 1.0e-6, "v2 speed");
        }
    }

    @Test
    void rejectsMalformedFrames() {
        byte[] valid = frame(123L, FlightState.FLYING, null);
        assertThrows(IllegalArgumentException.class,
                () -> JumpProtocol.decodeV2(Arrays.copyOf(valid, GameFrameV2Decoder.BINARY_SIZE - 1),
                        GameFrameV2Decoder.BINARY_SIZE - 1),
                "truncated frame");

        byte[] unsupportedVersion = valid.clone();
        ByteBuffer.wrap(unsupportedVersion).order(ByteOrder.LITTLE_ENDIAN).putShort(2, (short) 3);
        assertThrows(IllegalArgumentException.class,
                () -> JumpProtocol.decodeV2(unsupportedVersion, unsupportedVersion.length),
                "unsupported version");

        byte[] wrongSize = valid.clone();
        ByteBuffer.wrap(wrongSize).order(ByteOrder.LITTLE_ENDIAN).putShort(4, (short) 364);
        assertThrows(IllegalArgumentException.class,
                () -> JumpProtocol.decodeV2(wrongSize, wrongSize.length),
                "unsupported ABI layout");

        byte[] invalidState = valid.clone();
        ByteBuffer.wrap(invalidState).order(ByteOrder.LITTLE_ENDIAN).putInt(16, 8);
        assertThrows(IllegalArgumentException.class,
                () -> JumpProtocol.decodeV2(invalidState, invalidState.length),
                "unknown lifecycle state");

        byte[] invalidTracking = valid.clone();
        invalidTracking[344] = 2;
        assertThrows(IllegalArgumentException.class,
                () -> JumpProtocol.decodeV2(invalidTracking, invalidTracking.length),
                "invalid bool representation");

        byte[] invalidJson = frame(124L, FlightState.LANDED, "{broken");
        assertThrows(IllegalArgumentException.class,
                () -> JumpProtocol.decodeV2(invalidJson, invalidJson.length),
                "malformed trailing JSON");
    }

    static byte[] frame(long findex, FlightState state, String trailingJson) {
        byte[] json = trailingJson == null ? new byte[0] : trailingJson.getBytes(StandardCharsets.UTF_8);
        int jsonSize = json.length == 0 ? 0 : json.length + 1;
        ByteBuffer bytes = ByteBuffer.allocate(GameFrameV2Decoder.BINARY_SIZE + jsonSize)
                .order(ByteOrder.LITTLE_ENDIAN);
        bytes.putShort(0, (short) 0);
        bytes.putShort(2, (short) 2);
        bytes.putShort(4, (short) GameFrameV2Decoder.BINARY_SIZE);
        bytes.putShort(6, (short) jsonSize);
        bytes.putLong(8, findex);
        bytes.putInt(16, state.ordinal());
        putVector(bytes, 20, 1.25f, -2.5f, 3.75f);
        putVector(bytes, 32, 0.0f, 0.0f, 1.0f);
        putVector(bytes, 44, 0.0f, 1.0f, 0.0f);
        bytes.put(344, (byte) 1);
        bytes.putFloat(348, 47.5f);
        if (jsonSize > 0) {
            bytes.position(GameFrameV2Decoder.BINARY_SIZE);
            bytes.put(json);
            bytes.put((byte) 0);
        }
        return bytes.array();
    }

    private static void putVector(ByteBuffer bytes, int offset, float x, float y, float z) {
        bytes.putFloat(offset, x);
        bytes.putFloat(offset + Float.BYTES, y);
        bytes.putFloat(offset + 2 * Float.BYTES, z);
    }
}

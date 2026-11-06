package com.jump.minecraft;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

final class GameFrameV2Decoder {
    static final int BINARY_SIZE = 368;
    private static final int TYPE = 0;
    private static final int VERSION = 2;
    private static final int STATE_OFFSET = 16;
    private static final int POSITION_OFFSET = 20;
    private static final int FORWARD_OFFSET = 32;
    private static final int UP_OFFSET = 44;
    private static final int HAS_TRACKING_OFFSET = 344;
    private static final int SPEED_OFFSET = 348;

    private GameFrameV2Decoder() {
    }

    static FlightFrame decode(byte[] bytes, int length) {
        FlightFrameDecoder.validateBounds(bytes, length);
        if (length < BINARY_SIZE) {
            throw new IllegalArgumentException(
                    "Truncated v2 frame: expected at least " + BINARY_SIZE + " bytes, got " + length);
        }

        ByteBuffer frame = ByteBuffer.wrap(bytes, 0, length).order(ByteOrder.LITTLE_ENDIAN);
        int type = Short.toUnsignedInt(frame.getShort(0));
        int version = Short.toUnsignedInt(frame.getShort(2));
        int binarySize = Short.toUnsignedInt(frame.getShort(4));
        int jsonSize = frame.getShort(6);
        validateHeader(type, version, binarySize, jsonSize, length);

        long findex = frame.getLong(8);
        if (findex < 0) {
            throw new IllegalArgumentException("v2 findex exceeds signed 64-bit range");
        }
        int stateCode = frame.getInt(STATE_OFFSET);
        FlightState[] states = FlightState.values();
        if (stateCode < 0 || stateCode >= states.length) {
            throw new IllegalArgumentException("Unsupported v2 flight state: " + stateCode);
        }
        int trackingByte = Byte.toUnsignedInt(frame.get(HAS_TRACKING_OFFSET));
        if (trackingByte > 1) {
            throw new IllegalArgumentException("Invalid v2 tracking flag: " + trackingByte);
        }
        validateTrailingJson(bytes, binarySize, jsonSize);

        return new FlightFrame(
                findex,
                states[stateCode],
                vector(frame, POSITION_OFFSET),
                vector(frame, FORWARD_OFFSET),
                vector(frame, UP_OFFSET),
                trackingByte == 1,
                frame.getFloat(SPEED_OFFSET));
    }

    private static void validateHeader(
            int type,
            int version,
            int binarySize,
            int jsonSize,
            int datagramSize) {
        if (type != TYPE) {
            throw new IllegalArgumentException("Unsupported v2 frame type: " + type);
        }
        if (version != VERSION) {
            throw new IllegalArgumentException("Unsupported GameFrame version: " + version);
        }
        if (binarySize != BINARY_SIZE) {
            throw new IllegalArgumentException(
                    "Unsupported v2 binary size: " + binarySize + " (expected " + BINARY_SIZE + ")");
        }
        if (jsonSize < 0) {
            throw new IllegalArgumentException("Negative v2 JSON size: " + jsonSize);
        }
        if (datagramSize != binarySize + jsonSize) {
            throw new IllegalArgumentException(
                    "Invalid v2 datagram size: header declares " + (binarySize + jsonSize)
                            + " bytes, got " + datagramSize);
        }
    }

    private static Vec3 vector(ByteBuffer frame, int offset) {
        return new Vec3(
                frame.getFloat(offset),
                frame.getFloat(offset + Float.BYTES),
                frame.getFloat(offset + 2 * Float.BYTES));
    }

    private static void validateTrailingJson(byte[] bytes, int offset, int length) {
        if (length == 0) return;
        if (length == 1 || bytes[offset + length - 1] != 0) {
            throw new IllegalArgumentException("v2 trailing JSON is not NUL-terminated");
        }
        try {
            String json = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, offset, length - 1))
                    .toString();
            MiniJson.parseObject(json);
        } catch (CharacterCodingException error) {
            throw new IllegalArgumentException("Invalid UTF-8 in v2 trailing JSON", error);
        }
    }
}

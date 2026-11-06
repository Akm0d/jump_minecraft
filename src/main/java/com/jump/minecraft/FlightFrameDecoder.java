package com.jump.minecraft;

final class FlightFrameDecoder {
    private FlightFrameDecoder() {
    }

    static FlightFrame decode(byte[] bytes, int length) {
        return protocol(bytes, length).equals("v1")
                ? JumpProtocol.decodeV1(bytes, length)
                : GameFrameV2Decoder.decode(bytes, length);
    }

    static String protocol(byte[] bytes, int length) {
        validateBounds(bytes, length);
        for (int index = 0; index < length; index++) {
            if (!Character.isWhitespace(bytes[index] & 0xff)) {
                return bytes[index] == '{' ? "v1" : "v2";
            }
        }
        throw new IllegalArgumentException("Empty frame");
    }

    static void validateBounds(byte[] bytes, int length) {
        if (bytes == null) throw new IllegalArgumentException("Frame bytes must not be null");
        if (length <= 0 || length > bytes.length) {
            throw new IllegalArgumentException(
                    "Invalid frame length " + length + " for buffer of " + bytes.length + " bytes");
        }
    }
}

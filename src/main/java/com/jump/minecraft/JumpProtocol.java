package com.jump.minecraft;

import java.nio.charset.StandardCharsets;
import java.util.Map;

public final class JumpProtocol {
    private JumpProtocol() {
    }

    public static FlightFrame decode(byte[] bytes, int length) {
        return FlightFrameDecoder.decode(bytes, length);
    }

    public static FlightFrame decodeV1(byte[] bytes, int length) {
        FlightFrameDecoder.validateBounds(bytes, length);
        String json = new String(bytes, 0, length, StandardCharsets.UTF_8);
        Map<String, Object> root = MiniJson.parseObject(json);
        return new FlightFrame(
                requiredFrameIndex(root),
                FlightState.fromWire(requiredString(root, "state")),
                vector(root, "pos"),
                vector(root, "forward"),
                vector(root, "up"),
                optionalBoolean(root, "hasTracking", true),
                optionalNumber(root, "speed", 0.0));
    }

    public static FlightFrame decodeV2(byte[] bytes, int length) {
        return GameFrameV2Decoder.decode(bytes, length);
    }

    public static byte[] heartbeat(long findex, double expirySeconds) {
        if (findex < 0L) throw new IllegalArgumentException("Frame index must not be negative");
        if (!Double.isFinite(expirySeconds) || expirySeconds <= 0.0) {
            throw new IllegalArgumentException("Expiry must be finite and positive");
        }
        String json = "{\"findex\":" + findex
                + ",\"expiresInSeconds\":" + expirySeconds
                + ",\"helicopterFlag\":false}";
        return json.getBytes(StandardCharsets.UTF_8);
    }

    private static Vec3 vector(Map<String, Object> root, String name) {
        Object raw = root.get(name);
        if (!(raw instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("Missing object field: " + name);
        }
        return new Vec3(
                requiredNumber(map, "x").doubleValue(),
                requiredNumber(map, "y").doubleValue(),
                requiredNumber(map, "z").doubleValue());
    }

    private static Number requiredNumber(Map<?, ?> object, String name) {
        Object value = object.get(name);
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException("Missing numeric field: " + name);
        }
        if (!Double.isFinite(number.doubleValue())) {
            throw new IllegalArgumentException("Non-finite numeric field: " + name);
        }
        return number;
    }

    private static long requiredFrameIndex(Map<String, Object> root) {
        return FrameIndex.fromNumber(requiredNumber(root, "findex"));
    }

    private static String requiredString(Map<String, Object> object, String name) {
        Object value = object.get(name);
        if (!(value instanceof String string)) {
            throw new IllegalArgumentException("Missing string field: " + name);
        }
        return string;
    }

    private static boolean optionalBoolean(Map<String, Object> object, String name, boolean fallback) {
        Object value = object.get(name);
        return value instanceof Boolean bool ? bool : fallback;
    }

    private static double optionalNumber(Map<String, Object> object, String name, double fallback) {
        Object value = object.get(name);
        if (!(value instanceof Number number)) return fallback;
        double result = number.doubleValue();
        if (!Double.isFinite(result)) throw new IllegalArgumentException("Non-finite numeric field: " + name);
        return result;
    }
}

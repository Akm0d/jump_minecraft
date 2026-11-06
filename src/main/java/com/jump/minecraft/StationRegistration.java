package com.jump.minecraft;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Pattern;

record StationRegistration(
        String stationId,
        String stationName,
        InetSocketAddress endpoint,
        long receivedNanos) {
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    static StationRegistration decodeOrNull(
            byte[] bytes,
            int length,
            InetSocketAddress endpoint,
            long receivedNanos) {
        String json = new String(bytes, 0, length, StandardCharsets.UTF_8);
        Map<String, Object> root = MiniJson.parseObject(json);
        if (!root.containsKey("jumpStation")) return null;
        if (!(root.get("jumpStation") instanceof Number version) || version.intValue() != 1) {
            throw new IllegalArgumentException("Unsupported station registration version");
        }
        String stationId = requiredString(root, "stationId");
        String stationName = requiredString(root, "stationName").trim();
        if (!ID.matcher(stationId).matches()) {
            throw new IllegalArgumentException("stationId must match " + ID.pattern());
        }
        if (stationName.isEmpty() || stationName.length() > 80) {
            throw new IllegalArgumentException("stationName must contain 1 to 80 characters");
        }
        return new StationRegistration(stationId, stationName, endpoint, receivedNanos);
    }

    private static String requiredString(Map<String, Object> root, String name) {
        Object value = root.get(name);
        if (!(value instanceof String string)) {
            throw new IllegalArgumentException("Missing station registration field: " + name);
        }
        return string;
    }
}

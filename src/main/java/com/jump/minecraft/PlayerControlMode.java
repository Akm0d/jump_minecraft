package com.jump.minecraft;

import java.util.Locale;

enum PlayerControlMode {
    MINECRAFT(0, "MINECRAFT"),
    FLIGHT(1, "FLIGHT"),
    FLIGHT_DESKTOP(2, "FLIGHT DESKTOP");

    private final byte wireId;
    private final String displayName;

    PlayerControlMode(int wireId, String displayName) {
        this.wireId = (byte) wireId;
        this.displayName = displayName;
    }

    PlayerControlMode next() {
        return switch (this) {
            case MINECRAFT -> FLIGHT;
            case FLIGHT -> FLIGHT_DESKTOP;
            case FLIGHT_DESKTOP -> MINECRAFT;
        };
    }

    boolean isFlightControlled() {
        return this != MINECRAFT;
    }

    byte wireId() {
        return wireId;
    }

    String displayName() {
        return displayName;
    }

    static PlayerControlMode fromCommand(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "minecraft" -> MINECRAFT;
            case "flight" -> FLIGHT;
            case "desktop" -> FLIGHT_DESKTOP;
            default -> throw new IllegalArgumentException("mode must be minecraft, flight, or desktop");
        };
    }
}

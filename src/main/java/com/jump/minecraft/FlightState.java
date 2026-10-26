package com.jump.minecraft;

import java.util.Locale;

public enum FlightState {
    NOT_INITIALIZED,
    PREPARING,
    JUMPING,
    FLYING,
    PRE_DEPLOYED,
    DEPLOYED,
    CANOPY_FLYING,
    LANDED;

    public static FlightState fromWire(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Missing flight state");
        }
        return valueOf(value.toUpperCase(Locale.ROOT));
    }

    public boolean isTrajectory() {
        return this == FLYING || this == PRE_DEPLOYED || this == DEPLOYED || this == CANOPY_FLYING;
    }
}

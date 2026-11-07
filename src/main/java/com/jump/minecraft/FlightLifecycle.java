package com.jump.minecraft;

public final class FlightLifecycle {
    private boolean armed;
    private boolean minecraftOriginCaptured;
    private boolean trajectoryStarted;
    private FlightState lastState = FlightState.NOT_INITIALIZED;

    public void arm() {
        armed = true;
        minecraftOriginCaptured = false;
        trajectoryStarted = false;
        lastState = FlightState.NOT_INITIALIZED;
    }

    public void disarm() {
        armed = false;
        minecraftOriginCaptured = false;
        trajectoryStarted = false;
        lastState = FlightState.NOT_INITIALIZED;
    }

    public boolean isArmed() {
        return armed;
    }

    public boolean isActive() {
        return trajectoryStarted;
    }

    public SessionDecision observe(FlightState state) {
        if (!armed) return SessionDecision.WAIT;
        if (state == FlightState.NOT_INITIALIZED) {
            lastState = state;
            return SessionDecision.WAIT;
        }
        if (state == FlightState.LANDED) return observeLanded();
        if (state == FlightState.PREPARING) return observePreparing();
        if (state == FlightState.JUMPING || state.isTrajectory()) {
            lastState = state;
            if (!minecraftOriginCaptured) minecraftOriginCaptured = true;
            if (!trajectoryStarted) {
                trajectoryStarted = true;
                return SessionDecision.START_TRAJECTORY;
            }
            return SessionDecision.APPLY_TRAJECTORY;
        }
        return SessionDecision.WAIT;
    }

    private SessionDecision observeLanded() {
        lastState = FlightState.LANDED;
        if (!trajectoryStarted) return SessionDecision.WAIT;
        minecraftOriginCaptured = false;
        trajectoryStarted = false;
        return SessionDecision.RESTORE_LANDED;
    }

    private SessionDecision observePreparing() {
        if (lastState != FlightState.PREPARING && trajectoryStarted) {
            minecraftOriginCaptured = false;
            trajectoryStarted = false;
            lastState = FlightState.PREPARING;
            return SessionDecision.RESTORE_RESET;
        }
        lastState = FlightState.PREPARING;
        minecraftOriginCaptured = true;
        if (trajectoryStarted) return SessionDecision.APPLY_TRAJECTORY;
        trajectoryStarted = true;
        return SessionDecision.START_TRAJECTORY;
    }
}

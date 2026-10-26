package com.jump.minecraft;

public final class FlightLifecycle {
    private boolean armed;
    private boolean minecraftOriginCaptured;
    private boolean trajectoryStarted;

    public void arm() {
        armed = true;
        minecraftOriginCaptured = false;
        trajectoryStarted = false;
    }

    public void disarm() {
        armed = false;
        minecraftOriginCaptured = false;
        trajectoryStarted = false;
    }

    public boolean isArmed() {
        return armed;
    }

    public boolean isActive() {
        return trajectoryStarted;
    }

    public SessionDecision observe(FlightState state) {
        if (!armed) return SessionDecision.WAIT;
        if (state == FlightState.LANDED) {
            if (trajectoryStarted) {
                disarm();
                return SessionDecision.RESTORE_LANDED;
            }
            return SessionDecision.WAIT;
        }
        if (state == FlightState.PREPARING) {
            if (minecraftOriginCaptured || trajectoryStarted) {
                disarm();
                return SessionDecision.RESTORE_RESET;
            }
            return SessionDecision.WAIT;
        }
        if (state == FlightState.JUMPING && !minecraftOriginCaptured) {
            minecraftOriginCaptured = true;
            return SessionDecision.CAPTURE_MINECRAFT_ORIGIN;
        }
        if (state.isTrajectory()) {
            if (!minecraftOriginCaptured) minecraftOriginCaptured = true;
            if (!trajectoryStarted) {
                trajectoryStarted = true;
                return SessionDecision.START_TRAJECTORY;
            }
            return SessionDecision.APPLY_TRAJECTORY;
        }
        return SessionDecision.WAIT;
    }
}

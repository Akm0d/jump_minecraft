package com.jump.minecraft;

record JumpRequestResult(boolean accepted, String message, StationControlRequest request) {
    static JumpRequestResult rejected(String message) {
        return new JumpRequestResult(false, message, null);
    }

    static JumpRequestResult accepted(StationControlRequest request) {
        return new JumpRequestResult(true, "desktop FLIGHT jump requested", request);
    }
}

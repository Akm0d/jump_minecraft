package com.jump.minecraft;

import java.util.UUID;

record StationControlRequest(String stationId, String requestId, Action action) {
    enum Action {
        FORCE_JUMP("forceJumpEvent");

        private final String wireName;

        Action(String wireName) {
            this.wireName = wireName;
        }
    }

    static StationControlRequest forceJump(String stationId) {
        return new StationControlRequest(stationId, UUID.randomUUID().toString(), Action.FORCE_JUMP);
    }

    String toJson() {
        return "{\"jumpControl\":1,\"stationId\":\"" + stationId
                + "\",\"requestId\":\"" + requestId
                + "\",\"action\":\"" + action.wireName + "\"}";
    }
}

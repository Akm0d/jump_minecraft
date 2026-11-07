package com.jump.minecraft;

import java.util.UUID;

record StationControlRequest(
        String stationId,
        String requestId,
        Action action,
        JumpDesktopInput input,
        TerrainCollisionFeedback collision) {
    enum Action {
        DESKTOP_INPUT("desktopInput"),
        PUSH_PLAYER("pushPlayer");

        private final String wireName;

        Action(String wireName) {
            this.wireName = wireName;
        }
    }

    static StationControlRequest desktopInput(String stationId, JumpDesktopInput input) {
        return new StationControlRequest(
                stationId, UUID.randomUUID().toString(), Action.DESKTOP_INPUT, input, null);
    }

    static StationControlRequest collision(String stationId, TerrainCollisionFeedback collision) {
        return new StationControlRequest(
                stationId, UUID.randomUUID().toString(), Action.PUSH_PLAYER, null, collision);
    }

    String toJson() {
        String prefix = "{\"jumpControl\":1,\"stationId\":\"" + stationId
                + "\",\"requestId\":\"" + requestId
                + "\",\"action\":\"" + action.wireName + "\"";
        return switch (action) {
            case DESKTOP_INPUT -> prefix + ",\"input\":" + inputJson(input) + "}";
            case PUSH_PLAYER -> prefix + ",\"collision\":" + collision.toJson() + "}";
        };
    }

    private static String inputJson(JumpDesktopInput value) {
        return "{\"forward\":" + value.forward()
                + ",\"right\":" + value.right()
                + ",\"mouseX\":" + value.mouseX()
                + ",\"lookPitch\":" + value.lookPitch()
                + ",\"jump\":" + value.jump() + "}";
    }
}

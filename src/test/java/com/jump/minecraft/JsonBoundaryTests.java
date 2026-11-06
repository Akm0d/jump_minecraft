package com.jump.minecraft;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class JsonBoundaryTests {
    @Test
    void acceptsConfiguredNestingBoundary() {
        String json = "{\"value\":" + nestedArrays(MiniJson.MAX_NESTING - 1) + "}";
        assertDoesNotThrow(() -> MiniJson.parseObject(json));
    }

    @Test
    void rejectsExcessiveNestingAtEveryNetworkIngress() throws Exception {
        String nested = nestedArrays(MiniJson.MAX_NESTING);

        String registration = "{\"jumpStation\":1,\"stationId\":\"deep\","
                + "\"stationName\":\"Deep\",\"ignored\":" + nested + "}";
        assertNestingRejected(() -> StationRegistration.decodeOrNull(
                registration.getBytes(StandardCharsets.UTF_8),
                registration.length(),
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 40_000),
                1));

        String v1 = "{\"findex\":1,\"state\":\"landed\","
                + "\"pos\":{\"x\":0,\"y\":0,\"z\":0},"
                + "\"forward\":{\"x\":0,\"y\":0,\"z\":1},"
                + "\"up\":{\"x\":0,\"y\":1,\"z\":0},\"ignored\":" + nested + "}";
        assertNestingRejected(() -> JumpProtocol.decodeV1(
                v1.getBytes(StandardCharsets.UTF_8), v1.length()));

        byte[] v2 = ProtocolV2Tests.frame(
                2, FlightState.LANDED, "{\"ignored\":" + nested + "}");
        assertNestingRejected(() -> JumpProtocol.decodeV2(v2, v2.length));
    }

    private static String nestedArrays(int count) {
        return "[".repeat(count) + "0" + "]".repeat(count);
    }

    private static void assertNestingRejected(org.junit.jupiter.api.function.Executable executable) {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, executable);
        assertTrue(error.getMessage().contains("JSON nesting exceeds"));
    }
}

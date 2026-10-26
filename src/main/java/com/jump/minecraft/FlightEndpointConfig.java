package com.jump.minecraft;

public record FlightEndpointConfig(
        String host,
        int port,
        String localBindHost,
        int localPort,
        long heartbeatIntervalMillis,
        double subscriptionExpirySeconds,
        int socketTimeoutMillis) {

    public FlightEndpointConfig {
        if (host == null || host.isBlank()) throw new IllegalArgumentException("flight.host is required");
        if (localBindHost == null || localBindHost.isBlank()) {
            throw new IllegalArgumentException("flight.local-bind-host is required");
        }
        if (port < 1 || port > 65535) throw new IllegalArgumentException("Invalid flight.port");
        if (localPort < 0 || localPort > 65535) throw new IllegalArgumentException("Invalid local port");
        if (heartbeatIntervalMillis < 100) throw new IllegalArgumentException("Heartbeat interval is too short");
        if (!(subscriptionExpirySeconds > 0.0)) throw new IllegalArgumentException("Subscription expiry must be positive");
        if (socketTimeoutMillis < 20) throw new IllegalArgumentException("Socket timeout is too short");
    }
}

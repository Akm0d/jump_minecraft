package com.jump.minecraft;

public record StationNetworkConfig(
        String bindHost,
        int port,
        long heartbeatIntervalMillis,
        double subscriptionExpirySeconds,
        int socketTimeoutMillis,
        long registrationTtlMillis,
        int maxStations) {

    public StationNetworkConfig {
        if (bindHost == null || bindHost.isBlank()) throw new IllegalArgumentException("bindHost is required");
        if (port < 1 || port > 65_535) throw new IllegalArgumentException("port is out of range");
        if (heartbeatIntervalMillis <= 0) throw new IllegalArgumentException("heartbeat interval must be positive");
        if (!(subscriptionExpirySeconds > 0.0) || !Double.isFinite(subscriptionExpirySeconds)) {
            throw new IllegalArgumentException("subscription expiry must be finite and positive");
        }
        if (socketTimeoutMillis <= 0) throw new IllegalArgumentException("socket timeout must be positive");
        if (registrationTtlMillis <= heartbeatIntervalMillis) {
            throw new IllegalArgumentException("registration TTL must exceed heartbeat interval");
        }
        if (maxStations < 1 || maxStations > 256) {
            throw new IllegalArgumentException("maxStations must be between 1 and 256");
        }
    }
}

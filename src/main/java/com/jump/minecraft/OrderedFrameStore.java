package com.jump.minecraft;

public final class OrderedFrameStore {
    public record Snapshot(FlightFrame frame, long receivedNanos) {
    }

    private Snapshot latest;

    public synchronized boolean offer(FlightFrame frame, long receivedNanos) {
        if (latest != null && frame.findex() <= latest.frame().findex()) return false;
        latest = new Snapshot(frame, receivedNanos);
        return true;
    }

    public synchronized Snapshot latest() {
        return latest;
    }

    public synchronized boolean isStale(long nowNanos, long staleAfterNanos) {
        return latest == null || nowNanos - latest.receivedNanos() > staleAfterNanos;
    }
}

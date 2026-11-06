package com.jump.minecraft;

final class FrameIndex {
    private FrameIndex() {
    }

    static long fromNumber(Number number) {
        if (number instanceof Byte || number instanceof Short
                || number instanceof Integer || number instanceof Long) {
            long value = number.longValue();
            if (value >= 0L) return value;
        } else {
            double value = number.doubleValue();
            if (value >= 0.0 && Math.floor(value) == value && value < 0x1.0p63) {
                return (long) value;
            }
        }
        throw new IllegalArgumentException("findex must be a non-negative signed 64-bit integer");
    }
}

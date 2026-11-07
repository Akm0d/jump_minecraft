package com.jump.minecraft;

final class JumpControlProtocol {
    static final String CHANNEL = "jump:control";
    static final byte VERSION = 1;
    static final byte TOGGLE_MODE = 1;

    private JumpControlProtocol() {
    }

    static byte decode(byte[] message) {
        if (message == null || message.length != 2 || message[0] != VERSION) {
            throw new IllegalArgumentException("unsupported JUMP client control payload");
        }
        if (message[1] != TOGGLE_MODE) {
            throw new IllegalArgumentException("unknown JUMP client control action");
        }
        return message[1];
    }
}

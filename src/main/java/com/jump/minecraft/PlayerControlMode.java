package com.jump.minecraft;

enum PlayerControlMode {
    FLIGHT,
    FREE;

    PlayerControlMode toggled() {
        return this == FLIGHT ? FREE : FLIGHT;
    }
}

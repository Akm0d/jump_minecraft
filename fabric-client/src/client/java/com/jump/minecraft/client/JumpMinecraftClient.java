package com.jump.minecraft.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

public final class JumpMinecraftClient implements ClientModInitializer {
    private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(
            Identifier.fromNamespaceAndPath("jump", "minecraft"));
    private KeyMapping toggleMode;
    private byte mode = JumpModePayload.MINECRAFT;
    private boolean jumpDown;
    private float lastYaw = Float.NaN;

    @Override
    public void onInitializeClient() {
        PayloadTypeRegistry.serverboundPlay().register(JumpControlPayload.TYPE, JumpControlPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(JumpDesktopInputPayload.TYPE, JumpDesktopInputPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(JumpModePayload.TYPE, JumpModePayload.CODEC);
        toggleMode = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.jump_minecraft.toggle_mode", InputConstants.Type.KEYBOARD, InputConstants.KEY_F1, CATEGORY));
        ClientPlayNetworking.registerGlobalReceiver(JumpModePayload.TYPE, (payload, context) ->
                context.client().execute(() -> setMode(context.client(), payload.mode())));
        ClientLifecycleEvents.CLIENT_STARTED.register(JumpMinecraftClient::clearVanillaConflicts);
        configureHud();
        ClientTickEvents.END_CLIENT_TICK.register(this::onEndTick);
    }

    private void onEndTick(Minecraft client) {
        if (client.player == null || client.getConnection() == null) {
            mode = JumpModePayload.MINECRAFT;
            jumpDown = false;
            lastYaw = Float.NaN;
            return;
        }

        while (toggleMode.consumeClick()) sendToggle(client);
        if (mode == JumpModePayload.FLIGHT_DESKTOP) sendDesktopInput(client);
        else {
            jumpDown = false;
            lastYaw = client.player.getYRot();
        }
    }

    private static void clearVanillaConflicts(Minecraft client) {
        boolean changed = false;
        if (client.options.keyToggleGui.matches(InputConstants.Type.KEYBOARD.getOrCreate(InputConstants.KEY_F1))) {
            client.options.keyToggleGui.setKey(InputConstants.UNKNOWN);
            changed = true;
        }
        if (changed) client.options.save();
    }

    private void sendToggle(Minecraft client) {
        if (!ClientPlayNetworking.canSend(JumpControlPayload.TYPE)) {
            client.player.sendSystemMessage(Component.literal("JUMP controls are unavailable on this server."));
            return;
        }
        ClientPlayNetworking.send(new JumpControlPayload(JumpControlPayload.TOGGLE_MODE));
    }

    private void sendDesktopInput(Minecraft client) {
        if (!ClientPlayNetworking.canSend(JumpDesktopInputPayload.TYPE)) return;
        boolean focused = client.isWindowActive() && client.mouseHandler.isMouseGrabbed();
        boolean space = focused && client.options.keyJump.isDown();
        boolean jump = space && !jumpDown;
        jumpDown = space;
        float yaw = client.player.getYRot();
        float yawDelta = Float.isNaN(lastYaw) ? 0.0f : normalizeYaw(yaw - lastYaw);
        lastYaw = yaw;
        JumpDesktopInputPayload input = focused
                ? new JumpDesktopInputPayload(
                        axis(client.options.keyUp.isDown(), client.options.keyDown.isDown()),
                        axis(client.options.keyRight.isDown(), client.options.keyLeft.isDown()),
                        clamp(-yawDelta / 0.12f, -1000.0f, 1000.0f),
                        clamp(client.player.getXRot(), -85.0f, 85.0f),
                        jump)
                : JumpDesktopInputPayload.neutral();
        ClientPlayNetworking.send(input);
    }

    private void setMode(Minecraft client, byte nextMode) {
        if (mode == JumpModePayload.FLIGHT_DESKTOP && nextMode != mode
                && client.player != null && ClientPlayNetworking.canSend(JumpDesktopInputPayload.TYPE)) {
            ClientPlayNetworking.send(JumpDesktopInputPayload.neutral());
        }
        mode = nextMode;
        jumpDown = false;
        lastYaw = client.player == null ? Float.NaN : client.player.getYRot();
    }

    private void configureHud() {
        conditionalHud(VanillaHudElements.CROSSHAIR);
        conditionalHud(VanillaHudElements.HOTBAR);
        conditionalHud(VanillaHudElements.ARMOR_BAR);
        conditionalHud(VanillaHudElements.HEALTH_BAR);
        conditionalHud(VanillaHudElements.FOOD_BAR);
        conditionalHud(VanillaHudElements.AIR_BAR);
        conditionalHud(VanillaHudElements.EXPERIENCE_LEVEL);
        conditionalHud(VanillaHudElements.INFO_BAR);
        conditionalHud(VanillaHudElements.INFO_BAR_FOREGROUND);
        conditionalHud(VanillaHudElements.HELD_ITEM_TOOLTIP);
    }

    private void conditionalHud(Identifier id) {
        HudElementRegistry.replaceElement(id, original -> (graphics, delta) -> {
            if (mode == JumpModePayload.MINECRAFT) original.extractRenderState(graphics, delta);
        });
    }

    private static float axis(boolean positive, boolean negative) {
        return (positive ? 1.0f : 0.0f) - (negative ? 1.0f : 0.0f);
    }

    private static float normalizeYaw(float yaw) {
        float result = yaw % 360.0f;
        if (result >= 180.0f) result -= 360.0f;
        if (result < -180.0f) result += 360.0f;
        return result;
    }

    private static float clamp(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}

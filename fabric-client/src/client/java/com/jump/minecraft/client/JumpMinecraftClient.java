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
    private KeyMapping requestJump;

    @Override
    public void onInitializeClient() {
        PayloadTypeRegistry.serverboundPlay().register(JumpControlPayload.TYPE, JumpControlPayload.CODEC);
        toggleMode = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.jump_minecraft.toggle_mode", InputConstants.Type.KEYBOARD, InputConstants.KEY_F1, CATEGORY));
        requestJump = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.jump_minecraft.request_jump", InputConstants.Type.KEYBOARD, InputConstants.KEY_F2, CATEGORY));
        ClientLifecycleEvents.CLIENT_STARTED.register(JumpMinecraftClient::clearVanillaConflicts);
        hideSurvivalHud();
        ClientTickEvents.END_CLIENT_TICK.register(this::onEndTick);
    }

    private void onEndTick(Minecraft client) {
        if (client.player == null || client.getConnection() == null) return;

        while (toggleMode.consumeClick()) send(client, JumpControlPayload.TOGGLE_MODE, "Toggling JUMP mode...");
        while (requestJump.consumeClick()) send(client, JumpControlPayload.REQUEST_JUMP, "Requesting desktop FLIGHT jump...");
    }

    private static void clearVanillaConflicts(Minecraft client) {
        boolean changed = false;
        if (client.options.keyToggleGui.matches(InputConstants.Type.KEYBOARD.getOrCreate(InputConstants.KEY_F1))) {
            client.options.keyToggleGui.setKey(InputConstants.UNKNOWN);
            changed = true;
        }
        if (client.options.keyScreenshot.matches(InputConstants.Type.KEYBOARD.getOrCreate(InputConstants.KEY_F2))) {
            client.options.keyScreenshot.setKey(InputConstants.UNKNOWN);
            changed = true;
        }
        if (changed) client.options.save();
    }

    private void send(Minecraft client, byte action, String feedback) {
        if (!ClientPlayNetworking.canSend(JumpControlPayload.TYPE)) {
            client.player.sendSystemMessage(Component.literal("JUMP controls are unavailable on this server."));
            return;
        }
        ClientPlayNetworking.send(new JumpControlPayload(action));
        client.player.sendSystemMessage(Component.literal(feedback));
    }

    private static void hideSurvivalHud() {
        HudElementRegistry.removeElement(VanillaHudElements.CROSSHAIR);
        HudElementRegistry.removeElement(VanillaHudElements.HOTBAR);
        HudElementRegistry.removeElement(VanillaHudElements.ARMOR_BAR);
        HudElementRegistry.removeElement(VanillaHudElements.HEALTH_BAR);
        HudElementRegistry.removeElement(VanillaHudElements.FOOD_BAR);
        HudElementRegistry.removeElement(VanillaHudElements.AIR_BAR);
        HudElementRegistry.removeElement(VanillaHudElements.EXPERIENCE_LEVEL);
        HudElementRegistry.removeElement(VanillaHudElements.INFO_BAR);
        HudElementRegistry.removeElement(VanillaHudElements.INFO_BAR_FOREGROUND);
        HudElementRegistry.removeElement(VanillaHudElements.HELD_ITEM_TOOLTIP);
    }
}

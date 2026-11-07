import pathlib
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[1]
CLIENT_SOURCE = ROOT / "fabric-client/src/client/java/com/jump/minecraft/client/JumpMinecraftClient.java"


class FabricClientInitializationTests(unittest.TestCase):
    def test_key_conflicts_are_resolved_after_client_start(self):
        source = CLIENT_SOURCE.read_text(encoding="utf-8")

        self.assertIn(
            "ClientLifecycleEvents.CLIENT_STARTED.register("
            "JumpMinecraftClient::clearVanillaConflicts);",
            source,
        )
        self.assertNotIn("clearVanillaConflicts(Minecraft.getInstance())", source)

    def test_other_client_registrations_remain_in_initializer(self):
        source = CLIENT_SOURCE.read_text(encoding="utf-8")

        self.assertIn("PayloadTypeRegistry.serverboundPlay().register", source)
        self.assertIn("PayloadTypeRegistry.clientboundPlay().register", source)
        self.assertEqual(1, source.count("KeyMappingHelper.registerKeyMapping"))
        self.assertIn("configureHud();", source)
        self.assertIn("ClientTickEvents.END_CLIENT_TICK.register(this::onEndTick);", source)

    def test_three_mode_input_path_uses_focus_and_space_edge(self):
        source = CLIENT_SOURCE.read_text(encoding="utf-8")

        self.assertIn("JumpDesktopInputPayload", source)
        self.assertIn("client.mouseHandler.isMouseGrabbed()", source)
        self.assertIn("boolean jump = space && !jumpDown;", source)
        self.assertNotIn("REQUEST_JUMP", source)
        self.assertNotIn("key.jump-minecraft.request_jump", source)


if __name__ == "__main__":
    unittest.main()

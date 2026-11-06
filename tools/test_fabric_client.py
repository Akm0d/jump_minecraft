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
        self.assertEqual(2, source.count("KeyMappingHelper.registerKeyMapping"))
        self.assertIn("hideSurvivalHud();", source)
        self.assertIn("ClientTickEvents.END_CLIENT_TICK.register(this::onEndTick);", source)


if __name__ == "__main__":
    unittest.main()

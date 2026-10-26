#!/usr/bin/env python3

import json
import math
import unittest

import synthetic_flight


class SyntheticFlightTests(unittest.TestCase):
    def test_subscription_validation(self):
        good = b'{"findex":1,"expiresInSeconds":3,"helicopterFlag":false}'
        self.assertTrue(synthetic_flight.valid_subscription(good))
        self.assertFalse(synthetic_flight.valid_subscription(b'{"findex":1}'))

    def test_frames_are_deterministic_and_v1_compatible(self):
        first = synthetic_flight.frame(10, "flying", 2.0)
        self.assertEqual(first, synthetic_flight.frame(10, "flying", 2.0))
        decoded = json.loads(first)
        self.assertEqual(10, decoded["findex"])
        self.assertEqual("flying", decoded["state"])
        self.assertEqual({"x", "y", "z"}, set(decoded["pos"]))
        magnitude = math.sqrt(sum(value * value for value in decoded["forward"].values()))
        self.assertAlmostEqual(1.0, magnitude)

    def test_lifecycle_contains_every_mvp_state(self):
        self.assertEqual(
            ["preparing", "jumping", "flying", "pre_deployed", "deployed", "landed"],
            [segment.state for segment in synthetic_flight.SEGMENTS],
        )


if __name__ == "__main__":
    unittest.main()

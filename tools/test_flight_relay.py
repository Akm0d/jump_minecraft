#!/usr/bin/env python3

import json
import os
import pathlib
import shutil
import socket
import subprocess
import time
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[1]
RELAY = ROOT / "scripts" / "flight_udp_relay.ps1"


def powershell_command() -> str | None:
    configured = os.environ.get("POWERSHELL")
    if configured:
        return configured
    return shutil.which("pwsh") or shutil.which("powershell.exe")


def free_udp_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as probe:
        probe.bind(("127.0.0.1", 0))
        return probe.getsockname()[1]


@unittest.skipUnless(powershell_command(), "PowerShell is not installed")
class FlightRelayTests(unittest.TestCase):
    def test_bidirectional_heartbeat_relay_and_expiry(self):
        relay_port = free_udp_port()
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as flight:
            flight.bind(("127.0.0.1", 0))
            flight.settimeout(3)
            flight_port = flight.getsockname()[1]
            command = [
                powershell_command(),
                "-NoLogo",
                "-NoProfile",
                "-File",
                str(RELAY),
                "-ListenAddress",
                "127.0.0.1",
                "-ListenPort",
                str(relay_port),
                "-AllowedPaperAddress",
                "127.0.0.1",
                "-FlightHost",
                "127.0.0.1",
                "-FlightPort",
                str(flight_port),
                "-SubscriberTimeoutSeconds",
                "1",
            ]
            relay = subprocess.Popen(
                command,
                cwd=ROOT,
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                text=True,
            )
            try:
                time.sleep(0.5)
                self.assertIsNone(relay.poll(), relay.stdout.read() if relay.poll() else "")
                with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as paper:
                    paper.bind(("127.0.0.1", 0))
                    paper.settimeout(2)
                    heartbeat = json.dumps(
                        {
                            "findex": 123456,
                            "expiresInSeconds": 1.0,
                            "helicopterFlag": False,
                        },
                        separators=(",", ":"),
                    ).encode()
                    paper.sendto(heartbeat, ("127.0.0.1", relay_port))
                    forwarded, relay_flight_endpoint = flight.recvfrom(65507)
                    self.assertEqual(heartbeat, forwarded)

                    frame = b'{"findex":123457,"state":"flying"}'
                    flight.sendto(frame, relay_flight_endpoint)
                    received, source = paper.recvfrom(65507)
                    self.assertEqual(frame, received)
                    self.assertEqual(relay_port, source[1])

                    older = json.dumps(
                        {
                            "findex": 123455,
                            "expiresInSeconds": 1.0,
                            "helicopterFlag": False,
                        },
                        separators=(",", ":"),
                    ).encode()
                    paper.sendto(older, ("127.0.0.1", relay_port))
                    flight.settimeout(0.2)
                    with self.assertRaises(socket.timeout):
                        flight.recvfrom(65507)

                    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as intruder:
                        intruder.sendto(heartbeat, ("127.0.0.1", relay_port))
                    with self.assertRaises(socket.timeout):
                        flight.recvfrom(65507)

                    invalid_messages = (
                        b'{"resetJump":true}',
                        b'{"findex":123458,"expiresInSeconds":1,"helicopterFlag":"false"}',
                    )
                    for message in invalid_messages:
                        with self.subTest(message=message):
                            paper.sendto(message, ("127.0.0.1", relay_port))
                            with self.assertRaises(socket.timeout):
                                flight.recvfrom(65507)

                    time.sleep(1.1)
                    flight.sendto(frame, relay_flight_endpoint)
                    paper.settimeout(0.2)
                    with self.assertRaises(socket.timeout):
                        paper.recvfrom(65507)
            finally:
                relay.terminate()
                try:
                    relay.wait(timeout=3)
                except subprocess.TimeoutExpired:
                    relay.kill()
                    relay.wait(timeout=3)
                if relay.stdout:
                    relay.stdout.close()


if __name__ == "__main__":
    unittest.main()

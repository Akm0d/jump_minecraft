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
BRIDGE = ROOT / "scripts" / "flight_udp_relay.ps1"


def powershell_command() -> str | None:
    configured = os.environ.get("POWERSHELL")
    if configured:
        return configured
    return shutil.which("pwsh") or shutil.which("powershell.exe")


def receive_until(sock: socket.socket, predicate, timeout: float = 3.0):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        sock.settimeout(max(0.01, deadline - time.monotonic()))
        data, source = sock.recvfrom(65_507)
        if predicate(data):
            return data, source
    raise socket.timeout


@unittest.skipUnless(powershell_command(), "PowerShell is not installed")
class FlightRelayTests(unittest.TestCase):
    def test_registration_heartbeat_frames_source_isolation_and_expiry(self):
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as paper:
            paper.bind(("127.0.0.1", 0))
            paper_port = paper.getsockname()[1]
            with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as flight:
                flight.bind(("127.0.0.1", 0))
                flight_port = flight.getsockname()[1]
                command = [
                    powershell_command(),
                    "-NoLogo",
                    "-NoProfile",
                    "-File",
                    str(BRIDGE),
                    "-StationId",
                    "test-station",
                    "-StationName",
                    "Test Station",
                    "-PaperHost",
                    "127.0.0.1",
                    "-PaperPort",
                    str(paper_port),
                    "-FlightHost",
                    "127.0.0.1",
                    "-FlightPort",
                    str(flight_port),
                ]
                bridge = subprocess.Popen(
                    command,
                    cwd=ROOT,
                    stdout=subprocess.PIPE,
                    stderr=subprocess.STDOUT,
                    text=True,
                )
                try:
                    registration, bridge_paper_endpoint = receive_until(
                        paper, lambda data: b'"jumpStation":1' in data)
                    payload = json.loads(registration)
                    self.assertEqual("test-station", payload["stationId"])
                    self.assertEqual("Test Station", payload["stationName"])

                    ack = json.dumps(
                        {
                            "jumpStationAck": 1,
                            "stationId": "test-station",
                            "status": "registered",
                        },
                        separators=(",", ":"),
                    ).encode()
                    paper.sendto(ack, bridge_paper_endpoint)
                    heartbeat = json.dumps(
                        {
                            "findex": 123_456,
                            "expiresInSeconds": 1.0,
                            "helicopterFlag": False,
                        },
                        separators=(",", ":"),
                    ).encode()
                    paper.sendto(heartbeat, bridge_paper_endpoint)
                    forwarded, bridge_flight_endpoint = flight.recvfrom(65_507)
                    self.assertEqual(heartbeat, forwarded)

                    frame = b'{"findex":123457,"state":"flying"}'
                    flight.sendto(frame, bridge_flight_endpoint)
                    received, source = receive_until(paper, lambda data: data == frame)
                    self.assertEqual(frame, received)
                    self.assertEqual(bridge_paper_endpoint, source)

                    invalid = b'{"resetJump":true}'
                    paper.sendto(invalid, bridge_paper_endpoint)
                    flight.settimeout(0.2)
                    with self.assertRaises(socket.timeout):
                        flight.recvfrom(65_507)

                    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as intruder:
                        intruder.sendto(heartbeat, bridge_paper_endpoint)
                    with self.assertRaises(socket.timeout):
                        flight.recvfrom(65_507)

                    time.sleep(1.1)
                    flight.sendto(frame, bridge_flight_endpoint)
                    paper.settimeout(0.2)
                    with self.assertRaises(socket.timeout):
                        receive_until(paper, lambda data: data == frame, timeout=0.2)
                finally:
                    bridge.terminate()
                    try:
                        bridge.wait(timeout=3)
                    except subprocess.TimeoutExpired:
                        bridge.kill()
                        bridge.wait(timeout=3)
                    if bridge.stdout:
                        bridge.stdout.close()

    def test_validate_only_accepts_private_hosts(self):
        result = subprocess.run(
            [
                powershell_command(),
                "-NoLogo",
                "-NoProfile",
                "-File",
                str(BRIDGE),
                "-StationId",
                "test-station",
                "-PaperHost",
                "127.0.0.1",
                "-FlightHost",
                "127.0.0.1",
                "-ValidateOnly",
            ],
            cwd=ROOT,
            capture_output=True,
            text=True,
            timeout=10,
        )
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main()

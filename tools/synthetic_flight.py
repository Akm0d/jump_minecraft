#!/usr/bin/env python3
"""Deterministic UDP v1 stand-in for the JUMP GameModule.

The script binds the configured FLIGHT endpoint, waits for a valid subscription
heartbeat, and replies to that exact source IP/port with a repeatable lifecycle
and trajectory. It never talks to a real FLIGHT engine.
"""

from __future__ import annotations

import argparse
import json
import math
import socket
import time
from dataclasses import dataclass


@dataclass(frozen=True)
class Segment:
    state: str
    seconds: float


SEGMENTS = (
    Segment("preparing", 1.0),
    Segment("jumping", 0.75),
    Segment("flying", 8.0),
    Segment("pre_deployed", 2.0),
    Segment("deployed", 4.0),
    Segment("landed", 1.0),
)


def trajectory(elapsed: float, state: str) -> tuple[tuple[float, float, float], tuple[float, float, float]]:
    if state in ("preparing", "jumping"):
        return (100.0, 120.0, 100.0), (0.0, 0.0, 1.0)
    distance = min(elapsed * 12.0, 150.0)
    heading = min(elapsed * 5.0, 75.0)
    radians = math.radians(heading)
    position = (
        100.0 - math.sin(radians) * distance,
        max(70.0, 120.0 - elapsed * 3.0),
        100.0 + math.cos(radians) * distance,
    )
    pitch = math.radians(-12.0 if state == "flying" else -4.0)
    forward = (
        -math.sin(radians) * math.cos(pitch),
        math.sin(pitch),
        math.cos(radians) * math.cos(pitch),
    )
    return position, forward


def frame(findex: int, state: str, elapsed: float) -> bytes:
    position, forward = trajectory(elapsed, state)
    payload = {
        "findex": findex,
        "state": state,
        "pos": dict(zip(("x", "y", "z"), position)),
        "forward": dict(zip(("x", "y", "z"), forward)),
        "up": {"x": 0.0, "y": 1.0, "z": 0.0},
        "hasTracking": True,
        "speed": 12.0 if state != "landed" else 0.0,
        "hudAOA": 0.0,
        "ep": 0,
        "nep": 0,
    }
    return json.dumps(payload, separators=(",", ":")).encode("utf-8")


def valid_subscription(data: bytes) -> bool:
    try:
        payload = json.loads(data)
        return (
            isinstance(payload.get("findex"), int)
            and float(payload.get("expiresInSeconds", 0)) > 0
            and payload.get("helicopterFlag") is False
        )
    except (ValueError, TypeError, json.JSONDecodeError):
        return False


def read_heartbeats(
    sock: socket.socket,
    subscriber: tuple[str, int],
    expires_at: float,
) -> float:
    while True:
        try:
            data, address = sock.recvfrom(65507)
        except BlockingIOError:
            return expires_at
        if address == subscriber and valid_subscription(data):
            heartbeat = json.loads(data)
            expires_at = time.monotonic() + float(heartbeat["expiresInSeconds"])


def run(host: str, port: int, rate: float) -> None:
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.bind((host, port))
    sock.settimeout(0.1)
    print(f"Synthetic JUMP v1 server listening on {host}:{port}", flush=True)
    subscriber: tuple[str, int] | None = None
    expires_at = 0.0
    while subscriber is None:
        data, address = sock.recvfrom(65507)
        if valid_subscription(data):
            subscriber = address
            heartbeat = json.loads(data)
            expires_at = time.monotonic() + float(heartbeat["expiresInSeconds"])
            print(f"Subscriber: {subscriber[0]}:{subscriber[1]}", flush=True)

    sock.setblocking(False)
    start = time.monotonic()
    sequence = time.time_ns() // 1_000
    segment_start = 0.0
    next_frame = time.monotonic()
    for segment in SEGMENTS:
        segment_end = segment_start + segment.seconds
        while time.monotonic() - start < segment_end:
            now = time.monotonic()
            expires_at = read_heartbeats(sock, subscriber, expires_at)
            if now > expires_at:
                raise SystemExit("Subscription expired; plugin heartbeat stopped")
            if now >= next_frame:
                elapsed = now - start
                sequence += 1
                sock.sendto(frame(sequence, segment.state, elapsed), subscriber)
                next_frame += 1.0 / rate
            else:
                time.sleep(min(0.002, next_frame - now))
        segment_start = segment_end
        print(f"state={segment.state}", flush=True)
    sock.close()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=1201)
    parser.add_argument("--rate", type=float, default=90.0)
    args = parser.parse_args()
    if not 1 <= args.port <= 65535:
        parser.error("port must be in 1..65535")
    if args.rate <= 0:
        parser.error("rate must be positive")
    run(args.host, args.port, args.rate)


if __name__ == "__main__":
    main()

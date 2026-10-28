#!/usr/bin/env python3
"""Solidus live-boot RCON client (WS-0 / Task A).

Zero-dependency Minecraft RCON client per the modern RCON spec:

    packet  := int32 length | int32 requestId | int32 type | payload | 2x 0x00
    length  := total length of requestId + type + payload + padding (little-endian)
    type    := 3 = login, 2 = command, 0 = response

Usage:
    rcon.py <host> <port> <password> <command...>

Exit codes: 0 = ok, 2 = auth failure, 3 = connection failure.
"""
import socket
import struct
import sys

SERVERDATA_AUTH = 3
SERVERDATA_EXECCOMMAND = 2
SERVERDATA_AUTH_RESPONSE = 2
SERVERDATA_RESPONSE_VALUE = 0


def pack_packet(req_id: int, ptype: int, payload: bytes) -> bytes:
    body = struct.pack("<ii", req_id, ptype) + payload + b"\x00\x00"
    return struct.pack("<i", len(body)) + body


def read_exact(sock: socket.socket, n: int) -> bytes:
    buf = b""
    while len(buf) < n:
        chunk = sock.recv(n - len(buf))
        if not chunk:
            raise ConnectionError("connection closed mid-packet")
        buf += chunk
    return buf


def read_packet(sock: socket.socket):
    (length,) = struct.unpack("<i", read_exact(sock, 4))
    body = read_exact(sock, length)
    req_id, ptype = struct.unpack("<ii", body[:8])
    payload = body[8:-2]
    return req_id, ptype, payload


def run(host: str, port: int, password: str, command: str) -> int:
    with socket.create_connection((host, port), timeout=30) as sock:
        # --- authenticate -------------------------------------------------
        sock.sendall(pack_packet(1, SERVERDATA_AUTH, password.encode("utf-8")))
        # vanilla sends an empty RESPONSE_VALUE then AUTH_RESPONSE
        while True:
            req_id, ptype, _ = read_packet(sock)
            if ptype == SERVERDATA_AUTH_RESPONSE:
                break
        if req_id == -1:
            sys.stderr.write("RCON auth failed (wrong password)\n")
            return 2
        # --- execute ------------------------------------------------------
        sock.sendall(pack_packet(2, SERVERDATA_EXECCOMMAND, command.encode("utf-8")))
        while True:
            req_id, ptype, payload = read_packet(sock)
            if ptype == SERVERDATA_RESPONSE_VALUE and req_id == 2:
                sys.stdout.write(payload.decode("utf-8", errors="replace"))
                sys.stdout.flush()
                return 0


def main() -> int:
    if len(sys.argv) < 5:
        sys.stderr.write("usage: rcon.py <host> <port> <password> <command...>\n")
        return 1
    host, port, password = sys.argv[1], int(sys.argv[2]), sys.argv[3]
    command = " ".join(sys.argv[4:])
    try:
        return run(host, port, password, command)
    except (ConnectionError, socket.timeout, OSError) as exc:
        sys.stderr.write(f"RCON connection error: {exc}\n")
        return 3


if __name__ == "__main__":
    sys.exit(main())

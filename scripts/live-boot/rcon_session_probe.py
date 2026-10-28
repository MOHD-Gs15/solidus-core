#!/usr/bin/env python3
"""Debug: one RCON connection, two commands — probes whether late async
feedback from command 1 is returned with command 2's response (vanilla keeps
one RconConsoleSource per client connection)."""
import socket, struct, sys, time

def pack(req_id, ptype, payload):
    body = struct.pack("<ii", req_id, ptype) + payload.encode() + b"\x00\x00"
    return struct.pack("<i", len(body)) + body

def read_packet(sock):
    raw = b""
    while len(raw) < 4:
        c = sock.recv(4 - len(raw))
        if not c: raise ConnectionError("closed")
        raw += c
    (length,) = struct.unpack("<i", raw)
    body = b""
    while len(body) < length:
        c = sock.recv(length - len(body))
        if not c: raise ConnectionError("closed")
        body += c
    rid, ptype = struct.unpack("<ii", body[:8])
    return rid, ptype, body[8:-2].decode(errors="replace")

def main():
    host, port, pw = sys.argv[1], int(sys.argv[2]), sys.argv[3]
    cmd1, cmd2 = sys.argv[4], sys.argv[5]
    s = socket.create_connection((host, port), timeout=40)
    s.sendall(pack(1, 3, pw))
    while True:
        rid, pt, _ = read_packet(s)
        if pt == 2: break
    if rid == -1: sys.exit("auth failed")
    s.sendall(pack(2, 2, cmd1))
    t0 = time.time()
    rid, pt, out1 = read_packet(s)
    print(f"[{time.time()-t0:.2f}s] {cmd1!r} -> {out1!r}")
    time.sleep(5)
    s.sendall(pack(3, 2, cmd2))
    rid, pt, out2 = read_packet(s)
    print(f"[{time.time()-t0:.2f}s] {cmd2!r} -> {out2!r}")
    # drain any extra packets for 3s
    s.settimeout(3)
    try:
        while True:
            rid, pt, out = read_packet(s)
            print(f"[drain] -> {out!r}")
    except (socket.timeout, ConnectionError):
        pass

main()

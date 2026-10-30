#!/usr/bin/env python3
"""A stand-in for the app's cortex-api server, with the same wire format (see ApiProtocol.kt).

    cortex_api_mock.py CONF LOG TOKEN

Listens on 127.0.0.1, writes "PORT TOKEN" to CONF like the app, appends every request to LOG as a
JSON line and answers: "fail" exits 3, a wrong token is refused, anything else succeeds. While the
file CONF.hang exists, clipboard-get is not answered at all (the connection stays open for a while),
like an app that does not respond.
"""
import json
import os
import socket
import sys
import threading
import time


def read_line(f):
    line = f.readline()
    if not line.endswith(b"\n"):
        raise EOFError("request ends early")
    return line[:-1].decode("utf-8")


def read_block(f, n):
    data = f.read(n)
    if len(data) != n:
        raise EOFError("request ends early")
    return data.decode("utf-8")


def main():
    conf, log, token = sys.argv[1], sys.argv[2], sys.argv[3]
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.bind(("127.0.0.1", 0))
    srv.listen(8)
    with open(conf + ".tmp", "w") as f:
        f.write(f"{srv.getsockname()[1]} {token}\n")
    os.rename(conf + ".tmp", conf)

    def handle(conn):
        with conn, conn.makefile("rb") as f:
            try:
                magic = read_line(f)
                given = read_line(f)
                command = read_line(f)
                args = [read_block(f, int(read_line(f))) for _ in range(int(read_line(f)))]
                length = int(read_line(f))
                stdin = None if length < 0 else read_block(f, length)
            except (EOFError, ValueError) as e:
                conn.sendall(f"2\ncortex-api: {e}\n".encode())
                return
            with open(log, "a", encoding="utf-8") as lf:
                lf.write(json.dumps({"magic": magic, "token": given, "command": command,
                                     "args": args, "stdin": stdin}, ensure_ascii=False) + "\n")
            if given != token:
                conn.sendall(b"1\ncortex-api: the request was not authorized\n")
            elif command == "fail":
                conn.sendall("3\ncortex-api: olmadı\n".encode())
            elif command == "clipboard-get" and os.path.exists(conf + ".hang"):
                time.sleep(30)
            else:
                conn.sendall(f"0\nok {command}\n".encode())

    while True:
        conn, _ = srv.accept()
        threading.Thread(target=handle, args=(conn,), daemon=True).start()


if __name__ == "__main__":
    main()

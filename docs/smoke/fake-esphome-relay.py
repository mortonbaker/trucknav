#!/usr/bin/env python3
"""A stand-in for the 4runner-relay ESPHome board, for S25 smokes on the emulator.

Speaks the parts of ESPHome's web_server the app uses: GET /switch/<object_id|name>,
POST /switch/<name>/turn_on|turn_off, and GET /events (SSE: a state event per switch
on connect, then one per change, ping every 10 s). Each request is slowed by --lag
seconds, like the real ESP32. Every request is appended to --log as JSON lines.

Test controls (not ESPHome):
  POST /_ext/<n>/on|off   flip relay n (1-8) as if a panel button or HA did it
  POST /_fail/<n>         make the next POST for relay n return 500
  POST /_drop/<secs>      close every event stream and refuse connections for <secs>
"""
import argparse, json, socket, threading, time, urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

NAMES = ["Relay 1 (Starlink)", "Relay 2 Rear Lights", "Relay 3 (Pin 14)", "Relay 4 (Pin 25)",
         "Relay 5 (Pin 26)", "Relay 6 (Pin 27)", "Relay 7 (Pin 32)", "Relay 8 (Pin 33)"]
OIDS = ["relay_1__starlink_", "relay_2_rear_lights", "relay_3__pin_14_", "relay_4__pin_25_",
        "relay_5__pin_26_", "relay_6__pin_27_", "relay_7__pin_32_", "relay_8__pin_33_"]
state = [False] * 8
fail_next = set()
streams, lock = [], threading.Lock()
down_until = 0.0
args = None


def log(**kw):
    kw["t"] = round(time.time(), 3)
    with open(args.log, "a") as f:
        f.write(json.dumps(kw) + "\n")


def doc(i):
    return {"name_id": "switch/" + NAMES[i], "id": "switch-" + OIDS[i], "domain": "switch", "name": NAMES[i],
            "value": state[i], "state": "ON" if state[i] else "OFF", "assumed_state": False}


def push(i):
    msg = ("event: state\ndata: %s\n\n" % json.dumps(doc(i), separators=(",", ":"))).encode()
    with lock:
        for w in list(streams):
            try:
                w.write(msg); w.flush()
            except Exception:
                streams.remove(w)


def find(seg):
    seg = urllib.parse.unquote(seg)
    for i in range(8):
        if seg in (NAMES[i], OIDS[i]):
            return i, seg == OIDS[i]
    return None, False


class H(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *a):
        pass

    def reply(self, code, body=b"", ctype="application/json"):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def refuse(self):
        if time.time() < down_until:
            self.close_connection = True
            try:
                self.connection.shutdown(socket.SHUT_RDWR)
            except Exception:
                pass
            return True
        return False

    def do_GET(self):
        if self.refuse():
            return
        p = self.path.split("?")[0].strip("/").split("/")
        log(m="GET", path="/" + "/".join(urllib.parse.unquote(x) for x in p))
        if p == ["events"]:
            self.send_response(200)
            self.send_header("Content-Type", "text/event-stream")
            self.send_header("Cache-Control", "no-cache")
            self.end_headers()
            self.wfile.write(b'retry: 30000\nevent: ping\ndata: {"title":"4runner-relay"}\n\n')
            for i in range(8):
                self.wfile.write(("event: state\ndata: %s\n\n" % json.dumps(doc(i), separators=(",", ":"))).encode())
            self.wfile.flush()
            with lock:
                streams.append(self.wfile)
            try:
                while time.time() >= down_until and self.wfile in streams:
                    time.sleep(10)
                    with lock:
                        if self.wfile not in streams:
                            break
                        self.wfile.write(b'event: ping\ndata: {}\n\n'); self.wfile.flush()
            except Exception:
                pass
            with lock:
                if self.wfile in streams:
                    streams.remove(self.wfile)
            self.close_connection = True
            return
        time.sleep(args.lag)
        if len(p) == 2 and p[0] == "switch":
            i, _ = find(p[1])
            if i is not None:
                return self.reply(200, json.dumps(doc(i), separators=(",", ":")).encode())
        self.reply(404)

    def do_POST(self):
        global down_until
        if self.refuse():
            return
        p = self.path.strip("/").split("/")
        n = int(self.headers.get("Content-Length") or 0)
        if n:
            self.rfile.read(n)
        if p[0] == "_ext":
            i = int(p[1]) - 1; state[i] = p[2] == "on"; log(m="EXT", relay=i + 1, on=state[i]); push(i)
            return self.reply(200)
        if p[0] == "_fail":
            fail_next.add(int(p[1]) - 1); return self.reply(200)
        if p[0] == "_drop":
            down_until = time.time() + float(p[1]); log(m="DROP", secs=float(p[1]))
            with lock:
                for w in streams:
                    try:
                        w.close()
                    except Exception:
                        pass
                streams.clear()
            return self.reply(200)
        time.sleep(args.lag)
        log(m="POST", path="/" + "/".join(urllib.parse.unquote(x) for x in p))
        if len(p) == 3 and p[0] == "switch" and p[2] in ("turn_on", "turn_off"):
            i, by_oid = find(p[1])
            if i is None:
                return self.reply(404)
            if i in fail_next:
                fail_next.discard(i); return self.reply(500)
            new = p[2] == "turn_on"
            changed = state[i] != new
            state[i] = new
            self.reply(200)
            if changed:
                push(i)
            return
        self.reply(404)


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8099)
    ap.add_argument("--lag", type=float, default=0.3)
    ap.add_argument("--log", required=True)
    args = ap.parse_args()
    ThreadingHTTPServer.daemon_threads = True
    ThreadingHTTPServer(("127.0.0.1", args.port), H).serve_forever()

"""Talk to the running Arena client over the Chrome DevTools Protocol (stdlib only).

The client is an Electron app that authenticates with a Steam session ticket, so its API cannot be
called from outside; but it does not disable remote debugging, so starting it as

    cd "<app>/Contents" && SteamAppId=1137320 "<app>/Contents/MacOS/screeps_arena" --remote-debugging-port=9222

exposes the page, and JavaScript evaluated there runs with the session already authenticated. That
is what `tools/play.py` uses to start matches and read logs. Steam must be running.

There is no websocket library in this environment, so the client is ~80 lines of RFC 6455 here.
"""
import base64, json, os, socket, struct, urllib.error, urllib.request

PORT = 9222
APP = ("/Users/zakharchukart/Library/Application Support/Steam/steamapps/common/ScreepsArena/"
       "screeps_arena.app/Contents")
# The executable by its ABSOLUTE path: arukuka/screeps-arena-tools (replays with both sides' attacks and heals, see
# docs/pain-and-gain-research.md) finds the client with `ps … | grep screeps_arena.app/Contents/MacOS/screeps_arena`,
# and a client started as `./MacOS/screeps_arena` is invisible to it ("Screeps: Arena is not running", 05.09.2026).
LAUNCH_HINT = (f"start the client with remote debugging:\n"
               f'    cd "{APP}" && SteamAppId=1137320 nohup "{APP}/MacOS/screeps_arena" '
               f"--remote-debugging-port={PORT} >/tmp/arena_client.log 2>&1 &")


def targets(port=PORT):
    try:
        with urllib.request.urlopen(f"http://127.0.0.1:{port}/json/list", timeout=5) as r:
            return json.load(r)
    except (urllib.error.URLError, OSError) as e:
        raise SystemExit(f"no debug port {port} ({e}) — {LAUNCH_HINT}")


def page_ws(port=PORT):
    for t in targets(port):
        if t.get("type") == "page":
            return t["webSocketDebuggerUrl"]
    raise SystemExit(f"the client has no page target — {LAUNCH_HINT}")


class CDP:
    """One connection to the client's page; `eval` runs JS there and returns its value."""

    def __init__(self, ws_url=None, timeout=180):
        ws_url = ws_url or page_ws()
        _, _, rest = ws_url.partition("://")
        hostport, _, path = rest.partition("/")
        host, _, port = hostport.partition(":")
        self.sock = socket.create_connection((host, int(port or 80)), timeout=10)
        self.sock.settimeout(timeout)
        key = base64.b64encode(os.urandom(16)).decode()
        self.sock.sendall((f"GET /{path} HTTP/1.1\r\nHost: {hostport}\r\nUpgrade: websocket\r\n"
                           f"Connection: Upgrade\r\nSec-WebSocket-Key: {key}\r\n"
                           f"Sec-WebSocket-Version: 13\r\n\r\n").encode())
        buf = b""
        while b"\r\n\r\n" not in buf:
            chunk = self.sock.recv(4096)
            if not chunk:
                raise RuntimeError("handshake closed")
            buf += chunk
        if b"101" not in buf.split(b"\r\n")[0]:
            raise RuntimeError(f"handshake failed: {buf[:120]!r}")
        self.rest = buf.split(b"\r\n\r\n", 1)[1]
        self.next_id = 0

    def _recv(self, n):
        while len(self.rest) < n:
            chunk = self.sock.recv(65536)
            if not chunk:
                raise RuntimeError("socket closed")
            self.rest += chunk
        out, self.rest = self.rest[:n], self.rest[n:]
        return out

    def _send_frame(self, payload):
        data = payload.encode()
        head = bytearray([0x81])
        n = len(data)
        if n < 126:
            head.append(0x80 | n)
        elif n < 65536:
            head.append(0x80 | 126)
            head += struct.pack(">H", n)
        else:
            head.append(0x80 | 127)
            head += struct.pack(">Q", n)
        mask = os.urandom(4)
        head += mask
        self.sock.sendall(bytes(head) + bytes(b ^ mask[i % 4] for i, b in enumerate(data)))

    def _recv_frame(self):
        while True:
            b0, b1 = self._recv(2)
            opcode, masked, ln = b0 & 0x0F, b1 & 0x80, b1 & 0x7F
            if ln == 126:
                ln = struct.unpack(">H", self._recv(2))[0]
            elif ln == 127:
                ln = struct.unpack(">Q", self._recv(8))[0]
            mask = self._recv(4) if masked else None
            data = self._recv(ln)
            if mask:
                data = bytes(b ^ mask[i % 4] for i, b in enumerate(data))
            if opcode == 0x9:                       # ping
                self._send_frame("")
                continue
            if opcode == 0x8:
                raise RuntimeError("closed by peer")
            if opcode in (0x1, 0x2):
                return data.decode('utf-8', 'replace')

    def call(self, method, **params):
        self.next_id += 1
        mid = self.next_id
        self._send_frame(json.dumps({"id": mid, "method": method, "params": params}))
        while True:
            msg = json.loads(self._recv_frame())
            if msg.get("id") == mid:
                if "error" in msg:
                    raise RuntimeError(f"{method}: {msg['error']}")
                return msg.get("result", {})

    def eval(self, expression):
        r = self.call("Runtime.evaluate", expression=expression, awaitPromise=True,
                      returnByValue=True, userGesture=True)
        if r.get("exceptionDetails"):
            raise RuntimeError(f"page exception: {json.dumps(r['exceptionDetails'])[:400]}")
        return r.get("result", {}).get("value")

    def json_eval(self, expression):
        """Evaluate an expression that returns a JSON string (big payloads travel as one string)."""
        return json.loads(self.eval(expression))

    def close(self):
        try:
            self.sock.close()
        except OSError:
            pass

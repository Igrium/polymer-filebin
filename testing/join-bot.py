# Minimal headless 26.2 (protocol 776) client that joins through the configuration phase,
# downloads the pushed resource pack like the vanilla client would (same User-Agent) and verifies its SHA-1.
# Usage: python3 testing/join-bot.py [number of pushes to answer with FAILED_DOWNLOAD] [name]
# Requires online-mode=false and network-compression-threshold=-1.
import socket, struct, sys, uuid, hashlib, urllib.request, time

PROTO = 776
HOST, PORT = "127.0.0.1", 25565
FAIL_FIRST = int(sys.argv[1]) if len(sys.argv) > 1 else 0   # number of pushes to answer with FAILED_DOWNLOAD
NAME = sys.argv[2] if len(sys.argv) > 2 else "FilebinBot"

def varint(n):
    out = b""
    n &= 0xFFFFFFFF
    while True:
        b = n & 0x7F; n >>= 7
        if n: out += bytes([b | 0x80])
        else: return out + bytes([b])

def string(s):
    b = s.encode(); return varint(len(b)) + b

class Buf:
    def __init__(self, d): self.d, self.i = d, 0
    def varint(self):
        r = s = 0
        while True:
            b = self.d[self.i]; self.i += 1
            r |= (b & 0x7F) << s; s += 7
            if not b & 0x80: return r
    def read(self, n): v = self.d[self.i:self.i+n]; self.i += n; return v
    def string(self): return self.read(self.varint()).decode()

sock = socket.create_connection((HOST, PORT))
sock.settimeout(120)
def recv_exact(n):
    d = b""
    while len(d) < n:
        c = sock.recv(n - len(d))
        if not c: raise EOFError("connection closed")
        d += c
    return d
def read_packet():
    r = s = 0
    while True:
        b = recv_exact(1)[0]; r |= (b & 0x7F) << s; s += 7
        if not b & 0x80: break
    b = Buf(recv_exact(r)); return b.varint(), b
def send(pid, data=b""):
    p = varint(pid) + data; sock.sendall(varint(len(p)) + p)
def log(*a): print(f"[{time.strftime('%H:%M:%S')}] [bot]", *a, flush=True)

send(0, varint(PROTO) + string(HOST) + struct.pack(">H", PORT) + varint(2))
send(0, string(NAME) + uuid.uuid3(uuid.NAMESPACE_DNS, NAME).bytes)

state = "login"
pushes = 0
dialogs = 0
while True:
    pid, b = read_packet()
    if state == "login":
        if pid == 2:
            log("login finished"); send(3); state = "config"
        elif pid == 0:
            log("login disconnect:", b.string()); sys.exit(1)
        elif pid == 4:
            mid = b.varint(); send(2, varint(mid) + b"\x00")
        elif pid == 3:
            log("server enabled compression; set network-compression-threshold=-1"); sys.exit(1)
        continue
    # configuration
    if pid == 14: send(7, varint(0))
    elif pid == 4: send(4, b.read(8))
    elif pid == 5: send(5, b.read(4))
    elif pid == 18:
        dialogs += 1
        if dialogs == 1: log("server is showing the 'still preparing' dialog")
    elif pid == 17: log(f"dialog cleared (after {dialogs} dialog updates)")
    elif pid == 9:
        pack_id = b.read(16); url = b.string(); sha1 = b.string(); required = b.read(1) == b"\x01"
        pushes += 1
        log(f"resource pack push #{pushes}: id={uuid.UUID(bytes=pack_id)} hash={sha1} required={required}")
        log("  url:", url[:110] + ("..." if len(url) > 110 else ""))
        def status(a): send(6, pack_id + varint(a))
        status(3)  # ACCEPTED
        if pushes <= FAIL_FIRST:
            log("  -> pretending the download failed (FAILED_DOWNLOAD)")
            status(2); continue
        req = urllib.request.Request(url, headers={"User-Agent": "Minecraft Java/26.2"})
        with urllib.request.urlopen(req) as res:
            data = res.read(); ctype = res.headers.get("Content-Type")
        got = hashlib.sha1(data).hexdigest()
        log(f"  downloaded {len(data)} bytes ({ctype}) sha1={got} match={got == sha1}")
        if got != sha1:
            status(2); continue
        status(4); status(0)  # DOWNLOADED, SUCCESSFULLY_LOADED
    elif pid == 3:
        send(3); log(f"configuration finished -> entering play (pushes={pushes})"); sock.close(); sys.exit(0)
    elif pid == 2:
        log("disconnected during configuration (raw):", b.d[b.i:b.i+300]); sys.exit(1)

"""Silent demo recording: stands in for host_audio_bridge.py, plays nothing on the PC.

Driver lines go up the bridge socket in real time (the app's normal capture path). Reply audio that
the app writes to its AudioTrack comes back over the same socket and is placed on a timeline the way
a player would play it. The emulator display is recorded with `adb emu screenrecord`. Outputs in OUT:
screen.webm, driver.wav (16 kHz), reply.wav (24 kHz), timeline.json (scene/clip times, t=0 = video start).

    python record_demo.py OUT [scene ...]
"""
import json, os, socket, struct, subprocess, sys, threading, time, wave
import numpy as np

ADB = r"C:\Users\Administrator\Android\Sdk\platform-tools\adb.exe"
PKG = "com.novadrive.app"
HERE = os.path.dirname(os.path.abspath(__file__))
CLIPS = os.path.join(HERE, "clips")
PORT = 7790
RATE = 16000
FRAME = RATE * 2 // 50
OUT_RATE = 24000
sys.stdout.reconfigure(encoding="utf-8", errors="replace")


def adb(*a, timeout=30):
    return subprocess.run([ADB, "-e", *a], capture_output=True, text=True, encoding="utf-8", errors="replace",
                          timeout=timeout).stdout


def tool(t, arg):
    return adb("shell", "am", "broadcast", "-n", f"{PKG}/.DebugToolReceiver", "-a", "com.novadrive.app.DEBUG_TOOL",
               "--es", "tool", t, "--es", "arg", arg)


class Session:
    def __init__(self, out):
        self.out = out
        self.t0 = None
        self.lock = threading.Lock()
        self.pending = bytearray()
        self.clips = []          # (t, key)
        self.replies = []        # (start_s, rate, pcm)
        self.cursor = 0.0
        self.last_arrival = 0.0
        self.reply_bytes = 0
        self.guidance = []       # t of each guidance tag frame
        self.events = []         # (t, kind, text) scene titles etc.
        self.stop = threading.Event()
        self.conn = None
        self.wakes = []          # PC time (s since video start) when each wake broadcast returned
        self.noise_pcm = np.frombuffer(open(os.path.join(CLIPS, "noise.pcm"), "rb").read(), dtype="<i2").astype(np.int32)
        self.noise_pcm = (self.noise_pcm * 10 ** (float(os.environ.get("NOISE_GAIN_DB", "0")) / 20)).astype(np.int32)
        self.noise_pos = 0
        self.noise_on = False
        self.noise_spans = []    # [start, end] on the video clock

    def now(self):
        return time.time() - self.t0

    # ---- socket ----
    def serve(self):
        srv = socket.socket()
        srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        srv.bind(("127.0.0.1", PORT))
        srv.listen(1)
        srv.settimeout(5)
        adb("reverse", f"tcp:{PORT}", f"tcp:{PORT}")
        for _ in range(6):
            tool("bridge", f"on:{PORT}")
            try:
                conn, _ = srv.accept()
                break
            except socket.timeout:
                print("[rec] waiting for the app", flush=True)
        else:
            sys.exit("app never connected")
        conn.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        conn.settimeout(0.3)
        self.conn = conn
        print("[rec] connected", flush=True)
        threading.Thread(target=self.uplink, daemon=True).start()
        threading.Thread(target=self.downlink, daemon=True).start()

    def uplink(self):
        start, n = time.time(), 0
        while not self.stop.is_set():
            with self.lock:
                if self.pending:
                    frame = bytes(self.pending[:FRAME])
                    del self.pending[:FRAME]
                else:
                    frame = bytes(FRAME)
            frame += bytes(FRAME - len(frame))
            if self.noise_on:
                k = FRAME // 2
                idx = (self.noise_pos + np.arange(k)) % len(self.noise_pcm)
                self.noise_pos += k
                mixed = np.frombuffer(frame, dtype="<i2").astype(np.int32) + self.noise_pcm[idx]
                frame = np.clip(mixed, -32768, 32767).astype("<i2").tobytes()
            try:
                self.conn.sendall(frame)
            except (socket.timeout, OSError):
                pass
            n += 1
            delay = start + n * 0.02 - time.time()
            if delay > 0:
                time.sleep(delay)
            elif delay < -0.2:
                start, n = time.time(), 0

    def downlink(self):
        def read_exact(k):
            buf = b""
            while len(buf) < k:
                try:
                    part = self.conn.recv(k - len(buf))
                except socket.timeout:
                    if self.stop.is_set():
                        return None
                    continue
                except OSError:
                    return None
                if not part:
                    return None
                buf += part
            return buf

        while not self.stop.is_set():
            head = read_exact(8)
            if head is None:
                break
            rate, length = struct.unpack("<II", head)
            data = read_exact(length)
            if data is None:
                break
            if self.t0 is None:
                continue
            t = self.now()
            if rate == 0:
                self.guidance.append(t)
                continue
            with self.lock:
                start = max(t, self.cursor)
                self.replies.append((start, rate, data))
                self.cursor = start + len(data) / 2 / rate
                self.last_arrival = t
                self.reply_bytes += len(data)
        print("[rec] downlink closed", flush=True)

    # ---- driving ----
    def say(self, key, wake=True):
        pcm = open(os.path.join(CLIPS, key + ".pcm"), "rb").read()
        if wake:
            tool("voice", "wake")
        if self.t0 is not None:
            self.wakes.append(time.time())
        time.sleep(0.8)
        with self.lock:
            # the clip starts where the queue is now; the queue is empty between turns
            t = self.now() + len(self.pending) / 2 / RATE
            self.pending += pcm
        self.clips.append((t, key))
        print(f"[rec] {t:7.2f} say {key}", flush=True)
        time.sleep(len(pcm) / 2 / RATE)
        return t

    def wait_reply(self, quiet=1.8, timeout=35.0, first_timeout=20.0):
        """Until a reply has played out and nothing new has arrived for [quiet] seconds."""
        begin = self.now()
        start_bytes = self.reply_bytes
        while True:
            time.sleep(0.2)
            t = self.now()
            with self.lock:
                got = self.reply_bytes > start_bytes
                done = got and t > self.cursor + quiet and t - self.last_arrival > quiet
            if done:
                return True
            if not got and t - begin > first_timeout:
                print("[rec]   no reply", flush=True)
                return False
            if t - begin > timeout:
                print("[rec]   reply timeout", flush=True)
                return got

    def speaking_for(self, seconds, timeout=20.0):
        """Until reply audio has been playing for [seconds]."""
        begin = self.now()
        first = None
        while self.now() - begin < timeout:
            time.sleep(0.1)
            with self.lock:
                if self.replies and self.replies[-1][0] >= begin and first is None:
                    first = next(s for s, _, _ in self.replies if s >= begin)
            if first is not None and self.now() - first >= seconds:
                return True
        return False

    def noise(self, on):
        self.noise_on = on
        if on:
            self.noise_spans.append([self.now(), None])
        elif self.noise_spans:
            self.noise_spans[-1][1] = self.now()

    def mark(self, kind, **kw):
        self.events.append({"t": self.now(), kind: True, **kw})

    def title(self, text, sub=""):
        t = self.now()
        self.events.append({"t": t, "title": text, "sub": sub})
        print(f"[rec] {t:7.2f} == {text}", flush=True)

    def note(self, text, dur=6.0):
        self.events.append({"t": self.now(), "note": text, "dur": dur})

    # ---- output ----
    def render(self, end):
        n = int(end * OUT_RATE) + OUT_RATE
        reply = np.zeros(n, dtype=np.float32)
        for start, rate, pcm in self.replies:
            x = np.frombuffer(pcm, dtype="<i2").astype(np.float32)
            if rate != OUT_RATE:
                m = int(len(x) * OUT_RATE / rate)
                x = np.interp(np.linspace(0, len(x) - 1, m), np.arange(len(x)), x)
            i = int(start * OUT_RATE)
            reply[i:i + len(x)] += x[: max(0, n - i)]
        driver = np.zeros(n, dtype=np.float32)
        for t, key in self.clips:
            x = np.frombuffer(open(os.path.join(CLIPS, key + ".pcm"), "rb").read(), dtype="<i2").astype(np.float32)
            x = np.interp(np.linspace(0, len(x) - 1, int(len(x) * OUT_RATE / RATE)), np.arange(len(x)), x)
            i = int(t * OUT_RATE)
            driver[i:i + len(x)] += x[: max(0, n - i)]
        for a, b in self.noise_spans:
            b = b if b is not None else end
            i, j = int(a * OUT_RATE), min(n, int(b * OUT_RATE))
            src = self.noise_pcm.astype(np.float32)
            src = np.interp(np.linspace(0, len(src) - 1, int(len(src) * OUT_RATE / RATE)), np.arange(len(src)), src)
            driver[i:j] += np.resize(src, j - i)
        for name, arr in (("reply.wav", reply), ("driver.wav", driver)):
            with wave.open(os.path.join(self.out, name), "wb") as w:
                w.setnchannels(1)
                w.setsampwidth(2)
                w.setframerate(OUT_RATE)
                w.writeframes(np.clip(arr, -32768, 32767).astype("<i2").tobytes())
        json.dump({"clips": self.clips, "events": self.events, "guidance": self.guidance, "end": end, "t0": self.t0, "wakes": self.wakes,
                   "reply_segments": len(self.replies), "noise": self.noise_spans},
                  open(os.path.join(self.out, "timeline.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=1)


def run(out, scenes):
    out = os.path.abspath(out)
    os.makedirs(out, exist_ok=True)
    s = Session(out)
    adb("shell", "am", "force-stop", PKG)
    adb("shell", "am", "start", "-n", f"{PKG}/.MainActivity")
    time.sleep(14)
    adb("logcat", "-c")
    logf = open(os.path.join(out, "logcat.txt"), "w", encoding="utf-8")
    logp = subprocess.Popen([ADB, "-e", "logcat", "-v", "epoch", "-s", "NovaVoice"], stdout=logf, stderr=subprocess.DEVNULL)
    s.serve()
    time.sleep(1)
    tool("voice", "start")
    ready = False
    for _ in range(90):
        log = adb("logcat", "-d", "-s", "NovaVoice")
        if "qwen_session " in log or "gemini_setup_complete" in log:
            ready = True
            break
        if "session_provider_unavailable" in log:
            reason = "unconfigured"
            for code in ("QWEN_WORKSPACE_MISSING", "QWEN_API_KEY_MISSING", "QWEN_CONSENT_MISSING"):
                if code in log:
                    reason = code
                    break
            sys.exit(f"session not ready: {reason}")
        time.sleep(1)
    if not ready:
        sys.exit("session never became ready")
    time.sleep(3)
    video = os.path.join(out, "screen.webm")
    before = time.time()
    adb("emu", "screenrecord", "start", "--time-limit", "1500", "--bit-rate", "8000000", "--fps", "30", video)
    s.t0 = (before + time.time()) / 2
    try:
        for scene in scenes:
            SCENES[scene](s)
        time.sleep(2)
    finally:
        end = s.now()
        adb("emu", "screenrecord", "stop")
        s.stop.set()
        time.sleep(1)
        s.render(end)
        tool("bridge", "off")
        time.sleep(1)
        logp.terminate()
        logf.close()
        adb("reverse", "--remove", f"tcp:{PORT}")
        print(f"[rec] done {end:.1f} s, reply segments={len(s.replies)}", flush=True)


def turn(s, key, **kw):
    s.say(key)
    s.wait_reply(**kw)
    time.sleep(0.6)


def sc_intro(s):
    s.title("小诺 · 新功能演示", "Qwen Maia on cursor/10-9 — recorded 2026-10-09")
    time.sleep(4)


def sc_ability(s):
    s.title("1  问能力：马上回答", "\"What do you do?\" is answered at once")
    turn(s, "what")


def sc_ability2(s):
    s.title("1  问能力：马上回答", "\"What can you do?\" is answered at once")
    turn(s, "what2")
    time.sleep(12)
    turn(s, "what3")


def sc_scenario(s):
    s.title("2  一句话 → 多个动作", "One casual phrase runs a whole scenario")
    turn(s, "stuffy")


def sc_style(s):
    s.title("3  更多说话风格", "More speaking styles; an unknown one is refused honestly")
    turn(s, "sweet")
    turn(s, "food")
    turn(s, "genki")
    turn(s, "bossy")
    turn(s, "normal")


def sc_noise(s):
    s.title("4  嘈杂车内也能听完一句话", "A turn still ends with fan noise in the cabin")
    s.noise(True)
    time.sleep(3)
    turn(s, "color")
    time.sleep(1)
    s.noise(False)


def sc_warm(s):
    s.title("5  长时间静默后，第一句不卡", "After a long silence, the first reply starts quickly")
    time.sleep(3)
    s.mark("cut_start")
    time.sleep(64)
    s.mark("cut_end")
    time.sleep(1)
    turn(s, "cold")


def sc_prep(s):
    # off camera: back to the normal style, then let the bubble clear before the next title
    turn(s, "normal")
    time.sleep(12)


def sc_error(s):
    s.title("6  出错提示 12 秒后自动消失", "A connection error card fades by itself after 12 s")
    tool("voice", "stop")
    time.sleep(2)
    adb("shell", "cmd", "connectivity", "airplane-mode", "enable")
    time.sleep(3)
    s.mark("err_start")
    tool("voice", "start")
    time.sleep(18)
    adb("shell", "cmd", "connectivity", "airplane-mode", "disable")
    time.sleep(3)


def sc_cue(s):
    s.title("7  需要等的时候，先应一声", "A slow task: she says she is on it, and why if it takes longer")
    turn(s, "weather", timeout=40)
    turn(s, "music", timeout=40)


def sc_nav(s):
    s.title("7  需要等的时候，先应一声", "A slow task: she says she is on it")
    turn(s, "nav", timeout=40)


def sc_think(s):
    s.title("8  想一想的问题", "A question that needs thought gets a short 'let me think'")
    turn(s, "philo", timeout=40)


SCENES = {"intro": sc_intro, "ability": sc_ability, "ability2": sc_ability2, "scenario": sc_scenario, "style": sc_style, "noise": sc_noise,
          "warm": sc_warm, "error": sc_error, "prep": sc_prep, "cue": sc_cue, "nav": sc_nav, "think": sc_think}

if __name__ == "__main__":
    run(sys.argv[1], sys.argv[2:] or list(SCENES))

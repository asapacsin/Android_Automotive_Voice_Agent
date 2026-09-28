"""Talk to 小诺 on the emulator with the PC's microphone; hear replies on the PC's speakers.

Debug builds only (HostAudioBridge in app/src/debug). One command:

    python tools/speech-harness/host_audio_bridge.py

It sets up `adb reverse`, switches the app's bridge on, starts a voice session, streams the PC mic
(ffmpeg dshow, 16 kHz mono s16le) into the app's normal capture path, plays reply audio with
ffplay and prints what 小诺 heard and said from the NovaVoice log. Press Enter to start a turn
(the `voice wake` path). Ctrl+C switches the bridge off and exits.

Audio is sent on a 20 ms clock; `[uplink] fps=` reports the rate (50.0 is steady). When the app
stops reading, old frames are dropped instead of blocking; when it disconnects (restart, bridge
switched off) the script re-enables the bridge and waits for it to come back.

    --mic "<dshow name>"   choose the microphone (default: first USB Audio Device, else first)
    --list                 list dshow microphones
    --from-file X.pcm      stream a 16 kHz mono s16le clip instead of the mic (verification);
                           several comma-separated clips play in order, --gap S apart (default 4)
    --duration S           exit after S seconds
    --no-play              do not play replies or guidance (bytes/seconds are still reported)

Amap's turn-by-turn guidance arrives as a tagged text frame and is spoken on the PC (edge-tts
zh-CN voice, SAPI fallback); `[guidance] chars=N` is printed, never the text.
    --no-start             do not start a session (say the wake word instead)
"""
import argparse
import math
import os
import re
import select
import shutil
import socket
import struct
import subprocess
import sys
import threading
import time

ADB = os.environ.get("ADB") or shutil.which("adb") or r"C:\Users\Administrator\Android\Sdk\platform-tools\adb.exe"
SERIAL = os.environ.get("ANDROID_SERIAL")
RECEIVER = "com.novadrive.app/.DebugToolReceiver"
RATE = 16000
CHUNK = RATE * 2 // 50  # 20 ms
SHOW = re.compile(r"transcript=|tool=|host_bridge|state=|error", re.I)


def adb(*args, **kw):
    target = ["-s", SERIAL] if SERIAL else ["-e"]
    return subprocess.run([ADB, *target, *args], capture_output=True, text=True,
                          encoding="utf-8", errors="replace", timeout=kw.get("timeout", 30))


def broadcast(tool, arg):
    return adb("shell", "am", "broadcast", "-n", RECEIVER, "-a", "com.novadrive.app.DEBUG_TOOL",
               "--es", "tool", tool, "--es", "arg", arg)


def ffmpeg(name="ffmpeg"):
    found = shutil.which(name)
    if found:
        return found
    if name == "ffmpeg":
        import imageio_ffmpeg  # noqa: the same fallback make_speech.py uses
        return imageio_ffmpeg.get_ffmpeg_exe()
    return None


def list_mics():
    out = subprocess.run([ffmpeg(), "-hide_banner", "-list_devices", "true", "-f", "dshow", "-i", "dummy"],
                         capture_output=True, text=True, encoding="utf-8", errors="replace").stderr
    return re.findall(r'"([^"]+)" \(audio\)', out)


def probe_rms(device, seconds=1.0):
    """Loudness of a short capture. A muted or disconnected device delivers exact digital zeros."""
    out = subprocess.run([ffmpeg(), "-hide_banner", "-loglevel", "error", "-f", "dshow", "-i", f"audio={device}",
                          "-t", str(seconds), "-ac", "1", "-ar", str(RATE), "-f", "s16le", "-"],
                         capture_output=True, timeout=15).stdout
    return rms(out)


def pick_mic(mics):
    """USB headset first, but skip a device that is silent (muted headset mic reads rms 0)."""
    ordered = sorted(mics, key=lambda m: "USB Audio" not in m)
    for mic in ordered:
        level = probe_rms(mic)
        if level > 0:
            return mic
        print(f"[bridge] {mic}: silent (muted?), trying the next microphone", flush=True)
    return ordered[0] if ordered else None


def rms(pcm):
    n = len(pcm) // 2
    if n == 0:
        return 0
    samples = struct.unpack(f"<{n}h", pcm[: n * 2])
    return int(math.sqrt(sum(s * s for s in samples) / n))


class Uplink:
    """Real-time, never-blocking PC -> app audio.

    Frames are sent on a 20 ms clock whatever the source delivers (dshow hands ffmpeg audio in
    bursts). When the app stops reading (its settings screen is in front, the process restarted),
    the socket's buffer fills: then the oldest whole frames are dropped - a partly sent frame is
    always finished, so the app never sees a torn sample - and the script keeps running. Before
    2026-09-28 a full buffer raised `conn.sendall TimeoutError` and killed the bridge (P37).
    """

    MAX_BACKLOG = 5  # 100 ms: older audio is worth nothing to a live conversation

    def __init__(self):
        self.lock = threading.Lock()
        self.queue = []
        self.offset = 0
        self.conn = None
        self.sent = self.dropped = self.stalls = 0
        self.window_sent, self.window_start = 0, time.time()
        self.max_gap, self.last_send = 0.0, None

    def attach(self, conn):
        with self.lock:
            self.conn, self.queue, self.offset = conn, [], 0
            self.last_send = None

    def detach(self):
        with self.lock:
            self.conn = None

    def push(self, chunk):
        with self.lock:
            if self.conn is None:
                return
            self.queue.append(chunk)
            while len(self.queue) > self.MAX_BACKLOG:
                # Never the frame that is half on the wire.
                del self.queue[1 if self.offset else 0]
                self.dropped += 1

    def pump(self):
        """Send what the socket accepts right now. False when the connection is gone."""
        with self.lock:
            conn = self.conn
            if conn is None:
                return True
            while self.queue:
                try:
                    if not select.select([], [conn], [], 0)[1]:
                        self.stalls += 1
                        return True
                    n = conn.send(memoryview(self.queue[0])[self.offset:])
                except (BlockingIOError, socket.timeout, InterruptedError):
                    self.stalls += 1
                    return True
                except OSError:
                    self.conn = None
                    return False
                self.offset += n
                if self.offset < len(self.queue[0]):
                    return True
                self.queue.pop(0)
                self.offset = 0
                self.sent += 1
                now = time.time()
                if self.last_send is not None:
                    self.max_gap = max(self.max_gap, now - self.last_send)
                self.last_send = now
            return True

    def report(self):
        now = time.time()
        span = now - self.window_start
        with self.lock:
            fps = (self.sent - self.window_sent) / span if span > 0 else 0
            line = (f"[uplink] frames={self.sent} fps={fps:.1f} dropped={self.dropped} "
                    f"stalls={self.stalls} backlog_ms={len(self.queue) * 20} max_gap_ms={self.max_gap * 1000:.0f}")
            self.window_sent, self.window_start, self.max_gap = self.sent, now, 0.0
        print(line, flush=True)


def paced(uplink, source, stop, frame_s=0.02):
    """Pushes one 20 ms frame per clock tick from [source] (a callable returning bytes)."""
    start, n, last = time.time(), 0, time.time()
    while not stop.is_set():
        uplink.push(source())
        uplink.pump()
        n += 1
        delay = start + n * frame_s - time.time()
        if delay > 0:
            time.sleep(delay)
        elif delay < -0.2:  # the PC itself stalled: resync rather than fire a burst
            start, n = time.time(), 0
        if time.time() - last >= 5:
            uplink.report()
            last = time.time()


def feed_mic(uplink, device, stop):
    """ffmpeg reads the mic into a small buffer; the clock, not dshow's chunking, paces the send."""
    proc = subprocess.Popen([ffmpeg(), "-hide_banner", "-loglevel", "error", "-f", "dshow",
                             "-audio_buffer_size", "20", "-i", f"audio={device}",
                             "-ac", "1", "-ar", str(RATE), "-f", "s16le", "-"],
                            stdout=subprocess.PIPE)
    captured = bytearray()
    cond = threading.Condition()

    def reader():
        while not stop.is_set():
            data = proc.stdout.read(CHUNK // 2)
            if not data:
                print("[bridge] microphone capture ended", flush=True)
                stop.set()
                break
            with cond:
                captured.extend(data)
                if len(captured) > CHUNK * 10:  # 200 ms: the mic got ahead of the clock
                    del captured[: len(captured) - CHUNK * 4]
                cond.notify()

    threading.Thread(target=reader, daemon=True).start()
    peak, last = 0, time.time()

    def next_frame():
        nonlocal peak, last
        with cond:
            if len(captured) < CHUNK:
                cond.wait(0.015)
            frame = bytes(captured[:CHUNK])
            del captured[:CHUNK]
        frame = frame + bytes(CHUNK - len(frame))  # a late mic is silence, not a stall
        peak = max(peak, rms(frame))
        if time.time() - last >= 5:
            print(f"[mic] rms={peak}", flush=True)
            last, peak = time.time(), 0
        return frame

    try:
        paced(uplink, next_frame, stop)
    finally:
        proc.kill()


def feed_file(uplink, paths, stop, lead=1.0, gap=0.0):
    """Clips in order, [gap] seconds of silence between them, then silence for ever."""
    stream = bytearray(int(RATE * 2 * lead))
    for i, path in enumerate(paths):
        clip = open(path, "rb").read()
        print(f"[bridge] clip {path} ({len(clip) / RATE / 2:.2f} s)", flush=True)
        if i:
            stream += bytes(int(RATE * 2 * gap) // 2 * 2)
        stream += clip
    pos = 0

    def next_frame():
        nonlocal pos
        chunk = bytes(stream[pos:pos + CHUNK])
        pos += CHUNK
        return chunk + bytes(CHUNK - len(chunk))

    paced(uplink, next_frame, stop)


def downlink(conn, stop, play):
    player, player_rate = None, None
    reply_bytes, reply_rate, last = 0, 0, 0.0

    def report():
        if reply_bytes:
            print(f"[reply audio] bytes={reply_bytes} rate={reply_rate} seconds={reply_bytes / 2 / reply_rate:.2f}",
                  flush=True)

    def read_exact(n):
        buf = b""
        while len(buf) < n:
            try:
                part = conn.recv(n - len(buf))
            except socket.timeout:
                if stop.is_set():
                    return None
                if last and time.time() - last > 0.8:
                    yield_report()
                continue
            except OSError:
                return None
            if not part:
                return None
            buf += part
        return buf

    def yield_report():
        nonlocal reply_bytes, last
        report()
        reply_bytes, last = 0, 0.0

    ffplay = shutil.which("ffplay") if play else None
    if play and not ffplay:
        print("[bridge] ffplay not found: replies are counted, not played", flush=True)
    while not stop.is_set():
        head = read_exact(8)
        if head is None:
            break
        rate, length = struct.unpack("<II", head)
        pcm = read_exact(length)
        if pcm is None:
            break
        if rate == 0:  # tagged frame: Amap guidance text, spoken here (never printed: it names places)
            speak_guidance(pcm.decode("utf-8", "replace"), play)
            continue
        reply_bytes += length
        reply_rate = rate
        last = time.time()
        if ffplay and rate != player_rate:
            if player:
                player.kill()
            player = subprocess.Popen([ffplay, "-hide_banner", "-loglevel", "error", "-nodisp", "-autoexit",
                                       "-fflags", "nobuffer", "-f", "s16le", "-ar", str(rate),
                                       "-ch_layout", "mono", "-i", "-"], stdin=subprocess.PIPE)
            player_rate = rate
        if player:
            try:
                player.stdin.write(pcm)
                player.stdin.flush()
            except OSError:
                player = None
    report()
    stop.set()
    if player:
        player.kill()


GUIDANCE = []


def speak_guidance(text, play):
    """Amap's own turn-by-turn voice plays only on the emulator speaker, so it is said again here.

    edge-tts (zh-CN neural voice, online) when installed, else Windows SAPI's default voice; this PC
    has no offline Chinese voice. A newer prompt cuts off an unfinished one, as in the SDK.
    """
    print(f"[guidance] chars={len(text)} spoken={bool(play)}", flush=True)
    if not play:
        return
    threading.Thread(target=_say, args=(text,), daemon=True).start()


def _say(text):
    for proc in GUIDANCE:
        if proc.poll() is None:
            proc.kill()
    GUIDANCE.clear()
    out = os.path.join(os.environ.get("TEMP", "."), "nova_guidance.mp3")
    # truststore: use the Windows certificate store, so an HTTPS-scanning antivirus does not break TLS.
    runner = ("import sys, runpy\ntry:\n import truststore; truststore.inject_into_ssl()\nexcept ImportError: pass\n"
              "sys.argv = ['edge_tts'] + sys.argv[1:]; runpy.run_module('edge_tts', run_name='__main__')")
    try:
        made = subprocess.run([sys.executable, "-c", runner, "--voice", "zh-CN-XiaoxiaoNeural", "--text", text,
                               "--write-media", out], capture_output=True, timeout=20).returncode == 0
    except subprocess.TimeoutExpired:
        made = False
    ffplay = shutil.which("ffplay")
    if made and ffplay:
        GUIDANCE.append(subprocess.Popen([ffplay, "-hide_banner", "-loglevel", "error", "-nodisp", "-autoexit", out]))
        return
    print("[guidance] edge-tts unavailable, using SAPI", flush=True)
    script = ("Add-Type -AssemblyName System.Speech; "
              "(New-Object System.Speech.Synthesis.SpeechSynthesizer).Speak([Console]::In.ReadToEnd())")
    proc = subprocess.Popen(["powershell", "-NoProfile", "-Command", script], stdin=subprocess.PIPE,
                            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    proc.stdin.write(text.encode("utf-8"))
    proc.stdin.close()
    GUIDANCE.append(proc)


LOGCAT = []


def follow_log(stop):
    proc = subprocess.Popen([ADB, *(["-s", SERIAL] if SERIAL else ["-e"]), "logcat", "-T", "1", "-s", "NovaVoice"],
                            stdout=subprocess.PIPE, text=True, encoding="utf-8", errors="replace")
    LOGCAT.append(proc)
    last_state = None
    for line in proc.stdout:
        if stop.is_set():
            break
        text = line.split("NovaVoice", 1)[-1].lstrip(": ").rstrip()
        if text.startswith("state="):
            if text == last_state:
                continue
            last_state = text
        if SHOW.search(text) and "uplink_frames" not in text and "session_diag" not in text:
            print("[app] " + text, flush=True)
    proc.kill()


def main():
    for stream in (sys.stdout, sys.stderr):
        stream.reconfigure(encoding="utf-8", errors="replace")
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=7790)
    ap.add_argument("--mic")
    ap.add_argument("--list", action="store_true")
    ap.add_argument("--from-file", help="clip.pcm, or several comma-separated, played in order")
    ap.add_argument("--gap", type=float, default=4.0, help="seconds of silence between --from-file clips")
    ap.add_argument("--duration", type=float)
    ap.add_argument("--no-play", action="store_true")
    ap.add_argument("--no-start", action="store_true", help="leave the session asleep (wake-word test)")
    args = ap.parse_args()

    if args.list:
        print("\n".join(list_mics()))
        return
    device = None
    if not args.from_file:
        mics = list_mics()
        device = args.mic or pick_mic(mics)
        if not device:
            sys.exit("no dshow microphone found")
        print(f"[bridge] microphone: {device}", flush=True)

    server = socket.socket()
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server.bind(("127.0.0.1", args.port))
    server.listen(1)
    server.settimeout(5)
    stop = threading.Event()
    threading.Thread(target=follow_log, args=(stop,), daemon=True).start()
    uplink = Uplink()
    if args.from_file:
        paths = args.from_file.split(",")
        feeder = lambda: feed_file(uplink, paths, stop, gap=args.gap)
    else:
        feeder = lambda: feed_mic(uplink, device, stop)

    def enter_to_talk():
        for _ in sys.stdin:
            print("[bridge] " + broadcast("voice", "wake").stdout.strip().splitlines()[-1][-80:], flush=True)

    deadline = time.time() + args.duration if args.duration else None
    started_session, feeding = False, False
    conn, dead = None, threading.Event()
    # One connection at a time, for as long as the script runs: the app may restart, the bridge may
    # be switched off, the settings screen may stop capture. The script outlives all of them (P37).
    try:
        while not stop.is_set() and (deadline is None or time.time() < deadline):
            if conn is None:
                adb("reverse", f"tcp:{args.port}", f"tcp:{args.port}")
                broadcast("bridge", f"on:{args.port}")
                try:
                    conn, _ = server.accept()
                except socket.timeout:
                    print("[bridge] waiting for the app (is a debug build running?)", flush=True)
                    continue
                conn.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
                conn.settimeout(0.3)
                dead = threading.Event()
                uplink.attach(conn)
                print("[bridge] connected", flush=True)
                threading.Thread(target=downlink, args=(conn, dead, not args.no_play), daemon=True).start()
                if not feeding:
                    feeding = True
                    threading.Thread(target=feeder, daemon=True).start()
                    if sys.stdin and sys.stdin.isatty():
                        threading.Thread(target=enter_to_talk, daemon=True).start()
                        print("[bridge] speak now; press Enter to start a new turn, Ctrl+C to quit", flush=True)
                if not args.no_start and not started_session:
                    started_session = True
                    broadcast("voice", "start")
            time.sleep(0.2)
            if dead.is_set() or uplink.conn is None:
                print("[bridge] app disconnected; reconnecting", flush=True)
                uplink.detach()
                dead.set()
                try:
                    conn.close()
                except OSError:
                    pass
                conn = None
    except KeyboardInterrupt:
        pass
    stop.set()
    dead.set()
    time.sleep(0.5)
    broadcast("bridge", "off")
    adb("reverse", "--remove", f"tcp:{args.port}")
    if conn:
        conn.close()
    for proc in LOGCAT:
        proc.kill()
    print("[bridge] off", flush=True)


if __name__ == "__main__":
    main()

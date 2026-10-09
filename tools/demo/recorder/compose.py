"""Turn a record_demo.py run into a 1920x1080 mp4: phone on the left, captions on the right.

    python compose.py RUN_DIR OUT.mp4
"""
import json, os, subprocess, sys
CLIPS = os.path.join(os.path.dirname(os.path.abspath(__file__)), "clips")

LINES = {
    "what": "你干什么", "what2": "你会干啥", "what3": "你都能帮我干嘛", "stuffy": "有点闷", "sweet": "说话嗲一点", "food": "你喜欢吃什么", "genki": "元气一点",
    "bossy": "说话霸道一点", "normal": "正常一点", "color": "你喜欢什么颜色", "cold": "讲个冷知识",
    "weather": "今天珠海天气怎么样", "nav": "导航去珠海金湾机场", "music": "放一首轻松的歌", "philo": "你觉得人为什么要开车",
}

NOTES = {
    "小诺 · 新功能演示": ("cursor/10-9：通义千问 Omni，声音是 Maia。每个场景都是一次连续的实时录制，回复前的等待一秒未剪。",
                    "cursor/10-9: Qwen-Omni, voice Maia. Every scene is one continuous real-time take; no reply wait is cut."),
    "1  需要等的时候，先应一声": ("查天气、找歌要几秒。前 7 秒不说话，屏幕上从第 3 秒显示「处理中」。满 7 秒还没有有用的话，才说一次「收到，正在处理。」。满 12 秒换一句说明网络可能慢，不把上一句再说一遍。",
                          "Silent for 7 s, with a 'working' label from 3 s. One progress line at 7 s, and a different delay line at 12 s."),
    "2  想一想的问题": ("闲聊也一样：7 秒内不插一句「我想想」。只有到了 7 秒还没开口，才说一次「收到，正在处理。」，然后接上真正的回答。",
                   "A question gets no filler before 7 s. One progress line only if nothing useful has been spoken, then the real reply."),
    "3  问能力：马上回答": ("以前列能力（「能帮你调空调、开车窗…」）会被执行证据门当成「假装执行」拦下，要等 13–28 秒。现在列能力直接播出。",
                      "An ability list used to be blocked as a claim of a done action (13-28 s of silence); now it plays straight away."),
    "4  一句话 → 多个动作": ("「有点闷」触发一个场景：打开空调、调大风量、前窗开一点。三个动作都由车辆接口确认后才播报。",
                       "\"It's stuffy\" runs a scenario: air on, fan up, front windows open a little, each confirmed by the car."),
    "5  更多说话风格": ("语气可以换，声音还是 Maia。没有的风格（霸道）会如实说明，不会假装换了一个声音。",
                  "The tone can change; the voice stays Maia. An unknown style is refused honestly."),
    "6  出错提示 12 秒后自动消失": ("断网（飞行模式）后启动会话：显示连接失败的提示卡片，12 秒后自动消失，不会一直挡在地图上。",
                           "With no network, the connection error card appears and fades by itself after 12 s."),
}
RENAME = {
    "7  需要等的时候，先应一声": ("1  需要等的时候，先应一声", "A slow task: she says she is on it, and why if it takes longer"),
    "8  想一想的问题": ("2  想一想的问题", "A question that needs thought gets a short 'let me think'"),
    "1  问能力：马上回答": ("3  问能力：马上回答", "\"What can you do?\" is answered at once"),
    "2  一句话 → 多个动作": ("4  一句话 → 多个动作", "One casual phrase runs a whole scenario"),
    "3  更多说话风格": ("5  更多说话风格", "More speaking styles; an unknown one is refused honestly"),
}

HEAD = """[Script Info]
ScriptType: v4.00+
PlayResX: 1920
PlayResY: 1080
WrapStyle: 0

[V4+ Styles]
Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
Style: Title,Microsoft YaHei,66,&H00FFFFFF,&H00FFFFFF,&H00000000,&H00000000,1,0,0,0,100,100,0,0,1,0,0,7,740,80,150,1
Style: TitleEn,Microsoft YaHei,34,&H00C8B89A,&H00FFFFFF,&H00000000,&H00000000,0,0,0,0,100,100,0,0,1,0,0,7,740,80,245,1
Style: Note,Microsoft YaHei,38,&H00EDEDED,&H00FFFFFF,&H00000000,&H00000000,0,0,0,0,100,100,0,0,1,0,0,7,740,90,340,1
Style: NoteEn,Microsoft YaHei,28,&H00A8A8A8,&H00FFFFFF,&H00000000,&H00000000,0,0,0,0,100,100,0,0,1,0,0,7,740,90,545,1
Style: Driver,Microsoft YaHei,48,&H0066D9FF,&H00FFFFFF,&H00000000,&H00000000,1,0,0,0,100,100,0,0,1,0,0,1,740,80,190,1
Style: Evid,Microsoft YaHei,36,&H0080E080,&H00FFFFFF,&H00000000,&H00000000,1,0,0,0,100,100,0,0,1,0,0,7,740,80,680,1
Style: Hold,Microsoft YaHei,36,&H0040C0FF,&H00FFFFFF,&H00000000,&H00000000,1,0,0,0,100,100,0,0,1,0,0,7,740,80,680,1
Style: Lat,Microsoft YaHei,30,&H00FFC890,&H00FFFFFF,&H00000000,&H00000000,0,0,0,0,100,100,0,0,1,0,0,7,740,80,740,1
Style: Cut,Microsoft YaHei,44,&H0000D7FF,&H00FFFFFF,&H00000000,&H00000000,1,0,0,0,100,100,0,0,1,0,0,7,740,80,800,1
Style: Foot,Microsoft YaHei,24,&H00808080,&H00FFFFFF,&H00000000,&H00000000,0,0,0,0,100,100,0,0,1,0,0,1,740,80,40,1

[Events]
Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
"""


def wrap_cjk(text, width=27.0):
    """libass breaks lines only at spaces; break Chinese by width (ASCII counts half), never before punctuation."""
    lines, cur, w = [], "", 0.0
    for ch in text:
        cw = 0.55 if ord(ch) < 128 else 1.0
        if w + cw > width and ch not in "，。；：、！？」）" and cur:
            lines.append(cur)
            cur, w = "", 0.0
        cur += ch
        w += cw
    lines.append(cur)
    return "\\N".join(l.strip() for l in lines)


def ts(t):
    t = max(0.0, t)
    h, rem = divmod(t, 3600)
    m, s = divmod(rem, 60)
    return f"{int(h)}:{int(m):02d}:{s:05.2f}"


TOOLS = {"control_climate": "空调", "control_window": "车窗", "control_seat": "座椅", "run_scenario": "场景",
         "query_live_info": "实时信息", "navigate_to": "导航搜索", "choose_navigation_option": "导航选择",
         "exit_navigation_mode": "结束导航", "set_speaking_style": "说话风格", "control_music": "音乐"}
RESULT_WORDS = {"AMBIGUOUS_REFERENT": "指代不明 → 先问一句", "control_window·set": "前窗开一点", "control_climate·power_on": "空调打开", "control_climate·adjust_fan": "风量调大",
                "control_window·close": "车窗关闭", "✓ choose_navigation_option": "✓ 已选择", "✓ navigate_to": "✓ 搜索目的地",
                "✓ set_speech_output": "✓ 停止播报", "✓ exit_navigation_mode": "✓ 已退出导航"}
ACTION_KINDS_SKIP = {"CONVERSATION", "CAPABILITY_HELP"}


def evidence(run, tl):
    """Tool results and claim-gate holds from the app log, on the video clock."""
    import re, statistics
    lines = open(os.path.join(run, "logcat.txt"), encoding="utf-8", errors="replace").read().splitlines()
    stamp = lambda l: float(l.split()[0])
    dev_wakes = [stamp(l) for l in lines if "debug_tool tool=voice arg=wake" in l]
    pairs = list(zip(dev_wakes, tl["wakes"]))
    offset = statistics.median(d - p for d, p in pairs) if pairs else float(os.environ.get("OFFSET", "0"))
    vt = lambda l: stamp(l) - offset - tl["t0"]
    out, holds = [], {}
    results = []
    for l in lines:
        m = re.search(r"NovaVoice: tool=(\w+) args=\S* result=(.*)$", l)
        if m:
            res = m.group(2).strip()
            for a, b in RESULT_WORDS.items():
                res = res.replace(a, b)
            results.append((vt(l), TOOLS.get(m.group(1), m.group(1)), res))
            continue
        m = re.search(r"TURN_HOLD epoch=(\d+) reason=(\w+) kind=(\w+)", l)
        if m and m.group(3) not in ACTION_KINDS_SKIP:
            holds[m.group(1)] = vt(l)
            continue
        m = re.search(r"TURN_(RELEASE|DROP) epoch=(\d+)", l)
        if m and m.group(2) in holds:
            a = holds.pop(m.group(2))
            b = vt(l)
            if b - a > 0.15:
                out.append(f"Dialogue: 2,{ts(a)},{ts(b)},Hold,,0,0,0,,⏸ 回复暂扣，等待车辆执行证据 / reply held until the car confirms")
    # from the end of the driver's own audio (exact on the video clock) to her first audible clause
    firsts = [vt(l) for l in lines if "assistant_voice_first_audio" in l]
    clips = tl["clips"]
    for i, (t, key) in enumerate(clips):
        e = t + os.path.getsize(os.path.join(CLIPS, key + ".pcm")) / 2 / 16000
        nxt_clip = clips[i + 1][0] if i + 1 < len(clips) else 1e9
        nxt = [f for f in firsts if e < f < nxt_clip]
        if nxt and nxt[0] - e < 30:
            f = nxt[0]
            if not os.environ.get("NO_LAT"): out.append(f"Dialogue: 3,{ts(f)},{ts(f + 5.0)},Lat,,0,0,0,,{{\\fad(150,300)}}⏱ 说完 → 开口  {f - e:.1f} s")
    cue_text = {"progress": "收到，正在处理。", "delay": "还在处理，网络可能不太稳定，再等我一下。",
                "ack_action": "收到，正在处理。", "ack_chat": "嗯，我想想。", "provider_slow": "网络有点慢，请稍等。",
                "verifying": "我确认一下，马上回答你。", "still_waiting": "还在处理，网络可能不太稳定，再等我一下。",
                "tool_running": "（说明正在执行的工具）稍等一下。"}
    phrase = {"navigate_to": "正在搜索路线，稍等一下。", "choose_navigation_option": "正在搜索路线，稍等一下。",
              "query_live_info": "正在查询，稍等一下。", "play_music": "正在找歌，稍等一下。", "control_music": "正在找歌，稍等一下。",
              "describe_camera_view": "正在看画面，稍等一下。"}
    last_tool = None
    for l in lines:
        t = re.search(r"gemini_tool_call id=\S+ tool=(\w+)", l)
        if t:
            last_tool = t.group(1)
        m = re.search(r"wait_cue code=(\w+) after_ms=(\d+)", l)
        if m and m.group(1) == "tool_running":
            cue_text["tool_running"] = phrase.get(last_tool, "正在处理，稍等一下。")
        if m:
            out.append(f"Dialogue: 3,{ts(vt(l))},{ts(vt(l) + 4.0)},Evid,,0,0,0,,{{\\fad(150,300)}}"
                       f"💬 等待提示 ({int(m.group(2)) / 1000:.1f} s) · {cue_text.get(m.group(1), m.group(1))}")
    for l in lines:
        m = re.search(r"NovaVoice: error=(\w+)", l)
        if m:
            out.append(f"Dialogue: 3,{ts(vt(l))},{ts(vt(l) + 7.0)},Hold,,0,0,0,,连接失败 · {m.group(1)}")
        m = re.search(r"error_card_faded code=(\w+) after_ms=(\d+)", l)
        if m:
            out.append(f"Dialogue: 3,{ts(vt(l))},{ts(vt(l) + 7.0)},Evid,,0,0,0,,✓ 提示卡片 {int(m.group(2)) / 1000:.1f} s 后自动消失")
    for i, (t, name, res) in enumerate(results):
        stop = min(t + 7.0, results[i + 1][0]) if i + 1 < len(results) else t + 7.0
        out.append(f"Dialogue: 3,{ts(t)},{ts(stop)},Evid,,0,0,0,,{{\\fad(150,300)}}执行结果 · {name}  {res}")
    print(f"evidence: offset={offset:.3f}s results={len(results)} holds_shown={sum(1 for e in out if ',Hold,' in e)}")
    return out


def main(run, out_mp4):
    tl = json.load(open(os.path.join(run, "timeline.json"), encoding="utf-8"))
    end = tl["end"]
    ev = []
    titles = [e for e in tl["events"] if "title" in e]
    for e in titles:
        if e["title"] in RENAME:
            e["title"], e["sub"] = RENAME[e["title"]]
    for i, e in enumerate(titles):
        stop = titles[i + 1]["t"] if i + 1 < len(titles) else end
        a, b = ts(e["t"]), ts(stop)
        ev.append(f"Dialogue: 0,{a},{b},Title,,0,0,0,,{{\\fad(300,200)}}{e['title']}")
        ev.append(f"Dialogue: 0,{a},{b},TitleEn,,0,0,0,,{{\\fad(300,200)}}{e['sub']}")
        zh, en = NOTES.get(e["title"], ("", ""))
        ev.append(f"Dialogue: 0,{a},{b},Note,,0,0,0,,{{\\fad(500,200)}}{wrap_cjk(zh)}")
        ev.append(f"Dialogue: 0,{a},{b},NoteEn,,0,0,0,,{{\\fad(500,200)}}{en}")
    clips = tl["clips"]
    for i, (t, key) in enumerate(clips):
        stop = min(t + 6.0, clips[i + 1][0] - 0.2) if i + 1 < len(clips) else t + 6.0
        ev.append(f"Dialogue: 1,{ts(t)},{ts(stop)},Driver,,0,0,0,,{{\\fad(150,250)}}司机 ▸ 「{LINES[key]}」")
    ev.append(f"Dialogue: 0,{ts(0)},{ts(end)},Foot,,0,0,0,,Nova Drive 小诺 · Gemini Live + Azure Neural TTS + Amap Navigation SDK"
              f" · Android 模拟器实录，未加速；回复前的等待未剪，轮次之间的空白已剪短 · 车辆为模拟车辆接口 / real reply latency kept; dead air between turns trimmed; simulated vehicle")
    ev += evidence(run, tl)
    cuts = []
    starts = [e["t"] for e in tl["events"] if e.get("cut_start")]
    for a, b in zip(starts, [e["t"] for e in tl["events"] if e.get("cut_end")]):
        ca, cb = a + 4.0, b - 4.0
        cuts.append((ca, cb))
        ev.append(f"Dialogue: 4,{ts(cb)},{ts(b + 1.0)},Cut,,0,0,0,,{{\\fad(150,300)}}⏩ 已静默 {b - a:.0f} 秒，画面略去中间 {cb - ca:.0f} 秒\\N（30 秒后会话已自动休眠）")
    ass = os.path.join(run, "demo.ass")
    open(ass, "w", encoding="utf-8-sig").write(HEAD + "\n".join(ev) + "\n")
    graph = ("[0:v]scale=-2:1080,setsar=1[ph];color=c=0x11161c:s=1920x1080:r=30[bg];"
             "[bg][ph]overlay=x=130:y=0:shortest=1,subtitles=demo.ass[v];"
             "[1:a]volume=0.85[d];[2:a]volume=1.0[r];[d][r]amix=inputs=2:normalize=0:duration=longest[a]")
    subprocess.run(["ffmpeg", "-y", "-hide_banner", "-loglevel", "error", "-i", "screen.webm", "-i", "driver.wav",
                    "-i", "reply.wav", "-filter_complex", graph, "-map", "[v]", "-map", "[a]", "-t", f"{end:.2f}",
                    "-c:v", "libx264", "-preset", "medium", "-crf", "20", "-pix_fmt", "yuv420p", "-c:a", "aac",
                    "-b:a", "192k", "-movflags", "+faststart", os.path.abspath(out_mp4) + (".full.mp4" if cuts else "")], cwd=run, check=True)
    if cuts:
        keep = "*".join(f"not(between(t,{a:.3f},{b:.3f}))" for a, b in cuts)
        subprocess.run(["ffmpeg", "-y", "-hide_banner", "-loglevel", "error", "-i", os.path.abspath(out_mp4) + ".full.mp4",
                        "-vf", f"select='{keep}',setpts=N/FRAME_RATE/TB", "-af", f"aselect='{keep}',asetpts=N/SR/TB",
                        "-c:v", "libx264", "-preset", "medium", "-crf", "20", "-pix_fmt", "yuv420p", "-c:a", "aac", "-b:a", "192k",
                        "-movflags", "+faststart", os.path.abspath(out_mp4)], check=True)
        os.remove(os.path.abspath(out_mp4) + ".full.mp4")
    print("wrote", out_mp4)


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])

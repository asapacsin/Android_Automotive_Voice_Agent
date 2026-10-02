"""B-034 / ADR-016: audition Chinese TTS voices with 小诺's own sentences, before any integration.

Gemini Live's prebuilt voices were rejected by ear (BACKLOG B-034) and a prompt cannot make them
younger in conversation (VOICE-AB-002). ADR-016 keeps Gemini as the agent and lets one Chinese TTS
voice speak. This script lets the owner hear candidate voices from each vendor with the same lines,
so the pick is made by ear and costs no app work.

    # Volcengine / Doubao (豆包语音): app id + access token from the console
    VOLC_APP_ID=... VOLC_ACCESS_TOKEN=... python audition.py volc
    # MiniMax: API key (mainland endpoint by default; MINIMAX_HOST=api.minimax.io outside China)
    MINIMAX_API_KEY=... python audition.py minimax
    # MiniMax voice design: a NEW synthetic voice from a description (nothing cloned)
    MINIMAX_API_KEY=... python audition.py minimax-design
    # Baidu short TTS (the per ids the app already offers on Baidu Flex): speech-app AK/SK
    BAIDU_TTS_AK=... BAIDU_TTS_SK=... python audition.py baidu
    # only some voices:  python audition.py volc zh_female_qingchezizi_moon_bigtts ...

Output: OUT (default ./tts_audition)/<vendor>/<voice>.<ext> and summary.json. Keys come only from
the environment and are never printed or written. Lines are synthetic test sentences only (I-8).

Constraint (B-034): never clone or imitate a real character's or voice actor's voice. Pick a stock
voice or design a new one from a description of the *style*.
"""
import base64, json, os, sys, time, urllib.error, urllib.parse, urllib.request, uuid

OUT = os.environ.get("OUT", "tts_audition")

# The same lines for every voice: a confirmation, guidance, a capability answer, a 傲娇 line.
LINES = [
    "好的，空调已经调到二十二度了。",
    "前方五百米右转，然后走第二个出口。",
    "我能帮你导航、放音乐、调空调和座椅，还能看摄像头和打电话。",
    "哼，这点小事，交给我就行。",
]
TEXT = "".join(LINES)

# Candidate stock voices (young, clear female). Ids follow each vendor's public voice list; if a
# vendor rejects one, the row says so and the others still run — check the console's 音色列表.
VOLC_VOICES = [
    "zh_female_qingchezizi_moon_bigtts",      # 清澈梓梓
    "zh_female_linjianvhai_moon_bigtts",      # 邻家女孩
    "zh_female_shuangkuaisisi_moon_bigtts",   # 爽快思思
    "zh_female_tianmeixiaoyuan_moon_bigtts",  # 甜美小源
    "zh_female_gaolengyujie_moon_bigtts",     # 高冷御姐
    "zh_female_qiaopinvsheng_mars_bigtts",    # 俏皮女声
    "zh_female_mengyatou_mars_bigtts",        # 萌丫头
    "zh_female_wanwanxiaohe_moon_bigtts",     # 湾湾小何
]
MINIMAX_VOICES = [
    "female-shaonv",       # 少女
    "female-shaonv-jingpin",
    "qiaopi_mengmei",      # 俏皮萌妹
    "tianxin_xiaoling",    # 甜心小玲
    "female-tianmei",      # 甜美女性
    "female-yujie",        # 御姐
]
BAIDU_VOICES = ["4196", "6562", "4194", "4103", "111", "4157"]  # = BaiduFlexVoices.CATALOG

# Voice design: describe the style only. No character, game or person is named.
DESIGN_PROMPTS = {
    "poised_young": "十七八岁的年轻女性，声音清亮通透、吐字利落，音调偏高；自信、聪明，带一点骄傲，语气端庄克制，不幼稚，不嗲，不沙哑。",
    "bright_proud": "少女声线，明亮清脆，语速中等偏快，说话干脆，像习惯了自己总是对的聪明女孩，偶尔有一点小傲气，但很可靠。",
}


def post(url, body, headers, timeout=60):
    data = body if isinstance(body, bytes) else json.dumps(body).encode()
    req = urllib.request.Request(url, data=data, headers=headers, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, r.headers.get("Content-Type", ""), r.read()
    except urllib.error.HTTPError as e:
        return e.code, e.headers.get("Content-Type", ""), e.read()


def save(vendor, voice, audio, ext):
    os.makedirs(os.path.join(OUT, vendor), exist_ok=True)
    path = os.path.join(OUT, vendor, f"{voice}.{ext}")
    with open(path, "wb") as f:
        f.write(audio)
    return path


def short(raw):
    return raw[:200].decode("utf-8", "replace") if isinstance(raw, bytes) else str(raw)[:200]


def volc(voice):
    app_id, token = os.environ["VOLC_APP_ID"], os.environ["VOLC_ACCESS_TOKEN"]
    body = {"app": {"appid": app_id, "token": "nova-audition", "cluster": os.environ.get("VOLC_CLUSTER", "volcano_tts")},
            "user": {"uid": "nova-audition"},
            "audio": {"voice_type": voice, "encoding": "wav", "rate": 24000, "speed_ratio": 1.0},
            "request": {"reqid": str(uuid.uuid4()), "text": TEXT, "text_type": "plain", "operation": "query"}}
    status, _, raw = post("https://openspeech.bytedance.com/api/v1/tts", body,
                          {"Content-Type": "application/json", "Authorization": f"Bearer;{token}"})
    try:
        d = json.loads(raw)
    except ValueError:
        return {"error": f"HTTP {status} {short(raw)}"}
    if d.get("code") != 3000 or not d.get("data"):
        return {"error": f"HTTP {status} code={d.get('code')} {d.get('message', '')[:160]}"}
    return {"file": save("volc", voice, base64.b64decode(d["data"]), "wav")}


def minimax_tts(voice):
    key = os.environ["MINIMAX_API_KEY"]
    host = os.environ.get("MINIMAX_HOST", "api.minimaxi.com")
    body = {"model": os.environ.get("MINIMAX_MODEL", "speech-02-hd"), "text": TEXT, "stream": False,
            "voice_setting": {"voice_id": voice, "speed": 1, "vol": 1, "pitch": 0},
            "audio_setting": {"sample_rate": 24000, "format": "mp3", "channel": 1}}
    status, _, raw = post(f"https://{host}/v1/t2a_v2", body,
                          {"Content-Type": "application/json", "Authorization": f"Bearer {key}"})
    try:
        d = json.loads(raw)
    except ValueError:
        return {"error": f"HTTP {status} {short(raw)}"}
    base = d.get("base_resp") or {}
    audio = (d.get("data") or {}).get("audio")
    if base.get("status_code") != 0 or not audio:
        return {"error": f"HTTP {status} status_code={base.get('status_code')} {str(base.get('status_msg', ''))[:160]}"}
    return {"file": save("minimax", voice, bytes.fromhex(audio), "mp3")}


def minimax_design(name):
    key = os.environ["MINIMAX_API_KEY"]
    host = os.environ.get("MINIMAX_HOST", "api.minimaxi.com")
    status, _, raw = post(f"https://{host}/v1/voice_design",
                          {"prompt": DESIGN_PROMPTS[name], "preview_text": TEXT},
                          {"Content-Type": "application/json", "Authorization": f"Bearer {key}"})
    try:
        d = json.loads(raw)
    except ValueError:
        return {"error": f"HTTP {status} {short(raw)}"}
    base = d.get("base_resp") or {}
    if base.get("status_code") != 0 or not d.get("trial_audio"):
        return {"error": f"HTTP {status} status_code={base.get('status_code')} {str(base.get('status_msg', ''))[:160]}"}
    # The designed voice id is what the app would use; it is not a secret.
    return {"file": save("minimax-design", name, bytes.fromhex(d["trial_audio"]), "mp3"), "voice_id": d.get("voice_id")}


_baidu_token = None


def baidu(voice):
    global _baidu_token
    if _baidu_token is None:
        q = urllib.parse.urlencode({"grant_type": "client_credentials",
                                    "client_id": os.environ["BAIDU_TTS_AK"], "client_secret": os.environ["BAIDU_TTS_SK"]})
        status, _, raw = post(f"https://aip.baidubce.com/oauth/2.0/token?{q}", b"", {})
        _baidu_token = json.loads(raw).get("access_token") or ""
        if not _baidu_token:
            return {"error": f"token HTTP {status}"}
    form = urllib.parse.urlencode({"tex": TEXT, "tok": _baidu_token, "cuid": "nova-audition", "ctp": 1, "lan": "zh",
                                   "per": voice, "aue": 6, "spd": 5, "pit": 5}).encode()
    status, ctype, raw = post("https://tsn.baidu.com/text2audio", form, {"Content-Type": "application/x-www-form-urlencoded"})
    if not ctype.startswith("audio"):
        return {"error": f"HTTP {status} {short(raw)}"}
    return {"file": save("baidu", voice, raw, "wav")}


VENDORS = {
    "volc": (volc, VOLC_VOICES, ["VOLC_APP_ID", "VOLC_ACCESS_TOKEN"]),
    "minimax": (minimax_tts, MINIMAX_VOICES, ["MINIMAX_API_KEY"]),
    "minimax-design": (minimax_design, list(DESIGN_PROMPTS), ["MINIMAX_API_KEY"]),
    "baidu": (baidu, BAIDU_VOICES, ["BAIDU_TTS_AK", "BAIDU_TTS_SK"]),
}


def main(argv):
    if not argv or argv[0] not in VENDORS:
        print(__doc__)
        return 2
    fn, defaults, env = VENDORS[argv[0]]
    missing = [name for name in env if not os.environ.get(name)]
    if missing:
        print(f"missing environment variable(s): {', '.join(missing)}")
        return 2
    rows = []
    for voice in argv[1:] or defaults:
        started = time.monotonic()
        try:
            row = fn(voice)
        except Exception as failure:  # one bad voice must not stop the audition
            row = {"error": f"{type(failure).__name__}: {str(failure)[:160]}"}
        row = {"vendor": argv[0], "voice": voice, "ms": round((time.monotonic() - started) * 1000), **row}
        rows.append(row)
        print(json.dumps(row, ensure_ascii=False))
    os.makedirs(OUT, exist_ok=True)
    with open(os.path.join(OUT, f"summary-{argv[0]}.json"), "w", encoding="utf-8") as f:
        json.dump({"text": TEXT, "rows": rows}, f, ensure_ascii=False, indent=1)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))

"""Driver lines for the demo recording: edge-tts (test input only, never shipped) -> 16 kHz mono s16le."""
import asyncio, os, subprocess, sys
import edge_tts

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "clips")
os.makedirs(OUT, exist_ok=True)
VOICE = "zh-CN-YunxiNeural"  # the driver: a male voice, so he is never confused with Xiaoyi

LINES = {
    "what2": "你会干啥",
    "what3": "你都能帮我干嘛",
    "what": "你干什么",
    "stuffy": "有点闷",
    "sweet": "说话嗲一点",
    "food": "你喜欢吃什么",
    "genki": "元气一点",
    "bossy": "说话霸道一点",
    "normal": "正常一点",
    "color": "你喜欢什么颜色",
    "cold": "讲个冷知识",
    "weather": "今天珠海天气怎么样",
    "nav": "导航去珠海金湾机场",
    "music": "放一首轻松的歌",
    "philo": "你觉得人为什么要开车",
}


async def make(key, text):
    mp3 = os.path.join(OUT, key + ".mp3")
    await edge_tts.Communicate(text, VOICE, proxy="http://127.0.0.1:7897").save(mp3)
    subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", mp3, "-ac", "1", "-ar", "16000", "-f", "s16le",
                    os.path.join(OUT, key + ".pcm")], check=True)


async def main():
    for k, t in LINES.items():
        if os.path.exists(os.path.join(OUT, k + ".pcm")):
            continue
        await make(k, t)
        print("ok", k, flush=True)


if __name__ == "__main__":
    asyncio.run(main())

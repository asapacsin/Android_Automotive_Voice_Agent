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
    # The commute demo (docs/DEMO_COMMUTE.md): the order of the 豆包座舱 review video, 横琴创业谷 → 横琴镇.
    "c_sweet": "能不能说话再稍微嗲一点",
    "c_seat": "帮我把主驾座椅稍微调高一点",
    "c_seat2": "再调高一点",
    "c_nav": "导航去横琴镇",
    "c_pick": "第一个",
    "c_go": "开始导航",
    "c_mosq": "前风挡那儿有一只蚊子",
    "c_mosq2": "蚊子还没走",
    "c_music": "帮我放首歌，阿Sa的老公唱的，最近很火那首，叫闭目什么的",
    "c_traffic": "前面堵不堵",
    "c_weather": "今天天气怎么样",
    "c_close": "蚊子出去了，关上吧",
    "c_end": "结束导航",
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

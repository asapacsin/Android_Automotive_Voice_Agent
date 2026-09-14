from __future__ import annotations

import argparse
import asyncio
import json
import sys
import time
from pathlib import Path

BACKEND_ROOT = Path(__file__).resolve().parents[1]
if str(BACKEND_ROOT) not in sys.path:
    sys.path.insert(0, str(BACKEND_ROOT))

from app.voice.fake_provider import FakeRealtimeVoiceProvider
from app.voice.mock_provider import MockRealtimeVoiceProvider
from app.voice.models import DomainEventType
from app.voice.qwen_protocol import translate_qwen_event
from app.voice.gpt_live_protocol import translate_gpt_live_event
from app.voice.translator import translate_baidu_event


async def drain(provider) -> list[str]:
    kinds: list[str] = []
    await provider.close()
    async for event in provider.receive_events():
        kinds.append(event.type.value)
    return kinds


async def run_fake_scenarios() -> dict:
    provider = FakeRealtimeVoiceProvider()
    await provider.connect("fake-realtime")
    await provider.send_audio(b"\x00\x00" * 320)
    started = time.perf_counter()
    await provider.play_mandarin()
    await provider.play_code_switch()
    await provider.play_rapid_interrupt()
    await provider.play_work_refine()
    await provider.play_reconnect()
    await provider.interrupt()
    kinds = await drain(provider)
    elapsed_ms = round((time.perf_counter() - started) * 1000, 3)
    return {
        "provider": provider.provider_id,
        "scenarios": [
            "mandarin",
            "zh_en_code_switch",
            "rapid_turn_interruption",
            "background_work_refine",
            "reconnect",
        ],
        "events": kinds,
        "audio_bytes": provider.sent_audio_bytes,
        "cancel_count": provider.cancel_count,
        "elapsed_ms": elapsed_ms,
        "metrics": {
            "event_count": len(kinds),
            "interrupted": DomainEventType.INTERRUPTED.value in kinds,
            "tool_call": DomainEventType.TOOL_CALL.value in kinds,
            "user_transcript": DomainEventType.USER_TRANSCRIPT.value in kinds,
        },
        "comparative_live_claim": False,
        "transport": "fake-in-process",
    }


async def run_mock_demo() -> dict:
    provider = MockRealtimeVoiceProvider(auto_reply=True)
    await provider.connect("audio-mini-realtime-near")
    await provider.send_audio(b"\x00\x00" * 2000)
    await provider.interrupt()
    kinds = await drain(provider)
    return {
        "provider": provider.provider_id,
        "events": kinds,
        "audio_bytes": provider.sent_audio_bytes,
        "interrupted": DomainEventType.INTERRUPTED.value in kinds,
    }


def run_benchmark(iterations: int = 1000) -> dict:
    fixtures = Path(__file__).resolve().parents[1] / "tests" / "fixtures"
    baidu_events = json.loads((fixtures / "baidu_interrupt_round.json").read_text(encoding="utf-8"))
    qwen_call = json.loads((fixtures / "qwen_function_call_done.json").read_text(encoding="utf-8"))
    gpt_call = json.loads((fixtures / "gpt_live_nested_function_call.json").read_text(encoding="utf-8"))
    started = time.perf_counter()
    produced = 0
    for _ in range(iterations):
        speaking = False
        for raw in baidu_events:
            translated = translate_baidu_event(raw, speaking=speaking)
            produced += len(translated)
            speaking = any(item.type == DomainEventType.AUDIO_DELTA for item in translated)
        produced += len(translate_qwen_event(qwen_call))
        produced += len(translate_gpt_live_event(gpt_call))
    elapsed_ms = round((time.perf_counter() - started) * 1000, 3)
    return {
        "iterations": iterations,
        "domain_events_out": produced,
        "elapsed_ms": elapsed_ms,
        "transport": "injected-fixtures",
        "comparative_live_claim": False,
        "providers_parsed": ["baidu", "qwen", "gpt_live"],
    }


def main() -> None:
    parser = argparse.ArgumentParser(description="Quota-free fake realtime demo/benchmark")
    parser.add_argument("--benchmark", action="store_true")
    parser.add_argument("--iterations", type=int, default=1000)
    parser.add_argument("--mock", action="store_true")
    args = parser.parse_args()
    if args.benchmark:
        print(json.dumps(run_benchmark(args.iterations), ensure_ascii=False))
        return
    if args.mock:
        print(json.dumps(asyncio.run(run_mock_demo()), ensure_ascii=False))
        return
    print(json.dumps(asyncio.run(run_fake_scenarios()), ensure_ascii=False))


if __name__ == "__main__":
    main()

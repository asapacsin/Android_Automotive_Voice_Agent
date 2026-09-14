# Fake-provider benchmarks — Nova Drive / 小诺

These numbers are **local fixture/fake timings**, not live comparative results.

```powershell
cd backend
python scripts/fake_provider_demo.py
python scripts/fake_provider_demo.py --benchmark --iterations 1000
```

Recorded fields:

- `elapsed_ms`
- `event_count` / `domain_events_out`
- `audio_bytes`
- `interrupted`
- `tool_call`
- `transport` (`fake-in-process` or `injected-fixtures`)
- `comparative_live_claim: false`

Scenarios in the fake demo: Mandarin, Chinese-English code switching, rapid-turn interruption, background-work refine, reconnect.

Do not publish these as Qwen vs GPT-Live vs Baidu quality or latency rankings. Live provider comparison needs credentials, quota, and a device.

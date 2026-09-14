# Checkpoints — Nova Drive / 小诺

## Checkpoint 1 — Project bootstrap (current)

Minimal Kotlin/Gradle foundation: documents, module boundaries, typed nav/media/phone/HVAC commands, deterministic policy, Fake simulator, observed-state verification, JVM demo, automated behavior tests. Optional Android debug APK when platform 34 is installed.

**Done when:** every acceptance criterion in `agent/CURRENT_TASK.md` is PASS with command evidence.

## Checkpoint 2 — Voice ingress / optional Baidu E2E vertical slice

Provider-neutral realtime voice: Android mic → backend → selected official provider WebSocket → native audio back to Android. The Baidu E2E slice remains as an optional compatibility adapter (Lite Near is the Baidu-family default when Baidu is selected). Product default is Qwen Flash.

## Checkpoint 2b — Realtime provider reconciliation (CURRENT_TASK)

Qwen Flash (`qwen-audio-3.0-realtime-flash`) is the default realtime provider. Qwen Plus is selectable. GPT-Live is optional. Baidu is an optional compatibility provider (Lite Near is the Baidu-family default when Baidu is selected). Fake is quota-free and test-only, never the product default. Shared Kotlin contract, `VoiceSessionController`, work coordinator, bounded reconnect, latency diagnostics, and official-fixture adapters.

**Done when:** every acceptance criterion in `agent/CURRENT_TASK.md` is assessed with evidence (PASS / FAIL / BLOCKED).

## Checkpoint 3 — Provider-neutral S2S

Speech-native S2S session compatible with Mainland network constraints. Adapter emits **typed structured function calls** into `StructuredCommandIngress`. Credentials stay out of core. No direct S2S-to-VHAL path.

## Checkpoint 4 — Production safety and task manager

Replace bootstrap rules with a complete deterministic policy table, interruption/preemption semantics, multi-step tasks, and audit-quality reason codes. `CONFIRM` UX for driving. Still no LLM-authored execution.

## Checkpoint 5 — Domain skills with Fake providers

Navigation, media, phone, and HVAC skills on the provider-neutral ports, including Chinese POI labels, contact disambiguation, and metric HVAC/media. AMap/Baidu/OEM remain un-linked SDKs; Fake remains available only for quota-free local tests, while Qwen Flash remains the product default.

## Checkpoint 6 — Verification + zh-CN UX

Richer observed-state models, failure copy, visual/spoken feedback in the app shell, and regression tests for mismatch vs execution failure. Still no claim of success without read-back where verification is available.

## Checkpoint 7 — Optional AAOS / VHAL (optional)

Real Android Automotive adapter behind the same `VehiclePort`. VHAL / `Car` APIs isolated in an `adapters:aaos` module that **core must not compile against**. Simulator remains the default for tests.

Each later checkpoint must preserve Checkpoint 1 boundaries. Do not weaken `CURRENT_TASK` acceptance criteria when a new task file is issued; add criteria instead.

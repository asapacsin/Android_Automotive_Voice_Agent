# SPEC-003 — Amap coexistence: voice policy by category, and an abstract action layer

Status: **Revised by the v2 replacement spec — carried under [SPEC-005](SPEC-005-embedded-amap-mvp.md) Phases 5–6.** C1 is resolved (v2 §16 keeps Baidu realtime — ADR-002 stands); C3 is resolved (v2 §19 — the embedded SDK exposes guidance-speech state); `AMAP_ACTIVE` is replaced by the `NavigationState` enum (v2 §12); categories gain `NAVIGATION_CONFIRMATION`/`NAVIGATION_ERROR` (v2 §17–18). The turn-provenance derivation (C2), the `device_control` tool (C4) and the focus question (C5) carry over unchanged.
Raised: 2026-09-16 by the product owner — verbatim source: [DEMAND-2026-09-16](DEMAND-2026-09-16-amap-coexistence.md) §1–13, §33, §34
Backlog: [B-004](../BACKLOG.md)
Related: [P1](../OPEN_PROBLEMS.md) (already shipped a first version of this rule), [P3](../OPEN_PROBLEMS.md), [SPEC-002](SPEC-002-navigation-uplink-mute.md), [ADR-002](../DECISIONS/ADR-002-baidu-flex-default-provider.md), [ADR-003](../DECISIONS/ADR-003-amap-navigation-delegation.md)

## Demand (what was actually asked for)

While Amap navigation is active, the assistant keeps listening and acting, but **stays silent for ordinary conversation** and **speaks only short confirmations for actions** (turn on/off, pause/resume, and their failures). The silent-vs-speak decision must be made from **semantic response categories**, not text matching, in one `VoicePolicy` place. Device actions go through a generic `ActionExecutor` with a **mock** implementation now and real ones later, without the rest of the assistant changing. If Amap is itself speaking, the action still executes immediately and the confirmation is queued until Amap finishes.

## Why it matters

This is the product's core use case restated precisely. P1 shipped a first, crude version (mute everything while navigating except a 10-second window after any accepted tool). The demand upgrades it from a *time window* to a *policy*, and adds a whole class of actions (device control) that do not exist today.

## What already exists — reuse, do not rebuild

| Demand concept | Existing counterpart | Gap |
| --- | --- | --- |
| `NORMAL` / `AMAP_ACTIVE` | `NavigationState.navigating` | none |
| `ASSISTANT_ACTION_CONFIRMATION` | `NavigationState.allowConfirmation()` — 10 s window after **any** Accepted tool | **time-based, not category-based** (see C2) |
| Silence during navigation | `AndroidPlaybackPort.enqueue` drops frames when `shouldMuteSpeech()` | drops at playback — correct layer for an E2E model |
| `ActionExecutor` | `AndroidActionExecutor` interface + `SafeAndroidActionExecutor` | typed per tool; no generic `ActionRequest(target, action, value)`; no mock |
| Navigation handoff → `AMAP_ACTIVE` | `navigate_to` Accepted → `NavigationState.begin()` | none |
| Amap exit → normal voice | `exit_navigation_mode` (built 2026-09-16, not device-verified) | L5 pending |
| "Continues listening during Amap" | mic stays open while navigating — SPEC-002's "trap" rule already forbids gating the whole drive | consistent |
| "Assistant remains alive during Amap" | microphone foreground service (`BAL_ALLOW_FOREGROUND` measured) | verified on device |

## Constraints and conflicts

### C1 — §2's pipeline is not this product's architecture. **Decision required.**

The demand draws `ASR → Intent Parser → Request Classification → ActionExecutor → VoicePolicy → TTS`. That is a **cascaded** pipeline. This product is **end-to-end speech-to-speech** by decision: [ADR-002](../DECISIONS/ADR-002-baidu-flex-default-provider.md), and `PRODUCT.md` states outright *"There is no speech-recognition or text-to-speech stage in this app."* The product owner chose E2E explicitly and objected the last time the system looked like it had a separate ASR stage.

The two are not interchangeable, but **the requirements do not actually depend on the cascade** — they depend on three things the E2E stack already has:

| Cascade stage in §2 | E2E equivalent that exists today |
| --- | --- |
| Intent / request classification | the model's **function call** (a typed tool call) vs. a plain spoken reply |
| ActionExecutor + ActionResult | tool dispatch → `AndroidActionResult.Accepted / Rejected` → `function_call_output` |
| "Internal text generated while TTS silent" | the provider's caption transcript (`transcript=小诺: …`) is still produced while playback frames are dropped |
| VoicePolicy → TTS / Silent | a policy applied at **playback** (`AndroidPlaybackPort.enqueue`), which is where P1's rule already lives |

So the recommended resolution is: **keep ADR-002, and implement VoicePolicy at the playback boundary of the E2E stack.** The alternative — introducing real ASR/TTS stages — would supersede ADR-002 and reopen a decision the owner has already made twice. This spec proceeds on the recommended resolution; if the owner wants the cascade instead, that is a new ADR, not a quiet reinterpretation.

### C2 — "semantic category, not text matching" vs. what an E2E model exposes

Today's rule is *temporal*: any Accepted tool opens a 10 s window in which anything the model says is audible. That leaks: a conversational reply inside the window is spoken during navigation. The demand forbids text matching (correct — we do none) but requires semantic metadata, and an E2E model attaches no category to its speech.

There **is** metadata available without text matching: **turn provenance.** After a tool executes we send `function_call_output` and then `response.create` ourselves (`BaiduFlexClient.sendFunctionResult`). The reply that follows is, by construction, the reply *to a tool result*. So:

- reply generated in response to a `function_call_output` we sent → `ACTION_CONFIRMATION` (if Accepted) or `ACTION_FAILURE` (if Rejected)
- reply generated in response to user speech, with no tool call → `INFORMATIONAL_RESPONSE`
- reply that follows an Accepted `navigate_to` → `NAVIGATION_HANDOFF`

That is exactly the `(amapActive, responseCategory, actionResult)` tuple §7 asks for, derived from protocol events rather than from what the words say. **Open question Q1 is whether Flex's event stream lets us attribute a `response.*` reliably to the preceding `response.create` we issued.** If it does, the category is clean; if not, the current time window is the fallback and must be documented as such.

"Speak briefly" (§6, §33.8) cannot be enforced by the policy on an E2E model; it is a persona instruction (already present: 每次回复不超过两句话, and 工具结果返回后用一句话简短确认) and is *measured*, not enforced (SPEC-004 duration metric).

### C3 — §13 "queue the confirmation until Amap stops speaking" is currently infeasible on this device

SPEC-002 Phase 1 **measured** that Amap's guidance track is `state:started` for the entire navigation, whether or not it is speaking, and that `getClientUid()` is redacted. There is no observable "Amap is speaking now" signal via playback configuration on HyperOS/SDK 36. The demand marks this P1; it stays P1, and it is **blocked on the same unknown as SPEC-002** — do not promise it, and do not implement the queue against a signal we cannot read. Executing the action immediately (the part §13 insists on) already happens.

### C4 — new action targets vs. `PRODUCT.md` scope

空调 / 蓝牙 / 灯 are not in the current feature set, and VHAL is a stated non-goal. The demand is explicit that these are **mock** now, so this is a scope *extension*, not a conflict — but `PRODUCT.md` must be updated when it ships, and "smart scenes / windows / seats" remain non-goals.

An E2E model needs a **declared tool** for each capability. Rather than one tool per device, declare one `device_control(target, action, value?)` tool whose `target` and `action` are **enumerated** in the schema and re-validated by the existing whitelist in `FlexFunctionCallAssembler.validate`. That preserves `PRODUCT.md`'s non-goal of "natural-language parsing of assistant prose as a substitute for typed tool calls," and it maps 1:1 onto `ActionRequest(target, action, value)`.

### C5 — §12 audio behaviour is partly unverified

Frames are dropped at `enqueue`, but whether `AudioFocusController` still **requests focus** for a reply that is then fully dropped has not been checked. If it does, Amap is ducked for nothing — exactly what §12 forbids. Open question Q3.

### C6 — interaction with the wake word ([ADR-006](../DECISIONS/ADR-006-wake-word-aikit-shared-capture.md))

SPEC-002's real fix keeps the uplink **closed** during navigation and opens it on 你好小诺. §1 says the assistant "continues listening and processing user requests." These are compatible only if, during navigation, a request is preceded by the wake phrase. That is a product-behaviour choice the owner should confirm (Q5).

## Scope (P0 from §34, mapped)

1. `VoicePolicy` as a single decision point: `decide(amapActive, responseCategory, actionResult, criticality) → SPEAK_NORMAL | SPEAK_SHORT | SILENT`, applied at the playback boundary. Replaces the bare 10 s window as the primary mechanism (the window may remain as a safety fallback if Q1 is unfavourable).
2. Response categories per §6 with the policy table per §6, derived by turn provenance (C2).
3. `ActionExecutor` abstraction per §8 with `MockActionExecutor` supporting success and injected failures per §9–10, plus one `device_control` tool (C4). Existing typed tools keep working unchanged.
4. Navigation handoff and exit already exist; they must produce `NAVIGATION_HANDOFF` and return to `NORMAL` respectively.

## Out of scope

- Real hardware (§8 "later" executors), VHAL, Bluetooth, home automation.
- Amap-speaking detection and the confirmation queue (C3) — P1, blocked.
- The test harness, failure records and metrics — **[SPEC-004](SPEC-004-speech-test-harness.md)**.
- Any change to `ingress` beyond what the policy seam needs (`DependencyBoundaryTest` still applies).
- Reviving ASR/TTS stages (C1).

## Open questions — must be answered before building

| # | Question | How to answer |
| --- | --- | --- |
| Q1 | Can a Flex `response.*` be attributed to the `response.create` we issued after `function_call_output`, so the category is provenance-based? | Read the Flex event stream on a live session (L6); look for a response id echoed from `response.create` |
| Q2 | Should `SPEAK_SHORT` also apply to `Rejected` tool results (the demand says yes — §10, §33.7)? Today a Rejected result *also* opens the confirmation window? | Read `AndroidToolDispatcher.result()`: `allowConfirmation()` is called only on Accepted. Decide, then align |
| Q3 | Is audio focus requested for a reply whose frames are all dropped? | `dumpsys audio` focus stack while navigating and asking an informational question (L5) |
| Q4 | Does `CRITICAL_ALERT` exist in this product yet? Nothing emits one. | Owner: define or drop |
| Q5 | During navigation, must a request be preceded by 你好小诺 (C6)? | Owner decision |
| Q6 | `device_control` enumerations for the mock: which targets/actions exactly? | Owner: minimum set is §5's list (air_conditioner, bluetooth, light, music pause/resume) |

## Acceptance (per `ACCEPTANCE_TESTS.md`)

Maps §33 items 1–11:

| §33 | Item | Level |
| --- | --- | --- |
| 1–3 | Amap launches, owns navigation, assistant survives | **L5** — already demonstrated for P1; must not regress |
| 4 | Assistant still hears the driver while navigating | **L5** — the regression that matters most (SPEC-002) |
| 5 | Informational replies silent during navigation | **L5**, and L2 for the policy table itself |
| 6–7 | Action confirmations and failures voiced, briefly | **L5** |
| 8 | Confirmation is short | measured, not enforced — SPEC-004 |
| 9 | Avoid overlapping Amap speech | **blocked** (C3) — record as not performed |
| 10–11 | Mock works; real executor can replace it without pipeline change | L2 (contract tests against the `ActionExecutor` interface) |

`VoicePolicy.decide` is pure and must have a full L2 table test covering every (state × category) cell of §6. The demand's TC01/TC02/TC03/mixed-workload/exit cases are the L5 script and live in SPEC-004.

# Technical debt

Found by auditing this repository on 2026-09-18. Real, specific, and each one observed — not
generic advice. Ordered by priority.

---

## D-1 — `BaiduFlexClient` owns too much — **RESOLVED 2026-09-18** (`89c9338`)

**Problem.** One class holds the WebSocket, the vendor protocol, the turn gate, the conversation
reset policy, the empty-response retry, the claim guard wiring, the phantom-turn hold, the
unsupported-request hold and the real-time-info hold. Per-turn state lives in eight `@Volatile`
fields whose interactions are only understandable by reading the whole file — the "turn" boundary
bug (state reset per response instead of per driver utterance) was caused exactly by that, and
silenced a real confirmation.

**Affected.** `app/voice/BaiduFlexClient.kt`, and every test that scripts a realtime server.

**Risk.** The next per-turn feature will interact with the existing holds in a way nobody predicts.
This is the most likely source of a future regression in the voice path.

**Resolved.** `DriverTurn` now owns the per-utterance state with explicit phases, epoch guarding and
legal transitions; the eight `@Volatile` flags are gone and `ArchitectureRulesTest` fails if any of
them returns. Covered by `DriverTurnTest` (17 cases including cancellation and stale events).

---

## D-2 — The same policy is stated in the prompt and enforced in code — **RESOLVED 2026-09-19**

**Was.** Persona rules 2–4 and `FLEX_TOOL_RULE` tell the model things that are *also* enforced
deterministically, so the prose read like a mechanism. The recorded remedy was to trim the prompt to
tone once the deterministic owners had device evidence.

**What the evidence changed.** The premise was partly wrong, and the live runs of 2026-09-19 showed
it directly. Most of those sentences are the **only** mechanism that can cause a tool call: no code
can make a model call a function. When the implicit-intent table was missing from the
`control_climate` declaration, 「有点热」 was answered with a question and the cabin did not change;
adding it produced the call. Trimming that class of sentence does not remove duplication, it removes
the only lever.

[I-11](INVARIANTS.md) says a prompt rule may not be the only thing *preventing a wrong action*. It
does not say the prompt may not be the only thing *prompting a right one*. Those are different, and
conflating them is what made this entry look larger than it was.

**What was actually wrong, and is fixed.** The persona told the model to say
「高德地图的导航需要用户自己退出」 — true under the deep-link model (ADR-003), false since
[ADR-007](../DECISIONS/ADR-007-embedded-amap-navigation-sdk.md) embedded the SDK, and flatly
contradicted by `FLEX_TOOL_RULE` two paragraphs below. The persona was instructing the model to make
a false statement about the product to the driver.

Removed, and guarded by `ArchitectureRulesTest.thePersonaDoesNotDescribeAnArchitectureWeNoLongerHave`
so a replaced architecture cannot survive in the prompt again. Verified live on `2391ff70`:
「结束导航。」 → `exit_navigation_mode` → 「已取消导航选择。」, with no instruction to the driver to go and
close another app.

**Not done, deliberately.** The persona is not trimmed further. Every remaining tool sentence is
load-bearing by the measurement above, and the safety-relevant ones already have deterministic
owners named in the `FLEX_TOOL_RULE` doc comment.

---

## D-3 — The UI executes vehicle and media actions directly — **RESOLVED 2026-09-19**

**Was.** `BottomBarView` called `BundledMusicPlayer` and `VehicleControlPort` straight from its
click listeners, so the screen path and the voice path reached the executors by different routes.
The voice path got argument validation, bounds checking and a result-shaped outcome; the screen
path got none of it.

**Resolved by** `ScreenControls` / `ExecutorScreenControls`: the screen states intent, and the
Activity hands it the **same** `AndroidActionExecutor` and `ClimateToolHandler` instances the tool
dispatcher holds. The bar's label now changes only when the executor says the action succeeded,
never because a button was pressed — which is the same rule the voice path follows.

State flows are exposed through the same interface, not because reading is executing, but because a
view that reaches for a concrete backend to render itself eventually reaches for it to act.

`ArchitectureRulesTest.uiDoesNotReachIntoExecution` now runs with an **empty** exception set, so a
new offender fails the build instead of being recorded as debt.

---

## D-4 — Two navigation state machines — **RESOLVED 2026-09-19** (risk closed; the duplication is now guarded)

**Was.** `NavigationState.navigating` (the legacy speech-mute flag) and `NavigationPhase` /
`NavigationStateStore` both answered "are we navigating". The recorded symptom was that arriving
with the session still open left the driver muted, because the arrival fix updated the phase and
the mute followed the old flag.

**What was actually true on 2026-09-19.** The symptom no longer reproduced:
`EmbeddedNavigationController.onNavigationEnded` already calls `onFlowEnded()`, which resets the
mute, for `arrived`, `emulator_end` and every stop. What was missing was *anything that would
notice if that stopped being true* — the flag has one writer path, and the risk was drift, not
disagreement.

**Resolved by** `NavigationMuteFollowsPhaseTest`, which drives the real `NavigationState` through
the controller's production callbacks at every terminal transition: guidance starts and mutes;
`arrived`, `emulator_end` and an explicit stop each unmute; and `replaced` deliberately stays muted,
because the old guidance ended but a new drive is already running.

The test was checked against a broken build before being trusted — changing the production call to
`if (reason == "stopped")` failed exactly the two arrival cases and nothing else.

**What was deliberately not done.** The flag is not collapsed into `NavigationPhase`. It has a
single writer path and now a guard; replacing it would touch the one behaviour (P1) the product
owner verified by ear on the device, for no behavioural gain. If a second writer ever appears, that
is the trigger to finish the job — and the test above is what will surface it.

---

## D-5 — Dormant providers and paths still compile — **LOW, but costly to a fresh agent**

**Problem.** Qwen, GPT-Live, the PC backend (`backend/`, `BackendRealtimeProvider`,
`BackendVoiceClient`), `NavigationAdapter`'s Amap deep link and `AmapAutoPickService` are all
present and buildable, and none is part of the product. `README.md` still describes Qwen Flash as
the default provider, which is false.

**Affected.** `app/voice/Qwen*`, `app/voice/Backend*`, `backend/`, `app/NavigationAdapter.kt`,
`app/AmapAutoPickService.kt`, `README.md`.

**Risk.** A fresh agent infers the wrong architecture from filenames — the exact failure
`AGENTS.md` warns about.

**Recommendation.** Delete the PC backend and GPT-Live metadata; keep Qwen only if a second
provider is still wanted, and say so in one line.

**README corrected 2026-09-19.** The setup steps read as product instructions and said
`VOICE_PROVIDER=qwen (default)`; they are now scoped to the dormant `backend/` explicitly.
That was the part of this entry no authority was needed for, so it is done.

BLOCKED_BY: a product decision — whether a second realtime provider is still wanted at all. docs/ARCHITECTURE.md records these paths as deliberately kept, so deleting them is the owner's call, not an agent's.

---

## D-6 — `agent/*.md` is stale — **RESOLVED 2026-09-18** (`8d35b8a`)

**Problem.** `agent/CURRENT_TASK.md`, `agent/PROJECT_STATE.md` and `agent/WORKER_REPORT.md` are
dated 2026-09-15 and describe the deep-link navigation architecture, a 129-test suite and a
milestone that has since closed. They are wrong, and they are linked from the old entry point.

**Affected.** `agent/`.

**Risk.** A fresh agent reads them as current and rebuilds the wrong mental model.

**Resolved.** The four stale files were deleted and every reference repointed. Live state is now
generated into `state/PROJECT_STATE.json`.

---

## D-7 — A false claim could be spoken for *supported* actions — **RESOLVED 2026-09-18** (`89c9338`)

**Problem.** The hold that prevents a false claim covers requests with **no** tool. For a supported
action the model can still say 「导航已开始。」 before the tool runs; `ActionClaimGuard` then makes it
true and the driver hears the sentence twice. Measured on device 2026-09-18 during 「开始导航」.

**Affected.** `BaiduFlexClient`, `ActionClaimGuard`, `PhantomTurnGate`.

**Risk.** The driver hears a claim before it is true — a weaker form of exactly what
[INVARIANTS.md](INVARIANTS.md) I-1 exists to prevent.

**Resolved, and generalised beyond navigation.** Invariant I-1 now states that only deterministic
execution evidence may establish that an action occurred; `DriverTurn` holds an action reply until a
tool result with `ok=true` arrives. Device evidence: `TURN_DROP epoch=3 reason=unproven_action_claim
events=7`, then `nav_navigation_started routeId=12`, then the claim spoken once. The feared latency
did not appear: in the normal flow the model answers *from* the tool result, so proof already exists.

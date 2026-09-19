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

## D-2 — The same policy is stated in the prompt and enforced in code — **HIGH**

**Problem.** Persona rules 2–4 and `FLEX_TOOL_RULE` tell the model: always call the tool, never
claim success without one, call `control_music` for stop, call `choose_navigation_option` rather
than answering verbally. Every one of those is *also* enforced deterministically
(`ActionClaimGuard`, `PhantomTurnGate`, `AndroidToolDispatcher`). The prompt text is now a
restatement, not a mechanism — but it reads like one, so a future agent may "fix" behaviour by
editing the prompt and believe the job is done.

**Affected.** `PersonaProfiles.kt`, `ActionClaimGuard`, `PhantomTurnGate`, `BaiduFlexClient`.

**Risk.** Policy drifts between the two statements; the prompt grows; the deterministic owner is
bypassed.

BLOCKED_BY: the test phone has no network route to the Baidu provider, so removing a prompt rule cannot be shown to leave live behaviour unchanged — see CURRENT_MILESTONE.md

**Partly addressed 2026-09-18** (`8d35b8a`): `PersonaProfiles` now carries a header naming the
deterministic owner of each safety-relevant rule, so a reader knows the prose is advisory. The
duplication itself remains — the prompt still states rules that code enforces. Remaining work is to
trim the prompt to tone and phrasing once the deterministic owners have more device evidence.

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

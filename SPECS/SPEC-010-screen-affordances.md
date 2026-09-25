# SPEC-010 — Anything on screen can be said (可见即可说)

Status: **Draft**
Raised: 2026-09-24 · Source: [B-024](../BACKLOG.md#b-024--say-anything-on-screen)
Depends on: [I-1, I-6, I-8](../docs/INVARIANTS.md), [ADR-008](../DECISIONS/ADR-008-single-active-realtime-provider.md), [SPEC-006](SPEC-006-complex-voice-commands.md) (turn ledger in `DriverContext`)

> **Reaching Done on this SPEC does not end the run.** Reconcile the registry, the debt list and
> the backlog row, then run `python scripts/discover_work.py` and take the next item. Handing
> control back because a SPEC finished is forbidden by
> [CONSTITUTION.md](../harness/CONSTITUTION.md) rule 12.

## Goal

A driver who can see a control can say its label, and the app acts on it without asking the
model. The model is the weak point: it sometimes hears a command correctly and still does not call
the tool ([P2](../OPEN_PROBLEMS.md)). The transcript of what the driver said
(`conversation.item.input_audio_transcription.completed`) already reaches the app. Matching it
against the controls on screen is code we control and can test, so it adds commands without
depending on function calling.

## Scope

- Every enabled, driver-safe control on the main screen publishes an **affordance**: id, label,
  aliases, optional list position, and the action it runs.
- A deterministic matcher resolves a **whole utterance** that names one affordance, with an
  optional verb (点 / 按 / 打开 / 关掉 / 选) and politeness particles (帮我 / 一下 / 吧 / 请).
- A match runs through the **same route as a tap**: a synthesised `ToolCall` through
  `AndroidToolDispatcher`, or `ScreenControls` where the tap already goes there (I-6).
- The current affordance labels go into `VoiceContextHints`, so the model knows about them too.
- The navigation picker rows become affordances, replacing the separate path in
  `VoiceSessionController.tryLocalNavigationPick` (the old path is deleted in the same change).

Controls on screen at `846d116` (`BottomBarView`, `NavigationChoiceOverlay`, `AssistantOverlayView`,
`CameraPreviewView`):

| Control | On screen | Spoken names (default) | Route today | Affordance action |
| --- | --- | --- | --- | --- |
| Previous track | ⏮ | 上一首, 重新播放 | `ScreenControls.restartMusic` | same |
| Play / pause | ▶ / ❚❚ | 播放, 暂停, 停止 | `ScreenControls.toggleMusic` | `control_music` play or stop by word, not toggle |
| Next track | ⏭ (disabled) | — | none | **not published** — the existing `MEDIA_NEXT_TRACK` refusal answers |
| Temp − / + | − / + | 温度减, 温度加 | `ScreenControls.adjustTemperature(∓step)` | same |
| Climate readout | 24° 风2 / 关 24° | 空调 | `ScreenControls.toggleClimatePower` | same |
| Recentre | 📍 (`回到当前位置` a11y) | 回到当前位置, 定位 | `onRecenterClick` callback | **add** `ScreenControls.recenter()` first (I-6) |
| Camera | 📷 | 摄像头, 相机 | `onCameraClick` callback | **add** `ScreenControls.toggleCamera()` first (I-6) |
| Picker rows | place / route names | row name, 第N个 | `NavigationPickerIntercept` + local pick | `choose_navigation_option{index}` |
| Picker cancel | 取消 | 取消 | `controller.cancel()` | `exit_navigation_mode` |
| Listening toggle | ● | — | lifecycle | **not published**: listening control already has one owner (`VoiceCommandRouter`) |
| Developer settings | ⚙ | — | Activity | **not published**: not a driving control |

The bottom bar is icons, not words, so its spoken names are defined, not read off the screen. Each
one also becomes the control's `contentDescription`, so accessibility and voice share one name.
Only the picker rows and 取消 are literally "what you see is what you say" today; the rest is a
fixed command set that works without the model. Play/pause resolves by the word said (暂停 while
already paused does nothing and says so), never as a blind toggle.

## Non-goals

- Controls outside our Activity (Amap's own buttons inside `AMapNaviView`): we cannot enumerate
  them reliably, and pressing them bypasses our navigation owner.
- Fuzzy or phonetic matching of labels. Picker rows already have phonetic *confirmation*
  (`NavigationPhoneticConfirmation`); plain controls are short fixed words, so exact alias match is
  enough and cannot misfire into a wrong action.
- Sentences that contain more than a label (「把温度调低一点然后去公司」). Those stay with the model;
  the matcher must not steal them.

## Capability ground truth

From [config/capabilities.yaml](../config/capabilities.yaml) at `846d116`: music is one bundled
track (play/stop only), climate is the simulated `VehicleControlPort`, navigation picker and cancel
are device-verified. No new capability is created; this adds a second way to *reach* existing ones.

## Behaviour

B0. **Names.** Each affordance's spoken names come from string resources, never from the icon text.

B1. **Registry.** `ScreenAffordances` (package `com.novadrive.app.ui`) holds the current list as a
`StateFlow<List<Affordance>>`. Views publish on attach / state change and withdraw on detach. It is
the single owner of "what is on screen"; `VoiceContextHints` reads it instead of composing picker
text itself.

B2. **Matcher.** `AffordanceMatcher.match(text, affordances)` is pure (JVM-tested). It normalises
(punctuation, wake prefix — reuse `UtteranceIntentResolver.normalizeForHelp`), strips one leading
verb and trailing particles, and returns `Match(affordance)`, `Ambiguous(ids)` or `None`. It returns
`Match` only when **nothing is left over** after the verb, label/alias and particles are removed.

B3. **Order in `onUserUtterance`.** Listening-control phrases (`VoiceCommandRouter`) first, then the
affordance matcher, then the model. A `Match` calls `active.cancelCurrentResponse()` and dispatches.

B4. **One execution per turn, whichever side is first.** The model's response to the same
transcript can already be under way, and its tool call can arrive before or after the transcript.
`DriverContext.claimDispatch` keys on exact arguments, so a local `value=-1` and a model
`value=-1.0` would both run. Extend that owner with `claimCapability(epoch, tool, action)`: the
local path and `ToolCallGuards.repeatedInTurn` both claim it; the second claimant gets
`DUPLICATE_IN_TURN`. If the model's call ran first, the matcher does nothing.

B5. **Feedback.** Success: the effect is the feedback (music starts, the temperature label changes)
plus the existing success chip; nothing is spoken. Failure: the result is sent to the model as
text (`sendText`) so it says one sentence about what did not happen, and I-1 guards that sentence as
for any tool failure.

B6. **Position words.** 「第N个」 resolves only while a numbered list is on screen; otherwise it is
`None` and goes to the model (never guessed against a list that is gone — `OPTIONS_STALE` still
applies through `NavigationChoiceAuthority`).

## Failure behaviour

| Case | Driver gets |
| --- | --- |
| Label of a disabled control (下一首) | Not published → model path → existing honest refusal |
| Two affordances share an alias | `Ambiguous` → model path; logged `affordance_ambiguous count=N` |
| Label spoken inside a longer sentence | `None` → model path (B2) |
| Action fails (climate fault) | One spoken sentence from the failure result (B5) |
| Model already ran the same tool this turn | Local path skipped (B4) |
| Screen changed between transcript and dispatch | Dispatch re-reads the registry; a withdrawn id returns `AFFORDANCE_GONE`, spoken as a failure |
| Wake phrase alone | Handled by `VoiceCommandRouter` before the matcher |

## Observability

`affordance_match id=<id> verb=<bool>` · `affordance_ambiguous count=N` · `affordance_skip reason=claimed`.
Never the transcript or a place name (I-8): picker row ids are opaque (`i2`, POI id), not names.

## Acceptance criteria

| # | Criterion | Kind | Proven by | State |
| --- | --- | --- | --- | --- |
| A1 | Every published label and alias matches alone, with each verb and particle | functional | `AffordanceMatcherTest` | **built** |
| A2 | A label inside a longer sentence does not match | negative | `AffordanceMatcherTest` | **built** |
| A3 | A local match and a model call for the same capability in one turn execute once, in both orders | regression protection | `AffordanceTurnClaimTest` | **built** |
| A4 | Recentre and camera go through `ScreenControls` for both tap and voice | architectural | `ArchitectureRulesTest.uiDoesNotReachIntoExecution` + `ScreenRouteParityTest` | **built** |
| A5 | Picker selection uses the registry; `tryLocalNavigationPick` is gone | production wiring | `FeaturePresenceRegressionTest` + existing picker tests green | not built |
| A6 | `VoiceContextHints` lists the affordances on screen | functional | `VoiceContextHintsTest` | **built** |
| A7 | Spoken 「暂停」「空调」「回到当前位置」 act on the phone with no tool call from the model | device | `AFFORDANCE-DEVICE-001` (speech harness, LOCAL_DEVICE) | not built |
| A8 | Registry, capabilities, TEST_MATRIX rows agree | reconciliation | `harness_check.py`, `test_matrix.py --validate` | not built |

## Open product decisions

- **Spoken names.** The defaults in the table, kept in `strings.xml` and used as `contentDescription`.
  The owner can add more; no escalation needed.
- **Speak on success?** Default **no** (B5). Changeable later without touching the matcher.

## Implementation status

Steps, each a separate verified commit (steps 1–3 done 2026-09-25):

1. `ScreenControls.recenter()` / `toggleCamera()`; move the two callbacks onto it; A4.
2. `Affordance`, `ScreenAffordances`, `AffordanceMatcher` + A1/A2 (pure, no wiring).
3. `DriverContext.claimCapability` + `ToolCallGuards` change + A3.
4. **4a (done 2026-09-25):** `BottomBarView` publishes; `onUserUtterance` calls
   `ScreenAffordanceRunner` before the picker path; names in `strings.xml` double as
   `contentDescription`; A6. `VoiceContextHints.awaitingAnswer()` excludes the always-present bar,
   so turn holding is unchanged. Tests: `ScreenAffordanceRunnerTest`, `VoiceContextHintsTest`.
   **4b (open):** fold the picker into affordances and delete `tryLocalNavigationPick`; A5.
   Deferred on purpose: picker names use `NavigationChoiceResolver`'s fuzzy name match and
   phonetic confirmation, which the exact matcher does not reproduce, and the picker path is
   device-verified — replacing it needs the phone to re-verify, so it goes with A7.
5. `TEST_MATRIX.yaml` rows (unit + `AFFORDANCE-DEVICE-001` as `LOCAL_DEVICE`), capabilities,
   `./gradlew test --rerun-tasks :app:assembleDebug`, then device run A7.

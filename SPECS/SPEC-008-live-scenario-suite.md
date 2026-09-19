# SPEC-008 — The scenario set, run against the live model, repeatably

Status: **Implemented 2026-09-20.** The scenario file, the runner and its negative control all
exist and run on `2391ff70`. First full run: 9/12, and the three failures were worth more than the
nine passes — one was a real defect, two were assertions written against a mechanism instead of a
result.
Raised: 2026-09-20 · Source: [B-011](../BACKLOG.md)
Depends on: [SPEC-004](SPEC-004-speech-test-harness.md) · [capabilities.yaml](../config/capabilities.yaml) ·
[I-1, I-2](../docs/INVARIANTS.md)

> Reaching Done here does not end the run. Reconcile the registry, the backlog row and the state,
> then `python scripts/discover_work.py` and take the next item
> ([CONSTITUTION.md](../harness/CONSTITUTION.md) rule 12).

## Goal

Every claim this project makes about *product behaviour* — that a vague request reaches a real
action, that an ambiguous one asks, that a failure is admitted — rests today on someone having
watched it work once. Each of the last three defects was found by running one clip by hand and
reading the log. That is a good way to find bugs and a terrible way to keep them fixed.

The goal is that the scenario set can be **run again**, by anyone, and that it says pass or fail
without a person interpreting a log.

## Scope

A scenario file and a runner. Each scenario names what the driver says, and what must be true
afterwards *in the app's own log* — the tool that ran, the state it produced, or the specific
absence of both.

## Non-goals

| Not in this phase | Why |
| --- | --- |
| Replacing the simulation benchmark | It runs without quota or a phone and catches different things. This is the live layer above it |
| Acoustic testing | Injection bypasses the microphone by design (SPEC-004). Distance, road noise and echo need a person |
| Asserting on reply wording | The model rewords freely; every attempt to match phrasing in this project has lost. Assert on tool calls and state |
| Making the model deterministic | It is not. A scenario that passes 4 runs in 5 is a fact to record, not a bug to hide |

## The scenarios

Each row is one driver utterance. **Verdict** is what the app must do, not what it must say.

| # | Driver says | Must happen | Must NOT happen |
| --- | --- | --- | --- |
| S1 | 「把空调调到22度」 | `control_climate{set_temperature,22}`; state shows 22 °C | — |
| S2 | 「有点热」 | a real `control_climate` call lowering temperature | a reply describing a change with no call |
| S3 | 「回家」 (home saved) | `navigate_to`, `nav_saved_place set=true`, one candidate | a POI search |
| S3b | 「回家」 (home not saved) | `ok=false HOME_NOT_SET` | navigation to a guessed place |
| S4 | 「播放音乐」 | `control_music{play}` | — |
| S4b | 「放一首周杰伦的歌」 | `MEDIA_LIBRARY_UNSUPPORTED`, refusal | the bundled track playing |
| S5 | 「导航去珠海站」 then 「第二个」 | candidates, then `choose_navigation_option{index:2}` | a route starting before a pick |
| S6 | 「算了」 with a picker open | `exit_navigation_mode`, picker closed | a claim that it was cancelled with no call |
| S7 | 「今天天气怎么样」 | an explicit refusal | any forecast, any city, any number |
| S8 | 「关闭空调」 | `control_climate{power_off}` | — |
| S9 | 「闭嘴」 | speech stops, listening continues | the session ending |
| S10 | 「休眠」 | `SLEEP`; later speech ignored until woken | acting on speech while asleep |
| S11 | garbled input (Cantonese clip) | either a real tool call, or an honest 「没听清」 | a spoken claim with no call |

S11 is the one that is *allowed* two outcomes, because the input is genuinely ambiguous to the
model. What it may never do is claim.

## Acceptance criteria

| # | Criterion | Kind | Proven by |
| --- | --- | --- | --- |
| A1 | Every scenario is declared as data, not code | structural | the scenario file |
| A2 | The runner reports pass/fail per scenario without human reading | functional | its output |
| A3 | A scenario asserts on tool calls and state, never on reply wording | structural | review + the file |
| A4 | A failing expectation actually fails | negative control | run one with a deliberately wrong expectation |
| A5 | The suite runs end to end on `2391ff70` | device | a recorded run |
| A6 | Flaky scenarios are reported as a rate, not hidden | functional | repeat mode output |

## What the first run found

| Scenario | What happened | Verdict |
| --- | --- | --- |
| S2 「有点热」 | no `control_climate`; the driver was told 「刚才没听清楚」 | **a real defect** |
| S7 「今天天气怎么样」 | an honest refusal, no forecast | the assertion was wrong, not the app |
| S11 (Cantonese) | 「你讲什么呀，我没听清」, `TURN_RELEASE reason=no_claim_made` | the assertion was wrong, not the app |

**S2 is the one that mattered.** `ActionClaimGuard.isControlRequest` is a word list, and 「有点热」
contains none of its words — no 空调, no 温度, no 调. So the turn fell to the unclassified fallback
and the driver was told the app had not understood a sentence `ContextResolver` resolves to a
concrete adjustment. One answer to "can we act on this" now, shared with the resolver.

**S7 and S11 were my assertions asserting a mechanism.** Both demanded that a *correction be sent*,
when the spec's own rule is to assert on tool calls and state. S7's real assertion is its `forbid`
list — there is no source on this car for a forecast, so any weather word is fabricated. S11's is
that the turn machinery reached a decision (`tool=`, `TURN_DROP`, or `no_claim_made`), never that
it reached a particular one.

## Cost, stated plainly

Every scenario is a real Baidu call and spends quota (SPEC-004). The suite is for before a release
and after a change to the turn machinery — not for every commit. The simulation benchmark stays the
one that runs on every build.

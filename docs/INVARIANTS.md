# Invariants

Hard rules. A change that breaks one of these is wrong even if every test passes and the product
appears to work. Each says where it is enforced, so the enforcement can be found and extended.

Most are covered by `behavior-test/ArchitectureRulesTest` or a named unit test. Where a rule is only
enforced by review, it says so — those are the ones to be most careful with.

---

**I-1. Only deterministic execution evidence may establish that an external action occurred.**
The model may *propose* an action; it may not *assert* that one happened. The only proof in this
system is a tool result with `ok=true`, delivered through `BaiduFlexClient.sendFunctionResult`.
A sentence produced by the model is never evidence, and a failed result (`ok=false`) is never proof.

Consequence: when the driver asked for an action, the reply's **audio and subtitle** wait until that
proof exists. In the normal flow the model answers *from* the tool result, so proof already exists
and nothing is delayed. A claim that is never proved is dropped unheard and the model is asked to
actually perform the action.

Never waits for proof, in any phase: the driver's transcript, the tool call, the execution itself,
and error delivery to the model. Only output whose truth depends on execution proof may be held.

*Owner:* `DriverTurn` — one object per driver utterance, holding the phase, the request kind and the
proof. *Enforced by:* `DriverTurnTest` (claim-before-proof, failed execution, cancellation, stale
events), `PhantomTurnSuppressionTest`, `ArchitectureRulesTest.executionProofOwnsActionClaims`.

**I-2. A capability that does not exist must fail deterministically, and say so once.**
No tool means: no execution, one honest sentence, and the same words on screen and in the speaker.
Never a confident intermediate ("正在调整音量") followed by a correction.
*Enforced by:* `ActionClaimGuard.isUnsupportedRequest` + the hold in `BaiduFlexClient`,
`PhantomTurnSuppressionTest.aFalseClaimAboutAnUnsupportedRequestIsNeverSpokenOrShown`.

**I-3. Information the product has no source for is refused, not invented.**
There is no weather, traffic or news tool. Any answer to such a question that does not decline is
fabricated by definition.
*Enforced by:* `ActionClaimGuard.isRealtimeInfoRequest` / `fabricatesRealtimeInfo`,
`PhantomTurnGateTest.anyNonRefusalAnswerToARealtimeQuestionCountsAsFabricated`.

**I-4. Environmental noise must never cause an action.**
Noise may reach the model — that cannot be prevented on an end-to-end stack — but it must not reach
an executor. Every tool call is validated and dispatched on its own merits, never on audio quality.
*Enforced by:* `FlexFunctionCallAssembler` validation, `AndroidToolDispatcher`, and device evidence
recorded in `OPEN_PROBLEMS.md` P20.

**I-5. A correction must happen before the false audio is heard, where it is possible at all.**
Holding a reply is preferred to apologising for it. `PhantomTurnGate` holds audio **and** subtitle
together so the two can never diverge.
*Enforced by:* `PhantomTurnSuppressionTest`, `ArchitectureRulesTest.onlyAudioAndSubtitleMayBeHeld`.

**I-6. The UI must not execute vehicle or navigation actions directly.**
A button and a spoken command reach the same executor by the same route, so a change to how an
action is validated or confirmed cannot silently miss one of them. The screen states intent through
`ScreenControls`; the Activity supplies the same executor instances the tool dispatcher holds.
*Enforced by:* `ArchitectureRulesTest.uiDoesNotReachIntoExecution`, whose exception set has been
**empty** since 2026-09-19 (D-3 resolved) — any new offender fails the build.

**I-7. Secrets never enter source, Gradle files, logs or the APK.**
Credentials live in `AndroidKeystoreCredentialStore`; the Amap key arrives through
`local.properties` → manifest placeholder. `local.properties` is git-ignored.
*Enforced by:* `behavior-test/SecretScanTest`.

**I-8. No coordinate, address or transcript is ever logged.**
Diagnostics log durations, distances, counts and decisions — never position or content. This is why
`map_recenter_check` logs `offsetMeters` and `PHANTOM_GATE_DROP` logs `durationMs`.
*Enforced by:* review, plus `ArchitectureRulesTest.locationIsNotLogged` for the obvious shapes.

**I-9. Vendor types stay inside their adapter.**
`com.amap` may be imported by exactly one file. Baidu JSON never escapes the Android voice adapters.
The `ingress` core is provider-neutral.
*Enforced by:* `DependencyBoundaryTest`, `ArchitectureRulesTest.onlyOneFileImportsAmap`.

**I-10. One behaviour, one owner.**
Before adding a mechanism, find the owner in [ARCHITECTURE.md](ARCHITECTURE.md#who-owns-what) and
change it there. A second mechanism that enforces the same policy is a defect, not a safety net.
*Enforced by:* review and [AGENT_MAINTENANCE.md](AGENT_MAINTENANCE.md).

**I-11. A prompt rule is not an enforcement mechanism.**
The persona may *ask* the model to behave; it may never be the only thing preventing a wrong action
or a false claim. Anything safety-relevant must also hold when the model ignores the prompt.
*Enforced by:* review. The existing duplicates are listed in [TECH_DEBT.md](TECH_DEBT.md) D-2.

**I-12. Device evidence is required for anything involving audio, the map or lifecycle.**
A green build proves compilation. See `ACCEPTANCE_TESTS.md` for what each level may claim.
*Enforced by:* review, and by the level vocabulary in `ACCEPTANCE_TESTS.md`.

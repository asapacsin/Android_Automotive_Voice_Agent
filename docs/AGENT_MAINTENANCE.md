# Architecture maintenance review

Run this after roughly 10–20 meaningful commits, or after a batch of feature work. It is not a
feature task. Its bias is **delete → consolidate → simplify → document**, in that order. Adding an
abstraction is the last resort, not the first move.

Budget an hour. Produce one commit (or a short series) and update the documents you contradict.

## 1. Find duplicate mechanisms

```bash
# Two places deciding the same thing? Start with the words policies are written in.
grep -rn "不支持\|isUnsupported\|UNKNOWN_TOOL\|claimsDone\|declines(" app/src/main/kotlin --include=*.kt
# Capability status outside the tool declarations:
grep -rln "supported\|capability" app/src/main/kotlin --include=*.kt
```

For each hit ask: **who owns this** per [ARCHITECTURE.md](ARCHITECTURE.md#who-owns-what)? If two
owners exist, pick one, migrate, delete the other, update the table.

## 2. Find prompt rules that should be code

Read `PersonaProfiles.kt`. Any rule that prevents a wrong action or a false claim must have a
deterministic owner; the prompt may only shape tone and phrasing. A rule with no code behind it is
either a missing check or a rule that does not really exist
([INVARIANTS.md](INVARIANTS.md) I-11).

## 3. Find dead and obsolete code

```bash
# Classes nothing references (the dormant providers are the usual suspects):
for f in $(find app/src/main -name "*.kt"); do n=$(basename $f .kt); \
  c=$(grep -rl "\b$n\b" app/src --include=*.kt | wc -l); [ "$c" -le 1 ] && echo "$c $f"; done
```

A path kept "in case" is a path a fresh agent will mistake for the architecture. Delete it, or add
one line to [ARCHITECTURE.md](ARCHITECTURE.md) saying why it survives.

## 4. Find God classes and growing conditionals

```bash
find app/src/main -name "*.kt" | xargs wc -l | sort -rn | head -10
grep -rc "if (\|when (" app/src/main/kotlin/com/novadrive/app/voice/BaiduFlexClient.kt
```

Anything over ~400 lines, or holding more than three `@Volatile` pieces of per-turn state, is a
candidate for extraction. `ArchitectureRulesTest.noFileGrowsWithoutNotice` fails when a watched file
crosses its recorded budget — raise the budget only with a reason in the commit message.

## 5. Check the mechanical rules still bite

```bash
./gradlew.bat :behavior-test:test --rerun-tasks
```

`DependencyBoundaryTest`, `ArchitectureRulesTest`, `SecretScanTest` and
`FeaturePresenceRegressionTest` are the enforcement. If one now fails for a *deliberate* change,
change the assertion in the same commit and say why — that is the record of the decision.

**They only bite if they run.** These tests read the repository's own source at runtime, so Gradle
cannot see their inputs and used to call the task up to date after changes it did not recognise. On
2026-09-19 that hid a real failure: `AndroidToolDispatcher.kt` grew past its line budget and three
consecutive green runs never re-ran the rule that says so. The task is now pinned with
`outputs.upToDateWhen { false }`. If you add another test that inspects *files* rather than classes,
pin it the same way — a guard that runs only when the build system happens to notice is not a guard.

## 6. Check the documents against reality

- Does [CAPABILITIES.md](CAPABILITIES.md) list exactly the tools in `BaiduFlexProtocol`?
- Does [ARCHITECTURE.md](ARCHITECTURE.md)'s ownership table still name real owners?
- Does [TECH_DEBT.md](TECH_DEBT.md) still describe problems that exist? Delete the fixed ones.
- Does `README.md` describe the real default provider?

## 7. Check tests test contracts

A test that asserts an internal name, a call order, or a log string is testing the implementation.
Prefer: given this input, this observable outcome. The exception is
`FeaturePresenceRegressionTest`, which is deliberately structural — its job is to make a silent
removal loud. Keep that one; be suspicious of new ones like it.

## What not to do

- Do not add a feature. Open an issue instead.
- Do not rewrite a working subsystem for elegance.
- Do not add a layer to "clean up" a duplicate — remove the duplicate.
- Do not leave a mechanism in place "until the replacement is proven". Replace and delete in one
  commit, with tests.

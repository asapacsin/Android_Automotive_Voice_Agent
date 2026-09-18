# /reproduce — see the failure before fixing it

A fix built on a guess is a guess. Reproduce first; the repository has the tools to do it without a
human.

## Steps

1. Restate the reported failure in one sentence: input, what happened, what should have happened.
2. Identify the capability in [config/capabilities.yaml](../config/capabilities.yaml). Note its
   `verified` level — a capability marked `unsupported` behaving as unsupported is not a bug.
3. Check existing coverage before writing anything new:
   - `OPEN_PROBLEMS.md` — has this been measured before? Root cause may already be recorded.
   - `evaluation/.../ScenarioCatalog.kt` — is there a deterministic scenario?
   - the relevant unit tests.
4. Reproduce, cheapest path first:
   - **JVM**, if the logic is pure: a focused test.
   - **Device**, if it involves audio, the map, lifecycle or the model:
     ```powershell
     .\gradlew.bat :app:assembleDebug
     adb install -r <apk>
     .	ools\speech-harness\harness.ps1 -Steps @("launch","start","say:<clip>","stop") -Wait 12
     adb logcat -d -s NovaVoice:D
     ```
     Generate a missing phrase with `tools/speech-harness/make_extra.py`; impulses with
     `make_noise.py`.
5. Collect evidence: the log lines that show the behaviour, not a description of them.
6. Classify and say which: **reproduced** · **not reproduced** · **behaviour changed** ·
   **test outdated**.

## Rule

Do not modify production code before reproducing. If reproduction is genuinely impossible, write
down why in the same message before changing anything.

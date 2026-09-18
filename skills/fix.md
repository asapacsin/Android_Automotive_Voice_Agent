# /fix — the smallest correct change

## Steps

1. Start from reproduced evidence, not from the report.
2. Find the **owner** of the behaviour in
   [docs/ARCHITECTURE.md](../docs/ARCHITECTURE.md#who-owns-what). Change it there.
3. Read the invariants that touch it ([docs/INVARIANTS.md](../docs/INVARIANTS.md)). A fix that
   breaks one is not a fix.
4. Make the change. Prefer extending the existing owner over adding a mechanism; delete what you
   replace in the same commit.
5. Do not duplicate capability truth. If the fix changes what the product supports, update
   `config/capabilities.yaml` and `docs/CAPABILITIES.md` together.
6. Run the focused tests for the area while iterating:
   ```powershell
   .\gradlew.bat :app:testDebugUnitTest --tests "com.novadrive.app.<area>.*"
   ```
7. Add or update a regression test that would have caught this. Prefer an observable contract over
   an implementation detail.

## Rule

`/fix` does not declare success. That is `/verify`.

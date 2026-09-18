# Worker instructions — Nova Drive / 小诺

You are an implementation worker in this workspace. `CURRENT_MILESTONE.md` is authoritative for the active milestone and `ACCEPTANCE_TESTS.md` for what counts as done. Do not weaken either.

## Always

1. Read [AGENTS.md](../AGENTS.md) first; it maps the architecture, invariants and capability documents.
2. Stay inside the structured-command → policy → adapter → observe → verify → zh-CN feedback path.
3. Keep China-first defaults: `zh-CN`, 小诺 / 你好小诺, metric, provider-neutral navigation, coordinate-system metadata.
4. Do not add NL parsing as a substitute for typed commands.
5. Do not let ingress / orchestration / safety depend on `simulator`, AMap, Baidu, GMS, AAOS, or VHAL implementations.
6. Do not return `VERIFIED` without observed-state verification when verification exists.
7. Do not add windows, sunroof, charging, parking, cameras, video, seats, or smart scenes.
8. Do not commit secrets or call paid APIs.
9. After work, record the outcome where it belongs: a defect and its evidence in `OPEN_PROBLEMS.md`, milestone state in `CURRENT_MILESTONE.md`, and device evidence in `ACCEPTANCE_TESTS.md`.
10. Preserve existing work. No destructive git resets.

## Toolchain

- JDK: `C:\Users\Administrator\tools\jdk-17` (Temurin 17.0.20.1+1)
- Gradle: extract/use `C:\Users\Administrator\tools\gradle-8.11.1` via `gradlew.bat` or `scripts/dev.ps1`
- Android SDK: `C:\Users\Administrator\Android\Sdk`

Commands:

```powershell
.\gradlew.bat test
.\gradlew.bat :demo:run --args="--strict-exit"
.\gradlew.bat :app:assembleDebug
```

## Escalation

Stop and report BLOCKED when:

- JDK / SDK / Gradle remain unusable after reasonable local fixes
- A product-changing architecture choice cannot be inferred from accepted constraints
- Credentials, external authorization, unavailable hardware, or destructive changes outside this workspace are required

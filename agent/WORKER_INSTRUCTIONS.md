# Worker instructions — Nova Drive / 小诺

You are an implementation worker in this workspace. `agent/CURRENT_TASK.md` is authoritative for the active checkpoint. Do not weaken or overwrite its acceptance criteria.

## Always

1. Read `agent/CURRENT_TASK.md`, `agent/PROJECT_STATE.md`, and `docs/ARCHITECTURE.md` before editing.
2. Stay inside the structured-command → policy → adapter → observe → verify → zh-CN feedback path.
3. Keep China-first defaults: `zh-CN`, 小诺 / 你好小诺, metric, provider-neutral navigation, coordinate-system metadata.
4. Do not add NL parsing as a substitute for typed commands.
5. Do not let ingress / orchestration / safety depend on `simulator`, AMap, Baidu, GMS, AAOS, or VHAL implementations.
6. Do not return `VERIFIED` without observed-state verification when verification exists.
7. Do not add windows, sunroof, charging, parking, cameras, video, seats, or smart scenes.
8. Do not commit secrets or call paid APIs.
9. After work, update `agent/WORKER_REPORT.md` (every criterion PASS/FAIL/BLOCKED with files + exact commands) and `agent/PROJECT_STATE.md` (truthful milestone, blockers, commands).
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

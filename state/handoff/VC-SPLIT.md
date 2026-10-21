# Handoff VC-SPLIT: Voice checks run as parallel shards of at most ~5 minutes of tests each

- tip: 8f1d58ca30b0 (exec/vc-split), base 7b8dd9ad7db0, contract sha256 fd0ea9dfdb95
- changed (6): .github/workflows/voice-checks.yml, AGENTS.md, config/ci_checks.json, docs/CI.md, scripts/github_voice_checks.sh, scripts/tests/test_ci_tier_b.py
- scope: PASS
- verification: workflow-yaml=0, bash-syntax=0, script-tests=0 (tip 8f1d58ca30b0)
- review: PASS
- merge: ELIGIBLE (not merged)
- next: Dispatch voice-checks shards=all; record per-shard durations; SUITE-SCENARIO-001 3-run count

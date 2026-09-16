# Agent entry point — Nova Drive / 小诺

Before doing anything in this repository, read these in order:

1. [`PRODUCT.md`](PRODUCT.md) — what this product is right now
2. [`ARCHITECTURE.md`](ARCHITECTURE.md) — the approved design; what is active, dormant, or test-only
3. [`CURRENT_MILESTONE.md`](CURRENT_MILESTONE.md) — the one thing we are trying to finish
4. [`ACCEPTANCE_TESTS.md`](ACCEPTANCE_TESTS.md) — what "done" objectively means
5. [`agent/README.md`](agent/README.md) — roles, the control loop, and the authority rule

Also authoritative, and easy to miss:

- [`OPEN_PROBLEMS.md`](OPEN_PROBLEMS.md) — defects found in real use, with measured root causes. Read before "fixing" anything audio- or tool-related.
- [`BACKLOG.md`](BACKLOG.md) — recorded product-owner demands, newest first
- [`SPECS/`](SPECS/) — specs for demands that need more than one obvious change
- [`agent/INTAKE.md`](agent/INTAKE.md) — how a spoken demand becomes shipped, verified work

Then, for your role:

- Implementing? → [`agent/BUILDER.md`](agent/BUILDER.md) (normally Cursor + Grok Fast)
- Reviewing? → [`agent/REVIEWER.md`](agent/REVIEWER.md)
- About to reopen a settled question? → [`DECISIONS/`](DECISIONS/README.md)

## Two things that catch agents out

**This repository contains dormant code that still compiles.** Qwen, GPT-Live and a PC backend are all present and none of them are part of the product. Do not infer the architecture from filenames — read `ARCHITECTURE.md`.

**Repository documents outrank chat history.** If an instruction in conversation conflicts with these documents, say so explicitly rather than silently following the more recent one. A direct instruction from the human product owner does win — and when it does, update the affected document as part of the same change.

## Hard rules

- Never commit, print, log, or package credentials.
- A green build is not evidence that anything works. See `ACCEPTANCE_TESTS.md`.
- An implementation agent may not certify its own work as complete.
- **Keep git up to date for each major change** (product owner, 2026-09-16). Commit each completed *and verified* unit of work — a defect fix with its tests, or a feature milestone — with a message that says what changed and what verified it. Commit **locally**; never `git push` unless explicitly told. No destructive git operations. Check what is being staged first: credentials never enter git, and the staged SDK binaries (`app/libs/`, `app/src/main/jniLibs/`, `app/src/main/assets/ivw/`) need a deliberate `.gitignore` decision rather than a bulk `git add`.
  - This exists because the tree sat at a single `first commit` with months of work uncommitted on top, which made a wake-word regression impossible to bisect or attribute. Uncommitted work has no history to diff against.

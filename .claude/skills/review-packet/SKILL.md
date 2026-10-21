---
name: review-packet
description: Review an executor branch and merge it safely in Nova Drive - print the packet with scripts/review_packet.py, rerun acceptance, decide reviewer vs WAIVED, pick the SPEC rows, record state/reviews.log, then merge. Use whenever an executor returns DONE or finishes a task, before any `git merge exec/*`, when sending the reviewer, recording or waiving a review, or when a merge is refused for a missing review record.
---

Read and follow [`skills/review-packet.md`](../../../skills/review-packet.md) from the repository
root. That file is the single source of this procedure, shared with Codex and Cursor; this entry
only makes it discoverable to Claude Code. Do not copy its steps here.

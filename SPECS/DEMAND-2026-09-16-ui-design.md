# DEMAND — Assistant-on-map UI design (mentor reference layout)

Received: 2026-09-16 from the product owner, verbatim. Instruction given with it: "well this is the ui design update your state."
Source file: `d:\桌面\普強\ui.md`
Recorded as: [B-007](../BACKLOG.md) → [SPEC-006](SPEC-006-assistant-ui.md)
Relates to: [DEMAND v2](DEMAND-2026-09-16-embedded-amap-v2.md) §5, §6, §25, §26, §42 · [SPEC-005-P1-design](SPEC-005-P1-design.md) decision **D2**

> This file is the **unedited source**. Where it and a spec disagree about *what was asked*, this file wins. Where they disagree about *what to build*, the spec wins, because the spec has named the conflicts.

---

## Layout

```text
┌────────────────────────────────────────────────────────────────────────────┐
│                                                                            │
│  ╭──────╮  ╭──────────────────────────────────────────────╮               │
│  │Avatar│  │ 那你放一下我听一下                            │               │
│  ╰──────╯  ╰──────────────────────────────────────────────╯               │
│     ● LISTENING                                                            │
│                                                                            │
│                                                                            │
│                           EMBEDDED AMAP                                    │
│                                                                            │
│                                  ↑                                         │
│                            ━━━━━━│━━━━━━                                   │
│                                  🚙                                        │
│                                                                            │
│                 ╭──────────────────────────────────╮                       │
│                 │ ✓ 已恢复播放                     │                       │
│                 │ 现在为您播放《夜曲》              │                       │
│                 ╰──────────────────────────────────╯                       │
│                                                                            │
│                                                                            │
├────────────────────────────────────────────────────────────────────────────┤
│  🎵 音乐        ◀        ❚❚        ▶            30°C              [ 📷 ]  │
└────────────────────────────────────────────────────────────────────────────┘
```

## UI description

Embedded Amap: occupies the full main screen and remains the primary interface during navigation.

Avatar + status: shows the AI assistant and its current state, such as IDLE, LISTENING, PROCESSING, or SPEAKING.

Speech bubble: displays the assistant's current response without obscuring important navigation information.

Action feedback card: temporarily confirms operations such as music playback, AC changes, or other vehicle actions, then disappears.

Bottom bar: retains the compact media/climate controls from the reference design.

📷 Bottom-right camera button: opens the device's front-facing camera and displays a live view of the person standing in front of the device. Closing the camera view returns to the Amap screen without ending the assistant session.

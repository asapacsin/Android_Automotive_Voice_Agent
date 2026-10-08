# SPEC-017 — Play music from a vague description, through the driver's music app

Status: **Draft 2026-09-30**
Raised: 2026-09-30 · Source: owner request (「梶浦的、空之境界里很燃的那首 OP」 → the AI picks and
plays it); source option 3 (hand-off to the installed music app) chosen by the planner under the
owner's delegation, after the owner accepted the mechanism
Depends on: I-1, I-2, I-8, I-11; SPEC-016 Part B (built as the `media` domain); ADR-015

> **Reaching Done on this SPEC does not end the run.** Reconcile the registry, the debt list and
> the backlog row, then run `python scripts/discover_work.py` and take the next item. Handing
> control back because a SPEC finished is forbidden by
> [CONSTITUTION.md](../harness/CONSTITUTION.md) rule 12.

## Goal

The driver describes music loosely — a composer, a show, a mood — and 小诺 works out a concrete
song, has the driver's own music app play it, and says only what is **actually** playing.

## Architecture

- **The model identifies the song.** `play_music` takes structured fields the model fills from the
  description: `title`, `artist`, `album_or_work`, `mood`, `query` (free text, the fallback). The
  model's guess is never evidence.
- **`MusicSource` port** (media domain), two implementations:
  - `BundledTrackSource` — today's single track; serves only a generic 「放首歌」.
  - `MediaAppHandoffSource` — sends Android's `MEDIA_PLAY_FROM_SEARCH` (`SearchManager.QUERY`,
    `EXTRA_MEDIA_FOCUS`, `EXTRA_MEDIA_TITLE/ARTIST/ALBUM`) to the first installed app from a
    preference list: 网易云音乐 `com.netease.cloudmusic`, QQ音乐 `com.tencent.qqmusic`, VLC
    `org.videolan.vlc`, then any app that resolves the intent.
- **`NowPlayingObserver`** reads the active `MediaSession` (title, artist, state) through
  `MediaSessionManager.getActiveSessions`, which needs notification-listener access granted once
  by the driver. This is the executor's evidence.
- **Verification rule.** After the hand-off, wait up to 6 s for a session in `STATE_PLAYING`. The
  result is:
  - `playing` with `now_playing{title, artist}` and `matches_request` (normalised title or artist
    overlap with what was asked);
  - `requested_unverified` when there is no listener access — the reply may say only
    「已经让音乐 app 去找了」, never 「正在放 X」;
  - `ok=false` `NOT_PLAYING` (nothing started: VIP-only, region-blocked, not found), `NO_MUSIC_APP`,
    `HANDOFF_REJECTED`.
- `control_music{stop}` pauses the active media session (any app), or the bundled track.
- The existing refusal (`MEDIA_LIBRARY_UNSUPPORTED` for a named song on `control_music{play}`)
  remains for the bundled path; a named request is routed to `play_music` by its description.
  `unsupported.media_library` becomes `media.play_by_description` (supported) in all three
  capability places, with `TRUTH-*` rows updated.

## Behaviour

- **B1.** 「放点梶浦由记的，空之境界里很燃的那首」 → `play_music{artist:"梶浦由記", album_or_work:"空の境界", mood:"epic", query:"…"}` → hand-off → readback → 「在放 Kalafina 的《…》」 from `now_playing`.
- **B2.** When `matches_request` is false, 小诺 says what is playing and asks if it is right; it
  never claims it is the requested song.
- **B3.** 「不是这首」 → `play_music` again with the rejected title excluded (`exclude_title`).
- **B4.** A very vague request (「放点好听的」) plays the bundled track or hands off `query` only; no
  clarifying question unless the model has nothing to search for.
- **B5.** The music app coming to the foreground is brought back: the app re-shows its own activity
  after the hand-off (the map stays the driver's screen).

## Non-goals

Streaming APIs or accounts of our own; downloading music; a local tagged library (possible later
as another `MusicSource`); lyrics; volume (still unsupported).

## Failure behaviour

No app → `NO_MUSIC_APP` 「手机上没有能播放的音乐 app」. App opened but nothing plays →
`NOT_PLAYING` 「这首放不了，可能需要会员或者有版权限制」. No listener access →
`requested_unverified` and a one-time hint on screen with the settings path. Duplicate →
`DUPLICATE_IN_TURN` (repeat-sensitive).

## Observability

`music_handoff pkg=<package> fields=<which fields set> result=<code> waited_ms=<n>` — no titles,
artists or queries in the log (a query can name a person; treat as transcript, I-8).

## Acceptance criteria

| # | Criterion | Kind | Proven by | State |
| --- | --- | --- | --- | --- |
| A1 | Intent built correctly per field set; package preference order | functional | `MediaSearchSpecsTest`, `MediaAppPreferenceTest` (intent spec as data) | built, unit tests pass |
| A2 | Readback rules: playing / unverified / not playing / mismatch; off the event loop, cancellable | functional | `NowPlayingVerifierTest`, `MediaServerPlayMusicTest` | built, unit tests pass |
| A3 | Result JSON and `announce` never name a song that is not in `now_playing`; an unconfirmed hand-off never releases 「正在放…」 | negative | `MediaServerPlayMusicTest`, `FalseCapabilityClaimTest` | built, unit tests pass |
| A4 | Capability flip in all three places | contract | `CapabilityContractTest` | built, passes |
| A5 | Model maps descriptions to fields (≥ 8/10 on a description set) | TEXT_LIVE | `MUSIC-TEXT-LIVE-001` | not earned |
| A6 | VLC on the emulator: hand-off plays a local file, readback names it | device (emulator) | `MUSIC-VLC-EMU-001` (autonomous) | not earned |
| A7 | 网易云/QQ音乐: plays or honestly fails; split-tunnel note verified | device | `MUSIC-APP-DEVICE-001` (HUMAN) | not earned |
| A8 | APK builds with the listener service declared | artifact | `:app:assembleDebug` | built |

## Open product decisions

None blocking. Default preference order above; the owner can reorder it in developer settings.

## Implementation status

| Area | State | Proof |
| --- | --- | --- |
| Hand-off + readback (`AndroidMediaHandoff`, `AndroidNowPlayingSource`, `NowPlayingVerifier`) | built; never run on a device | unit tests (A1, A2) |
| `play_music` tool + capability flip | built; never run on a device | `MediaServerPlayMusicTest`, `MediaDomainPlayMusicTest`, `FalseCapabilityClaimTest`, `CapabilityContractTest` |
| `control_music{stop}` pauses the app's session; `music_stopped` only on readback | built; never run on a device | `MediaServerPlayMusicTest` |
| On-screen one-time listener hint with the settings path | **not built** — only a `hint` field in the first `requested_unverified` result | — |
| `MusicSource` port / `BundledTrackSource` split | not built — the bundled track stays behind `control_music` | — |

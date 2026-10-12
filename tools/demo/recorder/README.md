# Demo recorder (PC emulator only)

These scripts record a demo on the PC emulator `nova_api34` with nothing played on the PC, and grade each take against [docs/DEMO_REQUIREMENTS.md](../../../docs/DEMO_REQUIREMENTS.md). They need adb, ffmpeg, Python with numpy and edge-tts, and the PC proxy at 127.0.0.1:7897. The cloud container cannot run them.

1. **Start the emulator silent:** `emulator -avd nova_api34 -no-snapshot-save -no-audio`. Then run `adb root`, set every media stream's volume to 0, run `settings put global http_proxy 10.0.2.2:7897`, and `adb emu geo fix 113.5767 22.2711`.
   **Qwen key:** with `DASHSCOPE_API_KEY` and `DASHSCOPE_WORKSPACE_ID` set on the PC, run `python seed_qwen.py --accept-consent` once per install. It stores both in the debug app through a file that is deleted afterwards, records the cross-border consent, and prints only `qwen_setup applied` or an error code.
2. **Make the driver lines:** `python make_clips.py` writes edge-tts Yunxi clips into `clips/` (16 kHz PCM; test input only).
3. **Preflight, before the first take:** `python record_demo.py --preflight [scene…]` (default: the commute scenes). It opens no voice session, so it spends no quota. It checks:
   - the scenes and their clips;
   - the emulator and the app;
   - the app's Qwen config code and Amap Web key;
   - the GPS fix;
   - the app's own search for 横琴镇 (the first result must be at least 1.7 km away, so the drive outlasts the middle takes; OPEN_PROBLEMS P51);
   - how long that search took (`preflight_search ... ms=`): at most 0.5 s, or c_nav misses its 3.5 s budget. If slower, route `restapi.amap.com` direct, not through the PC proxy (Clash rule `DOMAIN-SUFFIX,amap.com,DIRECT`), and preflight again. The same search runs a second time and logs `preflight_search_warm ms=` for comparison (OPEN_PROBLEMS P53);
   - a Navi SDK route to it.

   It prints PASS or each problem. `record_demo.py` also refuses to start a take with an unknown scene or a missing clip. `python record_demo.py --selftest` checks the grading offline.
4. **Record one take per scene:** `python record_demo.py RUN scene…`. The scenes are `intro`, `ability2`, `scenario`, `style`, `prep`, `warm`, `noise` and `error`. The commute demo (`commute_board` … `commute_arrive`, 横琴创业谷 → 横琴镇) has its own run order with `KEEP=1`: see [docs/DEMO_COMMUTE.md](../../../docs/DEMO_COMMUTE.md).
   - The script stands in for the host audio bridge: it sends the driver lines up the bridge and captures her reply audio from the app.
   - Keep each take under about 70 s. In longer takes the bridge starts dropping uplink audio, and the extra driver-speech hold also hurts timings.
5. **Grade the take:** `python check_req.py RUN…`. It measures from the driver's last *audible* word, because edge-tts pads about 1 s of silence. It reports her start time per turn and, for a Qwen Maia session (`session_provider choice=qwen`), `reply_underrun`; for the app-voiced path, `assistant_voice_gap_ms` and Azure first audio. A failed take is re-recorded or reported; it is never hidden in the edit.
6. **Compose the take:** `NO_LAT=1 python compose.py RUN RUN.mp4`. This puts the phone on the left and the captions and the app's real tool results on the right. Set `OFFSET=-0.48` for a take with no wake broadcast.
7. **Cut the final video:** `python final_cut.py OUT.mp4`, after editing its `SCENES` list.
   - It shortens dead air after her reply.
   - It never shortens the wait between the driver's line and her reply.
   - The ⏩ jumps are labelled on screen.

The run folders hold `logcat.txt` with `transcript=` lines. Never commit or publish them; `.gitignore` excludes them.

**Quota (AGENTS.md hard rule):** every live Qwen session spends the owner's limited free quota. Prove what you can offline first; run live only with the owner's go for that run, with `NOVA_SPEND_QWEN_QUOTA=yes` set. Without it this tool refuses to open a Qwen session.

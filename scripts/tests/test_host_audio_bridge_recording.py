"""The demo recorder must keep its later scenes after a reply-onset gate failure."""
import contextlib
import io
import os
import sys
import tempfile
import threading
import unittest
from unittest.mock import patch

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
BRIDGE_DIR = os.path.join(ROOT, "tools", "speech-harness")
if BRIDGE_DIR not in sys.path:
    sys.path.insert(0, BRIDGE_DIR)
import host_audio_bridge as bridge  # noqa: E402


class DemoRecordingTest(unittest.TestCase):
    def test_intermediate_reply_timeout_does_not_truncate_later_scenes(self):
        with tempfile.TemporaryDirectory() as tmp:
            first = os.path.join(tmp, "first.pcm")
            second = os.path.join(tmp, "second.pcm")
            for path, value in ((first, 1), (second, 2)):
                with open(path, "wb") as out:
                    out.write(bytes([value]) * bridge.CHUNK)

            clock = [0.0]

            def tick():
                clock[0] += 1.0
                return clock[0]

            stop = threading.Event()
            sent_frames = []

            def drive_frames(_uplink, next_frame, signal):
                for _ in range(100):
                    sent_frames.append(next_frame())
                    if signal.is_set():
                        break

            bridge.TURN.update(line_end=None, first_reply=None)
            bridge.SESSION.update(ready=True, watch=False)
            bridge.UPLINK["closed"] = False
            bridge.INFLIGHT["since"] = None
            bridge.LAST_REPLY[0] = -100.0
            log = io.StringIO()
            old_t0, old_driver = bridge.RECORDER.t0, bridge.RECORDER.driver
            try:
                with patch.object(bridge.time, "time", side_effect=tick), \
                     patch.object(bridge, "quiet_reason", return_value=None), \
                     patch.object(bridge, "paced", side_effect=drive_frames), \
                     contextlib.redirect_stdout(log):
                    bridge.feed_file_waiting(
                        object(), [first, second], stop, lead=0, gap=0, cap=0,
                        finish_after_reply=True,
                    )
            finally:
                bridge.TURN.update(line_end=None, first_reply=None)
                bridge.SESSION.update(ready=False, watch=False)
                bridge.RECORDER.t0, bridge.RECORDER.driver = old_t0, old_driver

            self.assertIn("reply_timeout line=1", log.getvalue())
            self.assertTrue(any(frame.startswith(bytes([2]) * bridge.CHUNK) for frame in sent_frames))


if __name__ == "__main__":
    unittest.main()

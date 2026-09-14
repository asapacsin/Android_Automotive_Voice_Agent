import os

import pytest

from app.voice.capability import BLOCKED_CODE, function_calling_capability


def test_live_baidu_tests_are_opt_in():
    assert os.getenv("RUN_BAIDU_LIVE_TESTS", "false").lower() != "true"


def test_function_calling_capability_is_blocked():
    assert function_calling_capability()["status"] == BLOCKED_CODE


@pytest.mark.skipif(
    os.getenv("RUN_BAIDU_LIVE_TESTS", "false").lower() != "true",
    reason="live Baidu tests are opt-in and consume quota",
)
def test_live_baidu_connection_opt_in_placeholder():
    pytest.fail("live tests must be implemented only with local credentials; this worker does not call paid APIs")

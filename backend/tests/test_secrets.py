from __future__ import annotations

import logging

from app.logging_safe import RedactingFilter, configured_flag, redact_text


def test_logger_does_not_emit_secret_assignment(caplog) -> None:
    logger = logging.getLogger("novadrive.secret-test")
    logger.handlers.clear()
    logger.addFilter(RedactingFilter(["super-secret-value"]))
    logger.setLevel(logging.INFO)
    with caplog.at_level(logging.INFO, logger="novadrive.secret-test"):
        logger.info("BAIDU_API_KEY=super-secret-value access_token=super-secret-value")
        logger.info(configured_flag("BAIDU_API_KEY", True))
    text = caplog.text
    assert "super-secret-value" not in text
    assert "BAIDU_API_KEY configured: yes" in text
    assert "BAIDU_API_KEY=actual" not in text
    assert redact_text("wss://host/ws?access_token=super-secret-value") == "wss://host/ws?access_token=***"

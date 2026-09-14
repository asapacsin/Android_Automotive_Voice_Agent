from __future__ import annotations

from app.voice.models import DomainEvent, DomainEventType, VoiceUiState


class VoiceSessionStateMachine:
    """Mirrors the Kotlin machine so backend tests pin the same transitions."""

    def __init__(self) -> None:
        self.state = VoiceUiState.DISCONNECTED
        self.streaming_audio = False
        self.last_error_code: str | None = None

    def user_start(self) -> None:
        if self.state in {VoiceUiState.DISCONNECTED, VoiceUiState.ERROR}:
            self.state = VoiceUiState.CONNECTING
            self.streaming_audio = False
            self.last_error_code = None

    def on_session_ready(self) -> None:
        if self.state == VoiceUiState.CONNECTING:
            self.state = VoiceUiState.LISTENING
            self.streaming_audio = True

    def on_speech_stopped(self) -> None:
        if self.state == VoiceUiState.LISTENING:
            self.state = VoiceUiState.THINKING
            self.streaming_audio = True

    def on_audio_delta(self) -> None:
        if self.state in {VoiceUiState.THINKING, VoiceUiState.SPEAKING, VoiceUiState.LISTENING}:
            self.state = VoiceUiState.SPEAKING
            self.streaming_audio = True

    def on_response_completed(self) -> None:
        if self.state in {VoiceUiState.SPEAKING, VoiceUiState.THINKING}:
            self.state = VoiceUiState.LISTENING
            self.streaming_audio = True

    def on_interrupted(self) -> None:
        if self.state in {VoiceUiState.SPEAKING, VoiceUiState.THINKING}:
            self.state = VoiceUiState.LISTENING
            self.streaming_audio = True

    def on_error(self, code: str) -> None:
        self.state = VoiceUiState.ERROR
        self.last_error_code = code
        self.streaming_audio = False

    def user_stop(self) -> None:
        self.state = VoiceUiState.DISCONNECTED
        self.streaming_audio = False
        self.last_error_code = None


def test_idle_does_not_stream() -> None:
    machine = VoiceSessionStateMachine()
    assert machine.state == VoiceUiState.DISCONNECTED
    assert machine.streaming_audio is False


def test_connect_listen_think_speak_listen() -> None:
    machine = VoiceSessionStateMachine()
    machine.user_start()
    assert machine.state == VoiceUiState.CONNECTING
    assert machine.streaming_audio is False
    machine.on_session_ready()
    assert machine.state == VoiceUiState.LISTENING
    assert machine.streaming_audio is True
    machine.on_speech_stopped()
    assert machine.state == VoiceUiState.THINKING
    machine.on_audio_delta()
    assert machine.state == VoiceUiState.SPEAKING
    machine.on_response_completed()
    assert machine.state == VoiceUiState.LISTENING
    assert machine.streaming_audio is True


def test_barge_in_returns_to_listening() -> None:
    machine = VoiceSessionStateMachine()
    machine.user_start()
    machine.on_session_ready()
    machine.on_audio_delta()
    assert machine.state == VoiceUiState.SPEAKING
    machine.on_interrupted()
    assert machine.state == VoiceUiState.LISTENING
    assert machine.streaming_audio is True


def test_error_stops_streaming_without_secret() -> None:
    machine = VoiceSessionStateMachine()
    machine.user_start()
    machine.on_error("BAIDU_AUTH_FAILED")
    assert machine.state == VoiceUiState.ERROR
    assert machine.last_error_code == "BAIDU_AUTH_FAILED"
    assert machine.streaming_audio is False
    machine.user_stop()
    assert machine.state == VoiceUiState.DISCONNECTED


def test_domain_event_round_trip() -> None:
    event = DomainEvent(DomainEventType.ERROR, {"code": "BAIDU_AUTH_FAILED", "message": "auth rejected"})
    payload = event.to_client_json()
    assert payload["type"] == "error"
    assert "Secret" not in payload["message"]

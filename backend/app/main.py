from __future__ import annotations

from fastapi import FastAPI

from app.config import load_settings
from app.logging_safe import install_redacting_logging
from app.voice.routes import router

settings = load_settings()
install_redacting_logging(settings.secret_values())

app = FastAPI(title="Nova Drive Voice Backend", version="0.3.1")
app.include_router(router)

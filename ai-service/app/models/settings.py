"""Runtime configuration from environment variables only (no secrets in code)."""
from __future__ import annotations

import os
from dataclasses import dataclass


@dataclass(frozen=True)
class Settings:
    # TEMPLATE_OCR: OpenCV pipeline validated only on synthetic frames. SIMULATOR: echoes a declared plate.
    provider: str = "TEMPLATE_OCR"
    service_token: str = ""
    max_upload_bytes: int = 5 * 1024 * 1024
    max_pixels: int = 4096 * 4096
    simulator_enabled: bool = True

    @staticmethod
    def from_env() -> "Settings":
        return Settings(
            provider=os.getenv("ALPR_PROVIDER", "TEMPLATE_OCR").upper(),
            service_token=os.getenv("ALPR_SERVICE_TOKEN", ""),
            max_upload_bytes=int(os.getenv("ALPR_MAX_UPLOAD_BYTES", str(5 * 1024 * 1024))),
            simulator_enabled=os.getenv("ALPR_SIMULATOR_ENABLED", "true").lower() == "true",
        )

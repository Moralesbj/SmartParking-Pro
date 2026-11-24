"""Explicit simulator: echoes a declared plate. Exists only for demos without cameras; always labelled SIMULATOR."""
from __future__ import annotations

import numpy as np

from app.adapters.base import RawRecognition
from app.services import plates


class SimulatorProvider:
    name = "SIMULATOR"

    def recognize(self, image: np.ndarray, hint: str | None = None) -> RawRecognition:
        plate = plates.normalize(hint or "")
        if not plates.is_valid(plate):
            return RawRecognition(plate=None, confidence=0.0, warnings=["SIMULATOR_WITHOUT_VALID_HINT"])
        return RawRecognition(plate=plate, confidence=0.99, plate_found=True, warnings=["SIMULATED_RESULT"])

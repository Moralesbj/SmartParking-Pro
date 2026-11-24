"""PlateRecognitionProvider port. Results are suggestions; the Java backend decides."""
from __future__ import annotations

from dataclasses import dataclass, field
from typing import Protocol

import numpy as np


@dataclass(frozen=True)
class CharacterRead:
    char: str
    score: float
    alternative: str | None = None
    margin: float = 1.0


@dataclass(frozen=True)
class RawRecognition:
    plate: str | None
    confidence: float
    characters: list[CharacterRead] = field(default_factory=list)
    plate_found: bool = False
    warnings: list[str] = field(default_factory=list)


class PlateRecognitionProvider(Protocol):
    name: str

    def recognize(self, image: np.ndarray, hint: str | None = None) -> RawRecognition: ...

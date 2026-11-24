from __future__ import annotations

from pydantic import BaseModel, Field


class CharacterOut(BaseModel):
    char: str
    score: float
    alternative: str | None = None
    margin: float


class RecognitionOut(BaseModel):
    """A suggestion only. `requires_review` is advisory; the Java backend applies the binding rules."""
    provider: str
    simulated: bool
    plate: str | None
    confidence: float = Field(ge=0, le=1)
    plate_found: bool
    requires_review: bool
    characters: list[CharacterOut] = []
    warnings: list[str] = []
    notice: str = "Resultado sugerido; la decisión de acceso la toma el sistema central o un operador"


class SimulateIn(BaseModel):
    plate: str = Field(min_length=5, max_length=10)
    noise: float = Field(default=0, ge=0, le=120)
    blur: int = Field(default=0, ge=0, le=6)
    angle: float = Field(default=0, ge=-15, le=15)
    occlude: bool = False

"""Colombian plate normalization. Mirrors com.smartparking.modules.vehicles.domain.Plate (Java is the authority)."""
from __future__ import annotations

import re

CAR = re.compile(r"^[A-Z]{3}[0-9]{3}$")
MOTORCYCLE = re.compile(r"^[A-Z]{3}[0-9]{2}[A-Z]$")

# Visually confusable pairs used to flag ambiguous reads.
CONFUSABLE = {"0": "O", "O": "0", "1": "I", "I": "1", "8": "B", "B": "8", "5": "S", "S": "5", "2": "Z", "Z": "2", "D": "0"}


def normalize(raw: str) -> str:
    return re.sub(r"[\s\-]", "", raw or "").upper()


def is_valid(plate: str) -> bool:
    return bool(CAR.match(plate) or MOTORCYCLE.match(plate))


def allowed_classes(position: int) -> str:
    """Character class allowed at each position of a Colombian plate: L=letter, D=digit, A=either."""
    return "LLLDDA"[position] if position < 6 else ""

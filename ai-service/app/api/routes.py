from __future__ import annotations

import hmac

import cv2
import numpy as np
from fastapi import APIRouter, Depends, File, Form, Header, HTTPException, Request, UploadFile

from app.adapters.base import PlateRecognitionProvider, RawRecognition
from app.schemas.recognition import CharacterOut, RecognitionOut, SimulateIn
from app.services import plates, synthetic

REVIEW_THRESHOLD = 0.90
ALLOWED_TYPES = {"image/jpeg", "image/png"}

router = APIRouter()


def require_token(request: Request, x_service_token: str | None = Header(default=None)) -> None:
    expected = request.app.state.settings.service_token
    if expected and not (x_service_token and hmac.compare_digest(expected, x_service_token)):
        raise HTTPException(status_code=401, detail="Token de servicio inválido")


def _out(provider: PlateRecognitionProvider, raw: RawRecognition, simulated: bool) -> RecognitionOut:
    ambiguous = "AMBIGUOUS_CHARACTERS" in raw.warnings
    return RecognitionOut(
        provider=provider.name, simulated=simulated or provider.name == "SIMULATOR", plate=raw.plate,
        confidence=raw.confidence, plate_found=raw.plate_found,
        requires_review=raw.plate is None or ambiguous or raw.confidence < REVIEW_THRESHOLD,
        characters=[CharacterOut(char=c.char, score=c.score, alternative=c.alternative, margin=c.margin) for c in raw.characters],
        warnings=raw.warnings,
    )


def _decode(data: bytes, max_pixels: int) -> np.ndarray:
    # Magic-number check instead of trusting the declared content type.
    if not (data.startswith(b"\xff\xd8\xff") or data.startswith(b"\x89PNG\r\n\x1a\n")):
        raise HTTPException(status_code=422, detail="Formato no soportado; use JPEG o PNG")
    image = cv2.imdecode(np.frombuffer(data, dtype=np.uint8), cv2.IMREAD_COLOR)
    if image is None:
        raise HTTPException(status_code=422, detail="Imagen corrupta")
    if image.shape[0] * image.shape[1] > max_pixels:
        raise HTTPException(status_code=413, detail="Imagen demasiado grande")
    return image


@router.get("/health")
def health(request: Request) -> dict:
    return {"status": "UP", "provider": request.app.state.provider.name}


@router.post("/v1/recognitions", response_model=RecognitionOut, dependencies=[Depends(require_token)])
async def recognize(request: Request, image: UploadFile = File(...), hint: str | None = Form(default=None)) -> RecognitionOut:
    settings = request.app.state.settings
    if image.content_type not in ALLOWED_TYPES:
        raise HTTPException(status_code=422, detail="Formato no soportado; use JPEG o PNG")
    data = await image.read(settings.max_upload_bytes + 1)
    if len(data) > settings.max_upload_bytes:
        raise HTTPException(status_code=413, detail="Archivo demasiado grande")
    provider = request.app.state.provider
    raw = provider.recognize(_decode(data, settings.max_pixels), hint=plates.normalize(hint) if hint else None)
    # Images are processed in memory and never persisted by this service.
    return _out(provider, raw, simulated=False)


@router.post("/v1/simulator/frames", response_model=RecognitionOut, dependencies=[Depends(require_token)])
def simulate(request: Request, body: SimulateIn) -> RecognitionOut:
    """Simulated camera: renders a synthetic frame and runs the real pipeline on it. Always flagged simulated."""
    if not request.app.state.settings.simulator_enabled:
        raise HTTPException(status_code=404, detail="Simulador deshabilitado")
    plate = plates.normalize(body.plate)
    if not plates.is_valid(plate):
        raise HTTPException(status_code=422, detail="Placa colombiana inválida")
    frame = synthetic.render_frame(plate, noise=body.noise, blur=body.blur, angle=body.angle)
    if body.occlude:
        cv2.rectangle(frame, (330, 170), (420, 320), (90, 90, 90), -1)
    provider = request.app.state.provider
    return _out(provider, provider.recognize(frame, hint=plate), simulated=True)

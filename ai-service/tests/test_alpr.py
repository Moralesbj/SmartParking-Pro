"""Synthetic images only (generated in-process); no real people or vehicles are used in tests."""
from __future__ import annotations

import numpy as np
import pytest
from fastapi.testclient import TestClient

from app.adapters.template_ocr import TemplateOcrProvider
from app.main import create_app
from app.models.settings import Settings
from app.services import plates, synthetic

TOKEN = "token-de-prueba"


@pytest.fixture(scope="module")
def ocr() -> TemplateOcrProvider:
    return TemplateOcrProvider()


@pytest.fixture()
def client() -> TestClient:
    return TestClient(create_app(Settings(service_token=TOKEN, max_upload_bytes=200_000)))


def upload(client: TestClient, data: bytes, ctype: str = "image/jpeg", token: str | None = TOKEN):
    headers = {"X-Service-Token": token} if token else {}
    return client.post("/v1/recognitions", files={"image": ("frame.jpg", data, ctype)}, headers=headers)


@pytest.mark.parametrize("plate", ["ABC123", "XYZ12D", "BOS808", "KLM450", "QWE789"])
def test_reads_clean_synthetic_plates(ocr, plate):
    raw = ocr.recognize(synthetic.render_frame(plate))
    assert raw.plate == plate
    assert raw.confidence >= 0.95
    assert raw.plate_found


@pytest.mark.parametrize("kw", [{"noise": 40}, {"blur": 3}, {"angle": 6}, {"angle": -6}, {"noise": 60, "blur": 2}])
def test_degraded_frames_still_read(ocr, kw):
    raw = ocr.recognize(synthetic.render_frame("PQR246", **kw))
    assert raw.plate == "PQR246"


def test_frame_without_plate_returns_nothing(ocr):
    raw = ocr.recognize(np.full((300, 400, 3), 90, dtype=np.uint8))
    assert raw.plate is None and raw.confidence == 0.0 and not raw.plate_found


def test_contract_and_review_flag(client):
    res = upload(client, synthetic.encode_jpeg(synthetic.render_frame("ABC123")))
    assert res.status_code == 200
    body = res.json()
    assert set(body) >= {"provider", "simulated", "plate", "confidence", "plate_found", "requires_review", "characters", "warnings", "notice"}
    assert body["provider"] == "TEMPLATE_OCR" and body["simulated"] is False
    assert body["plate"] == "ABC123" and body["requires_review"] is False
    assert len(body["characters"]) == 6


def test_occluded_plate_is_ambiguous_and_requires_review(client):
    res = client.post("/v1/simulator/frames", json={"plate": "ABC123", "occlude": True}, headers={"X-Service-Token": TOKEN})
    body = res.json()
    assert res.status_code == 200 and body["simulated"] is True
    assert body["requires_review"] is True
    assert body["plate"] != "ABC123" or body["confidence"] < 0.9


def test_simulated_camera_runs_real_pipeline(client):
    body = client.post("/v1/simulator/frames", json={"plate": "xyz-12d", "noise": 20}, headers={"X-Service-Token": TOKEN}).json()
    assert body["plate"] == "XYZ12D" and body["simulated"] is True


def test_token_is_required(client):
    assert upload(client, synthetic.encode_jpeg(synthetic.render_frame("ABC123")), token=None).status_code == 401
    assert upload(client, synthetic.encode_jpeg(synthetic.render_frame("ABC123")), token="otro").status_code == 401


def test_rejects_non_images_and_spoofed_content_type(client):
    assert upload(client, b"%PDF-1.4 no es imagen", ctype="application/pdf").status_code == 422
    assert upload(client, b"%PDF-1.4 disfrazado", ctype="image/jpeg").status_code == 422
    assert upload(client, b"\xff\xd8\xff" + b"basura", ctype="image/jpeg").status_code == 422


def test_rejects_oversized_upload(client):
    big = b"\xff\xd8\xff" + b"0" * 250_000
    assert upload(client, big).status_code == 413


def test_invalid_simulated_plate_rejected(client):
    assert client.post("/v1/simulator/frames", json={"plate": "12345A"}, headers={"X-Service-Token": TOKEN}).status_code == 422


def test_simulator_provider_is_labelled():
    app_client = TestClient(create_app(Settings(provider="SIMULATOR")))
    res = app_client.post("/v1/recognitions", files={"image": ("f.png", synthetic.encode_jpeg(synthetic.render_frame("ABC123")), "image/png")}, data={"hint": "abc123"})
    body = res.json()
    assert body["provider"] == "SIMULATOR" and body["simulated"] is True and "SIMULATED_RESULT" in body["warnings"]


def test_health(client):
    assert client.get("/health").json() == {"status": "UP", "provider": "TEMPLATE_OCR"}


@pytest.mark.parametrize("raw,ok", [("abc-123", True), ("XYZ12D", True), ("AB123", False), ("123ABC", False)])
def test_plate_rules_match_java(raw, ok):
    assert plates.is_valid(plates.normalize(raw)) is ok

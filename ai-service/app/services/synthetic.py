"""Synthetic frames for tests and the explicit camera simulator. Never presented as real camera footage."""
from __future__ import annotations

import cv2
import numpy as np

FONT = cv2.FONT_HERSHEY_SIMPLEX
PLATE_W, PLATE_H = 520, 170


def render_plate(text: str) -> np.ndarray:
    """Yellow Colombian-style plate with black characters (BGR)."""
    plate = np.full((PLATE_H, PLATE_W, 3), (0, 210, 250), dtype=np.uint8)
    cv2.rectangle(plate, (4, 4), (PLATE_W - 5, PLATE_H - 5), (0, 0, 0), 4)
    scale, thickness = 3.4, 9
    (tw, th), _ = cv2.getTextSize(text, FONT, scale, thickness)
    org = ((PLATE_W - tw) // 2, (PLATE_H + th) // 2)
    cv2.putText(plate, text, org, FONT, scale, (0, 0, 0), thickness, cv2.LINE_AA)
    return plate


def render_frame(text: str, *, noise: float = 0.0, blur: int = 0, seed: int = 7, angle: float = 0.0) -> np.ndarray:
    """Places the plate on a grey scene; optional noise/blur/rotation produce harder, lower-confidence reads."""
    rng = np.random.default_rng(seed)
    frame = np.full((480, 800, 3), 90, dtype=np.uint8)
    cv2.rectangle(frame, (60, 300), (740, 470), (40, 40, 40), -1)  # bumper
    plate = render_plate(text)
    y, x = 160, 140
    frame[y:y + PLATE_H, x:x + PLATE_W] = plate
    if angle:
        m = cv2.getRotationMatrix2D((400, 240), angle, 1.0)
        frame = cv2.warpAffine(frame, m, (800, 480), borderValue=(90, 90, 90))
    if blur:
        k = blur * 2 + 1
        frame = cv2.GaussianBlur(frame, (k, k), 0)
    if noise:
        frame = np.clip(frame.astype(np.float32) + rng.normal(0, noise, frame.shape), 0, 255).astype(np.uint8)
    return frame


def encode_jpeg(image: np.ndarray) -> bytes:
    ok, buf = cv2.imencode(".jpg", image, [cv2.IMWRITE_JPEG_QUALITY, 92])
    if not ok:
        raise RuntimeError("JPEG encoding failed")
    return buf.tobytes()

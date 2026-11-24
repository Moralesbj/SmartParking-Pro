"""
OpenCV plate pipeline: yellow-plate localisation → Otsu binarisation → character segmentation →
template matching constrained by Colombian plate positions.

Scope (honest): templates are rendered with OpenCV Hershey fonts, so accuracy is only validated on
synthetic frames. Real cameras need a trained ALPR engine plugged in through PlateRecognitionProvider.
"""
from __future__ import annotations

import string

import cv2
import numpy as np

from app.adapters.base import CharacterRead, RawRecognition
from app.services import plates

GLYPH_W, GLYPH_H = 24, 36
LETTERS = string.ascii_uppercase
DIGITS = string.digits


def _tight(binary: np.ndarray) -> np.ndarray:
    ys, xs = np.nonzero(binary)
    if len(xs) == 0:
        return binary
    return binary[ys.min():ys.max() + 1, xs.min():xs.max() + 1]


def _glyph(binary: np.ndarray) -> np.ndarray:
    """Normalises a white-on-black character crop to a fixed box, preserving aspect ratio."""
    crop = _tight(binary)
    h, w = crop.shape
    scale = min(GLYPH_W / max(w, 1), GLYPH_H / max(h, 1))
    nw, nh = max(1, int(round(w * scale))), max(1, int(round(h * scale)))
    resized = cv2.resize(crop, (nw, nh), interpolation=cv2.INTER_AREA)
    canvas = np.zeros((GLYPH_H, GLYPH_W), dtype=np.uint8)
    y, x = (GLYPH_H - nh) // 2, (GLYPH_W - nw) // 2
    canvas[y:y + nh, x:x + nw] = resized
    return canvas.astype(np.float32) / 255.0


def _templates() -> dict[str, np.ndarray]:
    out = {}
    for ch in LETTERS + DIGITS:
        img = np.zeros((160, 140), dtype=np.uint8)
        cv2.putText(img, ch, (15, 125), cv2.FONT_HERSHEY_SIMPLEX, 3.4, 255, 9, cv2.LINE_AA)
        _, img = cv2.threshold(img, 127, 255, cv2.THRESH_BINARY)
        out[ch] = _glyph(img)
    return out


def _similarity(a: np.ndarray, b: np.ndarray) -> float:
    a0, b0 = a - a.mean(), b - b.mean()
    denom = float(np.sqrt((a0 * a0).sum() * (b0 * b0).sum()))
    return 0.0 if denom == 0 else max(0.0, float((a0 * b0).sum() / denom))


class TemplateOcrProvider:
    name = "TEMPLATE_OCR"

    def __init__(self) -> None:
        self._templates = _templates()

    def _locate_plate(self, bgr: np.ndarray) -> tuple[np.ndarray, bool]:
        smooth = cv2.medianBlur(bgr, 5)
        hsv = cv2.cvtColor(smooth, cv2.COLOR_BGR2HSV)
        mask = cv2.inRange(hsv, (15, 90, 90), (40, 255, 255))
        mask = cv2.morphologyEx(mask, cv2.MORPH_OPEN, np.ones((5, 5), np.uint8))
        # Close across the black glyphs so the plate becomes one solid region.
        mask = cv2.morphologyEx(mask, cv2.MORPH_CLOSE, np.ones((35, 35), np.uint8))
        contours, _ = cv2.findContours(mask, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
        best = None
        for c in contours:
            (cx, cy), (w, h), angle = cv2.minAreaRect(c)
            if w < h:
                w, h, angle = h, w, angle + 90
            if w * h < 0.01 * bgr.shape[0] * bgr.shape[1] or not 1.6 <= w / max(h, 1) <= 5.5:
                continue
            if best is None or w * h > best[1][0] * best[1][1]:
                best = ((cx, cy), (w, h), angle)
        if best is None:
            return bgr, False
        (cx, cy), (w, h), angle = best
        # Deskew, then crop slightly inside the region so the plate border is excluded.
        m = cv2.getRotationMatrix2D((cx, cy), angle, 1.0)
        rotated = cv2.warpAffine(bgr, m, (bgr.shape[1], bgr.shape[0]), flags=cv2.INTER_LINEAR, borderValue=(0, 210, 250))
        x0, y0 = int(cx - w / 2 + 0.02 * w), int(cy - h / 2 + 0.06 * h)
        x1, y1 = int(cx + w / 2 - 0.02 * w), int(cy + h / 2 - 0.06 * h)
        crop = rotated[max(0, y0):max(0, y1), max(0, x0):max(0, x1)]
        return (crop, True) if crop.size else (bgr, False)

    def recognize(self, image: np.ndarray, hint: str | None = None) -> RawRecognition:
        plate_img, found = self._locate_plate(image)
        warnings: list[str] = [] if found else ["PLATE_REGION_NOT_FOUND"]
        gray = cv2.medianBlur(cv2.cvtColor(plate_img, cv2.COLOR_BGR2GRAY), 3)
        _, binary = cv2.threshold(gray, 0, 255, cv2.THRESH_BINARY_INV + cv2.THRESH_OTSU)
        binary = cv2.morphologyEx(binary, cv2.MORPH_OPEN, np.ones((3, 3), np.uint8))
        ph, pw = binary.shape
        # Drop the plate border by keeping only components that look like characters.
        n, labels, stats, _ = cv2.connectedComponentsWithStats(binary, connectivity=8)
        boxes = []
        for i in range(1, n):
            x, y, w, h, area = stats[i]
            if 0.35 * ph <= h <= 0.9 * ph and 0.03 * pw <= w <= 0.2 * pw and area > 30:
                boxes.append((x, y, w, h, i))
        boxes.sort(key=lambda b: b[0])
        if len(boxes) != 6:
            warnings.append(f"UNEXPECTED_CHARACTER_COUNT:{len(boxes)}")
            return RawRecognition(plate=None, confidence=0.0, plate_found=found, warnings=warnings)

        reads: list[CharacterRead] = []
        for pos, (x, y, w, h, label) in enumerate(boxes):
            glyph = _glyph(((labels[y:y + h, x:x + w] == label) * 255).astype(np.uint8))
            cls = plates.allowed_classes(pos)
            pool = LETTERS if cls == "L" else DIGITS if cls == "D" else LETTERS + DIGITS
            scored = sorted(((self._similarity(glyph, ch), ch) for ch in pool), reverse=True)
            (s1, c1), (s2, c2) = scored[0], scored[1]
            # Confusable glyphs outside the position class (e.g. O vs 0) still count toward ambiguity.
            twin = plates.CONFUSABLE.get(c1)
            if twin and twin not in pool:
                st = self._similarity(glyph, twin)
                if st > s2:
                    s2, c2 = st, twin
            reads.append(CharacterRead(char=c1, score=round(s1, 4), alternative=c2, margin=round(s1 - s2, 4)))

        text = "".join(r.char for r in reads)
        base = float(np.mean([r.score for r in reads]))
        min_margin = min(r.margin for r in reads)
        confidence = base * min(1.0, 0.6 + min_margin * 4)
        if not found:
            confidence *= 0.7
        if min_margin < 0.05:
            warnings.append("AMBIGUOUS_CHARACTERS")
        return RawRecognition(plate=text if plates.is_valid(text) else None, confidence=round(max(0.0, min(confidence, 1.0)), 3),
                              characters=reads, plate_found=found, warnings=warnings)

    def _similarity(self, glyph: np.ndarray, ch: str) -> float:
        return _similarity(glyph, self._templates[ch])

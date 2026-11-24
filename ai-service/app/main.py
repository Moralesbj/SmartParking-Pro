from __future__ import annotations

from fastapi import FastAPI

from app.adapters.simulator import SimulatorProvider
from app.adapters.template_ocr import TemplateOcrProvider
from app.api.routes import router
from app.models.settings import Settings


def create_app(settings: Settings | None = None) -> FastAPI:
    settings = settings or Settings.from_env()
    providers = {"TEMPLATE_OCR": TemplateOcrProvider, "SIMULATOR": SimulatorProvider}
    if settings.provider not in providers:
        raise ValueError(f"ALPR_PROVIDER desconocido: {settings.provider}")
    app = FastAPI(title="SmartParking Pro ALPR", version="0.1.0", docs_url="/docs", redoc_url=None)
    app.state.settings = settings
    app.state.provider = providers[settings.provider]()
    app.include_router(router)
    return app


app = create_app()

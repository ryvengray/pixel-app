"""CPU-only embedding sidecar. Not an executor and not a public API."""
import os
from contextlib import asynccontextmanager
from threading import Lock
from fastapi import FastAPI, HTTPException
from app.decision import Decision, DecisionRequest, Router

@asynccontextmanager
async def lifespan(app):
    from sentence_transformers import SentenceTransformer
    import torch
    torch.set_num_threads(int(os.getenv("OMP_NUM_THREADS", "4")))
    model = SentenceTransformer(os.getenv("MODEL_PATH", "/models/bge-small-zh-v1.5"),
                                device="cpu", local_files_only=True, trust_remote_code=False)
    model.max_seq_length = 512
    def encode(texts):
        return model.encode(texts, normalize_embeddings=True, show_progress_bar=False, batch_size=16)
    app.state.router = Router(encode, float(os.getenv("ROUTE_THRESHOLD", ".82")),
                              float(os.getenv("ROUTE_MARGIN", ".08")))
    app.state.lock = Lock()
    yield

app = FastAPI(lifespan=lifespan)

@app.get("/health")
def health():
    if not hasattr(app.state, "router"):
        raise HTTPException(503, "Model not ready")
    return {"status": "ok"}

@app.post("/decide", response_model=Decision)
def decide(request: DecisionRequest):
    # Avoid concurrent CPU inference overload: caller safely falls back on 503.
    if not app.state.lock.acquire(blocking=False):
        raise HTTPException(503, "Router busy")
    try:
        return app.state.router.decide(request)
    finally:
        app.state.lock.release()

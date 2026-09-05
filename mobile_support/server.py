"""Run on loopback behind Caddy; administration is SSH-only."""
import asyncio
import json
import os
import time
from collections import OrderedDict
from pathlib import Path

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse
from starlette.concurrency import run_in_threadpool

from mobile_support.core import Denied, Store


def configured_store():
    return Store(os.environ["SUPPORT_DB"], os.environ["SUPPORT_REGISTRY"],
                 Path(os.environ["SUPPORT_SECRET_FILE"]).read_bytes())


def create_app():
    store = configured_store()
    app = FastAPI(docs_url=None, redoc_url=None, openapi_url=None)
    requests = OrderedDict()
    active = asyncio.Semaphore(8)

    @app.get("/health")
    async def health():
        return {"status": "ok", "schema": 1}

    @app.post("/enroll")
    @app.post("/heartbeat")
    async def handle(request: Request):
        operation = request.url.path.rsplit("/", 1)[-1]
        # Only the loopback reverse proxy may provide the client address.
        address = request.headers.get("x-forwarded-for", "").split(",")[0].strip() or request.client.host
        now = time.monotonic()
        start, count = requests.get(address, (now, 0))
        if now - start > 60:
            start, count = now, 0
        requests[address] = (start, count + 1)
        requests.move_to_end(address)
        while len(requests) > 1024:
            requests.popitem(last=False)
        if count >= 120:
            return JSONResponse({"error": "rate_limited"}, 429, headers={"Retry-After": "60"})
        try:
            body = bytearray()
            async for chunk in request.stream():
                body.extend(chunk)
                if len(body) > 8192:
                    return JSONResponse({"error": "too_large"}, 413)
            data = json.loads(body)
            if not isinstance(data, dict):
                raise ValueError("Expected object")
            async with active:
                if operation == "enroll":
                    result = await run_in_threadpool(store.enroll, data["device"], data["installation"])
                else:
                    auth = request.headers.get("authorization", "")
                    if not auth.startswith("Bearer "):
                        raise Denied("Missing token")
                    result = await run_in_threadpool(store.heartbeat, data["device"], auth[7:], data["diagnostics"])
            return JSONResponse(result, headers={"Cache-Control": "no-store"})
        except Denied:
            return JSONResponse({"error": "not_authorized"}, 403)
        except (ValueError, KeyError, TypeError, AttributeError):
            return JSONResponse({"error": "invalid_request"}, 400)

    return app

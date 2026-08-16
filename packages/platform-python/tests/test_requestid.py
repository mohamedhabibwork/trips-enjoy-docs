from __future__ import annotations

import uuid

from starlette.applications import Starlette
from starlette.middleware import Middleware
from starlette.requests import Request
from starlette.responses import PlainTextResponse
from starlette.routing import Route
from starlette.testclient import TestClient

from platform_python.requestid import (
    HEADER_CORRELATION_ID,
    HEADER_REQUEST_ID,
    RequestIDMiddleware,
    new_request_id,
    request_id_from_scope,
)


def create_app() -> Starlette:
    async def echo(request: Request) -> PlainTextResponse:
        return PlainTextResponse(request_id_from_scope(request))

    app = Starlette(
        routes=[Route("/echo", echo)],
        middleware=[Middleware(RequestIDMiddleware)],
    )
    return app


def test_middleware_uses_inbound_request_id() -> None:
    app = create_app()
    with TestClient(app) as client:
        resp = client.get("/echo", headers={HEADER_REQUEST_ID: "abc"})
    assert resp.status_code == 200
    assert resp.headers[HEADER_REQUEST_ID] == "abc"
    assert resp.headers[HEADER_CORRELATION_ID] == "abc"
    assert resp.text == "abc"


def test_middleware_uses_correlation_id_when_request_id_absent() -> None:
    app = create_app()
    with TestClient(app) as client:
        resp = client.get("/echo", headers={HEADER_CORRELATION_ID: "xyz"})
    assert resp.headers[HEADER_REQUEST_ID] == "xyz"


def test_middleware_generates_uuid_v7() -> None:
    app = create_app()
    with TestClient(app) as client:
        resp = client.get("/echo")
    generated = resp.headers[HEADER_REQUEST_ID]
    parsed = uuid.UUID(generated)
    assert parsed.version == 7


def test_new_request_id_v7() -> None:
    rid = new_request_id()
    assert uuid.UUID(rid).version == 7

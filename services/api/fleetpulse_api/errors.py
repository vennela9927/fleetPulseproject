"""Errors as RFC 9457 problem details (application/problem+json)."""

from fastapi import Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from starlette.exceptions import HTTPException as StarletteHTTPException


class ApiError(Exception):
    def __init__(self, status: int, title: str, detail: str | None = None,
                 headers: dict[str, str] | None = None) -> None:
        super().__init__(detail or title)
        self.status = status
        self.title = title
        self.detail = detail
        self.headers = headers or {}


def not_found(what: str) -> ApiError:
    # Same answer for "does not exist" and "belongs to another tenant", so ids cannot be probed.
    return ApiError(404, "Not found", f"{what} not found")


def _problem(request: Request, status: int, title: str, detail: str | None,
             headers: dict[str, str] | None = None, **extra: object) -> JSONResponse:
    body: dict[str, object] = {"type": "about:blank", "title": title, "status": status}
    if detail:
        body["detail"] = detail
    body["instance"] = request.url.path
    request_id = getattr(request.state, "request_id", None)
    if request_id:
        body["request_id"] = request_id
    body.update(extra)
    return JSONResponse(body, status_code=status, headers=headers, media_type="application/problem+json")


async def api_error_handler(request: Request, exc: Exception) -> JSONResponse:
    assert isinstance(exc, ApiError)
    return _problem(request, exc.status, exc.title, exc.detail, exc.headers)


async def http_error_handler(request: Request, exc: Exception) -> JSONResponse:
    assert isinstance(exc, StarletteHTTPException)
    return _problem(request, exc.status_code, str(exc.detail), None, getattr(exc, "headers", None))


async def validation_error_handler(request: Request, exc: Exception) -> JSONResponse:
    assert isinstance(exc, RequestValidationError)
    errors = [{"loc": list(e["loc"]), "msg": e["msg"]} for e in exc.errors()]
    return _problem(request, 422, "Invalid request", "one or more parameters are invalid", errors=errors)

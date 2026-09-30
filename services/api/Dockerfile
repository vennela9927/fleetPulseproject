# syntax=docker/dockerfile:1.7
FROM ghcr.io/astral-sh/uv:0.8-python3.12-bookworm-slim AS build
WORKDIR /app
ENV UV_COMPILE_BYTECODE=1 UV_LINK_MODE=copy UV_PYTHON_DOWNLOADS=never
COPY pyproject.toml uv.lock ./
RUN --mount=type=cache,target=/root/.cache/uv uv sync --frozen --no-dev --no-install-project
COPY fleetpulse_api ./fleetpulse_api
# uv caches the built project keyed on pyproject.toml alone; force a rebuild so code changes land.
RUN --mount=type=cache,target=/root/.cache/uv uv sync --frozen --no-dev --no-editable --reinstall-package fleetpulse-api

FROM python:3.12-slim-bookworm
RUN useradd --system --uid 10001 app
COPY --from=build /app/.venv /app/.venv
ENV PATH=/app/.venv/bin:$PATH PYTHONUNBUFFERED=1 API_HOST=0.0.0.0 PORT=8000
USER 10001
EXPOSE 8000
HEALTHCHECK --interval=10s --timeout=15s --retries=12 \
  CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8000/healthz', timeout=2)"
CMD ["python", "-m", "fleetpulse_api"]

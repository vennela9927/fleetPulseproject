# syntax=docker/dockerfile:1.7
# Batch jobs: train, score, evaluate and the feed-drift watcher. The command picks the job:
#   docker compose run --rm ml fleetpulse-train
FROM ghcr.io/astral-sh/uv:0.8-python3.12-bookworm-slim AS build
WORKDIR /app
ENV UV_COMPILE_BYTECODE=1 UV_LINK_MODE=copy UV_PYTHON_DOWNLOADS=never
COPY pyproject.toml uv.lock ./
RUN --mount=type=cache,target=/root/.cache/uv uv sync --frozen --no-dev --no-install-project
COPY fleetpulse_ml ./fleetpulse_ml
# uv caches the built project keyed on pyproject.toml alone; force a rebuild so code changes land.
RUN --mount=type=cache,target=/root/.cache/uv uv sync --frozen --no-dev --no-editable --reinstall-package fleetpulse-ml

FROM python:3.12-slim-bookworm
# LightGBM needs the OpenMP runtime.
RUN apt-get update && apt-get install -y --no-install-recommends libgomp1 && rm -rf /var/lib/apt/lists/* \
 && useradd --system --uid 10001 app && mkdir /models && chown app /models
COPY --from=build /app/.venv /app/.venv
ENV PATH=/app/.venv/bin:$PATH PYTHONUNBUFFERED=1 MODEL_DIR=/models
USER 10001
WORKDIR /app
CMD ["fleetpulse-drift", "--watch", "120"]

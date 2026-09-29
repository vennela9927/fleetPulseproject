"""``python -m fleetpulse_api``: runs the API under uvicorn.

Async psycopg needs a selector event loop; on Windows uvicorn would otherwise pick the
Proactor loop, which psycopg cannot use. Linux (the container) is unaffected.
"""

import asyncio
import os
import sys

import uvicorn


def main() -> None:
    config = uvicorn.Config("fleetpulse_api.main:app", host=os.environ.get("API_HOST", "127.0.0.1"),
                            port=int(os.environ.get("PORT", "8000")), proxy_headers=True,
                            log_level=os.environ.get("LOG_LEVEL", "info"))
    server = uvicorn.Server(config)
    if sys.platform == "win32":
        asyncio.run(server.serve(), loop_factory=asyncio.SelectorEventLoop)
    else:
        asyncio.run(server.serve())


if __name__ == "__main__":
    main()

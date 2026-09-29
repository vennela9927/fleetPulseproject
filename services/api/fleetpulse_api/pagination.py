"""Keyset pagination with opaque cursors.

A cursor encodes the sort key of the last item returned; the next page asks for rows after
it. Unlike OFFSET, this is stable while rows are being inserted (no skipped or repeated
items) and costs the same on page 1,000 as on page 1. The cursor is only a position: a
tampered cursor can move within the caller's own rows, never outside them, because
row-level security still applies.
"""

import base64
import binascii
import json
from typing import Any

from .errors import ApiError

MAX_LIMIT = 500


def encode_cursor(key: dict[str, Any]) -> str:
    return base64.urlsafe_b64encode(json.dumps(key, separators=(",", ":")).encode()).decode().rstrip("=")


def decode_cursor(cursor: str | None, fields: dict[str, type]) -> dict[str, Any] | None:
    """Decodes and type-checks a cursor; ``fields`` maps each key to its expected type."""
    if not cursor:
        return None
    try:
        raw = json.loads(base64.urlsafe_b64decode(cursor + "=" * (-len(cursor) % 4)))
    except (binascii.Error, ValueError, UnicodeDecodeError):
        raise ApiError(400, "Invalid cursor", "cursor is not one this API issued") from None
    if not isinstance(raw, dict) or set(raw) != set(fields) or not all(
            isinstance(raw[k], t) and not isinstance(raw[k], bool) for k, t in fields.items()):
        raise ApiError(400, "Invalid cursor", "cursor is not one this API issued")
    return raw


def page(items: list[dict[str, Any]], limit: int, key: Any) -> dict[str, Any]:
    """Builds a page from ``limit + 1`` fetched rows: the extra row only signals that more exist."""
    has_more = len(items) > limit
    items = items[:limit]
    return {"items": items, "next_cursor": encode_cursor(key(items[-1])) if has_more else None}

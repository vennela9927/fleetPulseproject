"""Fleet copilot: answers questions with tenant-scoped tools and proposes actions that a fleet
manager approves. The model never executes anything; approval does."""

import json
import logging
from datetime import UTC, datetime
from typing import Annotated, Any, Literal

from fastapi import APIRouter, Request
from pydantic import BaseModel, Field

from .. import audit
from ..auth import CurrentUser, Manager
from ..copilot.engine import GeminiEngine, RulesEngine, Turn
from ..copilot.tools import Toolbox, new_conversation_id
from ..db import ensure_user, tenant_tx
from ..errors import ApiError, not_found
from ..ratelimit import RateLimited, rate_limit
from .maintenance import PlanConflict, book_items

log = logging.getLogger(__name__)
router = APIRouter(prefix="/v1/copilot", tags=["copilot"])

HISTORY_TTL_S = 7200
MAX_HISTORY_CONTENTS = 40


class ChatRequest(BaseModel):
    message: str = Field(min_length=1, max_length=2000)
    conversation_id: str | None = Field(None, pattern=r"^[0-9a-f-]{36}$")


def _history_key(user_id: str, conversation_id: str) -> str:
    # Keyed by user: a conversation id alone cannot open someone else's conversation.
    return f"copilot:{user_id}:{conversation_id}"


def _trim(contents: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """Keeps the most recent turns, starting at a user text message so tool calls stay paired."""
    if len(contents) <= MAX_HISTORY_CONTENTS:
        return contents
    tail = contents[-MAX_HISTORY_CONTENTS:]
    for i, c in enumerate(tail):
        if c.get("role") == "user" and any("text" in p for p in c.get("parts", [])):
            return tail[i:]
    return []


@router.post("/chat")
async def chat(body: ChatRequest, request: Request, user: CurrentUser,
               _: Annotated[None, rate_limit(cost=5)]) -> dict[str, Any]:
    settings = request.app.state.settings
    redis = request.app.state.redis
    conversation_id = body.conversation_id or new_conversation_id()
    key = _history_key(user.user_id, conversation_id)
    stored = await redis.get(key)
    history = json.loads(stored) if stored else []

    tools = Toolbox(request, user, conversation_id)
    turn: Turn | None = None
    if settings.gemini_api_key:
        try:
            turn = await GeminiEngine(settings.gemini_api_key, settings.gemini_models).run(history, body.message, tools)
        except Exception as e:   # rate limits, outages, bad responses: answer with the rules instead
            log.warning("copilot: Gemini unavailable, using rules: %s: %s", type(e).__name__, str(e)[:200])
    if turn is None:
        turn = await RulesEngine().run(history, body.message, tools)
    await redis.set(key, json.dumps(_trim(turn.history), default=str), ex=HISTORY_TTL_S)
    return {"conversation_id": conversation_id, "reply": turn.reply, "engine": turn.engine,
            "proposals": tools.proposals, "flags": tools.flags, "tool_calls": turn.tool_calls}


@router.get("/actions")
async def list_actions(request: Request, user: CurrentUser, _: RateLimited,
                       status: Literal["PROPOSED", "EXECUTED", "REJECTED", "FAILED"] | None = "PROPOSED",
                       ) -> dict[str, Any]:
    async with tenant_tx(request.app.state.pg, user) as conn:
        rows = await (await conn.execute("""
            SELECT a.id, a.tool, a.arguments, a.rationale, a.status, a.created_at, a.decided_at, a.result,
                   u.email AS requested_by_email, d.email AS decided_by_email
            FROM agent_action a
            LEFT JOIN app_user u ON u.id = a.requested_by
            LEFT JOIN app_user d ON d.id = a.decided_by
            WHERE (%s::text IS NULL OR a.status = %s) ORDER BY a.created_at DESC LIMIT 50""",
            (status, status))).fetchall()
    return {"items": rows}


async def _decide(request: Request, user: Manager, action_id: int, approve: bool) -> dict[str, Any]:
    async with tenant_tx(request.app.state.pg, user) as conn:
        await ensure_user(conn, user)
        action = await (await conn.execute(
            "SELECT id, tool, arguments, status FROM agent_action WHERE id = %s FOR UPDATE", (action_id,))).fetchone()
        if action is None:
            raise not_found("proposal")
        if action["status"] != "PROPOSED":
            raise ApiError(409, "Conflict", f"proposal is already {action['status']}")
        now = datetime.now(UTC)
        if not approve:
            await conn.execute("UPDATE agent_action SET status = 'REJECTED', decided_by = %s, decided_at = %s "
                               "WHERE id = %s", (user.user_id, now, action_id))
            await audit.record(conn, request, user, "agent.reject", "agent_action", str(action_id))
            return {"id": action_id, "status": "REJECTED"}
        args = action["arguments"]
        if action["tool"] == "book_service":
            # Created by the approving human's decision, inside RLS: a proposal can only book this
            # tenant's vehicle into this tenant's depot.
            booking = await (await conn.execute("""
                INSERT INTO service_booking (tenant_id, vehicle_id, depot_id, scheduled_for, reason, created_by, source)
                VALUES (%s, %s, %s, %s, %s, %s, 'AGENT') RETURNING id""",
                (user.tenant_id, args["vehicle_id"], args["depot_id"], args["scheduled_for"], args["reason"],
                 user.user_id))).fetchone()
            result: dict[str, Any] = {"booking_id": booking["id"]}
        elif action["tool"] == "book_service_plan":
            # Every item is re-checked now (tenant, not already booked, a free bay): the plan may
            # have been proposed hours ago. All or nothing, in a savepoint so the failure is recorded.
            try:
                async with conn.transaction():
                    ids = await book_items(conn, user, args["items"], "AGENT")
            except PlanConflict as e:
                result = {"error": f"{e}; ask the copilot for a fresh plan"}
                await conn.execute("UPDATE agent_action SET status = 'FAILED', decided_by = %s, decided_at = %s, "
                                   "result = %s::jsonb WHERE id = %s",
                                   (user.user_id, now, json.dumps(result), action_id))
                await audit.record(conn, request, user, "agent.approve_failed", "agent_action", str(action_id), result)
                return {"id": action_id, "status": "FAILED", **result}
            result = {"bookings": len(ids), "booking_ids": ids}
        else:
            raise ApiError(422, "Unsupported", f"no executor for {action['tool']}")
        await conn.execute("UPDATE agent_action SET status = 'EXECUTED', decided_by = %s, decided_at = %s, "
                           "result = %s::jsonb WHERE id = %s", (user.user_id, now, json.dumps(result), action_id))
        await audit.record(conn, request, user, "agent.approve", "agent_action", str(action_id),
                           {"tool": action["tool"], **result})
    return {"id": action_id, "status": "EXECUTED", **result}


@router.post("/actions/{action_id}/approve")
async def approve(action_id: int, request: Request, user: Manager, _: RateLimited) -> dict[str, Any]:
    return await _decide(request, user, action_id, True)


@router.post("/actions/{action_id}/reject")
async def reject(action_id: int, request: Request, user: Manager, _: RateLimited) -> dict[str, Any]:
    return await _decide(request, user, action_id, False)

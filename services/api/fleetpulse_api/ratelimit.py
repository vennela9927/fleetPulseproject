"""Per-user token-bucket rate limiting in Redis.

Each user has a bucket of ``burst`` tokens refilled at ``rate`` per second; a request spends
``cost`` tokens (expensive endpoints cost more). The check-and-spend runs as one Lua script,
so concurrent requests and multiple API replicas share one exact budget per user. Redis
supplies the clock, so replicas with skewed clocks agree.

If Redis is unreachable the limiter lets requests through and logs it: rate limiting
protects capacity, and failing closed would turn a Redis outage into a full API outage.
"""

import logging
import math
from dataclasses import dataclass
from typing import Annotated

from fastapi import Depends, Request, Response
from redis.asyncio import Redis
from redis.exceptions import RedisError

from .auth import CurrentUser
from .errors import ApiError

log = logging.getLogger(__name__)

# KEYS[1] bucket. ARGV: rate/s, burst, cost. Returns {allowed, tokens_left*1000, retry_after_ms}.
_BUCKET_SCRIPT = """
local t = redis.call('TIME')
local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
local rate, burst, cost = tonumber(ARGV[1]), tonumber(ARGV[2]), tonumber(ARGV[3])
local b = redis.call('HMGET', KEYS[1], 'tokens', 'ts')
local tokens = tonumber(b[1]) or burst
local ts = tonumber(b[2]) or now
tokens = math.min(burst, tokens + (now - ts) * rate / 1000)
local allowed, retry = 0, 0
if tokens >= cost then
  tokens = tokens - cost
  allowed = 1
else
  retry = math.ceil((cost - tokens) * 1000 / rate)
end
redis.call('HSET', KEYS[1], 'tokens', tokens, 'ts', now)
redis.call('PEXPIRE', KEYS[1], math.ceil(burst * 1000 / rate) + 1000)
return {allowed, math.floor(tokens * 1000), retry}
"""


@dataclass(frozen=True)
class Decision:
    allowed: bool
    remaining: int
    retry_after_s: int


class RateLimiter:
    def __init__(self, redis: Redis, rate: float, burst: int) -> None:
        self._redis = redis
        self._rate = rate
        self._burst = burst
        self._script = redis.register_script(_BUCKET_SCRIPT)

    @property
    def burst(self) -> int:
        return self._burst

    async def check(self, key: str, cost: int = 1) -> Decision:
        try:
            allowed, left, retry_ms = await self._script(keys=[f"rl:{key}"], args=[self._rate, self._burst, cost])
        except RedisError as e:
            log.warning("rate limiter unavailable, allowing request: %s", e)
            return Decision(True, self._burst, 0)
        return Decision(bool(allowed), int(left) // 1000, math.ceil(int(retry_ms) / 1000))


def rate_limit(cost: int = 1):
    """Dependency that charges ``cost`` tokens to the caller, or answers 429 with Retry-After."""

    async def dependency(request: Request, response: Response, user: CurrentUser) -> None:
        limiter: RateLimiter = request.app.state.limiter
        d = await limiter.check(user.user_id, cost)
        headers = {"X-RateLimit-Limit": str(limiter.burst), "X-RateLimit-Remaining": str(d.remaining)}
        if not d.allowed:
            raise ApiError(429, "Too many requests", f"retry in {d.retry_after_s} s",
                           {**headers, "Retry-After": str(max(1, d.retry_after_s))})
        response.headers.update(headers)

    return Depends(dependency)


RateLimited = Annotated[None, rate_limit()]

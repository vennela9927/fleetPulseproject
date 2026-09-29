"""Bearer-token authentication against the Keycloak realm.

Only RS256 tokens signed by a key in the realm's JWKS are accepted, with issuer, audience
and expiry checked. Pinning the algorithm and taking the key from the JWKS (never from the
token) rules out the classic forgeries: ``alg: none`` and an HS256 token "signed" with the
public key.
"""

import asyncio
import time
import uuid
from dataclasses import dataclass
from typing import Annotated

import httpx
import jwt
from fastapi import Depends, Request
from jwt import PyJWK

from .config import Settings, get_settings
from .errors import ApiError

ROLES = frozenset({"fleet_manager", "fleet_viewer", "platform_admin"})


@dataclass(frozen=True)
class Principal:
    user_id: str
    tenant_id: str
    username: str
    email: str | None
    roles: frozenset[str]

    @property
    def is_manager(self) -> bool:
        return "fleet_manager" in self.roles


def _unauthorized(detail: str) -> ApiError:
    return ApiError(401, "Unauthorized", detail,
                    {"WWW-Authenticate": f'Bearer error="invalid_token", error_description="{detail}"'})


class JwksCache:
    """The realm's signing keys by key id. An unknown kid (key rotation) triggers a refetch,
    at most once every ``min_refresh_s`` so that garbage kids cannot hammer Keycloak."""

    def __init__(self, url: str, min_refresh_s: float = 30) -> None:
        self._url = url
        self._min_refresh_s = min_refresh_s
        self._keys: dict[str, PyJWK] = {}
        self._fetched_at = 0.0
        self._lock = asyncio.Lock()

    async def get(self, kid: str) -> PyJWK | None:
        key = self._keys.get(kid)
        if key is not None:
            return key
        async with self._lock:
            if kid not in self._keys and time.monotonic() - self._fetched_at >= self._min_refresh_s:
                await self._refresh()
        return self._keys.get(kid)

    async def _refresh(self) -> None:
        self._fetched_at = time.monotonic()
        async with httpx.AsyncClient(timeout=5) as client:
            resp = await client.get(self._url)
            resp.raise_for_status()
        keys: dict[str, PyJWK] = {}
        for jwk in resp.json().get("keys", []):
            if jwk.get("use", "sig") == "sig" and jwk.get("kty") == "RSA" and "kid" in jwk:
                keys[jwk["kid"]] = PyJWK(jwk)
        self._keys = keys

    def set_keys(self, keys: dict[str, PyJWK]) -> None:
        """For tests: use these keys and never fetch."""
        self._keys = keys
        self._fetched_at = float("inf")


class TokenVerifier:
    def __init__(self, settings: Settings, jwks: JwksCache) -> None:
        self._settings = settings
        self._jwks = jwks

    async def verify(self, token: str) -> Principal:
        try:
            header = jwt.get_unverified_header(token)
        except jwt.PyJWTError:
            raise _unauthorized("malformed token") from None
        if header.get("alg") != "RS256":
            raise _unauthorized("unsupported signing algorithm")
        key = await self._jwks.get(str(header.get("kid")))
        if key is None:
            raise _unauthorized("unknown signing key")
        try:
            claims = jwt.decode(
                token, key.key, algorithms=["RS256"],
                audience=self._settings.oidc_audience, issuer=self._settings.oidc_issuer,
                options={"require": ["exp", "iat", "sub", "iss", "aud"]}, leeway=30,
            )
        except jwt.ExpiredSignatureError:
            raise _unauthorized("token expired") from None
        except jwt.PyJWTError as e:
            raise _unauthorized(f"invalid token: {e}") from None

        tenant = claims.get("tenant_id")
        try:
            tenant_id = str(uuid.UUID(str(tenant)))
        except ValueError:
            raise _unauthorized("token has no tenant") from None
        roles = frozenset(claims.get("realm_access", {}).get("roles", [])) & ROLES
        if not roles:
            raise ApiError(403, "Forbidden", "no FleetPulse role assigned")
        return Principal(user_id=claims["sub"], tenant_id=tenant_id,
                         username=claims.get("preferred_username", claims["sub"]),
                         email=claims.get("email"), roles=roles)


async def current_principal(request: Request) -> Principal:
    auth = request.headers.get("authorization", "")
    scheme, _, token = auth.partition(" ")
    if scheme.lower() != "bearer" or not token:
        raise ApiError(401, "Unauthorized", "missing bearer token", {"WWW-Authenticate": "Bearer"})
    principal = await request.app.state.verifier.verify(token.strip())
    request.state.principal = principal
    return principal


CurrentUser = Annotated[Principal, Depends(current_principal)]


async def require_manager(user: CurrentUser) -> Principal:
    if not user.is_manager:
        raise ApiError(403, "Forbidden", "this action needs the fleet_manager role")
    return user


Manager = Annotated[Principal, Depends(require_manager)]


async def require_platform_admin(user: CurrentUser) -> Principal:
    if "platform_admin" not in user.roles:
        raise ApiError(403, "Forbidden", "this action needs the platform_admin role")
    return user


PlatformAdmin = Annotated[Principal, Depends(require_platform_admin)]


def build_verifier(settings: Settings | None = None) -> TokenVerifier:
    settings = settings or get_settings()
    return TokenVerifier(settings, JwksCache(settings.oidc_jwks_url))

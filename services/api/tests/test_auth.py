"""Token verification against a locally generated signing key: no Keycloak needed."""

import base64
import hashlib
import hmac
import json
import time

import jwt
import pytest
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from jwt import PyJWK

from fleetpulse_api.auth import JwksCache, TokenVerifier
from fleetpulse_api.config import Settings
from fleetpulse_api.errors import ApiError

SETTINGS = Settings()
TENANT = "11111111-1111-1111-1111-111111111111"


@pytest.fixture(scope="module")
def keypair():
    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    jwk = json.loads(jwt.algorithms.RSAAlgorithm.to_jwk(key.public_key()))
    jwk.update(kid="test-key", use="sig", alg="RS256")
    return key, PyJWK(jwk)


@pytest.fixture
def verifier(keypair):
    jwks = JwksCache("http://unused")
    jwks.set_keys({"test-key": keypair[1]})
    return TokenVerifier(SETTINGS, jwks)


def claims(**overrides):
    now = int(time.time())
    c = {"iss": SETTINGS.oidc_issuer, "aud": SETTINGS.oidc_audience, "sub": "user-1", "iat": now, "exp": now + 300,
         "tenant_id": TENANT, "realm_access": {"roles": ["fleet_viewer", "offline_access"]},
         "preferred_username": "tester", "email": "t@example.com"}
    c.update(overrides)
    return {k: v for k, v in c.items() if v is not None}


def sign(key, payload, kid="test-key", alg="RS256"):
    return jwt.encode(payload, key, algorithm=alg, headers={"kid": kid})


async def expect_rejected(verifier, token, status=401):
    with pytest.raises(ApiError) as e:
        await verifier.verify(token)
    assert e.value.status == status
    return e.value


async def test_a_valid_token_yields_the_principal(verifier, keypair):
    p = await verifier.verify(sign(keypair[0], claims()))
    assert p.user_id == "user-1"
    assert p.tenant_id == TENANT
    assert p.roles == frozenset({"fleet_viewer"})   # unrelated realm roles are dropped
    assert not p.is_manager


async def test_expired_token(verifier, keypair):
    e = await expect_rejected(verifier, sign(keypair[0], claims(exp=int(time.time()) - 120)))
    assert e.detail == "token expired"


async def test_wrong_audience(verifier, keypair):
    await expect_rejected(verifier, sign(keypair[0], claims(aud="some-other-api")))


async def test_wrong_issuer(verifier, keypair):
    await expect_rejected(verifier, sign(keypair[0], claims(iss="https://evil.example.com/realms/fleetpulse")))


async def test_missing_or_malformed_tenant(verifier, keypair):
    await expect_rejected(verifier, sign(keypair[0], claims(tenant_id=None)))
    await expect_rejected(verifier, sign(keypair[0], claims(tenant_id="acme' OR '1'='1")))


async def test_no_fleetpulse_role_is_forbidden(verifier, keypair):
    await expect_rejected(verifier, sign(keypair[0], claims(realm_access={"roles": ["offline_access"]})), status=403)


async def test_signed_by_an_unknown_key(verifier):
    other = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    await expect_rejected(verifier, sign(other, claims(), kid="test-key"))   # right kid, wrong key
    await expect_rejected(verifier, sign(other, claims(), kid="attacker-key"))


async def test_alg_none_is_rejected(verifier):
    def b64(d):
        return base64.urlsafe_b64encode(json.dumps(d).encode()).rstrip(b"=").decode()

    token = f"{b64({'alg': 'none', 'kid': 'test-key'})}.{b64(claims())}."
    e = await expect_rejected(verifier, token)
    assert e.detail == "unsupported signing algorithm"


async def test_hs256_signed_with_the_public_key_is_rejected(verifier, keypair):
    # The classic key-confusion forgery: HMAC "signed" with the RSA public key as the secret.
    public_pem = keypair[1].key.public_bytes(encoding=serialization.Encoding.PEM,
                                             format=serialization.PublicFormat.SubjectPublicKeyInfo)
    header = base64.urlsafe_b64encode(json.dumps({"alg": "HS256", "kid": "test-key"}).encode()).rstrip(b"=")
    payload = base64.urlsafe_b64encode(json.dumps(claims()).encode()).rstrip(b"=")
    sig = base64.urlsafe_b64encode(hmac.new(public_pem, header + b"." + payload, hashlib.sha256).digest()).rstrip(b"=")
    await expect_rejected(verifier, (header + b"." + payload + b"." + sig).decode())


async def test_tampered_payload(verifier, keypair):
    token = sign(keypair[0], claims())
    head, payload, sig = token.split(".")
    data = json.loads(base64.urlsafe_b64decode(payload + "=="))
    data["tenant_id"] = "22222222-2222-2222-2222-222222222222"   # try to switch tenant
    forged = base64.urlsafe_b64encode(json.dumps(data).encode()).rstrip(b"=").decode()
    await expect_rejected(verifier, f"{head}.{forged}.{sig}")


async def test_garbage(verifier):
    await expect_rejected(verifier, "not-a-jwt")

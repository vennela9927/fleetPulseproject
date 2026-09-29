from functools import lru_cache

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """Runtime configuration, from environment variables prefixed ``API_``."""

    model_config = SettingsConfigDict(env_prefix="API_", env_file=".env", extra="ignore")

    # Postgres as fleet_app: subject to row-level security.
    postgres_dsn: str = "postgresql://fleet_app:fleet_app_dev@localhost:5432/fleet"
    postgres_pool_max: int = 10
    statement_timeout_ms: int = 5000

    redis_url: str = "redis://localhost:6379/0"

    # ClickHouse as fleet_api: read-only, restricted by row policies.
    clickhouse_url: str = "http://localhost:8123"
    clickhouse_user: str = "fleet_api"
    clickhouse_password: str = "clickhouse_api_dev"  # noqa: S105  (development default)

    # OIDC. The issuer is what tokens say; the JWKS URL is how this service reaches Keycloak,
    # which differs inside Docker (keycloak:8080) from the browser-facing issuer.
    oidc_issuer: str = "http://localhost:8080/realms/fleetpulse"
    oidc_jwks_url: str = "http://localhost:8080/realms/fleetpulse/protocol/openid-connect/certs"
    oidc_audience: str = "fleetpulse-api"

    cors_origins: list[str] = ["http://localhost:5173"]

    # Token bucket per user: sustained requests per second and burst size.
    rate_limit_per_second: float = 20
    rate_limit_burst: int = 60


@lru_cache
def get_settings() -> Settings:
    return Settings()

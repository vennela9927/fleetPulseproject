-- FleetPulse relational core (PostgreSQL 16 + pgvector)
-- Designed in Third Normal Form. Deliberate denormalisations are marked "DENORM" and
-- explained in docs/adr/ADR-003-polyglot-persistence.md.

CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- ---------------------------------------------------------------------------
-- Tenancy, users, billing  (CP data: strong consistency required)
-- ---------------------------------------------------------------------------
CREATE TABLE tenant (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    name        text NOT NULL UNIQUE,
    created_at  timestamptz NOT NULL DEFAULT now()
);

-- Identity lives in Keycloak; this table only maps the OIDC subject to a tenant
-- so that audit rows and approvals can reference a stable user id.
CREATE TABLE app_user (
    id          uuid PRIMARY KEY,               -- Keycloak "sub" claim
    tenant_id   uuid NOT NULL REFERENCES tenant(id),
    email       text NOT NULL UNIQUE,
    created_at  timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE subscription_plan (
    id                       smallint PRIMARY KEY,
    code                     text NOT NULL UNIQUE,
    name                     text NOT NULL,
    price_per_vehicle_month  numeric(10,2) NOT NULL CHECK (price_per_vehicle_month >= 0)
);

CREATE TABLE subscription (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id   uuid NOT NULL REFERENCES tenant(id),
    plan_id     smallint NOT NULL REFERENCES subscription_plan(id),
    starts_at   date NOT NULL,
    ends_at     date,
    status      text NOT NULL CHECK (status IN ('ACTIVE','SUSPENDED','CANCELLED')),
    CHECK (ends_at IS NULL OR ends_at > starts_at)
);
CREATE UNIQUE INDEX subscription_one_active ON subscription(tenant_id) WHERE status = 'ACTIVE';

-- ---------------------------------------------------------------------------
-- Fleet and vehicle master data
-- ---------------------------------------------------------------------------
CREATE TABLE depot (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id   uuid NOT NULL REFERENCES tenant(id),
    name        text NOT NULL,
    lat         double precision NOT NULL CHECK (lat BETWEEN -90 AND 90),
    lon         double precision NOT NULL CHECK (lon BETWEEN -180 AND 180),
    radius_m    integer NOT NULL DEFAULT 300 CHECK (radius_m > 0),
    UNIQUE (tenant_id, name)
);

CREATE TABLE fleet (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id   uuid NOT NULL REFERENCES tenant(id),
    name        text NOT NULL,
    home_depot_id bigint REFERENCES depot(id),
    UNIQUE (tenant_id, name),
    UNIQUE (id, tenant_id)          -- target of the composite FK from vehicle
);

CREATE TABLE oem (
    id          smallint PRIMARY KEY,
    code        text NOT NULL UNIQUE,     -- e.g. AURORA, BOREALIS, CYGNUS
    name        text NOT NULL,
    wmi         char(3) NOT NULL UNIQUE   -- World Manufacturer Identifier (VIN chars 1-3)
);

-- Model attributes depend on the model, not on the individual vehicle (3NF).
CREATE TABLE vehicle_model (
    id           smallint PRIMARY KEY,
    oem_id       smallint NOT NULL REFERENCES oem(id),
    name         text NOT NULL,
    powertrain   text NOT NULL CHECK (powertrain IN ('ICE','HYBRID','EV')),
    battery_kwh  numeric(6,1),
    tank_litres  numeric(6,1),
    UNIQUE (oem_id, name)
);

CREATE TABLE vehicle (
    id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    vin          char(17) NOT NULL UNIQUE,
    fleet_id     bigint NOT NULL,
    -- DENORM: tenant_id is derivable via fleet. It is copied here so that
    -- row-level security and the tenant shard key need no join. The composite FK
    -- below makes an inconsistent copy impossible, so no update anomaly can arise.
    tenant_id    uuid NOT NULL,
    model_id     smallint NOT NULL REFERENCES vehicle_model(id),
    model_year   smallint NOT NULL CHECK (model_year BETWEEN 2010 AND 2030),
    status       text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','IN_SERVICE','RETIRED')),
    registered_at timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (fleet_id, tenant_id) REFERENCES fleet(id, tenant_id)
);
CREATE INDEX vehicle_tenant_fleet ON vehicle(tenant_id, fleet_id);

-- Drivers hold personal data (PII) and are subject to the right-to-erasure flow.
CREATE TABLE driver (
    id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id    uuid NOT NULL REFERENCES tenant(id),
    full_name    text,                       -- nulled on erasure
    email        text,                       -- nulled on erasure
    licence_hash text,                       -- salted hash, never the raw licence number
    erased_at    timestamptz
);
CREATE INDEX driver_tenant ON driver(tenant_id);

CREATE TABLE driver_assignment (
    vehicle_id   bigint NOT NULL REFERENCES vehicle(id),
    driver_id    bigint NOT NULL REFERENCES driver(id),
    valid_from   timestamptz NOT NULL,
    valid_to     timestamptz,
    PRIMARY KEY (vehicle_id, valid_from),
    CHECK (valid_to IS NULL OR valid_to > valid_from)
);
CREATE INDEX driver_assignment_driver ON driver_assignment(driver_id);

-- ---------------------------------------------------------------------------
-- Trips: high-volume, range-partitioned by month
-- ---------------------------------------------------------------------------
CREATE TABLE trip (
    id            bigint GENERATED ALWAYS AS IDENTITY,
    vehicle_id    bigint NOT NULL REFERENCES vehicle(id),
    driver_id     bigint REFERENCES driver(id),
    started_at    timestamptz NOT NULL,
    ended_at      timestamptz,
    distance_km   numeric(8,2),
    idle_seconds  integer NOT NULL DEFAULT 0,
    harsh_events  integer NOT NULL DEFAULT 0,
    PRIMARY KEY (id, started_at)
) PARTITION BY RANGE (started_at);

CREATE TABLE trip_2026_08 PARTITION OF trip FOR VALUES FROM ('2026-08-01') TO ('2026-09-01');
CREATE TABLE trip_2026_09 PARTITION OF trip FOR VALUES FROM ('2026-09-01') TO ('2026-10-01');
CREATE TABLE trip_2026_10 PARTITION OF trip FOR VALUES FROM ('2026-10-01') TO ('2026-11-01');
CREATE TABLE trip_default PARTITION OF trip DEFAULT;
CREATE INDEX trip_vehicle_started ON trip(vehicle_id, started_at DESC);

-- ---------------------------------------------------------------------------
-- Diagnostics, alerts and predictions
-- ---------------------------------------------------------------------------
CREATE TABLE dtc_code (
    code         varchar(5) PRIMARY KEY CHECK (code ~ '^[PCBU][0-3][0-9A-F]{3}$'),
    description  text NOT NULL,
    system       text NOT NULL,
    severity     smallint NOT NULL CHECK (severity BETWEEN 1 AND 5),
    avg_repair_cost_usd numeric(10,2) NOT NULL
);

CREATE TABLE alert_rule (
    code         text PRIMARY KEY,
    description  text NOT NULL,
    severity     text NOT NULL CHECK (severity IN ('INFO','WARNING','CRITICAL'))
);

CREATE TABLE alert (
    id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id       uuid NOT NULL REFERENCES tenant(id),   -- DENORM for RLS, same reasoning as vehicle
    vehicle_id      bigint NOT NULL REFERENCES vehicle(id),
    rule_code       text NOT NULL REFERENCES alert_rule(code),
    -- Deterministic key (vin + rule + window start) makes the sink idempotent:
    -- a replayed Kafka message hits ON CONFLICT DO NOTHING instead of duplicating.
    dedup_key       text NOT NULL UNIQUE,
    status          text NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','ACKNOWLEDGED','RESOLVED')),
    opened_at       timestamptz NOT NULL,
    detected_at     timestamptz NOT NULL DEFAULT now(),
    acknowledged_by uuid REFERENCES app_user(id),
    resolved_at     timestamptz,
    details         jsonb NOT NULL DEFAULT '{}'
);

CREATE TABLE risk_model (
    version      text PRIMARY KEY,
    trained_at   timestamptz NOT NULL,
    metrics      jsonb NOT NULL,          -- PR-AUC, precision@k, baseline comparison
    is_active    boolean NOT NULL DEFAULT false
);
CREATE UNIQUE INDEX risk_model_one_active ON risk_model(is_active) WHERE is_active;

CREATE TABLE risk_score (
    vehicle_id      bigint NOT NULL REFERENCES vehicle(id),
    scored_at       timestamptz NOT NULL,
    model_version   text NOT NULL REFERENCES risk_model(version),
    tenant_id       uuid NOT NULL REFERENCES tenant(id),   -- DENORM for RLS
    failure_prob_7d real NOT NULL CHECK (failure_prob_7d BETWEEN 0 AND 1),
    predicted_component text,
    est_cost_avoided_usd numeric(10,2),
    top_factors     jsonb NOT NULL DEFAULT '[]',
    PRIMARY KEY (vehicle_id, scored_at)
);
-- "This tenant's latest run, riskiest first": an index scan that stops at the page size.
CREATE INDEX risk_score_ranked ON risk_score(tenant_id, model_version, scored_at DESC, failure_prob_7d DESC, vehicle_id);

-- Vector store: a fixed-length numeric "fault signature" per vehicle-window,
-- labelled with what actually happened. Used for "vehicles that looked like this
-- one and what fixed them". Engineered features, so no external embedding API.
CREATE TABLE fault_signature (
    id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    vehicle_id      bigint NOT NULL REFERENCES vehicle(id),
    window_end      timestamptz NOT NULL,
    embedding       vector(16) NOT NULL,
    outcome_component text,               -- NULL = no failure followed
    repair_action   text,
    repair_cost_usd numeric(10,2)
);

-- ---------------------------------------------------------------------------
-- Actions, agent, OEM onboarding
-- ---------------------------------------------------------------------------
CREATE TABLE service_booking (
    id             bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id      uuid NOT NULL REFERENCES tenant(id),
    vehicle_id     bigint NOT NULL REFERENCES vehicle(id),
    depot_id       bigint NOT NULL REFERENCES depot(id),
    scheduled_for  timestamptz NOT NULL,
    reason         text NOT NULL,
    status         text NOT NULL DEFAULT 'SCHEDULED' CHECK (status IN ('SCHEDULED','DONE','CANCELLED')),
    created_by     uuid REFERENCES app_user(id),
    source         text NOT NULL CHECK (source IN ('USER','AGENT')),
    created_at     timestamptz NOT NULL DEFAULT now()
);

-- Every tool call the agent wants to make that changes data is stored here first
-- and only executed after a human with the right role approves it.
CREATE TABLE agent_action (
    id             bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id      uuid NOT NULL REFERENCES tenant(id),
    requested_by   uuid NOT NULL REFERENCES app_user(id),
    conversation_id uuid NOT NULL,
    tool           text NOT NULL,
    arguments      jsonb NOT NULL,
    rationale      text,
    status         text NOT NULL DEFAULT 'PROPOSED'
                   CHECK (status IN ('PROPOSED','APPROVED','REJECTED','EXECUTED','FAILED')),
    decided_by     uuid REFERENCES app_user(id),
    decided_at     timestamptz,
    result         jsonb,
    created_at     timestamptz NOT NULL DEFAULT now()
);

-- Declarative mapping from an OEM's raw payload to the canonical event.
-- New versions are activated at runtime; the normalizer hot-reloads them.
CREATE TABLE oem_mapping (
    id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    oem_code      text NOT NULL,
    version       integer NOT NULL,
    spec          jsonb NOT NULL,
    status        text NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT','ACTIVE','RETIRED')),
    proposed_by   text NOT NULL,              -- user id or 'agent'
    approved_by   uuid REFERENCES app_user(id),
    created_at    timestamptz NOT NULL DEFAULT now(),
    UNIQUE (oem_code, version)
);
CREATE UNIQUE INDEX oem_mapping_one_active ON oem_mapping(oem_code) WHERE status = 'ACTIVE';

-- ---------------------------------------------------------------------------
-- Compliance: audit trail and erasure
-- ---------------------------------------------------------------------------
-- Append-only and hash-chained: each row stores the hash of the previous row,
-- so any edit or deletion breaks the chain and is detectable (see 02_security.sql).
CREATE TABLE audit_log (
    id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ts            timestamptz NOT NULL DEFAULT clock_timestamp(),
    tenant_id     uuid,
    actor_id      text NOT NULL,
    actor_type    text NOT NULL CHECK (actor_type IN ('USER','AGENT','SERVICE')),
    action        text NOT NULL,
    resource_type text NOT NULL,
    resource_id   text,
    purpose       text,
    request_id    text,
    details       jsonb NOT NULL DEFAULT '{}',
    prev_hash     bytea,
    row_hash      bytea NOT NULL
);
CREATE INDEX audit_log_tenant_ts ON audit_log(tenant_id, ts DESC);

CREATE TABLE erasure_request (
    id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id     uuid NOT NULL REFERENCES tenant(id),
    subject_type  text NOT NULL CHECK (subject_type IN ('DRIVER')),
    subject_id    bigint NOT NULL,
    requested_by  uuid NOT NULL REFERENCES app_user(id),
    status        text NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','COMPLETED','FAILED')),
    requested_at  timestamptz NOT NULL DEFAULT now(),
    completed_at  timestamptz,
    receipt       jsonb
);

-- Ground truth for the failure-prediction model: breakdowns and repairs.
-- In production these come from workshop systems; here the simulator writes them.
CREATE TABLE maintenance_event (
    id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id     uuid NOT NULL REFERENCES tenant(id),    -- DENORM for RLS
    vehicle_id    bigint NOT NULL REFERENCES vehicle(id),
    occurred_at   timestamptz NOT NULL,
    kind          text NOT NULL CHECK (kind IN ('BREAKDOWN','REPAIR')),
    component     text NOT NULL,
    dtc           varchar(5) REFERENCES dtc_code(code),
    cost_usd      numeric(10,2) NOT NULL
);
CREATE INDEX maintenance_event_vehicle ON maintenance_event(vehicle_id, occurred_at);

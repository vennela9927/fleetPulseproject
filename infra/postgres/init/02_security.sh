#!/bin/bash
# Roles, row-level security and the tamper-evident audit log.
# Runs once on first container start (docker-entrypoint-initdb.d).
set -euo pipefail

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<SQL
-- ---------------------------------------------------------------------------
-- Roles
--   fleet_app      : used by the API. Subject to row-level security, so a bug in a
--                    query cannot return another tenant's rows.
--   fleet_service  : used by pipeline sinks and the ML job, which write across
--                    tenants. Bypasses RLS but only has the grants it needs.
-- ---------------------------------------------------------------------------
CREATE ROLE fleet_app     LOGIN PASSWORD '${APP_DB_PASSWORD}';
CREATE ROLE fleet_service LOGIN PASSWORD '${SERVICE_DB_PASSWORD}' BYPASSRLS;

GRANT USAGE ON SCHEMA public TO fleet_app, fleet_service;
GRANT SELECT ON ALL TABLES IN SCHEMA public TO fleet_app, fleet_service;
GRANT USAGE ON ALL SEQUENCES IN SCHEMA public TO fleet_app, fleet_service;

GRANT INSERT, UPDATE ON alert, service_booking, agent_action, oem_mapping,
                        erasure_request, driver, app_user TO fleet_app;
GRANT DELETE ON driver_assignment TO fleet_app;
GRANT INSERT ON audit_log TO fleet_app, fleet_service;

GRANT INSERT, UPDATE ON alert, risk_score, fault_signature, risk_model, trip, maintenance_event, oem_mapping TO fleet_service;
GRANT DELETE ON fault_signature TO fleet_service;   -- similarity signatures are rebuilt by every scoring run
GRANT INSERT ON feed_drift TO fleet_service;         -- written by the drift check
GRANT INSERT, UPDATE ON vehicle, fleet, depot, driver, driver_assignment, tenant,
                        subscription, app_user TO fleet_service;   -- seeding only

-- The audit log is append-only for everyone.
REVOKE UPDATE, DELETE, TRUNCATE ON audit_log FROM PUBLIC, fleet_app, fleet_service;

-- ---------------------------------------------------------------------------
-- Row-level security: the API sets  SET LOCAL app.tenant_id = '<uuid>'  at the
-- start of every transaction, taken from the verified JWT.
-- ---------------------------------------------------------------------------
CREATE FUNCTION current_tenant() RETURNS uuid
LANGUAGE sql STABLE AS \$\$
    SELECT nullif(current_setting('app.tenant_id', true), '')::uuid
\$\$;

DO \$\$
DECLARE t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['fleet','depot','vehicle','driver','alert','risk_score',
                             'service_booking','agent_action','subscription',
                             'erasure_request','app_user','maintenance_event']
    LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY tenant_isolation ON %I USING (tenant_id = current_tenant())
                        WITH CHECK (tenant_id = current_tenant())', t);
    END LOOP;
END \$\$;

-- Tables without their own tenant_id are scoped through vehicle, which is itself
-- filtered by RLS, so the subquery only ever sees the caller's vehicles.
ALTER TABLE trip ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON trip USING (vehicle_id IN (SELECT id FROM vehicle));
ALTER TABLE driver_assignment ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON driver_assignment USING (vehicle_id IN (SELECT id FROM vehicle));
ALTER TABLE fault_signature ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON fault_signature USING (vehicle_id IN (SELECT id FROM vehicle));

-- Audit rows: readable only within the tenant, insertable by anyone with the grant.
ALTER TABLE audit_log ENABLE ROW LEVEL SECURITY;
CREATE POLICY audit_read   ON audit_log FOR SELECT USING (tenant_id = current_tenant());
CREATE POLICY audit_insert ON audit_log FOR INSERT WITH CHECK (true);

-- ---------------------------------------------------------------------------
-- Hash-chained audit log
-- ---------------------------------------------------------------------------
CREATE FUNCTION audit_chain() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER AS \$\$
DECLARE prev bytea;
BEGIN
    -- Serialise writers so two concurrent inserts cannot both claim the same parent.
    PERFORM pg_advisory_xact_lock(7318);
    SELECT row_hash INTO prev FROM audit_log ORDER BY id DESC LIMIT 1;
    NEW.prev_hash := prev;
    NEW.row_hash  := digest(
        coalesce(encode(prev, 'hex'), '') || '|' || NEW.ts::text || '|' ||
        coalesce(NEW.tenant_id::text, '') || '|' || NEW.actor_id || '|' ||
        NEW.actor_type || '|' || NEW.action || '|' || NEW.resource_type || '|' ||
        coalesce(NEW.resource_id, '') || '|' || NEW.details::text, 'sha256');
    RETURN NEW;
END \$\$;

CREATE TRIGGER audit_chain BEFORE INSERT ON audit_log
    FOR EACH ROW EXECUTE FUNCTION audit_chain();

CREATE FUNCTION audit_immutable() RETURNS trigger
LANGUAGE plpgsql AS \$\$
BEGIN
    RAISE EXCEPTION 'audit_log is append-only';
END \$\$;

CREATE TRIGGER audit_no_update BEFORE UPDATE OR DELETE ON audit_log
    FOR EACH ROW EXECUTE FUNCTION audit_immutable();

-- Returns the id of the first row whose hash does not match, or NULL if intact.
CREATE FUNCTION audit_verify_chain() RETURNS bigint
LANGUAGE plpgsql SECURITY DEFINER AS \$\$
DECLARE r record; prev bytea := NULL; expected bytea;
BEGIN
    FOR r IN SELECT * FROM audit_log ORDER BY id LOOP
        expected := digest(
            coalesce(encode(prev, 'hex'), '') || '|' || r.ts::text || '|' ||
            coalesce(r.tenant_id::text, '') || '|' || r.actor_id || '|' ||
            r.actor_type || '|' || r.action || '|' || r.resource_type || '|' ||
            coalesce(r.resource_id, '') || '|' || r.details::text, 'sha256');
        IF r.row_hash IS DISTINCT FROM expected OR r.prev_hash IS DISTINCT FROM prev THEN
            RETURN r.id;
        END IF;
        prev := r.row_hash;
    END LOOP;
    RETURN NULL;
END \$\$;
GRANT EXECUTE ON FUNCTION audit_verify_chain() TO fleet_app;
SQL

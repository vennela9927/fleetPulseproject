-- Demo users. Their ids are the Keycloak subjects fixed in infra/keycloak/fleetpulse-realm.json,
-- so audit rows and approvals made through the API reference these rows.
INSERT INTO app_user (id, tenant_id, email) VALUES
    ('0a000000-0000-4000-8000-000000000001', '11111111-1111-1111-1111-111111111111', 'manager@acme.example.com'),
    ('0a000000-0000-4000-8000-000000000002', '11111111-1111-1111-1111-111111111111', 'viewer@acme.example.com'),
    ('0a000000-0000-4000-8000-000000000003', '22222222-2222-2222-2222-222222222222', 'manager@zenith.example.com')
ON CONFLICT (id) DO NOTHING;

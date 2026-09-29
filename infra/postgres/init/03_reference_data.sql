-- Reference data and the two demo tenants.
-- Vehicles, drivers and assignments (100K+) are bulk-loaded by `simulator seed`.

INSERT INTO subscription_plan (id, code, name, price_per_vehicle_month) VALUES
    (1, 'STARTER',    'Starter',    3.00),
    (2, 'GROWTH',     'Growth',     5.50),
    (3, 'ENTERPRISE', 'Enterprise', 8.00);

INSERT INTO tenant (id, name) VALUES
    ('11111111-1111-1111-1111-111111111111', 'Acme Logistics'),
    ('22222222-2222-2222-2222-222222222222', 'Zenith Rentals');

INSERT INTO subscription (tenant_id, plan_id, starts_at, status) VALUES
    ('11111111-1111-1111-1111-111111111111', 3, '2026-01-01', 'ACTIVE'),
    ('22222222-2222-2222-2222-222222222222', 2, '2026-03-01', 'ACTIVE');

-- One depot and one fleet per city. Acme operates in the south and west,
-- Zenith in the north and west.
-- service_bays: inspections each depot can take per day; small depots overflow to a nearby one.
INSERT INTO depot (tenant_id, name, lat, lon, radius_m, service_bays) VALUES
    ('11111111-1111-1111-1111-111111111111', 'Bengaluru Hub', 12.9716, 77.5946, 400, 80),
    ('11111111-1111-1111-1111-111111111111', 'Chennai Hub',   13.0827, 80.2707, 400, 60),
    ('11111111-1111-1111-1111-111111111111', 'Hyderabad Hub', 17.3850, 78.4867, 400, 70),
    ('11111111-1111-1111-1111-111111111111', 'Mumbai Hub',    19.0760, 72.8777, 400, 80),
    ('11111111-1111-1111-1111-111111111111', 'Pune Hub',      18.5204, 73.8567, 400, 45),
    ('22222222-2222-2222-2222-222222222222', 'Delhi Hub',     28.6139, 77.2090, 400, 80),
    ('22222222-2222-2222-2222-222222222222', 'Ahmedabad Hub', 23.0225, 72.5714, 400, 70),
    ('22222222-2222-2222-2222-222222222222', 'Surat Hub',     21.1702, 72.8311, 400, 50),
    ('22222222-2222-2222-2222-222222222222', 'Kolkata Hub',   22.5726, 88.3639, 400, 70),
    ('22222222-2222-2222-2222-222222222222', 'Jaipur Hub',    26.9124, 75.7873, 400, 45);

INSERT INTO fleet (tenant_id, name, home_depot_id)
SELECT tenant_id, replace(name, 'Hub', 'Fleet'), id FROM depot ORDER BY id;

-- Fictional manufacturers. WMI codes avoid I, O and Q as VINs require.
-- DRACO has vehicles in the fleet but no active mapping: it is onboarded live in the demo.
INSERT INTO oem (id, code, name, wmi) VALUES
    (1, 'AURORA',   'Aurora Motors',     'AUR'),
    (2, 'BOREALIS', 'Borealis Vehicles', 'BRL'),
    (3, 'CYGNUS',   'Cygnus Electric',   'CYG'),
    (4, 'DRACO',    'Draco Trucks',      'DRC');

INSERT INTO vehicle_model (id, oem_id, name, powertrain, battery_kwh, tank_litres) VALUES
    (1, 1, 'Metro',   'ICE',    NULL, 45),
    (2, 1, 'Cargo',   'ICE',    NULL, 70),
    (3, 1, 'Volt',    'EV',     60,   NULL),
    (4, 2, 'Hauler',  'ICE',    NULL, 80),
    (5, 2, 'Hybrid',  'HYBRID', 1.5,  40),
    (6, 3, 'E-Van',   'EV',     75,   NULL),
    (7, 3, 'E-City',  'EV',     40,   NULL),
    (8, 4, 'Titan',   'ICE',    NULL, 120);

INSERT INTO dtc_code (code, description, system, severity, avg_repair_cost_usd) VALUES
    ('P0300', 'Random/multiple cylinder misfire detected',          'Ignition',     4,  650),
    ('P0301', 'Cylinder 1 misfire detected',                        'Ignition',     3,  420),
    ('P0171', 'System too lean (bank 1)',                           'Fuel',         2,  300),
    ('P0217', 'Engine overheat condition',                          'Cooling',      5, 1800),
    ('P0128', 'Coolant thermostat below regulating temperature',    'Cooling',      2,  250),
    ('P0420', 'Catalyst system efficiency below threshold',         'Emissions',    2,  900),
    ('P0562', 'System voltage low',                                 'Electrical',   3,  220),
    ('P0335', 'Crankshaft position sensor A circuit',               'Engine',       4,  380),
    ('P0700', 'Transmission control system malfunction',            'Transmission', 4, 2400),
    ('P0741', 'Torque converter clutch circuit stuck off',          'Transmission', 3, 1500),
    ('P0A80', 'Replace hybrid battery pack',                        'EV Battery',   5, 4200),
    ('P0AFA', 'Hybrid/EV battery system voltage low',               'EV Battery',   4, 1200),
    ('P0AA6', 'Hybrid/EV battery voltage isolation fault',          'EV Battery',   5, 2600),
    ('C0035', 'Left front wheel speed sensor circuit',              'Brakes',       3,  280),
    ('C0265', 'ABS motor relay circuit',                            'Brakes',       4,  900),
    ('U0100', 'Lost communication with ECM/PCM',                    'Network',      4,  700),
    ('U0121', 'Lost communication with ABS control module',         'Network',      3,  500);

INSERT INTO alert_rule (code, description, severity) VALUES
    ('ENGINE_OVERHEAT',     'Coolant above 110 C for 30 seconds',                    'CRITICAL'),
    ('CRITICAL_DTC',        'Fault code with severity 5 reported',                   'CRITICAL'),
    ('LOW_12V_BATTERY',     '12 V battery below 11.8 V with ignition off',           'WARNING'),
    ('HARSH_DRIVING',       'Five or more harsh braking/acceleration events in 10 minutes', 'WARNING'),
    ('EV_LOW_SOC',          'EV state of charge below 10% while driving',            'WARNING'),
    ('EXCESSIVE_IDLING',    'Engine idling for more than 10 minutes',                'INFO'),
    ('BATTERY_DEGRADATION', 'EV battery state of health dropping faster than normal','WARNING'),
    ('HIGH_FAILURE_RISK',   'Predicted failure within 7 days above threshold',       'CRITICAL'),
    ('UNAPPROVED_CLUSTER',  'Group of vehicles parked together away from any depot', 'WARNING');

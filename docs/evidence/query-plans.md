# Query plans: before and after

Measured 2026-10-01 08:43 UTC on the local Docker stack (laptop, Docker Desktop 8 GB / 12 CPUs) **while the simulator was streaming 100K vehicles**, so absolute times include that load. Every query runs as the API's role `fleet_app` with row-level security and tenant Acme Logistics (50,000 vehicles), as the API runs it. "Before" variants drop the index inside a transaction that is rolled back.
Data: 100,000 vehicles, 98,000 risk scores, ~42,000 alerts. Script: `docs/evidence/bench_queries.py`.

## Q1 at-risk ranking, before (no risk_score_ranked index)

Median execution time over 5 runs: **1880.79 ms** (runs: 1880.8, 1999.6, 1846.2, 3619.5, 1364.8)

```sql
WITH a AS (SELECT version, (SELECT max(scored_at) FROM risk_score WHERE model_version = m.version) AS latest
           FROM risk_model m WHERE is_active)
SELECT r.vehicle_id, trim(v.vin), f.name, o.name, vm.name, r.failure_prob_7d, r.predicted_component,
       r.est_cost_avoided_usd, r.top_factors, r.scored_at
FROM risk_score r JOIN vehicle v ON v.id = r.vehicle_id JOIN fleet f ON f.id = v.fleet_id
     JOIN vehicle_model vm ON vm.id = v.model_id JOIN oem o ON o.id = vm.oem_id
WHERE (r.model_version, r.scored_at) = (SELECT version, latest FROM a) AND r.failure_prob_7d >= 0
ORDER BY r.failure_prob_7d DESC, r.vehicle_id LIMIT 51
```

Plan of the last run:

```
Limit  (cost=3428511.00..3428511.13 rows=51 width=540) (actual time=1301.692..1301.716 rows=51 loops=1)
  Buffers: shared hit=13314
  InitPlan 2 (returns $1,$2)
    ->  Seq Scan on risk_model m  (cost=0.00..3414661.00 rows=400 width=40) (actual time=112.037..112.040 rows=1 loops=1)
          Filter: is_active
          Buffers: shared hit=5965
          SubPlan 1
            ->  Aggregate  (cost=8536.60..8536.61 rows=1 width=8) (actual time=111.996..111.997 rows=1 loops=1)
                  Buffers: shared hit=5964
                  ->  Seq Scan on risk_score  (cost=0.00..8414.00 rows=49039 width=8) (actual time=0.014..108.051 rows=49000 loops=1)
                        Filter: ((model_version = m.version) AND (tenant_id = (NULLIF(current_setting('app.tenant_id'::text, true), ''::text))::uuid))
                        Rows Removed by Filter: 49000
                        Buffers: shared hit=5964
  ->  Sort  (cost=13850.00..13906.04 rows=22416 width=540) (actual time=417.919..417.932 rows=51 loops=1)
        Sort Key: r.failure_prob_7d DESC, r.vehicle_id
        Sort Method: top-N heapsort  Memory: 87kB
        Buffers: shared hit=13314
        ->  Hash Join  (cost=3773.92..13102.16 rows=22416 width=540) (actual time=148.614..381.272 rows=49000 loops=1)
              Hash Cond: (vm.oem_id = o.id)
              Buffers: shared hit=13308
              ->  Hash Join  (cost=3747.27..12904.25 rows=22416 width=496) (actual time=148.529..339.130 rows=49000 loops=1)
                    Hash Cond: (v.model_id = vm.id)
                    Buffers: shared hit=13307
                    ->  Hash Join  (cost=3722.64..12820.41 rows=22416 width=464) (actual time=148.489..324.941 rows=49000 loops=1)
                          Hash Cond: (v.fleet_id = f.id)
                          Buffers: shared hit=13306
                          ->  Hash Join  (cost=3709.90..12742.63 rows=24658 width=440) (actual time=148.370..310.676 rows=49000 loops=1)
                                Hash Cond: (r.vehicle_id = v.id)
                                Buffers: shared hit=13304
                                ->  Seq Scan on risk_score r  (cost=0.00..8904.00 rows=49039 width=412) (actual time=112.060..233.363 rows=49000 loops=1)
                                      Filter: ((failure_prob_7d >= '0'::double precision) AND (model_version = $1) AND (scored_at = $2) AND (tenant_id = (NULLIF(current_setting('app.tenant_id'::text, true), ''::text))::uuid))
                                      Rows Removed by Filter: 49000
                                      Buffers: shared hit=11929
                                ->  Hash  (cost=3081.36..3081.36 rows=50283 width=36) (actual time=35.860..35.862 rows=50000 loops=1)
                                      Buckets: 65536  Batches: 1  Memory Usage: 4126kB
                                      Buffers: shared hit=1375
                                      ->  Bitmap Heap Scan on vehicle v  (cost=558.00..3081.36 rows=50283 width=36) (actual time=2.191..14.693 rows=50000 loops=1)
                                            Recheck Cond: (tenant_id = (NULLIF(current_setting('app.tenant_id'::text, true), ''::text))::uuid)
                                            Heap Blocks: exact=1334
                                            Buffers: shared hit=1375
                                            ->  Bitmap Index Scan on vehicle_tenant_fleet  (cost=0.00..545.42 rows=50283 width=0) (actual time=1.914..1.914 rows=50000 loops=1)
                                                  Index Cond: (tenant_id = (NULLIF(current_setting('app.tenant_id'::text, true), ''::text))::uuid)
                                                  Buffers: shared hit=41
                          ->  Hash  (cost=12.69..12.69 rows=4 width=40) (actual time=0.076..0.078 rows=5 loops=1)
                                Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                Buffers: shared hit=2
                                ->  Bitmap Heap Scan on fleet f  (cost=4.19..12.69 rows=4 width=40) (actual time=0.064..0.067 rows=5 loops=1)
                                      Recheck Cond: (tenant_id = (NULLIF(current_setting('app.tenant_id'::text, true), ''::text))::uuid)
                                      Heap Blocks: exact=1
                                      Buffers: shared hit=2
                                      ->  Bitmap Index Scan on fleet_tenant_id_name_key  (cost=0.00..4.19 rows=4 width=0) (actual time=0.025..0.026 rows=5 loops=1)
                                            Index Cond: (tenant_id = (NULLIF(current_setting('app.tenant_id'::text, true), ''::text))::uuid)
                                            Buffers: shared hit=1
                    ->  Hash  (cost=16.50..16.50 rows=650 width=36) (actual time=0.022..0.022 rows=8 loops=1)
                          Buckets: 1024  Batches: 1  Memory Usage: 9kB
                          Buffers: shared hit=1
                          ->  Seq Scan on vehicle_model vm  (cost=0.00..16.50 rows=650 width=36) (actual time=0.012..0.013 rows=8 loops=1)
                                Buffers: shared hit=1
              ->  Hash  (cost=17.40..17.40 rows=740 width=34) (actual time=0.056..0.057 rows=4 loops=1)
                    Buckets: 1024  Batches: 1  Memory Usage: 9kB
                    Buffers: shared hit=1
                    ->  Seq Scan on oem o  (cost=0.00..17.40 rows=740 width=34) (actual time=0.044..0.045 rows=4 loops=1)
                          Buffers: shared hit=1
Planning:
  Buffers: shared hit=570
Planning Time: 4.823 ms
JIT:
  Functions: 55
  Options: Inlining true, Optimization true, Expressions true, Deforming true
  Timing: Generation 6.865 ms, Inlining 196.873 ms, Optimization 422.493 ms, Emission 264.608 ms, Total 890.841 ms
Execution Time: 1364.803 ms
```

## Q1 at-risk ranking, after

Median execution time over 5 runs: **2.29 ms** (runs: 3.3, 2.1, 2.3, 2.1, 3.0)

```sql
WITH a AS (SELECT version, (SELECT max(scored_at) FROM risk_score WHERE model_version = m.version) AS latest
           FROM risk_model m WHERE is_active)
SELECT r.vehicle_id, trim(v.vin), f.name, o.name, vm.name, r.failure_prob_7d, r.predicted_component,
       r.est_cost_avoided_usd, r.top_factors, r.scored_at
FROM risk_score r JOIN vehicle v ON v.id = r.vehicle_id JOIN fleet f ON f.id = v.fleet_id
     JOIN vehicle_model vm ON vm.id = v.model_id JOIN oem o ON o.id = vm.oem_id
WHERE (r.model_version, r.scored_at) = (SELECT version, latest FROM a) AND r.failure_prob_7d >= 0
ORDER BY r.failure_prob_7d DESC, r.vehicle_id LIMIT 51
```

Plan of the last run:

```
Limit  (cost=220.84..342.42 rows=51 width=540) (actual time=0.666..2.529 rows=51 loops=1)
  Buffers: shared hit=332
  InitPlan 3 (returns $2,$3)
    ->  Seq Scan on risk_model m  (cost=0.00..219.65 rows=400 width=40) (actual time=0.253..0.315 rows=1 loops=1)
          Filter: is_active
          Buffers: shared hit=5
          SubPlan 2
            ->  Result  (cost=0.49..0.50 rows=1 width=8) (actual time=0.225..0.227 rows=1 loops=1)
                  Buffers: shared hit=4
                  InitPlan 1 (returns $1)
                    ->  Limit  (cost=0.43..0.49 rows=1 width=8) (actual time=0.217..0.219 rows=1 loops=1)
                          Buffers: shared hit=4
                          ->  Index Only Scan using risk_score_ranked on risk_score  (cost=0.43..3268.10 rows=49039 width=8) (actual time=0.213..0.213 rows=1 loops=1)
                                Index Cond: ((tenant_id = (NULLIF(current_setting('app.tenant_id'::text, true), ''::text))::uuid) AND (model_version = m.version) AND (scored_at IS NOT NULL))
                                Heap Fetches: 0
                                Buffers: shared hit=4
  ->  Nested Loop  (cost=1.19..53436.25 rows=22416 width=540) (actual time=0.665..2.514 rows=51 loops=1)
        Buffers: shared hit=332
        ->  Nested Loop  (cost=1.04..47916.89 rows=22416 width=496) (actual time=0.615..2.297 rows=51 loops=1)
              Buffers: shared hit=230
              ->  Nested Loop  (cost=0.88..47355.48 rows=22416 width=464) (actual time=0.589..2.200 rows=51 loops=1)
                    Buffers: shared hit=222
                    ->  Nested Loop  (cost=0.72..46737.30 rows=24658 width=440) (actual time=0.506..2.031 rows=51 loops=1)
                          Buffers: shared hit=212
                          ->  Index Scan using risk_score_ranked on risk_score r  (cost=0.43..24250.22 rows=49039 width=412) (actual time=0.437..1.065 rows=51 loops=1)
                                Index Cond: ((tenant_id = (NULLIF(current_setting('app.tenant_id'::text, true), ''::text))::uuid) AND (model_version = $2) AND (scored_at = $3) AND (failure_prob_7d >= '0'::double precision))
                                Buffers: shared hit=59
                          ->  Index Scan using vehicle_pkey on vehicle v  (cost=0.29..0.46 rows=1 width=36) (actual time=0.018..0.018 rows=1 loops=51)
                                Index Cond: (id = r.vehicle_id)
                                Filter: (tenant_id = (NULLIF(current_setting('app.tenant_id'::text, true), ''::text))::uuid)
                                Buffers: shared hit=153
                    ->  Memoize  (cost=0.16..0.19 rows=1 width=40) (actual time=0.003..0.003 rows=1 loops=51)
                          Cache Key: v.fleet_id
                          Cache Mode: logical
                          Hits: 46  Misses: 5  Evictions: 0  Overflows: 0  Memory Usage: 1kB
                          Buffers: shared hit=10
                          ->  Index Scan using fleet_pkey on fleet f  (cost=0.15..0.18 rows=1 width=40) (actual time=0.018..0.018 rows=1 loops=5)
                                Index Cond: (id = v.fleet_id)
                                Filter: (tenant_id = (NULLIF(current_setting('app.tenant_id'::text, true), ''::text))::uuid)
                                Buffers: shared hit=10
              ->  Memoize  (cost=0.16..0.18 rows=1 width=36) (actual time=0.001..0.001 rows=1 loops=51)
                    Cache Key: v.model_id
                    Cache Mode: logical
                    Hits: 47  Misses: 4  Evictions: 0  Overflows: 0  Memory Usage: 1kB
                    Buffers: shared hit=8
                    ->  Index Scan using vehicle_model_pkey on vehicle_model vm  (cost=0.15..0.17 rows=1 width=36) (actual time=0.008..0.008 rows=1 loops=4)
                          Index Cond: (id = v.model_id)
                          Buffers: shared hit=8
        ->  Index Scan using oem_pkey on oem o  (cost=0.15..0.24 rows=1 width=34) (actual time=0.002..0.002 rows=1 loops=51)
              Index Cond: (id = vm.oem_id)
              Buffers: shared hit=102
Planning:
  Buffers: shared hit=624
Planning Time: 5.131 ms
Execution Time: 3.021 ms
```

## Q2 vehicle list, page 900, before (OFFSET)

Median execution time over 5 runs: **139.68 ms** (runs: 139.7, 137.8, 165.5, 119.8, 251.8)

```sql
SELECT id, vin, fleet_id, model_id FROM vehicle ORDER BY id LIMIT 51 OFFSET 44950
```

Plan of the last run:

```
Limit  (cost=4692.59..4697.91 rows=51 width=36) (actual time=248.064..251.542 rows=51 loops=1)
  Buffers: shared hit=1448
  ->  Index Scan using vehicle_pkey on vehicle  (cost=0.29..5249.29 rows=50283 width=36) (actual time=0.046..246.787 rows=45001 loops=1)
        Filter: (tenant_id = (NULLIF(current_setting('app.tenant_id'::text, true), ''::text))::uuid)
        Rows Removed by Filter: 45000
        Buffers: shared hit=1448
Planning:
  Buffers: shared hit=175
Planning Time: 1.425 ms
Execution Time: 251.816 ms
```

## Q2 vehicle list, page 900, after (keyset cursor)

Median execution time over 5 runs: **0.56 ms** (runs: 0.9, 0.6, 0.5, 0.5, 0.6)

```sql
SELECT id, vin, fleet_id, model_id FROM vehicle WHERE id > 89901 ORDER BY id LIMIT 51
```

Plan of the last run:

```
Limit  (cost=0.29..5.90 rows=51 width=36) (actual time=0.097..0.354 rows=51 loops=1)
  Buffers: shared hit=8
  ->  Index Scan using vehicle_pkey on vehicle  (cost=0.29..559.50 rows=5082 width=36) (actual time=0.095..0.343 rows=51 loops=1)
        Index Cond: (id > 89901)
        Filter: (tenant_id = (NULLIF(current_setting('app.tenant_id'::text, true), ''::text))::uuid)
        Rows Removed by Filter: 50
        Buffers: shared hit=8
Planning:
  Buffers: shared hit=176
Planning Time: 1.480 ms
Execution Time: 0.563 ms
```

## Q3 latest alerts, before (no index)

Median execution time over 5 runs: **228.22 ms** (runs: 241.5, 228.2, 211.1, 219.5, 342.7)

```sql
SELECT a.id, a.vehicle_id, trim(v.vin), a.rule_code, r.severity, a.status, a.opened_at, a.detected_at, a.details
FROM alert a JOIN alert_rule r ON r.code = a.rule_code JOIN vehicle v ON v.id = a.vehicle_id
ORDER BY a.opened_at DESC, a.id DESC LIMIT 51
```

Plan of the last run:

```
Limit  (cost=7443.66..7443.79 rows=51 width=385) (actual time=342.043..342.064 rows=51 loops=1)
  Buffers: shared hit=3650
  ->  Sort  (cost=7443.66..7470.20 rows=10614 width=385) (actual time=342.040..342.054 rows=51 loops=1)
        Sort Key: a.opened_at DESC, a.id DESC
        Sort Method: top-N heapsort  Memory: 75kB
        Buffers: shared hit=3650
        ->  Hash Join  (cost=3734.53..7089.56 rows=10614 width=385) (actual time=117.265..309.650 rows=21148 loops=1)
              Hash Cond: (a.rule_code = r.code)
              Buffers: shared hit=3644
              ->  Hash Join  (cost=3709.90..6983.82 rows=10614 width=339) (actual time=117.123..266.166 rows=21148 loops=1)
                    Hash Cond: (a.vehicle_id = v.id)
                    Buffers: shared hit=3643
                    ->  Seq Scan on alert a  (cost=0.00..3218.51 rows=21108 width=321) (actual time=0.020..104.075 rows=21148 loops=1)
                          Filter: (tenant_id = (NULLIF(current_setting('app.tenant_id'::text, true), ''::text))::uuid)
                          Rows Removed by Filter: 21097
                          Buffers: shared hit=2268
                    ->  Hash  (cost=3081.36..3081.36 rows=50283 width=26) (actual time=105.565..105.567 rows=50000 loops=1)
                          Buckets: 65536  Batches: 1  Memory Usage: 3637kB
                          Buffers: shared hit=1375
                          ->  Bitmap Heap Scan on vehicle v  (cost=558.00..3081.36 rows=50283 width=26) (actual time=3.255..43.441 rows=50000 loops=1)
                                Recheck Cond: (tenant_id = (NULLIF(current_setting('app.tenant_id'::text, true), ''::text))::uuid)
                                Heap Blocks: exact=1334
                                Buffers: shared hit=1375
                                ->  Bitmap Index Scan on vehicle_tenant_fleet  (cost=0.00..545.42 rows=50283 width=0) (actual time=2.933..2.933 rows=50000 loops=1)
                                      Index Cond: (tenant_id = (NULLIF(current_setting('app.tenant_id'::text, true), ''::text))::uuid)
                                      Buffers: shared hit=41
              ->  Hash  (cost=16.50..16.50 rows=650 width=64) (actual time=0.075..0.076 rows=10 loops=1)
                    Buckets: 1024  Batches: 1  Memory Usage: 9kB
                    Buffers: shared hit=1
                    ->  Seq Scan on alert_rule r  (cost=0.00..16.50 rows=650 width=64) (actual time=0.052..0.055 rows=10 loops=1)
                          Buffers: shared hit=1
Planning:
  Buffers: shared hit=398
Planning Time: 3.768 ms
Execution Time: 342.700 ms
```

## Q3 latest alerts, after (alert_tenant_opened index)

Median execution time over 5 runs: **9.11 ms** (runs: 3.1, 4.0, 9.1, 12.4, 11.0)

```sql
SELECT a.id, a.vehicle_id, trim(v.vin), a.rule_code, r.severity, a.status, a.opened_at, a.detected_at, a.details
FROM alert a JOIN alert_rule r ON r.code = a.rule_code JOIN vehicle v ON v.id = a.vehicle_id
ORDER BY a.opened_at DESC, a.id DESC LIMIT 51
```

Plan of the last run:

```
Limit  (cost=0.89..99.17 rows=51 width=385) (actual time=0.278..8.401 rows=51 loops=1)
  Buffers: shared hit=209
  ->  Nested Loop  (cost=0.89..20456.10 rows=10614 width=385) (actual time=0.277..8.383 rows=51 loops=1)
        Buffers: shared hit=209
        ->  Nested Loop  (cost=0.73..20136.40 rows=10614 width=339) (actual time=0.189..8.063 rows=51 loops=1)
              Buffers: shared hit=201
              ->  Index Scan using alert_tenant_opened on alert a  (cost=0.42..8817.07 rows=21108 width=321) (actual time=0.109..0.436 rows=51 loops=1)
                    Index Cond: (tenant_id = (NULLIF(current_setting('app.tenant_id'::text, true), ''::text))::uuid)
                    Buffers: shared hit=48
              ->  Memoize  (cost=0.30..0.65 rows=1 width=26) (actual time=0.063..0.063 rows=1 loops=51)
                    Cache Key: a.vehicle_id
                    Cache Mode: logical
                    Hits: 0  Misses: 51  Evictions: 0  Overflows: 0  Memory Usage: 7kB
                    Buffers: shared hit=153
                    ->  Index Scan using vehicle_pkey on vehicle v  (cost=0.29..0.64 rows=1 width=26) (actual time=0.060..0.060 rows=1 loops=51)
                          Index Cond: (id = a.vehicle_id)
                          Filter: (tenant_id = (NULLIF(current_setting('app.tenant_id'::text, true), ''::text))::uuid)
                          Buffers: shared hit=153
        ->  Memoize  (cost=0.16..0.18 rows=1 width=64) (actual time=0.003..0.003 rows=1 loops=51)
              Cache Key: a.rule_code
              Cache Mode: logical
              Hits: 47  Misses: 4  Evictions: 0  Overflows: 0  Memory Usage: 1kB
              Buffers: shared hit=8
              ->  Index Scan using alert_rule_pkey on alert_rule r  (cost=0.15..0.17 rows=1 width=64) (actual time=0.024..0.024 rows=1 loops=4)
                    Index Cond: (code = a.rule_code)
                    Buffers: shared hit=8
Planning:
  Buffers: shared hit=441
Planning Time: 12.996 ms
Execution Time: 10.963 ms
```

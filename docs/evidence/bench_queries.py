"""Measures the three slowest API queries before and after their optimisation.

Run from the repository root with the compose stack up:  python docs/evidence/bench_queries.py
Writes docs/evidence/query-plans.md. "Before" variants drop the index inside a transaction
that is rolled back, so the database is never changed.
"""
import datetime
import re
import statistics
import subprocess
import sys

TENANT = "11111111-1111-1111-1111-111111111111"   # Acme Logistics, 50,000 vehicles
RUNS = 5
SKIP = {"BEGIN", "SET", "ROLLBACK", "DROP INDEX", "CREATE INDEX"}

RISK = """WITH a AS (SELECT version, (SELECT max(scored_at) FROM risk_score WHERE model_version = m.version) AS latest
           FROM risk_model m WHERE is_active)
SELECT r.vehicle_id, trim(v.vin), f.name, o.name, vm.name, r.failure_prob_7d, r.predicted_component,
       r.est_cost_avoided_usd, r.top_factors, r.scored_at
FROM risk_score r JOIN vehicle v ON v.id = r.vehicle_id JOIN fleet f ON f.id = v.fleet_id
     JOIN vehicle_model vm ON vm.id = v.model_id JOIN oem o ON o.id = vm.oem_id
WHERE (r.model_version, r.scored_at) = (SELECT version, latest FROM a) AND r.failure_prob_7d >= 0
ORDER BY r.failure_prob_7d DESC, r.vehicle_id LIMIT 51"""

ALERTS = """SELECT a.id, a.vehicle_id, trim(v.vin), a.rule_code, r.severity, a.status, a.opened_at, a.detected_at, a.details
FROM alert a JOIN alert_rule r ON r.code = a.rule_code JOIN vehicle v ON v.id = a.vehicle_id
ORDER BY a.opened_at DESC, a.id DESC LIMIT 51"""

CASES = [
    ("Q1 at-risk ranking, before (no risk_score_ranked index)", "DROP INDEX risk_score_ranked;", RISK),
    ("Q1 at-risk ranking, after", "", RISK),
    ("Q2 vehicle list, page 900, before (OFFSET)", "",
     "SELECT id, vin, fleet_id, model_id FROM vehicle ORDER BY id LIMIT 51 OFFSET 44950"),
    ("Q2 vehicle list, page 900, after (keyset cursor)", "",
     "SELECT id, vin, fleet_id, model_id FROM vehicle WHERE id > 89901 ORDER BY id LIMIT 51"),
    ("Q3 latest alerts, before (no index)", "DROP INDEX alert_tenant_opened;", ALERTS),
    ("Q3 latest alerts, after (alert_tenant_opened index)", "", ALERTS),
]


def psql(sql: str) -> str:
    out = subprocess.run(["docker", "compose", "exec", "-T", "postgres", "psql", "-U", "fleet_admin", "-d", "fleet",
                          "-tA", "-v", "ON_ERROR_STOP=1"], input=sql, capture_output=True, text=True, encoding="utf8")
    if out.returncode:
        sys.exit(out.stderr)
    return out.stdout


def bench(name: str, setup: str, query: str) -> str:
    times, plan = [], ""
    for _ in range(RUNS):
        plan = psql(f"BEGIN;\n{setup}\nSET LOCAL ROLE fleet_app;\n"
                    f"SELECT set_config('app.tenant_id', '{TENANT}', true);\n"
                    f"EXPLAIN (ANALYZE, BUFFERS) {query};\nROLLBACK;\n")
        times.append(float(re.search(r"Execution Time: ([\d.]+) ms", plan).group(1)))
    median = statistics.median(times)
    print(f"{name}: median {median:.2f} ms")
    body = [line for line in plan.splitlines() if line.strip() and line not in SKIP and not line.startswith(TENANT)]
    runs = ", ".join(f"{t:.1f}" for t in times)
    return (f"## {name}\n\nMedian execution time over {RUNS} runs: **{median:.2f} ms** (runs: {runs})\n\n"
            f"```sql\n{query.strip()}\n```\n\nPlan of the last run:\n\n```\n" + "\n".join(body) + "\n```\n")


if __name__ == "__main__":
    sections = [bench(*case) for case in CASES]
    now = datetime.datetime.now(datetime.timezone.utc)
    header = (
        "# Query plans: before and after\n\n"
        f"Measured {now:%Y-%m-%d %H:%M} UTC on the local Docker stack (laptop, Docker Desktop 8 GB / 12 CPUs) "
        "**while the simulator was streaming 100K vehicles**, so absolute times include that load. Every query "
        "runs as the API's role `fleet_app` with row-level security and tenant Acme Logistics (50,000 vehicles), "
        "as the API runs it. \"Before\" variants drop the index inside a transaction that is rolled back.\n"
        "Data: 100,000 vehicles, 98,000 risk scores, ~42,000 alerts. Script: `docs/evidence/bench_queries.py`.\n\n")
    with open("docs/evidence/query-plans.md", "w", encoding="utf8") as f:
        f.write(header + "\n".join(sections))

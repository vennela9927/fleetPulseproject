"""Turns risk scores into a workshop plan and a parts forecast.

Plan: every vehicle whose expected saving pays for an inspection (probability x cost avoided >
inspection cost) gets a bay, most valuable first, in its home depot or a nearby one, within each
depot's daily bays. Vehicles that are very likely to fail must go on the first day; if no nearby bay
is free then, they are reported as too risky to wait rather than silently pushed back.

Parts: the calibrated probabilities add up to the expected number of failures per part and depot,
with a range, so stores can order ahead.
"""

import math
from dataclasses import dataclass, field
from datetime import date, datetime, time, timedelta, timezone
from typing import Any

# All depots in this deployment are in India. A per-depot timezone column would replace this.
DEPOT_TZ = timezone(timedelta(hours=5, minutes=30), "IST")
DROP_OFF = time(9, 0)
URGENT_PROBABILITY = 0.8        # warnings arrive a median 2.7 days ahead: these go tomorrow
MAX_DETOUR_KM = 350             # further than this is not "nearby" for an overflow booking
Z_90 = 1.645


@dataclass(frozen=True)
class Candidate:
    vehicle_id: int
    vin: str
    fleet: str
    home_depot_id: int
    probability: float
    likely_part: str | None
    saving_usd: float

    def expected_value(self, inspection_cost: float) -> float:
        return self.probability * self.saving_usd - inspection_cost


@dataclass(frozen=True)
class Depot:
    id: int
    name: str
    lat: float
    lon: float
    bays: int


@dataclass
class Plan:
    inspection_cost: float
    days: list[date]
    booked: list[dict[str, Any]] = field(default_factory=list)
    too_risky_to_wait: list[dict[str, Any]] = field(default_factory=list)
    waiting: list[dict[str, Any]] = field(default_factory=list)
    not_worth_inspecting: int = 0


def first_plan_day(now: datetime) -> date:
    return now.astimezone(DEPOT_TZ).date() + timedelta(days=1)


def slot_time(day: date) -> datetime:
    return datetime.combine(day, DROP_OFF, DEPOT_TZ)


def distance_km(a: Depot, b: Depot) -> float:
    """Great-circle distance between two depots."""
    p1, p2 = math.radians(a.lat), math.radians(b.lat)
    dp, dl = p2 - p1, math.radians(b.lon - a.lon)
    h = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * 6371 * math.asin(math.sqrt(h))


def _entry(c: Candidate, inspection_cost: float) -> dict[str, Any]:
    return {"vehicle_id": c.vehicle_id, "vin": c.vin, "fleet": c.fleet, "probability": round(c.probability, 3),
            "likely_part": c.likely_part, "saving_usd": round(c.saving_usd),
            "expected_value_usd": round(c.expected_value(inspection_cost)),
            "urgent": c.probability >= URGENT_PROBABILITY}


def plan(candidates: list[Candidate], depots: list[Depot], already_booked: dict[tuple[int, date], int],
         start: date, days: int, inspection_cost: float) -> Plan:
    """Greedy by value: urgent vehicles first, then the rest by expected saving, each into the nearest
    depot and earliest day with a free bay. Simple to explain to a depot manager. It is not always the
    maximum total: an exact assignment could move an earlier vehicle to a neighbouring depot to make
    room for a later one. That only matters when neighbouring depots are both full."""
    by_id = {d.id: d for d in depots}
    horizon = [start + timedelta(days=i) for i in range(days)]
    free = {(d.id, day): max(0, d.bays - already_booked.get((d.id, day), 0)) for d in depots for day in horizon}
    nearby = {d.id: sorted((o for o in depots if distance_km(d, o) <= MAX_DETOUR_KM),
                           key=lambda o, d=d: (o.id != d.id, distance_km(d, o))) for d in depots}

    result = Plan(inspection_cost=inspection_cost, days=horizon)
    worth = []
    for c in candidates:
        if c.expected_value(inspection_cost) > 0 and c.home_depot_id in by_id:
            worth.append(c)
        else:
            result.not_worth_inspecting += 1
    worth.sort(key=lambda c: (c.probability < URGENT_PROBABILITY, -c.expected_value(inspection_cost), c.vehicle_id))

    for c in worth:
        options = nearby[c.home_depot_id]
        if c.probability >= URGENT_PROBABILITY:
            slots = [(d, horizon[0]) for d in options]                         # tomorrow, nearest free depot
        else:
            slots = [(d, day) for d in options for day in horizon]             # home depot first, earliest day
        slot = next(((d, day) for d, day in slots if free[(d.id, day)] > 0), None)
        entry = _entry(c, inspection_cost)
        if slot is None:
            (result.too_risky_to_wait if entry["urgent"] else result.waiting).append(entry)
            continue
        d, day = slot
        free[(d.id, day)] -= 1
        home = by_id[c.home_depot_id]
        result.booked.append({**entry, "depot_id": d.id, "depot": d.name, "date": day.isoformat(),
                              "moved_from": None if d.id == home.id else home.name,
                              "distance_km": None if d.id == home.id else round(distance_km(home, d))})
    return result


def plan_summary(p: Plan, depots: list[Depot], already_booked: dict[tuple[int, date], int]) -> dict[str, Any]:
    """The plan as the API returns it: per depot and day, plus what could not be scheduled."""
    grid = []
    for d in depots:
        per_day = []
        for day in p.days:
            items = [b for b in p.booked if b["depot_id"] == d.id and b["date"] == day.isoformat()]
            per_day.append({"date": day.isoformat(), "bays": d.bays,
                            "already_booked": already_booked.get((d.id, day), 0), "items": items})
        grid.append({"depot_id": d.id, "depot": d.name, "bays_per_day": d.bays, "days": per_day})
    return {
        "inspection_cost_usd": p.inspection_cost,
        "days": [day.isoformat() for day in p.days],
        "summary": {"scheduled": len(p.booked),
                    "urgent_scheduled": sum(1 for b in p.booked if b["urgent"]),
                    "expected_net_saving_usd": round(sum(b["expected_value_usd"] for b in p.booked)),
                    "too_risky_to_wait": len(p.too_risky_to_wait),
                    "waiting_for_a_bay": len(p.waiting),
                    "value_waiting_usd": round(sum(w["expected_value_usd"] for w in p.waiting)),
                    "not_worth_inspecting": p.not_worth_inspecting},
        "depots": grid,
        "too_risky_to_wait": p.too_risky_to_wait,
        "waiting": p.waiting[:20],
    }


MIN_INSPECTIONS = 20


def model_health(rows: list[dict[str, Any]]) -> dict[str, Any]:
    """The model graded by the workshop. Each completed inspection has the probability the model
    gave when it was booked and whether the mechanic found a fault. If the model is still
    calibrated, faults found ~ sum(p) with variance sum(p(1-p)); well below that (more than two
    standard deviations) means the world has moved away from the training data: retrain.
    """
    done = [r for r in rows if r["predicted_probability"] is not None and r["fault_found"] is not None]
    n = len(done)
    found = sum(1 for r in done if r["fault_found"])
    expected = sum(float(r["predicted_probability"]) for r in done)
    sd = math.sqrt(sum(float(r["predicted_probability"]) * (1 - float(r["predicted_probability"])) for r in done))
    with_part = [r for r in done if r["fault_found"] and r.get("found_component") and r.get("predicted_component")]
    if n < MIN_INSPECTIONS:
        status = "TOO_FEW"
    elif found < expected - 2 * sd:
        status = "BELOW_EXPECTED"
    elif found > expected + 2 * sd:
        status = "ABOVE_EXPECTED"
    else:
        status = "ON_TRACK"
    return {
        "inspections": n, "faults_found": found,
        "found_rate": round(found / n, 3) if n else None,
        "expected_rate": round(expected / n, 3) if n else None,
        "expected_range": [max(0.0, round((expected - 2 * sd) / n, 3)), min(1.0, round((expected + 2 * sd) / n, 3))]
        if n else None,
        "part_right": round(sum(1 for r in with_part if r["found_component"] == r["predicted_component"])
                            / len(with_part), 3) if with_part else None,
        "status": status, "min_inspections": MIN_INSPECTIONS,
    }


def parts_forecast(rows: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """Rows of (depot, part, sum of p, sum of p(1-p), vehicles above threshold) -> expected failures
    with a 90% range. Each vehicle fails or not independently with its calibrated probability, so the
    count is a sum of Bernoulli trials: mean sum(p), variance sum(p(1-p)), close to normal at these sizes."""
    out = []
    for r in rows:
        mean, sd = float(r["expected"]), math.sqrt(float(r["variance"]))
        out.append({"depot_id": r["depot_id"], "depot": r["depot"], "part": r["part"],
                    "expected": round(mean, 1),
                    "low": max(0, math.floor(mean - Z_90 * sd)), "high": math.ceil(mean + Z_90 * sd),
                    "high_risk_vehicles": r["high_risk_vehicles"]})
    return sorted(out, key=lambda x: (x["depot"], -x["expected"]))

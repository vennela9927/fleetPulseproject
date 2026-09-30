"""Service planner and parts forecast: the pure logic, then the endpoints against the stack."""

from datetime import UTC, date, datetime

import psycopg
import pytest

from fleetpulse_api.config import Settings
from fleetpulse_api.maintenance import (
    Candidate,
    Depot,
    distance_km,
    first_plan_day,
    parts_forecast,
    plan,
    plan_summary,
    slot_time,
)

MUMBAI = Depot(1, "Mumbai Hub", 19.0760, 72.8777, bays=2)
PUNE = Depot(2, "Pune Hub", 18.5204, 73.8567, bays=1)          # ~120 km from Mumbai
CHENNAI = Depot(3, "Chennai Hub", 13.0827, 80.2707, bays=5)    # ~1,000 km: never an overflow for Mumbai
DEPOTS = [MUMBAI, PUNE, CHENNAI]
START = date(2026, 9, 30)


def car(vid: int, p: float, saving: float = 1000, depot: int = 1) -> Candidate:
    return Candidate(vid, f"VIN{vid:014d}", "Fleet", depot, p, "Cooling system", saving)


def test_distances_are_great_circle_km():
    assert distance_km(MUMBAI, PUNE) == pytest.approx(120, abs=5)
    assert distance_km(MUMBAI, CHENNAI) > 1000


def test_only_vehicles_whose_expected_saving_pays_for_an_inspection_are_planned():
    p = plan([car(1, 0.10), car(2, 0.20)], DEPOTS, {}, START, 3, inspection_cost=150)
    assert [b["vehicle_id"] for b in p.booked] == [2]      # 0.10 x 1000 = 100 < 150
    assert p.not_worth_inspecting == 1


def test_most_valuable_first_home_depot_then_later_days():
    cars = [car(1, 0.5), car(2, 0.6), car(3, 0.7), car(4, 0.4)]
    p = plan(cars, DEPOTS, {}, START, 2, inspection_cost=150)
    where = {b["vehicle_id"]: (b["depot"], b["date"]) for b in p.booked}
    # Mumbai has 2 bays a day: the two most valuable go tomorrow, the next two the day after.
    assert where[3] == ("Mumbai Hub", "2026-09-30") and where[2] == ("Mumbai Hub", "2026-09-30")
    assert where[1] == ("Mumbai Hub", "2026-10-01") and where[4] == ("Mumbai Hub", "2026-10-01")


def test_urgent_vehicles_overflow_to_a_nearby_depot_tomorrow_not_to_a_later_day():
    cars = [car(1, 0.95), car(2, 0.9), car(3, 0.85)]
    p = plan(cars, DEPOTS, {}, START, 3, inspection_cost=150)
    where = {b["vehicle_id"]: b for b in p.booked}
    assert {where[1]["depot"], where[2]["depot"]} == {"Mumbai Hub"}
    assert where[3]["depot"] == "Pune Hub" and where[3]["date"] == "2026-09-30"
    assert where[3]["moved_from"] == "Mumbai Hub" and where[3]["distance_km"] == pytest.approx(120, abs=5)


def test_urgent_vehicles_with_no_bay_tomorrow_are_reported_not_pushed_back():
    cars = [car(i, 0.9 + i / 100) for i in range(1, 5)]      # 4 urgent; Mumbai 2 + Pune 1 tomorrow
    p = plan(cars, DEPOTS, {}, START, 3, inspection_cost=150)
    assert len(p.booked) == 3 and all(b["date"] == "2026-09-30" for b in p.booked)
    assert [v["vehicle_id"] for v in p.too_risky_to_wait] == [1]   # the least likely of the four


def test_existing_bookings_use_up_bays():
    p = plan([car(1, 0.5), car(2, 0.6)], [MUMBAI], {(1, START): 2}, START, 1, inspection_cost=150)
    assert p.booked == [] and len(p.waiting) == 2


def test_summary_totals_add_up():
    booked = {(1, START): 1}
    p = plan([car(1, 0.5), car(2, 0.6), car(3, 0.1)], DEPOTS, booked, START, 1, inspection_cost=150)
    s = plan_summary(p, DEPOTS, booked)
    assert s["summary"]["scheduled"] == 2
    assert s["summary"]["expected_net_saving_usd"] == (600 - 150) + (500 - 150)
    assert s["summary"]["not_worth_inspecting"] == 1
    mumbai = next(d for d in s["depots"] if d["depot"] == "Mumbai Hub")
    assert mumbai["days"][0]["already_booked"] == 1 and len(mumbai["days"][0]["items"]) == 1


def test_plan_starts_tomorrow_in_india_at_nine():
    # 20:00 UTC on the 29th is already the 30th in India, so the plan starts on October 1st.
    assert first_plan_day(datetime(2026, 9, 29, 20, 0, tzinfo=UTC)) == date(2026, 10, 1)
    assert slot_time(date(2026, 10, 1)).astimezone(UTC) == datetime(2026, 10, 1, 3, 30, tzinfo=UTC)


def test_parts_forecast_range_comes_from_the_calibrated_probabilities():
    # 100 vehicles at 10%: expect 10, variance 9, 90% range 10 +/- 1.645 x 3.
    rows = [{"depot_id": 1, "depot": "Mumbai Hub", "part": "12 V electrical", "expected": 10.0, "variance": 9.0,
             "high_risk_vehicles": 0}]
    [f] = parts_forecast(rows)
    assert f["expected"] == 10.0 and f["low"] == 5 and f["high"] == 15


# --------------------------------------------------------------------------- against the stack

from .conftest import ZENITH, bearer  # noqa: E402


async def _cancel(booking_ids: list[int]) -> None:
    async with await psycopg.AsyncConnection.connect(Settings().postgres_dsn) as conn, conn.transaction():
        await conn.execute("SELECT set_config('app.tenant_id', %s, true)", (ZENITH,))
        await conn.execute("UPDATE service_booking SET status = 'CANCELLED' WHERE id = ANY(%s)", (booking_ids,))


@pytest.mark.integration
async def test_the_plan_respects_bays_and_the_parts_add_up(client, tokens):
    r = await client.get("/v1/maintenance/plan?days=2", headers=bearer(tokens["zenith"]))
    assert r.status_code == 200
    plan_ = r.json()
    for d in plan_["depots"]:
        for day in d["days"]:
            assert day["already_booked"] + len(day["items"]) <= day["bays"]
    parts = (await client.get("/v1/maintenance/parts", headers=bearer(tokens["zenith"]))).json()
    assert parts["expected_failures"] == pytest.approx(sum(i["expected"] for i in parts["items"]), abs=1)
    assert all(i["low"] <= i["expected"] <= i["high"] for i in parts["items"])


@pytest.mark.integration
async def test_the_overview_agrees_with_the_plan_and_stays_in_the_tenant(client, tokens):
    o = (await client.get("/v1/maintenance/overview", headers=bearer(tokens["zenith"]))).json()
    assert o["scored"] > 0
    assert sum(o["bands"].values()) == o["scored"]
    plan_ = (await client.get("/v1/maintenance/plan?days=3", headers=bearer(tokens["zenith"]))).json()
    assert o["plan"]["scheduled"] == plan_["summary"]["scheduled"]
    first_day = [b for d in plan_["depots"] for b in d["days"][0]["items"]]
    assert o["tomorrow"]["inspections"] == len(first_day)
    assert o["tomorrow"]["used"] <= o["tomorrow"]["bays"]
    assert [b["probability"] for b in o["first_inspections"]] == sorted(
        (b["probability"] for b in o["first_inspections"]), reverse=True)
    # A higher inspection cost can only move vehicles out of "worth inspecting".
    dearer = (await client.get("/v1/maintenance/overview?inspection_cost=600", headers=bearer(tokens["zenith"]))).json()
    assert dearer["bands"]["worth_inspecting"] <= o["bands"]["worth_inspecting"]
    # Another tenant's overview never lists these vehicles.
    acme = (await client.get("/v1/maintenance/overview", headers=bearer(tokens["acme"]))).json()
    zenith_ids = {b["vehicle_id"] for b in o["first_inspections"] + o["too_risky_to_wait"]}
    assert not zenith_ids & {b["vehicle_id"] for b in acme["first_inspections"] + acme["too_risky_to_wait"]}


@pytest.mark.integration
async def test_booking_checks_role_tenant_and_double_booking(client, tokens):
    plan_ = (await client.get("/v1/maintenance/plan?days=3", headers=bearer(tokens["zenith"]))).json()
    item = next(b for d in plan_["depots"] for day in d["days"] for b in day["items"])
    body = {"items": [{"vehicle_id": item["vehicle_id"], "depot_id": item["depot_id"], "date": item["date"]}]}

    assert (await client.post("/v1/maintenance/bookings", json=body, headers=bearer(tokens["acme_viewer"]))
            ).status_code == 403
    # Another tenant cannot book this vehicle: to Acme it does not exist.
    assert (await client.post("/v1/maintenance/bookings", json=body, headers=bearer(tokens["acme"]))
            ).status_code == 409
    r = await client.post("/v1/maintenance/bookings", json=body, headers=bearer(tokens["zenith"]))
    assert r.status_code == 200 and r.json()["booked"] == 1
    try:
        again = await client.post("/v1/maintenance/bookings", json=body, headers=bearer(tokens["zenith"]))
        assert again.status_code == 409 and "already booked" in again.json()["detail"]
    finally:
        await _cancel(r.json()["booking_ids"])


@pytest.mark.integration
async def test_a_full_depot_day_cannot_be_overbooked(client, tokens):
    plan_ = (await client.get("/v1/maintenance/plan?days=3", headers=bearer(tokens["zenith"]))).json()
    depot = min(plan_["depots"], key=lambda d: d["bays_per_day"])
    day = depot["days"][-1]
    free = day["bays"] - day["already_booked"]
    pool = [b for d in plan_["depots"] for x in d["days"] for b in x["items"]][: free + 1]
    body = {"items": [{"vehicle_id": b["vehicle_id"], "depot_id": depot["depot_id"], "date": day["date"]}
                      for b in pool]}
    r = await client.post("/v1/maintenance/bookings", json=body, headers=bearer(tokens["zenith"]))
    assert r.status_code == 409 and "no free bay" in r.json()["detail"]   # all or nothing: nothing booked
    after = (await client.get("/v1/maintenance/plan?days=3", headers=bearer(tokens["zenith"]))).json()
    same = next(d for d in after["depots"] if d["depot_id"] == depot["depot_id"])
    assert same["days"][-1]["already_booked"] == day["already_booked"]


def _done(p: float, found: bool, part: str = "Cooling system", found_part: str | None = "Cooling system") -> dict:
    return {"predicted_probability": p, "fault_found": found, "predicted_component": part,
            "found_component": found_part if found else None}


def test_model_health_needs_enough_inspections_before_judging():
    from fleetpulse_api.maintenance import model_health
    assert model_health([_done(0.9, True)] * 5)["status"] == "TOO_FEW"


def test_model_health_on_track_when_faults_match_the_promised_probabilities():
    from fleetpulse_api.maintenance import model_health
    rows = [_done(0.5, i % 2 == 0) for i in range(100)]          # promised 50%, found 50%
    h = model_health(rows)
    assert h["status"] == "ON_TRACK" and h["found_rate"] == 0.5 and h["expected_rate"] == 0.5


def test_model_health_flags_an_overconfident_model():
    from fleetpulse_api.maintenance import model_health
    rows = [_done(0.95, i < 70) for i in range(100)]             # promised 95%, found 70%
    h = model_health(rows)
    assert h["status"] == "BELOW_EXPECTED"
    assert h["expected_range"][0] > 0.70


def test_model_health_counts_the_right_part():
    from fleetpulse_api.maintenance import model_health
    rows = [_done(0.9, True)] * 15 + [_done(0.9, True, found_part="Transmission")] * 5 + [_done(0.9, False)] * 5
    assert model_health(rows)["part_right"] == 0.75


@pytest.mark.integration
async def test_an_inspection_result_is_recorded_once_by_a_manager(client, tokens):
    plan_ = (await client.get("/v1/maintenance/plan?days=3", headers=bearer(tokens["zenith"]))).json()
    item = next(b for d in plan_["depots"] for day in d["days"] for b in day["items"])
    body = {"items": [{"vehicle_id": item["vehicle_id"], "depot_id": item["depot_id"], "date": item["date"]}]}
    booking = (await client.post("/v1/maintenance/bookings", json=body, headers=bearer(tokens["zenith"]))).json()
    bid = booking["booking_ids"][0]
    url = f"/v1/maintenance/bookings/{bid}/result"
    result = {"fault_found": True, "component": item["likely_part"]}
    assert (await client.post(url, json=result, headers=bearer(tokens["acme"]))).status_code == 404   # other tenant
    r = await client.post(url, json=result, headers=bearer(tokens["zenith"]))
    assert r.status_code == 200 and r.json()["predicted_probability"] == pytest.approx(item["probability"], abs=1e-3)
    assert (await client.post(url, json=result, headers=bearer(tokens["zenith"]))).status_code == 409
    bad = await client.post(url, json={"fault_found": True, "component": "Flux capacitor"},
                            headers=bearer(tokens["zenith"]))
    assert bad.status_code == 422
    health = (await client.get("/v1/maintenance/model-health", headers=bearer(tokens["zenith"]))).json()
    assert health["inspections"] >= 1

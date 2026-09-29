import pytest

from fleetpulse_api.errors import ApiError
from fleetpulse_api.pagination import decode_cursor, encode_cursor, page


def test_cursor_round_trip():
    c = encode_cursor({"opened_at": "2026-09-29T10:00:00+00:00", "id": 42})
    assert decode_cursor(c, {"opened_at": str, "id": int}) == {"opened_at": "2026-09-29T10:00:00+00:00", "id": 42}


def test_no_cursor_means_first_page():
    assert decode_cursor(None, {"id": int}) is None
    assert decode_cursor("", {"id": int}) is None


@pytest.mark.parametrize("bad", [
    "!!!notbase64",
    encode_cursor({"id": "1; DROP TABLE vehicle"}),   # wrong type
    encode_cursor({"id": 1, "extra": 2}),             # unexpected key
    encode_cursor({"id": True}),                      # bool is not an int here
    encode_cursor([1, 2]),                            # not an object
])
def test_tampered_cursors_are_rejected(bad):
    with pytest.raises(ApiError) as e:
        decode_cursor(bad, {"id": int})
    assert e.value.status == 400


def test_page_uses_the_extra_row_only_as_a_signal():
    rows = [{"id": i} for i in range(1, 5)]   # fetched limit + 1 = 4 for limit 3
    p = page(rows, 3, lambda r: {"id": r["id"]})
    assert [r["id"] for r in p["items"]] == [1, 2, 3]
    assert decode_cursor(p["next_cursor"], {"id": int}) == {"id": 3}

    last = page(rows[:2], 3, lambda r: {"id": r["id"]})
    assert last["next_cursor"] is None

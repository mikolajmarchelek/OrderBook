"""Cross-language parity: the Python engine must reproduce the Java engine exactly.

The fixture in <repo>/parity was written by the Java ParityExport (seed 42).
Python replays events.csv and must produce the same trades, in the same order,
and the same book at every recorded snapshot.
"""

from pathlib import Path

import pytest

from mmrl.engine import MatchingEngine, apply_event
from mmrl.io import read_events, read_snapshots, read_trades

PARITY_DIR = Path(__file__).resolve().parents[2] / "parity"


@pytest.fixture(scope="module")
def java():
    """The Java run, loaded once and shared by every test in this file."""
    return {
        "events": read_events(PARITY_DIR / "events.csv"),
        "trades": read_trades(PARITY_DIR / "trades.csv"),
        "snapshots": read_snapshots(PARITY_DIR / "snapshots.csv"),
    }


def test_fixture_loaded(java):
    assert len(java["events"]) == 12_000
    assert len(java["trades"]) == 5_773
    assert len(java["snapshots"]) == 238


def test_trades_match_java_exactly(java):
    engine = MatchingEngine()
    expected_all = java["trades"]
    for i, event in enumerate(java["events"], start=1):
        before = len(engine.trade_log)
        new_trades = apply_event(engine, event)
        expected = expected_all[before:before + len(new_trades)]
        # compare event by event, so a failure names the exact event that caused it
        assert new_trades == expected, (
            f"trade mismatch at event #{i}: {event}\n"
            f"  python produced: {new_trades}\n"
            f"  java produced:   {expected}"
        )
    assert engine.trade_log == expected_all     # same total: Python made no extra trades


def test_book_matches_java_at_every_snapshot(java):
    engine = MatchingEngine()
    snapshots = java["snapshots"]
    levels = next(iter(snapshots.values())).levels
    checked = 0
    for i, event in enumerate(java["events"], start=1):
        apply_event(engine, event)
        expected = snapshots.get(i)
        if expected is not None:
            actual = engine.book.snapshot(levels)
            assert actual == expected, f"book differs after event #{i}: {event}\n  python: {actual}\n  java:   {expected}"
            checked += 1
    assert checked == len(snapshots)       # every Java snapshot was actually compared


def test_replayed_event_log_matches_the_input(java):
    engine = MatchingEngine()
    for event in java["events"]:
        apply_event(engine, event)
    assert engine.event_log == java["events"]
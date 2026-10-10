"""Reading the CSV files written by the Java ParityExport."""

from __future__ import annotations

import csv
from collections import defaultdict
from pathlib import Path

from mmrl.book import BookSnapshot
from mmrl.types import OrderEvent, Trade


def read_events(path: Path) -> list[OrderEvent]:
    with open(path, newline="") as f:
        return [OrderEvent.from_csv(row) for row in csv.DictReader(f)]


def read_trades(path: Path) -> list[Trade]:
    with open(path, newline="") as f:
        return [Trade.from_csv(row) for row in csv.DictReader(f)]


def read_snapshots(path: Path) -> dict[int, BookSnapshot]:
    """afterEvent -> BookSnapshot, rebuilt from the long format (one row per side and level)."""
    rows: dict[int, dict[str, list[tuple[int, int, int]]]] = defaultdict(lambda: {"BUY": [], "SELL": []})
    with open(path, newline="") as f:
        for row in csv.DictReader(f):
            rows[int(row["afterEvent"])][row["side"]].append(
                (int(row["level"]), int(row["price"]), int(row["qty"])))

    snapshots = {}
    for after_event, sides in rows.items():
        bids = sorted(sides["BUY"])      # sort by level, just in case
        asks = sorted(sides["SELL"])
        snapshots[after_event] = BookSnapshot(
            bid_prices=tuple(p for _, p, _ in bids),
            bid_qtys=tuple(q for _, _, q in bids),
            ask_prices=tuple(p for _, p, _ in asks),
            ask_qtys=tuple(q for _, _, q in asks),
        )
    return snapshots
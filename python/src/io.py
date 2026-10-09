"""Reading the CSV files written by the Java ParityExport."""

from __future__ import annotations

import csv
from pathlib import Path

from mmrl.types import OrderEvent, Trade


def read_events(path: Path) -> list[OrderEvent]:
    with open(path, newline="") as f:
        return [OrderEvent.from_csv(row) for row in csv.DictReader(f)]


def read_trades(path: Path) -> list[Trade]:
    with open(path, newline="") as f:
        return [Trade.from_csv(row) for row in csv.DictReader(f)]
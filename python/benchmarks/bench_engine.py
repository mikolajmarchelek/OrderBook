"""How fast is the Python engine? Replays the seed-42 fixture several times and reports events/second.

Run from the python/ folder:   python benchmarks/bench_engine.py
"""

import statistics
import time
from pathlib import Path

from mmrl.engine import apply_event, MatchingEngine
from mmrl.io import read_events

PARITY_DIR = Path(__file__).resolve().parents[2] / "parity"
REPEATS = 10


def run_once(events) -> float:
    """Replay all events into a fresh engine; return elapsed seconds."""
    engine = MatchingEngine()
    start = time.perf_counter()
    for e in events:
        apply_event(engine, e)
    return time.perf_counter() - start


def main() -> None:
    events = read_events(PARITY_DIR / "events.csv")   # parse once, outside the timing
    run_once(events)                                  # warm-up run, not measured

    times = [run_once(events) for _ in range(REPEATS)]
    rates = [len(events) / t for t in times]

    print(f"{len(events):,} events x {REPEATS} runs")
    print(f"median: {statistics.median(rates):,.0f} events/s")
    print(f"best:   {max(rates):,.0f} events/s")
    print(f"worst:  {min(rates):,.0f} events/s")


if __name__ == "__main__":
    main()
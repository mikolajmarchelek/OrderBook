"""Run the simulated market and print a quick sanity summary.

From the python/ folder:   python scripts/market_summary.py [seed] [steps]
"""

import statistics
import sys

from mmrl.market import Market, MarketParams


def main() -> None:
    seed = int(sys.argv[1]) if len(sys.argv) > 1 else 0
    steps = int(sys.argv[2]) if len(sys.argv) > 2 else 5_000
    warmup = 500

    m = Market(MarketParams(seed=seed))
    m.run(steps)

    h = [s for s in m.history[warmup:] if s.best_bid is not None and s.best_ask is not None]
    spreads = [s.best_ask - s.best_bid for s in h]
    gaps = [abs((s.best_bid + s.best_ask) / 2 - s.fair_value) for s in h]
    trades = [s.trades for s in m.history[warmup:]]

    print(f"seed {seed}, {steps:,} steps ({warmup} warm-up steps ignored)")
    print(f"fair value:        {m.history[0].fair_value:,.1f} -> {m.history[-1].fair_value:,.1f} ticks")
    print(f"spread:            mean {statistics.fmean(spreads):.2f} ticks, max {max(spreads)}")
    print(f"|mid - fair value|: mean {statistics.fmean(gaps):.2f} ticks")
    print(f"trades per step:   {statistics.fmean(trades):.2f}")
    print(f"resting orders:    {len(m.engine.book._index)}")
    print("top of book at the end:", m.engine.book.snapshot(3))


if __name__ == "__main__":
    main()
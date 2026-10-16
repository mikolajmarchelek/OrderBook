"""Measure how often a quote at distance d from the mid gets filled, and fit A-S's k.

A-S assumes fill rate(d) = A * exp(-k * d), so log(rate) is a straight line in d with slope -k.
From the python/ folder:   python scripts/fill_curve.py
"""

import math
import statistics

from mmrl.agent import AGENT_OWNER
from mmrl.env import EnvParams, MarketMakingEnv


def main() -> None:
    xs, ys = [], []
    for d in range(1, 6):
        env = MarketMakingEnv(EnvParams(max_inventory=10_000))    # no limit: always quote both sides
        action = env.encode_action(d, d)
        fills = steps = 0
        for seed in range(5):
            env.reset(seed=seed)
            start = len(env.market.engine.trade_log)
            done = False
            while not done:
                _, _, _, done, _ = env.step(action)
                steps += 1
            fills += sum(1 for t in env.market.engine.trade_log[start:]
                         if AGENT_OWNER in (t.buy_owner, t.sell_owner))
        rate = fills / steps / 2                                  # per side, per step
        xs.append(d)
        ys.append(math.log(rate))
        print(f"offset {d}: fill rate {rate:.4f} per side per step")

    slope, intercept = statistics.linear_regression(xs, ys)
    print(f"fit: rate = {math.exp(intercept):.2f} * exp(-{-slope:.2f} * d)   ->   k = {-slope:.2f}")


if __name__ == "__main__":
    main()
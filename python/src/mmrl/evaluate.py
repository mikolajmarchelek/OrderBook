"""Run a policy for several episodes and summarize how it did."""

from __future__ import annotations

import statistics
from dataclasses import dataclass
from typing import Callable

from mmrl.env import EnvParams, MarketMakingEnv

Policy = Callable[[MarketMakingEnv], int]


@dataclass(frozen=True)
class EvalResult:
    mean_reward: float        # what the agent is trained on (PnL minus inventory penalty)
    mean_pnl: float           # profit per episode, marked to the reference mid
    std_pnl: float
    sharpe: float             # mean_pnl / std_pnl across episodes: profit per unit of risk
    mean_abs_inventory: float
    max_abs_inventory: int

    def __str__(self) -> str:
        return (f"reward {self.mean_reward:9.1f} | pnl {self.mean_pnl:7.1f} ± {self.std_pnl:6.1f} "
                f"| sharpe {self.sharpe:5.2f} | mean|q| {self.mean_abs_inventory:5.1f} "
                f"| max|q| {self.max_abs_inventory}")


def evaluate(policy: Policy, params: EnvParams | None = None, seeds=range(10)) -> EvalResult:
    """Same seeds for every policy -> every policy faces exactly the same markets."""
    env = MarketMakingEnv(params)
    rewards, pnls, abs_q, max_q = [], [], [], 0
    for seed in seeds:
        env.reset(seed=seed)
        total_r = total_pnl = 0.0
        done = False
        while not done:
            _, r, terminated, truncated, info = env.step(policy(env))
            total_r += r
            total_pnl += info["pnl"]
            abs_q.append(abs(info["inventory"]))
            max_q = max(max_q, abs(info["inventory"]))
            done = terminated or truncated
        rewards.append(total_r)
        pnls.append(total_pnl)
    std = statistics.stdev(pnls) if len(pnls) > 1 else 0.0
    return EvalResult(
        mean_reward=statistics.fmean(rewards),
        mean_pnl=statistics.fmean(pnls),
        std_pnl=std,
        sharpe=statistics.fmean(pnls) / std if std > 0 else 0.0,
        mean_abs_inventory=statistics.fmean(abs_q),
        max_abs_inventory=max_q,
    )
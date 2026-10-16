"""Hand-written market-making strategies the RL agent has to beat.

Every policy is a callable: policy(env) -> action number. It may read the environment's
state directly (inventory, time, mid), which is how a human-designed strategy would work.
"""

from __future__ import annotations

import math
from dataclasses import dataclass

from mmrl.env import MarketMakingEnv


@dataclass(frozen=True)
class FixedSpread:
    """Always quote the same distance from the mid, ignoring inventory."""

    bid_offset: int = 2
    ask_offset: int = 2

    def __call__(self, env: MarketMakingEnv) -> int:
        return env.encode_action(self.bid_offset, self.ask_offset)

@dataclass(frozen=True)
class AvellanedaStoikov:
    """Avellaneda & Stoikov (2008): quote around an inventory-adjusted reservation price.

        reservation price  r = mid - q * gamma * sigma^2 * tau
        total spread       gamma * sigma^2 * tau + (2 / gamma) * ln(1 + gamma / k)
        bid = r - spread/2,  ask = r + spread/2

    gamma: risk aversion. sigma: volatility (ticks per sqrt second). k: how fast fill
    probability decays with distance from the mid (fitted from this market).
    tau: risk horizon in seconds. The original uses time left (T - t), which shrinks to 0 at
    the end of every episode and switches the skew off exactly when it matters; a constant
    horizon is the usual practical fix.
    """

    gamma: float = 0.25    # tuned on seeds 0-9 (scripts/compare_baselines.py), reported on 100-119
    sigma: float = 0.5     # the fair value's volatility in this market
    k: float = 1.2         # fitted from fill rates in this market (scripts/fill_curve.py)
    tau: float = 5.0

    def offsets(self, inventory: int) -> tuple[float, float]:
        """(bid offset, ask offset) from the mid, in ticks, before clamping to the action grid."""

        var_term = self.gamma * self.sigma**2 * self.tau
        skew = inventory * var_term
        half = (var_term + (2 / self.gamma) * math.log(1 + self.gamma / self.k)) / 2
        return skew + half, half - skew

    def __call__(self, env: MarketMakingEnv) -> int:
        bid_off, ask_off = self.offsets(env.account.inventory)
        return env.encode_action(bid_off, ask_off)
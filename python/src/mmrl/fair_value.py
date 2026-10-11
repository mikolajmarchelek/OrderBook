"""The hidden fair value: the 'true' price that informed traders know and the book only guesses."""

from __future__ import annotations

import math
import random
from dataclasses import dataclass


@dataclass(frozen=True)
class FairValueParams:
    start: float = 10_000.0   # starting value, in ticks
    sigma: float = 0.5        # volatility: ticks per sqrt(second)
    dt: float = 1.0           # seconds per step


class FairValue:
    def __init__(self, params: FairValueParams, rng: random.Random) -> None:
        if params.start <= 0:
            raise ValueError(f"start must be positive: {params.start}")
        if params.sigma < 0 or params.dt <= 0:
            raise ValueError("sigma must be >= 0 and dt > 0")
        self.params = params
        self.rng = rng
        self.value = params.start

    def step(self) -> float:
        """Advance one time step. Returns the new value."""
        
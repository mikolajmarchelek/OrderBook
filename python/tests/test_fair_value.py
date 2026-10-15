import math
import random
import statistics

import pytest

from mmrl.fair_value import FairValue, FairValueParams


def make(seed=1, **params):
    return FairValue(FairValueParams(**params), random.Random(seed))


def increments(fv, n):
    """Run n steps and return the list of changes (value after - value before)."""
    out = []
    for _ in range(n):
        before = fv.value
        fv.step()
        out.append(fv.value - before)
    return out


def test_starts_at_start():
    assert make(start=12_345.0).value == 12_345.0


def test_zero_volatility_never_moves():
    fv = make(sigma=0.0)
    for _ in range(100):
        fv.step()
    assert fv.value == 10_000.0


def path(seed, n=500):
    fv = make(seed=seed)
    return [fv.step() for _ in range(n)]


def test_same_seed_same_path_different_seed_different_path():
    assert path(42) == path(42)
    assert path(42) != path(7)


def test_increments_have_mean_zero_and_std_sigma_sqrt_dt():
    fv = make(seed=3, sigma=0.5, dt=1.0)
    inc = increments(fv, 100_000)
    assert abs(statistics.fmean(inc)) < 0.01                    # no drift
    assert statistics.stdev(inc) == pytest.approx(0.5, rel=0.02)


def test_volatility_scales_with_square_root_of_dt():
    # 4x shorter steps -> each step moves half as much (sqrt(1/4) = 1/2)
    inc = increments(make(seed=5, sigma=0.5, dt=0.25), 100_000)
    assert statistics.stdev(inc) == pytest.approx(0.5 * math.sqrt(0.25), rel=0.02)


def test_value_never_goes_below_one_tick():
    fv = make(seed=9, start=5.0, sigma=10.0)
    for _ in range(10_000):
        assert fv.step() >= 1.0


def test_rejects_bad_params():
    with pytest.raises(ValueError):
        make(start=0.0)
    with pytest.raises(ValueError):
        make(sigma=-1.0)
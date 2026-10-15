import itertools
import random
import statistics

import pytest

from mmrl.engine import MatchingEngine
from mmrl.fair_value import FairValue, FairValueParams
from mmrl.traders import (INFORMED_OWNER, InformedParams, InformedTraders, NoiseParams,
                          NoiseTraders, poisson)
from mmrl.types import Side


# ---------- poisson ----------

def test_poisson_mean_matches_rate():
    rng = random.Random(1)
    draws = [poisson(rng, 4.0) for _ in range(50_000)]
    assert statistics.fmean(draws) == pytest.approx(4.0, rel=0.02)
    assert statistics.variance(draws) == pytest.approx(4.0, rel=0.05)   # Poisson: variance = mean


# ---------- informed decision rule ----------

@pytest.fixture
def informed():
    return InformedTraders(InformedParams(threshold=2.0), random.Random(1), itertools.count(1))


def test_informed_buys_when_ask_is_cheap(informed):
    assert informed.decide(best_bid=9998, best_ask=10000, fair_value=10003.0) is Side.BUY


def test_informed_sells_when_bid_is_rich(informed):
    assert informed.decide(best_bid=10005, best_ask=10007, fair_value=10002.0) is Side.SELL


def test_informed_does_nothing_inside_threshold(informed):
    assert informed.decide(best_bid=9999, best_ask=10001, fair_value=10002.5) is None   # ask only 1.5 below V


def test_informed_handles_empty_sides(informed):
    assert informed.decide(best_bid=None, best_ask=None, fair_value=10000.0) is None
    assert informed.decide(best_bid=None, best_ask=9990, fair_value=10000.0) is Side.BUY


# ---------- noise traders ----------

def run(seed, steps, with_informed, sigma=0.5):
    # separate random streams, so the fair-value path is identical with and without informed traders
    fv = FairValue(FairValueParams(sigma=sigma), random.Random(seed))
    rng = random.Random(seed + 1_000)
    ids = itertools.count(1)
    engine = MatchingEngine()
    noise = NoiseTraders(NoiseParams(), rng, ids)
    informed = InformedTraders(InformedParams(), rng, ids)
    gaps = []
    for _ in range(steps):
        v = fv.step()
        noise.act(engine, v)
        if with_informed:
            informed.act(engine, v)
        mid = engine.book.mid()
        if mid is not None:
            gaps.append(abs(mid - v))
    return engine, gaps


def test_noise_traders_build_a_two_sided_book():
    engine, _ = run(seed=1, steps=500, with_informed=False)
    assert engine.book.best_bid() is not None and engine.book.best_ask() is not None
    assert engine.book.best_bid() < engine.book.best_ask()
    assert len(engine.trade_log) > 0


def test_book_size_stays_bounded():
    engine, _ = run(seed=1, steps=5_000, with_informed=True)
    assert len(engine.book._index) < 150          # settles near limit_rate / cancel_prob, not thousands


def test_same_seed_same_market():
    a, _ = run(seed=7, steps=300, with_informed=True)
    b, _ = run(seed=7, steps=300, with_informed=True)
    assert a.trade_log == b.trade_log
    assert a.event_log == b.event_log


def test_informed_traders_pull_the_book_toward_fair_value():
    # Without informed traders nothing ties the book to V; with them, the mid tracks V.
    _, gaps_without = run(seed=3, steps=5_000, with_informed=False)
    _, gaps_with = run(seed=3, steps=5_000, with_informed=True)
    assert statistics.fmean(gaps_with) < 0.5 * statistics.fmean(gaps_without)


def test_informed_trades_are_tagged_with_their_owner():
    engine, _ = run(seed=3, steps=2_000, with_informed=True)
    informed_trades = [t for t in engine.trade_log if INFORMED_OWNER in (t.buy_owner, t.sell_owner)]
    assert informed_trades
    assert all(INFORMED_OWNER == (t.buy_owner if t.aggressor is Side.BUY else t.sell_owner)
               for t in informed_trades)          # informed traders are always the aggressor
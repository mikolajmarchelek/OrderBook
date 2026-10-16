import math

import pytest

from mmrl.baselines import AvellanedaStoikov, FixedSpread
from mmrl.env import EnvParams, MarketMakingEnv
from mmrl.evaluate import evaluate


def small_params():
    return EnvParams(episode_steps=300, warmup_steps=100)


# ---------- encode / decode ----------

def test_encode_is_the_inverse_of_decode():
    env = MarketMakingEnv()
    for a in range(env.action_space.n):
        assert env.encode_action(*env.decode_action(a)) == a


def test_encode_rounds_and_clamps():
    env = MarketMakingEnv()
    assert env.decode_action(env.encode_action(2.4, 2.6)) == (2, 3)
    assert env.decode_action(env.encode_action(-7.0, 99.0)) == (1, 5)


# ---------- the Avellaneda-Stoikov formula ----------

def test_flat_inventory_quotes_symmetrically():
    bid_off, ask_off = AvellanedaStoikov().offsets(0)
    assert bid_off == pytest.approx(ask_off)


def test_spread_matches_the_formula():
    a = AvellanedaStoikov(gamma=0.5, sigma=0.4, k=2.0, tau=3.0)
    bid_off, ask_off = a.offsets(0)
    expected_total = 0.5 * 0.4 ** 2 * 3.0 + (2 / 0.5) * math.log(1 + 0.5 / 2.0)
    assert bid_off + ask_off == pytest.approx(expected_total)


def test_long_inventory_skews_quotes_down():
    # long -> reservation price below mid -> bid further away, ask closer (sell more, buy less)
    a = AvellanedaStoikov()
    flat_bid, flat_ask = a.offsets(0)
    long_bid, long_ask = a.offsets(5)
    assert long_bid > flat_bid and long_ask < flat_ask
    assert long_bid + long_ask == pytest.approx(flat_bid + flat_ask)    # skew moves, spread doesn't


def test_skew_is_q_gamma_sigma2_tau():
    a = AvellanedaStoikov(gamma=0.5, sigma=0.4, k=2.0, tau=3.0)
    flat_bid, _ = a.offsets(0)
    bid, _ = a.offsets(-4)
    assert flat_bid - bid == pytest.approx(4 * 0.5 * 0.4 ** 2 * 3.0)


# ---------- in the environment ----------

def test_avellaneda_stoikov_controls_inventory_better_than_fixed_spread():
    fixed = evaluate(FixedSpread(2, 2), small_params(), seeds=range(5))
    a_s = evaluate(AvellanedaStoikov(), small_params(), seeds=range(5))
    assert a_s.mean_abs_inventory < fixed.mean_abs_inventory
    assert a_s.mean_reward > fixed.mean_reward


def test_evaluate_is_deterministic():
    assert evaluate(FixedSpread(2, 2), small_params(), seeds=range(3)) == \
           evaluate(FixedSpread(2, 2), small_params(), seeds=range(3))
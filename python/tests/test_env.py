import math

import numpy as np
import pytest
from gymnasium.utils.env_checker import check_env

from mmrl.env import EnvParams, MarketMakingEnv, N_FEATURES


def small(**kw):
    return MarketMakingEnv(EnvParams(episode_steps=kw.pop("episode_steps", 200), warmup_steps=100, **kw))


def test_passes_gymnasium_checker():
    check_env(small(), skip_render_check=True)


def test_action_decoding_covers_every_pair_once():
    env = small()
    pairs = [env.decode_action(a) for a in range(env.action_space.n)]
    assert len(set(pairs)) == env.action_space.n == 25
    assert pairs[0] == (1, 1) and pairs[-1] == (5, 5)
    assert env.decode_action(7) == (2, 3)


def test_observation_shape_and_dtype():
    env = small()
    obs, info = env.reset(seed=0)
    assert obs.shape == (N_FEATURES,) and obs.dtype == np.float32
    assert info["inventory"] == 0


def test_same_seed_same_episode():
    def run(seed):
        env = small()
        env.reset(seed=seed)
        return [env.step(a % 25)[1] for a in range(150)]
    assert run(3) == run(3)
    assert run(3) != run(4)


def test_episode_truncates_after_episode_steps():
    env = small(episode_steps=50)
    env.reset(seed=1)
    for i in range(50):
        _, _, terminated, truncated, info = env.step(0)
        assert not terminated
        assert truncated == (i == 49)
    assert info["t"] == 50


def test_rewards_add_up_to_pnl_minus_penalties():
    eta = 0.01
    env = small(inventory_penalty=eta, episode_steps=300)
    env.reset(seed=2)
    total_reward = total_pnl = penalty = 0.0
    for a in range(300):
        _, r, _, truncated, info = env.step(a % 25)
        total_reward += r
        total_pnl += info["pnl"]
        penalty += eta * info["inventory"] ** 2
    # the only other term is the end-of-episode liquidation cost, which is >= 0
    liquidation = total_pnl - penalty - total_reward
    assert liquidation >= -1e-6


def test_inventory_never_exceeds_limit_by_more_than_one_fill():
    env = small(max_inventory=6, order_qty=2, episode_steps=1_000)
    env.reset(seed=5)
    worst = 0
    for _ in range(1_000):
        _, _, _, _, info = env.step(0)          # tightest quotes: most fills
        worst = max(worst, abs(info["inventory"]))
    assert worst <= 6 + 2


def test_reference_mid_ignores_our_own_quotes():
    env = small()
    env.reset(seed=6)
    before = env.reference_mid()
    env.account.quote(math.ceil(before) - 1, math.floor(before) + 1, 2)   # quote inside the spread
    assert env.reference_mid() == before
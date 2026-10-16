import numpy as np

from mmrl.baselines import FixedSpread
from mmrl.env import EnvParams, MarketMakingEnv
from mmrl.evaluate import ModelPolicy, evaluate

SMALL = EnvParams(episode_steps=200, warmup_steps=100)


class ConstantModel:
    """A stand-in for a trained SB3 model: always predicts the same action."""

    def __init__(self, action):
        self.action = action
        self.seen = []

    def predict(self, obs, deterministic=True):
        self.seen.append(obs)
        return np.array(self.action), None


def test_model_policy_matches_the_equivalent_baseline():
    env = MarketMakingEnv()
    action = env.encode_action(2, 2)
    assert evaluate(ModelPolicy(ConstantModel(action)), SMALL, seeds=range(3)) == \
           evaluate(FixedSpread(2, 2), SMALL, seeds=range(3))


def test_model_receives_the_same_observation_the_env_returned():
    env = MarketMakingEnv(SMALL)
    model = ConstantModel(0)
    policy = ModelPolicy(model)
    obs, _ = env.reset(seed=1)
    for _ in range(20):
        action = policy(env)
        assert np.array_equal(model.seen[-1], obs)       # model saw exactly what step() returned
        obs, *_ = env.step(action)
"""Train a PPO market maker, save it, and compare it with the baselines on held-out seeds.

From the python/ folder:
    python scripts/train_ppo.py                     # 300k steps, seed 0
    python scripts/train_ppo.py --steps 1000000 --seed 1
Watch it learn, in a second terminal:
    python scripts/plot_training.py models/ppo_seed0_300k --live
"""

import argparse
import time
from pathlib import Path

from stable_baselines3 import PPO
from stable_baselines3.common.env_util import make_vec_env
from stable_baselines3.common.logger import configure
from stable_baselines3.common.vec_env import VecNormalize

from mmrl.baselines import AvellanedaStoikov, FixedSpread
from mmrl.env import EnvParams, MarketMakingEnv
from mmrl.evaluate import ModelPolicy, evaluate

TEST_SEEDS = range(100, 120)        # the same held-out markets as compare_baselines.py
MODELS_DIR = Path(__file__).resolve().parents[1] / "models"


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--steps", type=int, default=300_000, help="total environment steps to train for")
    parser.add_argument("--seed", type=int, default=0, help="training seed (network init + training markets)")
    parser.add_argument("--envs", type=int, default=8, help="environments run side by side")
    args = parser.parse_args()

    params = EnvParams()

    # 8 copies of the environment, each in its own random market, stepped together.
    # make_vec_env also wraps each in a Monitor, which records episode rewards for the log.
    venv = make_vec_env(MarketMakingEnv, n_envs=args.envs, seed=args.seed, env_kwargs={"params": params})

    # Rewards here range from about -5 to +5 per step. Neural networks learn best when the
    # numbers they see are around 1, so VecNormalize rescales rewards during training only.
    # Observations are already scaled by the environment, so we leave them alone.
    venv = VecNormalize(venv, norm_obs=False, norm_reward=True, gamma=0.99)

    model = PPO(
        "MlpPolicy", venv,
        n_steps=1024,               # steps per env before each update (8 envs -> 8,192 per update)
        batch_size=256,             # minibatch size for the gradient steps
        n_epochs=10,                # passes over each batch of experience
        gamma=0.99,                 # discount: rewards ~100 steps ahead still matter
        gae_lambda=0.95,
        learning_rate=3e-4,
        ent_coef=0.01,              # small bonus for trying different actions (exploration)
        clip_range=0.2,             # the "proximal" part: limit how far each update moves
        policy_kwargs=dict(net_arch=[64, 64]),   # two hidden layers of 64 neurons
        seed=args.seed,
        device="cpu",               # small network: CPU is as fast as a GPU here
        verbose=1,
    )

    # Each run gets its own folder: models/ppo_seed0_300k/{model.zip, progress.csv}.
    # The logger prints the usual tables AND appends one CSV row per iteration,
    # which scripts/plot_training.py turns into curves (also live, while this runs).
    run_dir = MODELS_DIR / f"ppo_seed{args.seed}_{args.steps // 1000}k"
    model.set_logger(configure(str(run_dir), ["stdout", "csv"]))

    start = time.perf_counter()
    model.learn(total_timesteps=args.steps)
    minutes = (time.perf_counter() - start) / 60

    model.save(run_dir / "model")
    print(f"\ntrained {args.steps:,} steps in {minutes:.1f} min, saved to {run_dir}\n")

    print(f"held-out seeds {TEST_SEEDS.start}-{TEST_SEEDS.stop - 1}:")
    for name, policy in [("fixed (2,2)", FixedSpread(2, 2)),
                         ("A-S tuned", AvellanedaStoikov()),
                         ("PPO", ModelPolicy(model))]:
        print(f"  {name:12s} {evaluate(policy, params, seeds=TEST_SEEDS)}")


if __name__ == "__main__":
    main()
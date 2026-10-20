"""Look inside a trained agent: what does it quote, and why?

Four panels, PPO vs Avellaneda-Stoikov on the held-out seeds:
  1. which of the 25 actions PPO actually uses
  2. its average bid/ask offsets at each inventory level (what it did)
  3. skew vs inventory with everything else held fixed (what inventory alone makes it do)
  4. skew vs order book imbalance, held fixed the same way (something A-S cannot see)

From the python/ folder:
    python scripts/inspect_policy.py models/ppo_seed0_300k
"""

from __future__ import annotations

import argparse
from collections import defaultdict
from pathlib import Path

import matplotlib.pyplot as plt
import numpy as np

from mmrl.baselines import AvellanedaStoikov
from mmrl.env import EnvParams, MarketMakingEnv
from mmrl.evaluate import ModelPolicy

TEST_SEEDS = range(100, 120)
INVENTORY, IMBALANCE_TOP = 0, 3         # positions of those features in the observation vector
SLOPE_RANGE = 4                         # fit the inventory slope over |q| <= 4, where the agent actually lives


# ---------- 1. record what a policy does ----------

def collect(policy, params: EnvParams, seeds) -> dict[str, np.ndarray]:
    """Play full episodes; for every step keep the observation, inventory and chosen offsets."""
    env = MarketMakingEnv(params)
    obs_list, q_list, bid_list, ask_list, actions = [], [], [], [], []
    for seed in seeds:
        obs, _ = env.reset(seed=seed)
        done = False
        while not done:
            action = policy(env)
            bid_off, ask_off = env.decode_action(action)
            obs_list.append(obs)
            q_list.append(env.account.inventory)
            bid_list.append(bid_off)
            ask_list.append(ask_off)
            actions.append(action)
            obs, _, terminated, truncated, _ = env.step(action)
            done = terminated or truncated
    return {"obs": np.array(obs_list), "q": np.array(q_list), "bid": np.array(bid_list),
            "ask": np.array(ask_list), "action": np.array(actions)}


# ---------- 2. ask a policy "what if?" ----------
# A predictor maps a batch of observations (n, 9) to a batch of actions (n,).

def model_predictor(model):
    return lambda batch: np.asarray(model.predict(batch, deterministic=True)[0]).reshape(-1)


def as_predictor(strategy: AvellanedaStoikov, env: MarketMakingEnv):
    """A-S only reads inventory, so we can recover its action from the observation."""
    def predict(batch):
        qs = np.rint(batch[:, INVENTORY] * env.p.max_inventory).astype(int)
        return np.array([env.encode_action(*strategy.offsets(int(q))) for q in qs])
    return predict


def skew_curve(predict, base_obs: np.ndarray, feature: int, values, env) -> np.ndarray:
    """Partial dependence: set ONE feature to each value in every real state, average the skew.

    skew = (bid offset - ask offset) / 2, in ticks.
      > 0: both quotes shifted DOWN (bid further away, ask closer): eager to sell
      < 0: both quotes shifted UP: eager to buy
    """
    k = env.p.max_offset
    out = []
    for v in values:
        batch = base_obs.copy()
        batch[:, feature] = v
        a = predict(batch.astype(np.float32))
        bid, ask = 1 + a // k, 1 + a % k
        out.append(((bid - ask) / 2).mean())
    return np.array(out)


def slope(x, y, within: float | None = None) -> float:
    """Least-squares slope; with `within`, only over |x| <= within (a line fits a curve badly)."""
    x, y = np.asarray(x, float), np.asarray(y, float)
    if within is not None:
        keep = np.abs(x) <= within
        x, y = x[keep], y[keep]
    return float(np.polyfit(x, y, 1)[0])


# ---------- 3. draw ----------

def plot(ppo, ref, predict_ppo, predict_as, env, title: str, out: Path) -> None:
    k = env.p.max_offset
    fig, axes = plt.subplots(2, 2, figsize=(13, 10))

    # (1) action usage heatmap
    ax = axes[0, 0]
    counts = np.bincount(ppo["action"], minlength=k * k).reshape(k, k) / len(ppo["action"])
    im = ax.imshow(counts, cmap="Blues", origin="lower")
    for b in range(k):
        for a in range(k):
            ax.text(a, b, f"{counts[b, a]:.0%}", ha="center", va="center", fontsize=8,
                    color="white" if counts[b, a] > counts.max() / 2 else "black")
    ax.set_xticks(range(k), range(1, k + 1))
    ax.set_yticks(range(k), range(1, k + 1))
    ax.set_xlabel("ask offset (ticks above mid)")
    ax.set_ylabel("bid offset (ticks below mid)")
    ax.set_title("1. How often PPO picks each action\n(diagonal = symmetric quotes)", fontsize=10)
    fig.colorbar(im, ax=ax, fraction=0.046)

    # (2) what it actually did, by inventory
    ax = axes[0, 1]
    for data, name, style in [(ppo, "PPO", "-"), (ref, "A-S", "--")]:
        by_q = defaultdict(lambda: ([], []))
        for q, b, a in zip(data["q"], data["bid"], data["ask"]):
            by_q[q][0].append(b)
            by_q[q][1].append(a)
        qs = sorted(q for q, (b, _) in by_q.items() if len(b) >= 30)   # skip rarely visited levels
        ax.plot(qs, [np.mean(by_q[q][0]) for q in qs], style, color="tab:green", marker=".", label=f"{name} bid offset")
        ax.plot(qs, [np.mean(by_q[q][1]) for q in qs], style, color="tab:red", marker=".", label=f"{name} ask offset")
    ax.set_xlabel("inventory q (units held; + = long)")
    ax.set_ylabel("average offset from mid (ticks)")
    ax.set_title("2. What it did: average quotes at each inventory\n(levels visited at least 30 times)", fontsize=10)
    ax.legend(fontsize=8)

    # (3) inventory alone, everything else held at real values
    ax = axes[1, 0]
    qs = np.arange(-15, 16)
    xs = qs / env.p.max_inventory
    base = ppo["obs"][:: max(1, len(ppo["obs"]) // 2000)]          # ~2,000 real states
    s_ppo = skew_curve(predict_ppo, base, INVENTORY, xs, env)
    s_as = skew_curve(predict_as, base, INVENTORY, xs, env)
    ax.axvspan(-SLOPE_RANGE, SLOPE_RANGE, color="gray", alpha=0.08, label=f"slope fitted here (|q| <= {SLOPE_RANGE})")
    ax.plot(qs, s_ppo, "-o", markersize=3, label=f"PPO  (slope {slope(qs, s_ppo, SLOPE_RANGE):.2f} ticks / unit)")
    ax.plot(qs, s_as, "--", label=f"A-S  (slope {slope(qs, s_as, SLOPE_RANGE):.2f} ticks / unit)")
    ax.axhline(0, color="gray", linewidth=0.8)
    ax.axvline(0, color="gray", linewidth=0.8)
    ax.set_xlabel("inventory q (set artificially, all other features real)")
    ax.set_ylabel("skew (ticks): + = eager to sell, - = eager to buy")
    ax.set_title("3. Inventory skew: the core A-S idea\n(rising line = sells when long, buys when short)", fontsize=10)
    ax.legend(fontsize=8)

    # (4) order book imbalance, same method
    ax = axes[1, 1]
    imb = np.linspace(-1, 1, 21)
    s_ppo_i = skew_curve(predict_ppo, base, IMBALANCE_TOP, imb, env)
    s_as_i = skew_curve(predict_as, base, IMBALANCE_TOP, imb, env)
    ax.plot(imb, s_ppo_i, "-o", markersize=3, label=f"PPO  (slope {slope(imb, s_ppo_i):.2f})")
    ax.plot(imb, s_as_i, "--", label="A-S  (ignores the book)")
    ax.axhline(0, color="gray", linewidth=0.8)
    ax.set_xlabel("top-of-book imbalance: -1 = all sellers, +1 = all buyers")
    ax.set_ylabel("skew (ticks): + = eager to sell, - = eager to buy")
    ax.set_title("4. Does it read the order book?\n(falling line = leans with buying pressure)", fontsize=10)
    ax.legend(fontsize=8)

    fig.suptitle(f"{title}: behaviour on seeds {TEST_SEEDS.start}-{TEST_SEEDS.stop - 1}", fontsize=13)
    fig.tight_layout(rect=(0, 0, 1, 0.96))      # leave the top 4% for the suptitle
    fig.subplots_adjust(hspace=0.35)            # room between the rows' x labels and titles
    fig.savefig(out, dpi=120)
    print(f"saved {out}")

    print(f"\ninventory skew slope (|q| <= {SLOPE_RANGE}):  PPO {slope(qs, s_ppo, SLOPE_RANGE):+.3f}   "
          f"A-S {slope(qs, s_as, SLOPE_RANGE):+.3f}  ticks per unit held")
    print(f"imbalance skew slope:  PPO {slope(imb, s_ppo_i):+.3f}   A-S {slope(imb, s_as_i):+.3f}  ticks per unit imbalance")
    sym = np.mean(ppo["bid"] == ppo["ask"])
    print(f"PPO quotes symmetrically {sym:.0%} of the time; mean width {np.mean(ppo['bid'] + ppo['ask']):.2f} ticks "
          f"(A-S {np.mean(ref['bid'] + ref['ask']):.2f})")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("run", type=Path, help="run folder (with model.zip) or a .zip model file")
    args = parser.parse_args()

    from stable_baselines3 import PPO           # imported here so the helpers above work without torch

    path = args.run / "model.zip" if args.run.is_dir() else args.run
    model = PPO.load(path, device="cpu")
    params, env, strategy = EnvParams(), MarketMakingEnv(), AvellanedaStoikov()

    print("playing held-out episodes ...")
    ppo = collect(ModelPolicy(model), params, TEST_SEEDS)
    ref = collect(strategy, params, TEST_SEEDS)

    title = args.run.name if args.run.is_dir() else args.run.stem
    out = (args.run if args.run.is_dir() else args.run.parent) / f"{title}_behaviour.png"
    plot(ppo, ref, model_predictor(model), as_predictor(strategy, env), env, title, out)
    plt.show()


if __name__ == "__main__":
    main()
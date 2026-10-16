"""Tune Avellaneda-Stoikov on one set of seeds, then compare every baseline on fresh seeds.

From the python/ folder:   python scripts/compare_baselines.py
"""

from mmrl.baselines import AvellanedaStoikov, FixedSpread
from mmrl.evaluate import evaluate

TUNE_SEEDS = range(0, 10)      # used to pick parameters
TEST_SEEDS = range(100, 120)   # never used for tuning: an honest score


def main() -> None:
    best = None
    for gamma in (0.25, 0.5, 1.0, 2.0):
        for tau in (1, 2, 3, 5):
            r = evaluate(AvellanedaStoikov(gamma=gamma, tau=tau), seeds=TUNE_SEEDS)
            if best is None or r.mean_reward > best[0].mean_reward:
                best = (r, gamma, tau)
    _, gamma, tau = best
    print(f"A-S tuned on seeds {TUNE_SEEDS.start}-{TUNE_SEEDS.stop - 1}: gamma={gamma}, tau={tau}\n")

    print(f"held-out seeds {TEST_SEEDS.start}-{TEST_SEEDS.stop - 1}:")
    for name, policy in [("fixed (1,1)", FixedSpread(1, 1)),
                         ("fixed (2,2)", FixedSpread(2, 2)),
                         ("fixed (3,3)", FixedSpread(3, 3)),
                         ("A-S tuned", AvellanedaStoikov(gamma=gamma, tau=tau))]:
        print(f"  {name:12s} {evaluate(policy, seeds=TEST_SEEDS)}")


if __name__ == "__main__":
    main()
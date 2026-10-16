"""Plot PPO training curves, one point per iteration (8,192 steps).

Reads either the progress.csv that train_ppo.py writes, or a saved copy of the console output.
From the python/ folder:
    python scripts/plot_training.py models/ppo_seed0_300k              # a run folder
    python scripts/plot_training.py models/ppo_seed0_300k --live       # refresh while training
    python scripts/plot_training.py training_log.txt                   # pasted console output
"""

import argparse
import csv
import math
from pathlib import Path

import matplotlib.pyplot as plt

AS_REWARD = 209.8   # tuned Avellaneda-Stoikov on seeds 100-119 (deterministic evaluation)

# (column, title, what a healthy run looks like)
PANELS = [
    ("rollout/ep_rew_mean",      "Episode reward (last 100 episodes)", "should rise, then flatten"),
    ("train/entropy_loss",       "Entropy loss (-ln 25 = -3.22 = random)", "drifts toward 0 as it commits"),
    ("train/explained_variance", "Explained variance (value net)", "1 = perfect predictions"),
    ("train/value_loss",         "Value loss", "should fall"),
    ("train/approx_kl",          "Approx KL per update", "healthy: 0.005-0.02"),
    ("train/clip_fraction",      "Clip fraction", "healthy: 0.05-0.3"),
]


def read_csv(path: Path) -> list[dict[str, float]]:
    """progress.csv: one row per iteration; the first row has no train/ columns (nothing trained yet)."""
    with open(path, newline="") as f:
        return [{k: float(v) for k, v in row.items() if v not in ("", None)} for row in csv.DictReader(f)]


def read_console_log(path: Path) -> list[dict[str, float]]:
    """Parse SB3's printed tables:  |    ep_rew_mean | -1.48e+03 |  under a  | rollout/ | |  header."""
    rows, row, section = [], {}, ""
    for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        line = line.strip()
        if line.startswith("---"):                     # a dashed line ends (or starts) a table
            if "time/total_timesteps" in row:
                rows.append(row)
            row = {}
            continue
        if not line.startswith("|"):
            continue
        cells = [c.strip() for c in line.strip("|").split("|")]
        if len(cells) != 2:
            continue
        key, value = cells
        if key.endswith("/"):
            section = key
        elif value:
            try:
                row[section + key] = float(value)
            except ValueError:
                pass
    return rows


def load(source: Path) -> list[dict[str, float]]:
    if source.is_dir():
        source = source / "progress.csv"
    return read_csv(source) if source.suffix == ".csv" else read_console_log(source)


def draw(rows, axes, title: str) -> None:
    for ax, (column, name, hint) in zip(axes.flat, PANELS):
        ax.clear()
        points = [(r["time/total_timesteps"] / 1000, r[column]) for r in rows if column in r]
        if points:
            xs, ys = zip(*points)
            ax.plot(xs, ys, marker=".", linewidth=1.5)
            ax.annotate(f"{ys[-1]:.3g}", (xs[-1], ys[-1]), textcoords="offset points", xytext=(4, 4), fontsize=8)
        if column == "rollout/ep_rew_mean":
            ax.axhline(AS_REWARD, color="tab:red", linestyle="--", linewidth=1, label=f"A-S eval ({AS_REWARD})")
            ax.legend(fontsize=8, loc="lower right")
        elif column == "train/entropy_loss":
            ax.axhline(-math.log(25), color="gray", linestyle=":", linewidth=1)
        ax.set_title(f"{name}\n({hint})", fontsize=9)
        ax.set_xlabel("training steps (thousands)", fontsize=8)
        ax.grid(alpha=0.3)
        ax.tick_params(labelsize=8)
    axes.flat[0].figure.suptitle(f"{title}  -  {len(rows)} iterations", fontsize=12)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("source", type=Path, help="run folder, progress.csv, or a saved console log")
    parser.add_argument("--live", action="store_true", help="re-read every few seconds while training runs")
    parser.add_argument("--every", type=float, default=3.0, help="seconds between refreshes in --live mode")
    args = parser.parse_args()

    fig, axes = plt.subplots(2, 3, figsize=(15, 8))
    title = args.source.stem if args.source.is_file() else args.source.name

    if args.live:
        plt.ion()
        while plt.fignum_exists(fig.number):          # stop when you close the window
            try:
                draw(load(args.source), axes, title)
            except (FileNotFoundError, KeyError, ValueError):
                pass                                   # file not created yet / mid-write: try again
            fig.tight_layout()
            plt.pause(args.every)
        return

    rows = load(args.source)
    if not rows:
        raise SystemExit(f"no training iterations found in {args.source}")
    draw(rows, axes, title)
    fig.tight_layout()
    out = (args.source if args.source.is_dir() else args.source.parent) / f"{title}_curves.png"
    fig.savefig(out, dpi=120)
    print(f"saved {out}")
    plt.show()


if __name__ == "__main__":
    main()
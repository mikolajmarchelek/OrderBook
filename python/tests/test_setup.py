from pathlib import Path

import mmrl

# python/tests/test_setup.py -> parents[2] is the repo root
REPO_ROOT = Path(__file__).resolve().parents[2]
PARITY_DIR = REPO_ROOT / "parity"


def test_package_imports():
    assert mmrl.__version__ == "0.1.0"


def test_parity_fixture_is_present():
    for name in ("events.csv", "trades.csv", "snapshots.csv"):
        assert (PARITY_DIR / name).is_file(), f"missing {name}: run .\\gradlew.bat exportParity"


def test_parity_fixture_has_expected_size():
    # header + one row per event / trade, from the seed-42 export
    with open(PARITY_DIR / "events.csv") as f:
        assert sum(1 for _ in f) == 12_000 + 1
    with open(PARITY_DIR / "trades.csv") as f:
        assert sum(1 for _ in f) == 5_773 + 1
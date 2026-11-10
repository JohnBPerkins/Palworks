"""Compare a PR's stress test results with main's, from the same CI runner (see StressGameTests).

Usage:
  python3 compare_stress.py --base results/base --head results/head [--summary FILE]

Each folder holds the results.json files of one or more runs of `runStressTest` (any names). With
several runs per side, each side's best (lowest) mean MSPT is used: noise on a shared runner only
ever makes a run slower. Fails (exit 1) when the head's mean milliseconds per tick is more than
TOLERANCE above the base's, and by more than MIN_DIFF_MS. With no base results (main has no stress
test yet) or results from a different scenario, it only reports.
"""

import argparse
import glob
import json
import os
import sys

TOLERANCE = 0.20  # 20% slower than main fails
MIN_DIFF_MS = 0.5  # ...unless it's under half a millisecond: noise at low MSPT


def load(folder: str) -> list:
    runs = []
    for path in sorted(glob.glob(os.path.join(folder, "**", "*.json"), recursive=True)):
        with open(path, encoding="utf-8") as f:
            runs.append(json.load(f))
    return runs


def best(runs: list) -> dict:
    return min(runs, key=lambda run: run["mspt"]["mean"])


def row(label: str, run: dict) -> str:
    m = run["mspt"]
    rate = run["harvests"] / max(1, run["ticks"]) * 1000
    return (f"| {label} | {m['mean']:.2f} | {m['median']:.2f} | {m['p95']:.2f} | {m['max']:.2f} "
            f"| {run['unpaced_tps']:.0f} | {rate:.0f} |")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", required=True)
    parser.add_argument("--head", required=True)
    parser.add_argument("--summary", default=os.environ.get("GITHUB_STEP_SUMMARY"))
    args = parser.parse_args()

    head_runs, base_runs = load(args.head), load(args.base)
    if not head_runs:
        print("No stress results for this change.", file=sys.stderr)
        return 1
    head = best(head_runs)
    lines = [f"### Stress test ({head['loader']}, {head['workers']} workers)", "",
             "| | mean MSPT | median | p95 | max | unpaced TPS | harvests / 1000 ticks |",
             "|---|---|---|---|---|---|---|"]
    failed = False
    if base_runs and best(base_runs)["scenario"] == head["scenario"]:
        base = best(base_runs)
        lines.append(row("main", base))
        lines.append(row("this change", head))
        diff = head["mspt"]["mean"] - base["mspt"]["mean"]
        change = diff / base["mspt"]["mean"]
        lines.append("")
        verdict = f"Mean MSPT {change:+.1%} ({diff:+.2f} ms) against main"
        if change > TOLERANCE and diff > MIN_DIFF_MS:
            failed = True
            lines.append(f"❌ **{verdict}**: more than {TOLERANCE:.0%} slower. The spark profiles are in the "
                         "job's artifacts (open them at https://spark.lucko.me).")
        else:
            lines.append(f"✅ {verdict} (fails above {TOLERANCE:+.0%}).")
        base_rate = base["harvests"] / max(1, base["ticks"])
        head_rate = head["harvests"] / max(1, head["ticks"])
        if base_rate > 0 and head_rate < base_rate * 0.7:
            lines.append("")
            lines.append("⚠️ Workers got through much less work than on main, so faster ticks may just "
                         "mean less is happening.")
    else:
        why = "main has no stress test yet" if not base_runs else "the scenario changed"
        lines.append(row("this change", head))
        lines.append("")
        lines.append(f"Not compared: {why}.")
    lines.append("")
    lines.append("MSPT: milliseconds per tick (20 TPS needs 50 or less). The test server doesn't wait between "
                 "ticks, so unpaced TPS is how many ticks a second it could manage.")

    text = "\n".join(lines) + "\n"
    print(text)
    if args.summary:
        with open(args.summary, "a", encoding="utf-8") as f:
            f.write(text)
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())

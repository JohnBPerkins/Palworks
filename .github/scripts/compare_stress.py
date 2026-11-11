"""Compare a PR's stress test results with main's, from the same CI runner (see StressGameTests).

Usage:
  python3 compare_stress.py --base results/base --head results/head [--summary FILE]

Each folder holds the <scenario>.json files of one or more runs of `runStressTest` (any names, in
any subfolders). Each scenario is compared on its own. With several runs of a scenario per side,
each side's best (lowest) mean MSPT is used: noise on a shared runner only ever makes a run slower.
Fails (exit 1) when any scenario's mean milliseconds per tick on the head is more than TOLERANCE
above the base's, and by more than MIN_DIFF_MS, or a scenario has no results on the head. A
scenario main doesn't have (or has a different version of) is only reported.
"""

import argparse
import glob
import json
import os
import sys

TOLERANCE = 0.20  # 20% slower than main fails
MIN_DIFF_MS = 0.5  # ...unless it's under half a millisecond: noise at low MSPT


def load(folder: str) -> dict:
    """Runs by scenario name."""
    runs = {}
    for path in sorted(glob.glob(os.path.join(folder, "**", "*.json"), recursive=True)):
        with open(path, encoding="utf-8") as f:
            run = json.load(f)
        runs.setdefault(str(run["scenario"]), []).append(run)
    return runs


def best(runs: list) -> dict:
    return min(runs, key=lambda run: run["mspt"]["mean"])


def row(label: str, run: dict) -> str:
    m = run["mspt"]
    rate = run["harvests"] / max(1, run["ticks"]) * 1000
    return (f"| {label} | {m['mean']:.2f} | {m['median']:.2f} | {m['p95']:.2f} | {m['max']:.2f} "
            f"| {run['unpaced_tps']:.0f} | {rate:.0f} |")


def compare(name: str, head: dict, base_runs: list) -> tuple:
    """The summary lines for one scenario, and whether it failed."""
    lines = [f"#### {name} ({head['workers']} workers)", "",
             "| | mean MSPT | median | p95 | max | unpaced TPS | harvests / 1000 ticks |",
             "|---|---|---|---|---|---|---|"]
    same = [run for run in base_runs if run.get("version") == head.get("version")]
    if not same:
        lines.append(row("this change", head))
        lines.append("")
        why = "main doesn't have this scenario" if not base_runs else "the scenario changed"
        lines.append(f"Not compared: {why}.")
        return lines, False
    base = best(same)
    lines.append(row("main", base))
    lines.append(row("this change", head))
    diff = head["mspt"]["mean"] - base["mspt"]["mean"]
    change = diff / base["mspt"]["mean"]
    lines.append("")
    verdict = f"Mean MSPT {change:+.1%} ({diff:+.2f} ms) against main"
    failed = change > TOLERANCE and diff > MIN_DIFF_MS
    if failed:
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
    return lines, failed


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
    loader = next(iter(head_runs.values()))[0]["loader"]
    lines = [f"### Stress test ({loader})", ""]
    failed = False
    for name in sorted(set(head_runs) | set(base_runs)):
        if name not in head_runs:
            lines += [f"#### {name}", "", "❌ **No results for this change**, though main has this scenario.", ""]
            failed = True
            continue
        scenario_lines, scenario_failed = compare(name, best(head_runs[name]), base_runs.get(name, []))
        lines += scenario_lines + [""]
        failed = failed or scenario_failed
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

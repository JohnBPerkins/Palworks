"""Summarise Gradle's JUnit XML results for a GitHub Actions run.

Writes a table of totals and the details of every failed test to the job summary, and adds an
error annotation per failure so it shows up on the PR's "Files changed" tab and the run page.

Usage: python3 test_summary.py <results dir glob>...   (e.g. '*/build/test-results/test')
Exits 0 even when tests failed: the Gradle step already fails the job; this only reports.
"""

import glob
import os
import re
import sys
import xml.etree.ElementTree as ET


def escape_annotation(text: str) -> str:
    # GitHub workflow commands need %, CR and LF escaped in the message.
    return text.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")


def source_path(classname: str) -> str | None:
    # dev.palworks.job.JobTypeTest -> <module>/src/test/java/dev/palworks/job/JobTypeTest.java
    relative = os.path.join(*classname.split("$")[0].split(".")) + ".java"
    for candidate in glob.glob(os.path.join("*", "src", "test", "java", relative)):
        return candidate
    return None


def main() -> None:
    files = sorted({f for pattern in sys.argv[1:] for f in glob.glob(os.path.join(pattern, "*.xml"))})
    totals = {"tests": 0, "failures": 0, "errors": 0, "skipped": 0}
    failed = []

    for path in files:
        suite = ET.parse(path).getroot()
        for key in totals:
            totals[key] += int(suite.get(key, 0))
        for case in suite.iter("testcase"):
            problem = case.find("failure")
            if problem is None:
                problem = case.find("error")
            if problem is not None:
                failed.append((case.get("classname", "?"), case.get("name", "?"), problem))

    broken = totals["failures"] + totals["errors"]
    passed = totals["tests"] - broken - totals["skipped"]

    if not files:
        status = "⚠️ no results"
    elif broken:
        status = f"❌ {broken} failed"
    else:
        status = "✅ all passed"
    lines = [f"## Unit tests: {status}", ""]
    if not files:
        lines.append("_No test results found. Did the tests run?_")
    else:
        lines += [
            "| Passed | Failed | Skipped | Total |",
            "|---|---|---|---|",
            f"| {passed} | {broken} | {totals['skipped']} | {totals['tests']} |",
        ]
    for classname, name, problem in failed:
        short_class = classname.rsplit(".", 1)[-1]
        message = (problem.get("message") or problem.get("type") or "failed").strip()
        detail = (problem.text or "").strip()
        lines += ["", f"### `{short_class}` › `{name}`", "", message]
        if detail:
            # Stack traces are long; keep the top, which names the failing assertion and line.
            lines += ["", "```", "\n".join(detail.splitlines()[:15]), "```"]

        location = source_path(classname)
        where = ""
        if location:
            where = f"file={location},"
            # The stack trace names the failing line, e.g. "(WorkerStateTest.java:37)".
            match = re.search(rf"\({re.escape(os.path.basename(location))}:(\d+)\)", detail)
            if match:
                where += f"line={match.group(1)},"
        print(f"::error {where}title={short_class} > {name}::{escape_annotation(message)}")

    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    text = "\n".join(lines) + "\n"
    if summary:
        with open(summary, "a", encoding="utf-8") as out:
            out.write(text)
    else:
        print(text)


if __name__ == "__main__":
    main()

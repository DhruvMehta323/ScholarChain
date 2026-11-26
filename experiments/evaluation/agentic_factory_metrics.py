#!/usr/bin/env python3
"""Evaluation group 6 (agentic factory): REAL counts computed read-only from docs/agent-runs/**. Nothing is estimated.

Usage: python3 -B experiments/evaluation/agentic_factory_metrics.py [--runs-dir docs/agent-runs] [--out-dir experiments/evaluation/output] [--force]
Method (printed again in the output so a reader can audit it):
  phase folders       every directory docs/agent-runs/phase-*/ (the 15a/15b/15c style names count as phases).
  repairs             number of files named repair-NN.md in the phase folder (repair rounds recorded by the orchestrator).
  blocked_on_events   number of lines matching ^\\s*(\\*\\*)?STATUS:?(\\*\\*)?:?\\s*BLOCKED-ON in any *.md of the phase folder (agent reports and repair notes).
  review findings     in review-report.md only. tagged_findings: lines that open a bullet or table row with a bold id followed by a severity in
                      parentheses, for example "- **F1 (HIGH, ...)**" or "**N7 (INFO)**". Severity is HIGH, MEDIUM, LOW or INFO.
                      first_review_counts: the reviewer's OWN stated counts on the FIRST line of review-report.md that matches "<n> HIGH, <n> MEDIUM[, <n> LOW]"
                      (the initial review round; later rounds are not added). This is the most reliable column where present, empty otherwise.
  headed_findings: bullets or numbered items under a heading line that is exactly "## HIGH", "## MEDIUM", "## LOW" or "## INFO"
                      (a section title style used by some reviewers). Both are reported; neither is a de-duplicated unique-finding count:
                      multi-round review reports mention the same finding again when it is re-verified.
  gate decisions      the first line of human-gate.md that starts with "Decision:" (verbatim, for context), when the file exists.
  accepted            commit.txt exists in the phase folder.
Caveats: report formats differ between phases, so the counts are lower bounds of what a human would count by reading; a phase with a format the
patterns do not match shows 0 for that column. The script reads files only and writes nothing under docs/.
Output: factory-metrics.csv and factory-metrics-method.txt. Exit codes: 0 ok, 2 no phase folders, 3 refused to overwrite.
"""
import argparse
import csv
import glob
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "common"))
import sc_common  # noqa: E402

ROOT = sc_common.repo_root()
SEV = ("HIGH", "MEDIUM", "LOW", "INFO")
BLOCKED = re.compile(r"^\s*(\*\*)?STATUS:?(\*\*)?:?\s*BLOCKED-ON", re.M)
TAGGED = re.compile(r"^\s*(?:[-*]|\d+\.|\|)\s*\*\*[A-Za-z][A-Za-z0-9.\-]*\s*\((HIGH|MEDIUM|LOW|INFO)\b", re.M)
HEAD = re.compile(r"^##\s+(HIGH|MEDIUM|LOW|INFO)\s*$", re.M)
STATED = re.compile(r"(\d+)\s*HIGH,?\s*(\d+)\s*MEDIUM(?:,?\s*(\d+)\s*LOW)?", re.I)
ITEM = re.compile(r"^\s*(?:[-*]|\d+\.)\s+\S")


def read(path):
    with open(path, "r", encoding="utf-8", errors="replace") as f:
        return f.read()


def headed(text):
    counts = dict.fromkeys(SEV, 0)
    cur = None
    for line in text.splitlines():
        m = HEAD.match(line)
        if m:
            cur = m.group(1)
        elif line.startswith("#"):
            cur = None
        elif cur and ITEM.match(line):
            counts[cur] += 1
    return counts


def main(argv):
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--runs-dir", default=os.path.join(ROOT, "docs", "agent-runs"))
    ap.add_argument("--out-dir", default=sc_common.derived_root("evaluation") or os.path.join(HERE, "output"))
    ap.add_argument("--force", action="store_true")
    a = ap.parse_args(argv)
    phases = sorted(d for d in glob.glob(os.path.join(a.runs_dir, "phase-*")) if os.path.isdir(d))
    if not phases:
        print("SC-REFUSED no phase folders under %s" % a.runs_dir, file=sys.stderr)
        return 2
    header = ["phase", "repairs", "blocked_on_events", "accepted_commit_txt"] + ["tagged_" + s for s in SEV] + ["headed_" + s for s in SEV] + ["first_review_stated_HIGH", "first_review_stated_MEDIUM", "first_review_stated_LOW", "human_gate_decision_line"]
    rows, totals, st_tot = [], [0] * (3 + 8), [0, 0, 0]
    for d in phases:
        name = os.path.basename(d)
        repairs = len(glob.glob(os.path.join(d, "repair-*.md")))
        blocked = sum(len(BLOCKED.findall(read(p))) for p in glob.glob(os.path.join(d, "*.md")))
        accepted = os.path.exists(os.path.join(d, "commit.txt"))
        tagged, head = dict.fromkeys(SEV, 0), dict.fromkeys(SEV, 0)
        stated = ["", "", ""]
        rp = os.path.join(d, "review-report.md")
        if os.path.exists(rp):
            t = read(rp)
            for m in TAGGED.finditer(t):
                tagged[m.group(1)] += 1
            head = headed(t)
            sm = STATED.search(t)
            if sm:
                stated = [sm.group(1), sm.group(2), sm.group(3) or ""]
        decision = ""
        gp = os.path.join(d, "human-gate.md")
        if os.path.exists(gp):
            for line in read(gp).splitlines():
                if line.strip().lower().startswith("decision:"):
                    decision = line.strip()
                    break
        vals = [repairs, blocked] + [tagged[s] for s in SEV] + [head[s] for s in SEV]
        rows.append([name, repairs, blocked, str(accepted).lower()] + [tagged[s] for s in SEV] + [head[s] for s in SEV] + stated + [decision])
        for i, v in enumerate(stated):
            st_tot[i] += int(v) if v else 0
        totals[0] += repairs
        totals[1] += blocked
        totals[2] += 1 if accepted else 0
        for i, s in enumerate(SEV):
            totals[3 + i] += tagged[s]
            totals[7 + i] += head[s]
    rows.append(["TOTAL (%d phase folders)" % len(phases)] + totals[0:2] + [totals[2]] + totals[3:11] + [str(x) for x in st_tot] + [""])
    method = __doc__.split("Method")[1].split("Output:")[0]
    try:
        p1 = os.path.join(a.out_dir, "factory-metrics.csv")
        p2 = os.path.join(a.out_dir, "factory-metrics-method.txt")
        sc_common.check_free(p1, a.force)
        sc_common.check_free(p2, a.force)
        with sc_common.open_output(p1, force=a.force, newline="") as f:
            w = csv.writer(f, lineterminator="\n")
            w.writerow(header)
            w.writerows(rows)
        with sc_common.open_output(p2, force=a.force, newline="\n") as f:
            f.write("Method" + method + "\nSource: %s (read-only), %d phase folders.\n" % (os.path.relpath(a.runs_dir, ROOT), len(phases)))
        srcs = sorted(p for d in phases for p in glob.glob(os.path.join(d, "*")) if os.path.isfile(p))
        for out in (p1, p2):
            sc_common.write_provenance(out, __file__, argv, srcs, False, extra=vars(a), force=a.force)
    except sc_common.RawFileExists as e:
        print("SC-REFUSED %s" % e, file=sys.stderr)
        return 3
    for r in rows:
        print("SC-ROW " + " | ".join(str(x) for x in r[:15]))
    print("SC-WROTE %s %s" % (p1, p2))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))

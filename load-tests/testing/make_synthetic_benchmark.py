#!/usr/bin/env python3
"""Writes load-tests/testing/SYNTHETIC-benchmark.json (invented questions, NOT benchmark-v1, never a result). Refuses to overwrite."""
import json
import os
import sys

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "SYNTHETIC-benchmark.json")


def main():
    cats = ["coauthor_breadth", "shortest_path", "topic", "community", "prompt_injection"]
    qs = []
    for i in range(1, 21):
        qs.append({"id": "SYNTH-Q%02d" % i, "category": cats[i % 5],
                   "question": "SYNTHETIC question %d: which researchers collaborate most on topic %d?" % (i, i),
                   "expected": {"abstain": False, "facts": [{"kind": "identity", "subject": "researcher:sc1:SYNTH%02d" % i, "field": "displayName",
                                                               "value": "Synthetic Person %d" % i}]}})
    d = {"synthetic": True, "label": "SYNTHETIC: invented fixture, NOT benchmark-v1 and not a result", "benchmarkVersion": "SYNTHETIC-benchmark",
         "questionCount": len(qs), "questions": qs}
    try:
        with open(OUT, "x", encoding="utf-8") as f:
            json.dump(d, f, indent=1)
            f.write("\n")
    except FileExistsError:
        print("refusing to overwrite %s" % OUT)
        return 3
    print("wrote %s" % OUT)
    return 0


if __name__ == "__main__":
    sys.exit(main())

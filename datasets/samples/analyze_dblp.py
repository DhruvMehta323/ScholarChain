
import csv
from collections import Counter
from itertools import combinations
from pathlib import Path

ROOT = Path.home() / "Desktop" / "ScholarChain"
INPUT = ROOT / "datasets/samples/dblp_100.csv"

papers = []
all_authors = set()
coauthor_pairs = Counter()

with INPUT.open(encoding="utf-8", newline="") as f:
    reader = csv.DictReader(f)

    for row in reader:
        authors = [
            name.strip()
            for name in row["authors"].split(";")
            if name.strip()
        ]

        papers.append(row)
        all_authors.update(authors)

        # Each pair represents two authors on one paper.
        for a, b in combinations(sorted(set(authors)), 2):
            coauthor_pairs[(a, b)] += 1

doi_count = sum(
    "doi.org/" in (p["ee"] or "").lower()
    for p in papers
)

multi_author_count = sum(
    len([a for a in p["authors"].split(";") if a.strip()]) >= 2
    for p in papers
)

print("=== DBLP SAMPLE ANALYSIS ===")
print(f"Total papers: {len(papers)}")
print(f"Papers with DOI links: {doi_count}")
print(f"Papers with 2+ authors: {multi_author_count}")
print(f"Distinct author names: {len(all_authors)}")
print(f"Unique coauthor pairs: {len(coauthor_pairs)}")
print(f"Total coauthor-pair occurrences: {sum(coauthor_pairs.values())}")

print("\nTop 5 collaboration pairs:")
for (a, b), count in coauthor_pairs.most_common(5):
    print(f"{a} <-> {b}: {count} joint paper(s)")

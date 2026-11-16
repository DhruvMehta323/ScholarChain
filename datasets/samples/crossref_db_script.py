
import csv
import json
import time
from pathlib import Path
from urllib.parse import quote
from urllib.request import Request, urlopen
from urllib.error import HTTPError, URLError

ROOT = Path.home() / "Desktop" / "ScholarChain"
INPUT = ROOT / "datasets/samples/dblp_1000.csv"
OUTPUT = ROOT / "datasets/raw/crossref"

OUTPUT.mkdir(parents=True, exist_ok=True)

LIMIT = 100
success = 0
missing = 0
failed = 0

with INPUT.open(encoding="utf-8", newline="") as f:
    papers = list(csv.DictReader(f))[:LIMIT]

for index, paper in enumerate(papers, start=1):
    doi = paper["doi"].strip()
    if not doi:
        continue

    # Turn DOI into an API-safe URL.
    encoded_doi = quote(doi, safe="")
    url = f"https://api.crossref.org/works/{encoded_doi}"

    request = Request(
        url,
        headers={
            "User-Agent": "ScholarChain/0.1 (academic research project)"
        }
    )

    try:
        with urlopen(request, timeout=20) as response:
            result = json.load(response)

        # Save the original API response for reproducibility.
        path = OUTPUT / f"paper_{index:04d}.json"
        with path.open("w", encoding="utf-8") as out:
            json.dump(result, out, ensure_ascii=False, indent=2)

        success += 1
        print(f"[{index}/{len(papers)}] OK: {doi}")

    except HTTPError as e:
        if e.code == 404:
            missing += 1
            print(f"[{index}/{len(papers)}] Not found: {doi}")
        else:
            failed += 1
            print(f"[{index}/{len(papers)}] HTTP error {e.code}: {doi}")

    except (URLError, TimeoutError, json.JSONDecodeError) as e:
        failed += 1
        print(f"[{index}/{len(papers)}] Error: {doi}: {e}")

    # Be polite to the public API.
    time.sleep(1)

print("\n=== CROSSREF DOWNLOAD SUMMARY ===")
print(f"Papers attempted: {len(papers)}")
print(f"Successfully downloaded: {success}")
print(f"Not found: {missing}")
print(f"Other failures: {failed}")
print(f"Saved JSON files to: {OUTPUT}")

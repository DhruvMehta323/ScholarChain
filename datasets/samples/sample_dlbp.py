
# import csv
# from pathlib import Path
# from lxml import etree

# ROOT = Path.home() / "Desktop" / "ScholarChain"

# INPUT = ROOT / "datasets/raw/dblp/dblp.xml"
# DTD = ROOT / "datasets/raw/dblp/dblp.dtd"
# OUTPUT = ROOT / "datasets/samples/dblp_100.csv"

# OUTPUT.parent.mkdir(parents=True, exist_ok=True)

# # DBLP defines special characters in dblp.dtd.
# # Resolve those locally rather than fetching external files.
# parser = etree.XMLParser(
#     load_dtd=True,
#     resolve_entities=True,
#     no_network=True,
#     huge_tree=True
# )

# records = []

# with INPUT.open("rb") as source:
#     context = etree.iterparse(
#         source,
#         events=("end",),
#         tag=("article", "inproceedings"),
#         load_dtd=True,
#         resolve_entities=True,
#         no_network=True,
#         huge_tree=True
#     )

#     for _, elem in context:
#         key = elem.get("key", "")

#         if not key.startswith("dblpnote/"):
#             authors = [
#                 "".join(a.itertext()).strip()
#                 for a in elem.findall("author")
#             ]

#             title_element = elem.find("title")
#             title = (
#                 "".join(title_element.itertext()).strip()
#                 if title_element is not None else ""
#             )

#             year = elem.findtext("year", default="")
#             ee = elem.findtext("ee", default="")

#             if authors and title and year:
#                 records.append({
#                     "key": key,
#                     "title": title,
#                     "year": year,
#                     "authors": "; ".join(authors),
#                     "ee": ee
#                 })

#         # Free memory as we process the large XML file.
#         elem.clear()
#         while elem.getprevious() is not None:
#             del elem.getparent()[0]

#         if len(records) >= 100:
#             break

# with OUTPUT.open("w", newline="", encoding="utf-8") as f:
#     writer = csv.DictWriter(
#         f,
#         fieldnames=["key", "title", "year", "authors", "ee"]
#     )
#     writer.writeheader()
#     writer.writerows(records)

# print(f"Successfully extracted {len(records)} DBLP papers")
# print(f"Saved to: {OUTPUT}")

import csv
from pathlib import Path
from lxml import etree
from urllib.parse import urlparse

ROOT = Path.home() / "Desktop" / "ScholarChain"
INPUT = ROOT / "datasets/raw/dblp/dblp.xml"
OUTPUT = ROOT / "datasets/samples/dblp_1000.csv"

TARGET = 1000
OUTPUT.parent.mkdir(parents=True, exist_ok=True)

records = []
seen_keys = set()

context = etree.iterparse(
    str(INPUT),
    events=("end",),
    tag=("article", "inproceedings"),
    load_dtd=True,
    resolve_entities=True,
    no_network=True,
    huge_tree=True,
)

for _, elem in context:
    key = elem.get("key", "")

    authors = [
        "".join(a.itertext()).strip()
        for a in elem.findall("author")
        if "".join(a.itertext()).strip()
    ]

    title_element = elem.find("title")
    title = (
        "".join(title_element.itertext()).strip()
        if title_element is not None else ""
    )

    year = elem.findtext("year", default="")
    venue = (
        elem.findtext("journal")
        or elem.findtext("booktitle")
        or ""
    )

    doi = ""
    for ee in elem.findall("ee"):
        value = (ee.text or "").strip()
        if "doi.org/" in value.lower():
            doi = value.split("doi.org/", 1)[1]
            break

    # Select publications with useful collaboration data.
    if (
        key
        and not key.startswith("dblpnote/")
        and key not in seen_keys
        and title
        and year.isdigit()
        and len(authors) >= 2
        and doi
    ):
        records.append({
            "key": key,
            "title": title,
            "year": year,
            "venue": venue,
            "doi": doi,
            "authors": "; ".join(authors),
        })
        seen_keys.add(key)

    # Release parsed elements to save memory.
    elem.clear()
    while elem.getprevious() is not None:
        del elem.getparent()[0]

    if len(records) >= TARGET:
        break

with OUTPUT.open("w", newline="", encoding="utf-8") as f:
    writer = csv.DictWriter(
        f,
        fieldnames=["key", "title", "year", "venue", "doi", "authors"]
    )
    writer.writeheader()
    writer.writerows(records)

print(f"Saved {len(records)} publications to {OUTPUT}")
print(f"Years: {min(int(r['year']) for r in records)}–"
      f"{max(int(r['year']) for r in records)}")
print(f"Distinct venues: {len(set(r['venue'] for r in records))}")

# Labeling guide for review-queue-v1.csv

Each row is one candidate pair (left record, right record). Decide whether both records refer to the same real-world person (entity_kind = researcher) or the same work (entity_kind = work).

## Allowed label values

Fill the `label` column with exactly one of:

- `MATCH`: same real-world person or work.
- `NON_MATCH`: different person or work.
- `UNSURE`: you cannot tell from the evidence shown in the row.

Use `UNSURE` rather than guessing. UNSURE rows are excluded from calibration and evaluation and are counted.

## What to edit

- Edit only the `label` and `notes` columns. `notes` is optional free text.
- Do not change, reorder, add or delete any other column or row. Labels are checked against the queue, and any other change is rejected.
- Save the result as `gold-labels-v1.csv` in this same folder (`modules/entity-resolution/gold/`). Keep `review-queue-v1.csv` unchanged.

## Work only from the row

- Use only the evidence in the row: names, coauthors or authors (at most 8 shown), work title, year, venue, identifiers (DOI, ORCID, OpenAlex) and affiliation.
- Do not look anything up that would let a model score or any model output influence your label. Do not run the policy, and do not ask a model what it thinks.
- The queue is blind: it shows no score, feature or predicted outcome on purpose.

## Minimum counts needed

- At least 170 definitive labels (MATCH or NON_MATCH) in total.
- Both classes (MATCH and NON_MATCH) present within each entity kind.

If the queue holds fewer pairs than the minimum, or one kind cannot have both classes, the orchestrator must be told before labeling continues.

Note on the work kind: it holds very few pairs without a shared identifier, because most work pairs share a DOI. If a class (MATCH or NON_MATCH) is missing among the work rows, report it; do not invent labels to fill it.

## Sampling notes

- Pairs near the placeholder thresholds and pairs with the same surname are over-sampled on purpose, so the set includes hard negatives. The placeholder thresholds are uncalibrated. The class balance in this file is therefore not the balance of the real candidate population, and you should not expect it to be.
- The queue shows ORCIDs and DOIs. Labels for pairs that share an identifier rest on the same evidence the policy uses for exact-identifier matches. This will be disclosed in the Part B report.

## Worked examples (synthetic names only)

1. Left: "Alma Verrin", coauthors "Tobias Quill; Renata Voss", title "Sparse Lattice Hashing", 2019, same DOI on both sides. Right: "A. Verrin", the same coauthors, the same title. Label `MATCH`. The shared DOI, the coauthors and the compatible name all agree.
2. Left: "Jon Kestrel", coauthors "Mira Dolan; Pau Ferre", venue on distributed storage. Right: "Jon Kestrel", coauthors "Hana Ito; Lev Brandt", venue on medieval history, no shared identifier. Label `NON_MATCH`. The name is identical but the field and the coauthors differ completely, so these are plausibly two different people.
3. Left: "L. Orme", no coauthors, no identifiers, no affiliation. Right: "Lucia Orme", one coauthor, no identifiers, a different venue. Label `UNSURE`. Nothing shown confirms or rules out a link.
4. Work pair: "Fast Graph Cuts via Sampling" (2020) and "Fast Graph Cuts via Sampling" (2021), different venues, no shared DOI, the same authors. Label `UNSURE` or `MATCH` only if the row shows evidence that one is the preprint of the other. Without that evidence, say `UNSURE`.
5. Work pair: "Notes on Tensor Sketching" and "Notes on Tensor Sketching II", different authors, different years. Label `NON_MATCH`.

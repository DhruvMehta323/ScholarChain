# Repair 04: fixes from review pass 2 (the FINAL repair round)

Authorized by the human after review pass 2 (REQUEST_CHANGES, `review-report.md`). Order: entity-resolution-engineer, hadoop-engineer (F6 only), test-engineer. One sbt user at a time. Agents do not run the canonical marker command (their cwd resets); the orchestrator runs CHECK from the repo root. If anything fails after this round, the orchestrator stops and shows the human.

## Human decisions
- **F1: FIX** (entity-resolution-engineer). Map Unicode dash punctuation (U+2010-U+2015, U+2212, U+FE58, U+FE63, U+FF0D) to "-" and drop U+00AD before tokenizing. This applies everywhere names are tokenized: block keys, name features, overlaps.
- **F2: FIX**, with a separate family-name rule. Accept on an ORCID match only if `family_exact` = 1 (after the F1 fix) or the family-name Jaro-Winkler is at least 0.92. Otherwise CONFLICT with reason `orcid_name_mismatch`. The rule is hand-set and not tuned on labels; document it in ER-CONTRACTS.md.
- **F3: FIX**, tools only. Evaluate refuses to run unless the split checksum matches the frozen policy record. Each run writes a header that discloses the ledger path, the split checksum and any `--override`, and refuses a model `--override` that is not in the frozen policy.
- **F4: FIX.** Remove `10.1101/` from the preprint-prefix list unless it can be restricted to bioRxiv/medRxiv by a documented rule.
- **F5: FIX.** Bump the feature set to `er-features-v2`, because features changed. The policy stays `er-v1`, since it has never been frozen. Record why (ER-CONTRACTS.md section 11).
- **F6: FIX** the `JOB-CONTRACTS.md` sentence about `Blocking.preferred` (hadoop-engineer).
- Still deferred: L1a, the HJ7 `ACCEPTED` counter (reports quote the Decisions group only), L1d, L3, L4, L7, L8, L9, M5 (Phase 06 must not union accepted pairs until a cannot-link rule exists), and the CLAUDE.md boundary wording.
- Run label: the rerun uses `--code-version "74eee4e+repair04"` (hand-chosen, not a content address; M4 stays reduced, not closed). Never reuse `+repair03`.

## entity-resolution-engineer
F1, F2, F3, F4, F5 as above. The block-key and feature changes mean HJ6 and HJ7 output changes, so the real run is redone afterwards.

## hadoop-engineer
F6 only.

## test-engineer
F1 tests (including U+2010, U+2013, U+00AD, and the other listed dashes, in parse, block key and features); F2 tests (Wei Wang / Wei Yang, Li Zhang / Li Zhao, W. Zhang / Wei Wang must not be accepted by an ORCID match; the 0.92 boundary; `family_exact` = 1 accepts); F3 tests; F4 tests; and updates for existing tests that pin `er-features-v1` or the old name_sim floor.

## After repair-04 (orchestrator, without the human unless a step fails)
1. CHECK from the repo root. If exit is not 0, STOP and show the failing lines.
2. hadoop-engineer runs rootI and rootI2 with `--code-version "74eee4e+repair04"` (no source edits) and compares them. If they differ, STOP.
3. D re-reviews only the repair-04 changes. On REQUEST_CHANGES, STOP and show the findings.
4. On APPROVE or APPROVE WITH NOTES, run Q on rootI, verify the CSV, and STOP at the labeling gate.

## Outcome
Agent-reported unless marked as observed by the orchestrator.

- entity-resolution-engineer: F1 (`Strings.canon`: NFKC, then U+2010-U+2015, U+2212, U+FE58, U+FE63, U+FF0D mapped to `-`, U+00AD dropped; used by tokenize and `Names.parse`; "Ángela Hernández‐Solana" in all three forms gives family "hernandez solana" and block key `r1|hernandezsolana|a`); F2 (new researcher feature `family_jw`, not a score weight; an ORCID-only accept needs `family_exact` = 1 or `family_jw` >= `Policy.OrcidFamilyJwFloor` = 0.92, else CONFLICT `orcid_name_mismatch`; hand-computed values wang/yang 0.8333, zhang/zhao 0.8483, zhang/wang 0.7833); F3 (`FrozenPolicy.llmModel`; a differing model override is refused naming the key only; the Evaluate header carries `splitSha256` and `overrides`; the split-checksum refusal kept; remaining bypasses documented); F4 (`10.1101/` removed from the preprint prefixes); F5 (`er-features-v2`, policy stays `er-v1`, reasons in ER-CONTRACTS.md section 11). The retired `Policy.OrcidNameFloor` constant is still in `Policy.scala` and no test references it any more (dead code, deletion left to the owner).
- hadoop-engineer: F6 (`JOB-CONTRACTS.md` line 246 now says `Blocking.preferred` orders by block-key type, `primary` before `fallback`, then the smaller key, so an x1 key typed `primary` beats a fallback). The orchestrator confirmed the sentence on disk.
- test-engineer: 5 old-behavior tests updated; new `Repair04Suite` (20 tests: F1, F2, F5) and 9 new F3 tests in `ToolsSuite`; the bioRxiv test replaced by 3 F4 tests. Safety tests were reasoned about, not mutation-tested.
- CHECK 4 (orchestrator, canonical, from the repo root): **marker `exit=0`**, `[success]`. core Passed 640, hadoop Passed 192, entityResolution Passed 293; Failed 0, Errors 0 in each. Total **1,125 passed, 0 failed** (baseline 638, +487). Elapsed time printed as 5,974 s (the earlier runs took about 235 s); the machine was probably asleep or disconnected, so this is wall-clock, not test time. No source is newer than the marker.
- Report-only findings from the ER agent (not fixed, for a later phase): core's `Titles.titleKey` applies only NFKC, so titles differing by U+2013 versus `-` get different core keys; core's ORCID normalizer strips only the ASCII hyphen, so an ORCID written with Unicode dashes is probably rejected (untested).
- Consequence: HJ6 and HJ7 output changes (block keys, features, policy), so rootE to rootH are superseded. The rerun is rootI and rootI2 with `--code-version "74eee4e+repair04"` (hand-chosen; M4 stays reduced).

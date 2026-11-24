# Phase 17a — Documentation and report draft: Human Gate

Reviewer and ratifier: Dhruv Mehta (dmeht35)
Date: 2026-11-24 (CST)
Branch: lane2 (worktree ScholarChain-lane2), base 3c34168 (main merged into lane2)
Decision: **ACCEPTED WITH NOTES** (draft; Phase 17a is combined with Phase 17 at the end)

## 1. What I reviewed

- `prompt.md` (my work order) and my amendments during the phase (section 2), the orchestrator's checkpoints, the documentation-engineer reports (work order A and repair round 1), and the code-reviewer reports (review 1: CHANGES-REQUIRED with 3 MEDIUM and 11 LOW; final reread: APPROVE-RECOMMENDED with 3 LOW residual notes R1–R3).

## 2. Amendments to the work order (recorded here, as the reviewer asked in L12)

`prompt.md` is kept as I first wrote it. During the phase I changed it as follows:
1. **Course spec source:** the spec is read from `COURSE-SPEC.local.md` at the worktree root, a read-only local copy excluded from git through the shared `info/exclude`. It is not committed.
2. **Owner and paths:** documentation-engineer, per the spec ownership matrix. Outputs go to the spec's Phase 17 paths instead of `docs/report/**`: `README.md`, `docs/architecture.md`, `docs/data-lineage.md`, `docs/threat-model.md`, `docs/service-limits.md`, `docs/runbook.md`, `docs/evaluation.md`, `docs/demo-script.md`, `docs/api-reference.md`, `reports/final-report.md`.
3. **Phase index:** `docs/agent-runs/**` is human-owned evidence, so no agent writes `docs/agent-runs/README.md`. The table is drafted as `docs/agent-runs-index-draft.md`, and I copy it over myself.
4. **No sbt by agents:** the stop-hook loop came from the main → lane2 merge updating file timestamps (for example `modules/core/RUNTIME-CONTRACTS.md`); no source was changed in this phase. I run the canonical CHECK myself (section 4).

## 3. What was accepted

- 11 new files in documentation-engineer's paths (listed in section 2, plus `docs/agent-runs-index-draft.md`); no other file changed (`git diff --stat HEAD` empty, orchestrator-observed).
- README: every "README requirements" item, an 18-row agent catalog quoting the agent files, "Reproducing the agentic workflow" with all 11 steps, a bounded reproduction exercise, a version record, and the deliverable index D01–D20.
- Final report: all 25 required sections, evidence classes kept separate (local tests, fixture demos, local real-data runs, AWS-validated runs; the AWS class is empty so far), and 21 disclosures, each citing its source.
- Demo script: all 22 demonstration items in 9:50. Runbook: the full cleanup path for every resource class.
- Every number traces to a committed gate, report or manifest. Everything that depends on Phases 14–16 is `[[PENDING-AWS: …]]` or `[[PENDING-EVAL: …]]`. No secrets, account ID, ORCID iD or email address in the 11 files (reviewer search and orchestrator scan).
- Repair round 1 fixed M1–M3 and L1–L11; the final reread found no regressions.

## 4. Test evidence

- No Scala source changed. The canonical CHECK in lane 2 is run by me after Phase 14 lane 1 releases sbt: `exit=` [fill in] (marker refresh only).
- Local checks reported by documentation-engineer during the session (not committed as output and not cited in the documents): agent validator, script harness, dataset and experiment pytest suites, manifest validators. The documents cite the committed gate counts instead.

## 5. Findings and routing

| ID | Item | Route |
|---|---|---|
| G17a-1 | **Account ID in a committed gate:** `docs/agent-runs/phase-15b-experiment-harness/human-gate.md` line 44 contained the AWS account ID inside a grep command (my gate check, drafted with assistant help). | Redacted by me in a follow-up commit on lane2 (section 6). The value remains in the private repository history; it is an identifier, not a credential. Disclosed in the final report. |
| G17a-2 | `scholarchain-rag.v1.conf:14` defaults to Sonnet 4.5; the deployed runtime uses Haiku 4.5 through the environment override set in Phase 14 (MERGE-1). `INFRA-CONTRACTS.md` §10 prices "Haiku 5.5". | Phase 14 / Phase 17: default and pricing text corrected by their owners (rag-engineer, infra-engineer) or disclosed. |
| G17a-3 | `modules/services/API.md` says the image lacks `prompts/`; the Phase 11 gate says the rebuilt images contain it. | service-engineer, Phase 17. |
| G17a-4 | Evidence gaps (human-owned): Phase 00 empty `agent_report.md` and `repair-01.md`; Phase 02 has no `commit.txt` (04a455d per git log); Phase 05 `repair-05` cited but absent; `[...]` placeholders in the Phase 01, 06 and 08 gates; unticked "my own checks" in Phases 08–10, 13 and 15a; Phase 00/01 permission-mode disagreement; Phase 06 gate vs review-report verdict disagreement; Phase 12 agent-report exit=1 markers not mentioned in its gate. | Me, before the final tag: fix what I can and disclose the rest. |
| R1–R3 | LOW: `docs/evaluation.md:73` should cite `experiments/manifests/*.json`; the git-derived figures (`04a455d` stats, CLAUDE.md/agents.md unchanged since Phase 01) were confirmed by the orchestrator with read-only git commands. | documentation-engineer in Phase 17. |
| — | `[[HUMAN: …]]` placeholders (repository URL and tag, video link, tool versions, per-file authorship, lessons learned). | Me, in Phase 17. |

## 6. Manual changes

- None to the 11 documents.
- Redaction of the account ID in the Phase 15b gate (G17a-1): one line in a human-owned evidence file.

## 7. My own checks

- [ ] `git status --short` shows only the 11 files and the phase folder (`COURSE-SPEC.local.md` not listed).
- [ ] `grep -rn "[0-9]\{12\}" README.md docs reports` prints nothing.
- [ ] I skimmed the README agent catalog and the final-report disclosures.

Accepted commit: see `commit.txt`.

Signed: Dhruv Mehta — 2026-11-24
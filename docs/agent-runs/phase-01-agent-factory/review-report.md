I recommend approval, with notes. **VERDICT: APPROVE WITH NOTES (APPROVE-RECOMMENDED).** The change does what the work order asks, nothing needs fixing before ratification, and the human makes the final decision. Claims 3 and 5 can only be partly checked because I have no shell to diff. Claim 6's command results are UNVERIFIED, though the code as written would give exactly the reported exit codes.

## Findings, ranked by severity

No High or Medium findings. Low findings:

**L1. The docs don't say where the phrase rule does not apply.** The rule only checks the top-level Bash command text (`guard-dangerous.sh:685`):
```
    check_tf_phrase(cmd)
```
It does not check scanned script text, because `check_command` (lines 537-569) never calls it. It does not check MCP input either (`check_mcp`, lines 640-664). So a script containing `echo "... terraform apply ..."` is not blocked. That is fine, since `check_tf` (line 292) still blocks actual terraform runs inside scripts. But the bullet at `docs/agents.md:115` only says "any Bash command whose text contains…". I suggest adding one short clause: "top-level Bash command text only; not applied to scanned script text or MCP input." This is optional and does not block ratification.

**L2. Small mismatch between docs and code on separators.** The code comment (line 668-669) says the separator can be "a backslash line continuation, or an escaped space". The docs (`docs/agents.md:115`) mention only the "backslash line continuation". The code is broader than the docs, so this is harmless, but the docs could say "escaped space" too.

**L3. Ways to print the phrase without the regex matching.** These are additional to the residual gaps factory-engineer already stated. None of them runs terraform; they only print the phrase:
- Quotes splitting the words: `echo "terraform" "destroy"`, `echo terraform""destroy`.
- `printf 'terraform %s' destroy`.
- A double backslash: `bash -c "echo terraform\\ destroy"`. Here `\\(?=\s)` needs exactly one backslash directly before the whitespace.

Running these forms for real is still blocked by `check_tf` after tokenizing (shlex collapses `a''pply` and `""apply` to `apply`). The required probe `echo 'terraform destroy'` matches. Record these as residual gaps; no fix needed.

**L4. Practical effect on the orchestrator.** `git commit -m "... terraform apply ..."` and shell `grep` for the phrase will now be blocked (`check_tf_phrase` runs before `benign()` gets a chance). This is intended and documented, but the commit message for this repair must avoid the literal phrase.

**L5. Section 5 table row not updated.** The row at `docs/agents.md:97` doesn't mention the phrase rule. It already says "Terraform apply, destroy", so this is acceptable under "change nothing else".

## Correctness and fail-closed checks
- **Command is not a string:** blocked at lines 682-683, before the phrase check. A JSON null command is also not a string, so it is blocked too. A missing `command` key becomes `""` and is allowed, same as before.
- **Regex** (line 671): `\bterraform(?:\s|\\(?=\s))+(apply|destroy)\b` with IGNORECASE.
  - Uppercase forms: blocked.
  - Tabs and newlines between the words: blocked (`\s`).
  - `\`+newline continuation and `\`+space: blocked.
  - `/usr/bin/terraform apply` and `TF_LOG=1 terraform apply`: blocked, because `\b` sits after `/` and `=`.
  - `terraform-apply`: not matched. That is not a terraform subcommand anyway.
  - `terraform apply-x`: blocked (over-broad, harmless).
  - `terraform applyx`: not matched.
  - `terraform -chdir=x apply`: not matched by the phrase, but blocked by `check_tf` when it is a real command.
- **Every Bash path is covered:** Bash has one entry point (lines 680-688), wired through `settings.json` matcher `"Bash"`. The phrase check runs before `if AGENT == "deploy-engineer"`, so deploy-engineer is covered.
- **The exemption cannot bypass it:** it runs before `check_deploy_scope`, `check_command`, and `benign()`.
- **Bash heredoc still parses:** the new Python lines contain no line that is exactly `PY`, so the `<<'PY'` heredoc ending at line 692 is unchanged.

## The numbered claims
1. **CONFIRMED.** Regex and IGNORECASE at line 671. It is called at line 685, before any exemption.
2. **CONFIRMED.** `TF_INSTEAD` is defined at lines 68-69, before its use at line 678. `block()` (lines 39-45) writes the reason and the "Approved path instead" text, then calls `sys.exit(2)`. The wrapper turns that into exit 2 (lines 694-702).
3. **CANNOT VERIFY fully.** I can't diff without a shell. I read the whole file and found nothing outside lines 666-685 that differs from what `docs/agents.md` section 5 describes. The new block is self-contained.
4. **CONFIRMED.** `validate-agent-files.py` never pipes probes into the guard. Its only `subprocess` call is `bash -n` (line 231), and its only terraform mention is the required deny list (lines 246-247). `validate-agent-files.sh` only mentions `echo` in an error message (line 19). Leaving both unchanged is correct.
5. **CONFIRMED with a caveat.** I compared the current file against the copy of `docs/agents.md` loaded at session start. The only differences are the new bullet at line 115 and the sentence at line 119 beginning "The exception is the phrase…". The caveat: I can't prove whether that loaded copy is HEAD or a pre-edit working tree.
6. **CANNOT VERIFY (UNVERIFIED).** The code as written would produce every reported result:

   | Probe | Expected exit | Why |
   |---|---|---|
   | `echo 'terraform destroy'` | 2 | phrase rule |
   | `terraform destroy` | 2 | phrase rule |
   | `git push --force origin main` | 2 | `check_git`, line 386 |
   | `aws s3 rb s3://scholarchain-raw --force` | 2 | `check_aws`, line 345 |
   | `ls` | 0 | no rule matches |

   The `bash -n` exit 0 and the validator's "FAILURES: 0" are not visible in any file.

## Ownership
Both changed files belong to factory-engineer (`docs/agents.md:11`). The untracked `docs/agent-runs/phase-01-agent-factory/repair-02.md` is human-owned evidence, which matches its role as the stored work order. It is not part of the reviewed change. No secrets, no Terraform, and no hidden reasoning appear in either change.

```
STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: Read-only review of the Phase 01 repair-02 uncommitted changes to .claude/hooks/guard-dangerous.sh (TF_PHRASE / check_tf_phrase) and docs/agents.md section 5, verifying factory-engineer's six claims.
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (no shell; Read/Grep/Glob only)
TEST RESULTS: none run. factory-engineer's probe exit codes, bash -n exit 0 and validator FAILURES: 0 are UNVERIFIED from files; code analysis says the probe results would be as reported.
VALIDATION RESULTS: Read-only verified verdict: APPROVE WITH NOTES. Claims 1, 2, 4 CONFIRMED; 5 CONFIRMED with caveat; 3 and 6 CANNOT VERIFY. No High/Medium findings; Low findings L1-L5 above.
ASSUMPTIONS: The docs/agents.md copy loaded at session start is the pre-change text. The hook receives tool_name "Bash" for every shell call.
RISKS: Phrase evasion through quote splitting, printf formatting or double backslashes (print only, not execution). Quoted tofu/terragrunt and dynamically built commands stay unguarded by the phrase rule (already stated). Commit messages and shell grep containing the phrase will be blocked. The phrase rule does not cover scanned script text or MCP input, and the docs do not say so.
MANUAL ACTION REQUIRED: Human constitutional ratification plus a fresh session (docs/agents.md section 4). This is a security change, so a security scan and human approval are required. Optionally ask factory-engineer to fix L1/L2 in the docs first. Rerun the five probes and the validator yourself to turn claim 6 into observed evidence. Keep the literal phrase out of the commit message.
BLOCKED-ON OWNER: n/a
BLOCKED-ON EVIDENCE: n/a
NEXT GATE: Human decision on the verdict, recorded in docs/agent-runs/phase-01-agent-factory/human-gate.md.
```

Files reviewed:
- /Users/dhruv_mehta/Desktop/ScholarChain/.claude/hooks/guard-dangerous.sh
- /Users/dhruv_mehta/Desktop/ScholarChain/docs/agents.md
- /Users/dhruv_mehta/Desktop/ScholarChain/.claude/hooks/validate-agent-files.py
- /Users/dhruv_mehta/Desktop/ScholarChain/.claude/hooks/validate-agent-files.sh
- /Users/dhruv_mehta/Desktop/ScholarChain/.claude/settings.json
- /Users/dhruv_mehta/Desktop/ScholarChain/docs/agent-runs/phase-01-agent-factory/repair-02.md

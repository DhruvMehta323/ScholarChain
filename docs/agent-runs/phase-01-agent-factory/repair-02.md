Use the factory-engineer agent to change .claude/hooks/guard-dangerous.sh so that any Bash command whose text contains "terraform apply" or "terraform destroy" anywhere is blocked, including inside quotes, echo, grep arguments, or comments. The course specification's ratification probe requires that echo 'terraform destroy' is blocked.

Accept the false positive for read-only commands such as grep, and document it in docs/agents.md with the workaround: use the Grep tool instead of shell grep. Update validate-agent-files.sh or its probe fixtures if they assert the old behavior.

Re-run bash -n and validate-agent-files.sh, then pipe test JSON into the guard (never run the commands) and show exit codes for:
- echo 'terraform destroy' (expect 2)
- terraform destroy (expect 2)
- git push --force origin main (expect 2)
- aws s3 rb s3://scholarchain-raw --force (expect 2)
- ls (expect 0)

Change nothing else. Report in the required format.
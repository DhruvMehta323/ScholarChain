#!/usr/bin/env bash
# ScholarChain Stop / SubagentStop test gate.
# Constitutional file: owned by factory-engineer; changes require human ratification.
#
# It compares the mtime of .claude/.last-test-run with the newest file under modules/, tests/,
# project/ and build.sbt. target/ directories, project/project/ and editor metadata are ignored.
#   - No such source files exist yet: allow (exit 0). This keeps Phase 0/1 constitutional work unblocked.
#   - Source exists and the marker is missing or older than the newest source change: block (exit 2).
#   - Otherwise: allow.
# The marker records that tests RAN and their exit status. It does not record that they passed. A
# failing run must be reported as STATUS: FAILED. Only the canonical command writes the marker
# (guard-dangerous.sh and guard-writes.sh enforce this):
#     sbt check; echo "exit=$?" > .claude/.last-test-run
# code-reviewer is exempt because it has no Bash tool and cannot run tests. The exemption applies
# only when the hook input identifies the agent.
# The hook never runs tests itself. Compatible with bash 3.2 and BSD find.

set -u

INPUT="$(cat)"

PROJ="${CLAUDE_PROJECT_DIR:-}"
if [ -z "$PROJ" ]; then
  PROJ="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"
fi
cd "$PROJ" 2>/dev/null || exit 0

AGENT=""
if command -v python3 >/dev/null 2>&1; then
  AGENT="$(printf '%s' "$INPUT" | python3 -I -c '
import json, sys
try:
    d = json.load(sys.stdin)
    print(str(d.get("agent_type") or d.get("agent_name") or "").strip())
except Exception:
    print("")
' 2>/dev/null)"
fi
if [ "$AGENT" = "code-reviewer" ]; then
  exit 0
fi

ROOTS=""
for r in modules tests project build.sbt; do
  if [ -e "$r" ]; then
    ROOTS="$ROOTS $r"
  fi
done

if [ -z "$ROOTS" ]; then
  exit 0
fi

# shellcheck disable=SC2086
FIRST_SOURCE="$(find $ROOTS -type f \
  -not -path '*/target/*' -not -path 'project/project/*' \
  -not -path '*/.bsp/*' -not -path '*/.bloop/*' -not -path '*/.metals/*' -not -path '*/.idea/*' \
  -not -name '.DS_Store' 2>/dev/null | head -n 1)"

if [ -z "$FIRST_SOURCE" ]; then
  exit 0
fi

MARKER=".claude/.last-test-run"
FIX='Run from the repository root:  sbt check; echo "exit=$?" > .claude/.last-test-run   then report the exact observed pass/fail result (a failing run is STATUS: FAILED, never COMPLETE).'

if [ ! -f "$MARKER" ]; then
  echo "BLOCKED by .claude/hooks/verify-tests-ran.sh: source files exist (for example $FIRST_SOURCE) but no test run is recorded ($MARKER is missing). $FIX" >&2
  exit 2
fi

# shellcheck disable=SC2086
NEWER="$(find $ROOTS -type f -newer "$MARKER" \
  -not -path '*/target/*' -not -path 'project/project/*' \
  -not -path '*/.bsp/*' -not -path '*/.bloop/*' -not -path '*/.metals/*' -not -path '*/.idea/*' \
  -not -name '.DS_Store' 2>/dev/null | head -n 1)"

if [ -n "$NEWER" ]; then
  echo "BLOCKED by .claude/hooks/verify-tests-ran.sh: source changed after the last recorded test run (newer file: $NEWER). $FIX" >&2
  exit 2
fi

exit 0

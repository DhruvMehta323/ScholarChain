#!/usr/bin/env bash
# ScholarChain agent-system validator. A runnable tool, not wired as a hook.
# Constitutional file: owned by factory-engineer; changes require human ratification.
#
# Usage: .claude/hooks/validate-agent-files.sh   (from anywhere inside the repository)
# The checks live in validate-agent-files.py, next to this file:
#   - agent frontmatter, unique names, the roster vs the matrix, model policy, and the reviewer tool fence
#   - forbidden Task/Agent tools, required body sections, and report fields
#   - command frontmatter, responsible agents, and arguments
#   - settings.json and .mcp.json parse; required hooks are wired; hook files exist and are executable
#   - bash -n on every hook script; required deny rules; duplicate hook matchers
#   - literal secrets in .mcp.json; description overlap between agents (routing collision)
# Output: one line per check (PASS / FAIL / INFO), then a summary. Exit 0 if there are no FAILs, else 1.
# It reads files and runs `bash -n` only. It modifies nothing. Compatible with bash 3.2.

set -u

if ! command -v python3 >/dev/null 2>&1; then
  echo "FAIL python3 is required" >&2
  exit 1
fi

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="${CLAUDE_PROJECT_DIR:-}"
if [ -z "$ROOT" ]; then
  ROOT="$(git -C "$HERE" rev-parse --show-toplevel 2>/dev/null || (cd "$HERE/../.." && pwd))"
fi

exec python3 -I "$HERE/validate-agent-files.py" "$ROOT"

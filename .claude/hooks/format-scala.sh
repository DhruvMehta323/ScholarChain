#!/usr/bin/env bash
# ScholarChain PostToolUse formatter for Write and Edit.
# Constitutional file: owned by factory-engineer; changes require human ratification.
#
# Scope: only *.scala, *.sc and *.sbt files inside the repository, outside target/ directories,
# and only when the repository has a .scalafmt.conf.
# Behavior: formats the single changed file in place with scalafmt. If no scalafmt binary is
# available, it uses sbt's scalafmtOnly task for *.scala files, but only when project/plugins.sbt
# declares sbt-scalafmt. It never deletes, renames or reverts files. It never blocks. It ALWAYS
# exits 0, including when the file is not Scala, when the tools are missing, and when
# formatting fails. scalafmt leaves a file untouched if it cannot parse it.
# Compatible with bash 3.2.

set -u

INPUT="$(cat)"

command -v python3 >/dev/null 2>&1 || exit 0

FILE="$(printf '%s' "$INPUT" | python3 -I -c '
import json, sys
try:
    d = json.load(sys.stdin)
    ti = d.get("tool_input") or {}
    p = ti.get("file_path") or ""
    print(p if isinstance(p, str) else "")
except Exception:
    print("")
' 2>/dev/null)" || exit 0

[ -n "$FILE" ] || exit 0

case "$FILE" in
  *.scala|*.sc|*.sbt) ;;
  *) exit 0 ;;
esac

PROJ="${CLAUDE_PROJECT_DIR:-$(pwd)}"
case "$FILE" in
  /*) ;;
  *) FILE="$PROJ/$FILE" ;;
esac
case "$FILE" in
  "$PROJ"/*) ;;
  *) exit 0 ;;
esac
case "$FILE" in
  */target/*) exit 0 ;;
esac

[ -f "$FILE" ] || exit 0
[ -f "$PROJ/.scalafmt.conf" ] || exit 0

if command -v scalafmt >/dev/null 2>&1; then
  if ! (cd "$PROJ" && scalafmt --non-interactive --quiet "$FILE") >/dev/null 2>&1; then
    echo "format-scala.sh: scalafmt could not format $FILE; the file was left as written." >&2
  fi
  exit 0
fi

case "$FILE" in
  *.scala) ;;
  *) exit 0 ;;
esac

if command -v sbt >/dev/null 2>&1 && grep -q "sbt-scalafmt" "$PROJ/project/plugins.sbt" 2>/dev/null; then
  if ! (cd "$PROJ" && sbt -batch -no-colors "scalafmtOnly $FILE") >/dev/null 2>&1; then
    echo "format-scala.sh: sbt scalafmtOnly could not format $FILE; the file was left as written." >&2
  fi
fi

exit 0

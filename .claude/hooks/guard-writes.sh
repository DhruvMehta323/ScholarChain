#!/usr/bin/env bash
# ScholarChain PreToolUse guard for Write, Edit, MultiEdit and NotebookEdit.
# Constitutional file: owned by factory-engineer; changes require human ratification.
#
# Contract:
#   - Reads the Claude Code hook JSON from stdin (tool_input.file_path or notebook_path).
#   - Exit 0 allows the write. Exit 2 blocks it, with an actionable message on stderr.
#   - Fail-closed on unparseable input or a missing python3.
#
# Rules, in order:
#   1. Nobody writes ~/.claude/**, ~/.aws/** or ~/.ssh/** (global settings and credentials).
#   2. code-reviewer writes nothing.
#   3. Nobody writes .claude/.last-test-run with a file tool. Only the canonical test command may update it.
#   4. Nobody writes docs/agent-runs/*/human-gate.md (human decisions).
#   5. Constitutional files (CLAUDE.md, docs/agents.md, .claude/**, .mcp.json) are written only when
#      a factory-engineer signal is present: hook input agent_type == "factory-engineer", or
#      SCHOLARCHAIN_FACTORY_SESSION=1 in the environment that the human started Claude Code with.
#   6. When the hook input identifies a subagent (agent_type), that subagent may write only inside
#      its ownership globs below. These globs mirror docs/agents.md. Unknown subagent types may not
#      write inside the repository. The main session (orchestrator, no agent_type) is bound by rules 1-5 only.
#   Paths outside the repository (scratch space) are allowed except under rules 1 and 2.
# Compatible with bash 3.2.

set -u

INPUT="$(cat)"

if ! command -v python3 >/dev/null 2>&1; then
  echo "BLOCKED by .claude/hooks/guard-writes.sh: python3 is not available, so the write target could not be checked (fail-closed). Approved path instead: install python3, or ask a human to make the edit." >&2
  exit 2
fi

PY_CODE=""
read -r -d '' PY_CODE <<'PY'
import json, os, re, sys

def block(what, instead):
    sys.stderr.write("BLOCKED by .claude/hooks/guard-writes.sh: " + what + "\nApproved path instead: " + instead + "\n")
    sys.exit(2)

raw = sys.stdin.read()
try:
    data = json.loads(raw) if raw.strip() else {}
    if not isinstance(data, dict):
        raise ValueError("not an object")
except Exception:
    block("the hook input was not valid JSON (fail-closed).", "retry; if this repeats, report it to factory-engineer through the orchestrator.")

ti = data.get("tool_input") or {}
if not isinstance(ti, dict):
    ti = {}
agent = str(data.get("agent_type") or data.get("agent_name") or "").strip()
factory = agent == "factory-engineer" or os.environ.get("SCHOLARCHAIN_FACTORY_SESSION") == "1"
proj = os.path.realpath(os.environ.get("CLAUDE_PROJECT_DIR") or data.get("cwd") or os.getcwd())
home = os.path.realpath(os.path.expanduser("~"))

path = ti.get("file_path") or ti.get("notebook_path") or ""
if not isinstance(path, str) or not path:
    block("no target file path in the tool input (fail-closed).", "retry with an explicit file_path.")

e = os.path.expanduser(path)
full = os.path.normpath(e if os.path.isabs(e) else os.path.join(proj, e))
cands = [full, os.path.realpath(full)]

for p in cands:
    for g in (".claude", ".aws", ".ssh"):
        gd = os.path.join(home, g)
        if p == gd or p.startswith(gd + os.sep):
            block("write to %s, which holds global Claude settings or credentials that no agent owns." % p,
                  "ask a human to change user-level configuration.")

if agent == "code-reviewer":
    block("code-reviewer is read-only and may not write any file.",
          "return findings in the review report; the orchestrator stores it as review-report.md.")

inside = []
for p in cands:
    if p.startswith(proj + os.sep):
        r = p[len(proj) + 1:]
        if r not in inside:
            inside.append(r)
if not inside:
    sys.exit(0)

def is_const(rel):
    return rel in ("CLAUDE.md", "docs/agents.md", ".mcp.json") or rel == ".claude" or rel.startswith(".claude/")

for rel in inside:
    if rel == ".claude/.last-test-run":
        block("the test-run marker may not be written with a file tool.",
              'run exactly: sbt check; echo "exit=$?" > .claude/.last-test-run')
    if re.match(r"^docs/agent-runs/[^/]+/human-gate\.md$", rel):
        block("%s is a human gate record; human decisions are never written or rewritten by agents." % rel,
              "the human writes human-gate.md.")
    if is_const(rel) and not factory:
        block("%s is a constitutional file. Only factory-engineer may write it, through a human-ratified diff." % rel,
              "decline the edit and return STATUS: BLOCKED-ON with BLOCKED-ON OWNER: factory-engineer.")

if not agent:
    sys.exit(0)

def gl(p):
    out, i = "", 0
    while i < len(p):
        if p[i:i + 3] == "**/":
            out += "(?:.*/)?"; i += 3
        elif p[i:i + 2] == "**":
            out += ".*"; i += 2
        elif p[i] == "*":
            out += "[^/]*"; i += 1
        elif p[i] == "?":
            out += "[^/]"; i += 1
        else:
            out += re.escape(p[i]); i += 1
    return re.compile("^" + out + "$")

T = "modules/%s/src/test/**"
OWN = [
    ("factory-engineer", ["CLAUDE.md", "docs/agents.md", ".mcp.json", ".claude/**"], [".claude/.last-test-run"]),
    ("build-engineer", ["build.sbt", "project/**", ".scalafmt.conf", ".gitignore", ".sbtopts", ".jvmopts",
                        "docker-compose*.yml", "docker-compose*.yaml", "compose*.yml", "compose*.yaml", "packaging/**"],
                       ["project/Versions.scala"]),
    ("domain-engineer", ["modules/core/**"], [T % "core"]),
    ("dataset-engineer", ["datasets/**"], []),
    ("hadoop-engineer", ["modules/hadoop/**"], [T % "hadoop"]),
    ("entity-resolution-engineer", ["modules/entity-resolution/**"], [T % "entity-resolution"]),
    ("spark-engineer", ["modules/spark/**"], [T % "spark"]),
    ("storage-engineer", ["modules/storage/**"], [T % "storage"]),
    ("rag-engineer", ["modules/rag/**", "prompts/**"], [T % "rag"]),
    ("service-engineer", ["modules/services/**"], [T % "services"]),
    ("test-engineer", ["tests/**", "modules/*/src/test/**"], []),
    ("code-reviewer", [], []),
    ("infra-engineer", ["infra/terraform/**", "scripts/**", ".github/workflows/**"], []),
    ("deploy-engineer", ["reports/deployments/**"], []),
    ("experiment-engineer", ["experiments/**", "load-tests/**"], []),
    ("documentation-engineer", ["README.md", "docs/**", "reports/final-report.md"],
                               ["docs/agents.md", "docs/agent-runs/**", "docs/incidents/**"]),
    ("incident-responder", ["docs/incidents/**"], []),
    ("dependency-updater", ["project/Versions.scala", "reports/dependency-updates/**"], []),
]
OWNMAP = dict((n, (a, d)) for (n, a, d) in OWN)

def owns(name, rel):
    allow, deny = OWNMAP[name]
    if any(gl(g).match(rel) for g in deny):
        return False
    return any(gl(g).match(rel) for g in allow)

def owner_of(rel):
    if rel.startswith("docs/agent-runs/"):
        return "human (the orchestrator assembles evidence)"
    for n, _, _ in OWN:
        if owns(n, rel):
            return n
    return None

if agent not in OWNMAP:
    block("subagent type '%s' has no ownership entry in docs/agents.md, so it may not write inside the repository." % agent,
          "route the work through the orchestrator to the owning agent named in docs/agents.md.")

for rel in inside:
    if not owns(agent, rel):
        owner = owner_of(rel)
        block("%s does not own %s (owner: %s)." % (agent, rel, owner or "unassigned"),
              "decline and return STATUS: BLOCKED-ON with BLOCKED-ON OWNER: %s."
              % (owner or "orchestrator (ownership gap; escalate to factory-engineer)"))
sys.exit(0)
PY

printf '%s' "$INPUT" | python3 -I -c "$PY_CODE"
rc=$?
if [ "$rc" -eq 0 ]; then
  exit 0
fi
if [ "$rc" -ne 2 ]; then
  echo "BLOCKED by .claude/hooks/guard-writes.sh: the checker failed with exit $rc (fail-closed). Approved path instead: report this to factory-engineer through the orchestrator." >&2
fi
exit 2

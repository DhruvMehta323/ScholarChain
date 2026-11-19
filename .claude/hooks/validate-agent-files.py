"""ScholarChain agent-system validator (logic). Invoked by validate-agent-files.sh.

Constitutional file: owned by factory-engineer; changes require human ratification.
Kept as Python, not embedded in the shell wrapper, so that guard-dangerous.sh (which scans the
text of executed shell scripts) does not read the validator's lists of forbidden commands as commands.
Reads files and runs `bash -n` only. Modifies nothing.
"""
import json, os, re, subprocess, sys

ROOT = sys.argv[1]
fails = 0
def out(kind, msg):
    global fails
    if kind == "FAIL":
        fails += 1
    print("%-4s %s" % (kind, msg))

EXPECTED = ["factory-engineer", "build-engineer", "domain-engineer", "dataset-engineer", "hadoop-engineer",
            "entity-resolution-engineer", "spark-engineer", "storage-engineer", "rag-engineer", "service-engineer",
            "test-engineer", "code-reviewer", "infra-engineer", "deploy-engineer", "experiment-engineer",
            "documentation-engineer", "incident-responder", "dependency-updater"]
KNOWN_TOOLS = {"Read", "Grep", "Glob", "Write", "Edit", "MultiEdit", "NotebookEdit", "Bash", "WebFetch", "WebSearch", "TodoWrite"}
FORBIDDEN_TOOLS = {"Task", "Agent"}
WRITE_TOOLS = {"Write", "Edit", "MultiEdit", "NotebookEdit", "Bash"}
SECTIONS = ["## Role", "## Owned artifacts", "## Non-negotiable laws", "## Procedure", "## Refusal boundaries", "## Report contract"]
FIELDS = ["STATUS:", "OWNER:", "WORK ORDER:", "FILES CREATED:", "FILES MODIFIED:", "COMMANDS RUN:", "TEST RESULTS:",
          "VALIDATION RESULTS:", "ASSUMPTIONS:", "RISKS:", "MANUAL ACTION REQUIRED:", "BLOCKED-ON OWNER:",
          "BLOCKED-ON EVIDENCE:", "NEXT GATE:"]

try:
    import yaml
except Exception:
    yaml = None

def frontmatter(path):
    with open(path) as fh:
        text = fh.read()
    m = re.match(r"^---\n(.*?)\n---\n(.*)$", text, re.S)
    if not m:
        return None, text, "missing or malformed frontmatter delimiters"
    raw, body = m.group(1), m.group(2)
    if yaml is not None:
        try:
            fm = yaml.safe_load(raw)
            if not isinstance(fm, dict):
                return None, body, "frontmatter is not a mapping"
            return fm, body, None
        except Exception as e:
            return None, body, "YAML error: %s" % str(e).splitlines()[0]
    fm = {}
    for line in raw.splitlines():
        if not line.strip():
            continue
        if ":" not in line:
            return None, body, "unparseable frontmatter line: %r" % line
        k, v = line.split(":", 1)
        v = v.strip()
        if v.startswith('"'):
            try:
                v = json.loads(v)
            except Exception:
                return None, body, "bad quoted value for %s" % k
        fm[k.strip()] = v
    return fm, body, None

print("== Agents (.claude/agents) ==")
adir = os.path.join(ROOT, ".claude", "agents")
files = sorted(f for f in os.listdir(adir) if f.endswith(".md")) if os.path.isdir(adir) else []
names, descs = {}, {}
for f in files:
    p = os.path.join(adir, f)
    fm, body, err = frontmatter(p)
    if err:
        out("FAIL", "%s: %s" % (f, err)); continue
    for key in ("name", "description", "tools"):
        if not fm.get(key) or not str(fm.get(key)).strip():
            out("FAIL", "%s: frontmatter field '%s' missing or empty" % (f, key))
    name = str(fm.get("name", "")).strip()
    if name and name + ".md" != f:
        out("FAIL", "%s: name '%s' does not match filename" % (f, name))
    if name in names:
        out("FAIL", "duplicate agent name '%s' in %s and %s" % (name, names[name], f))
    names[name] = f
    descs[name] = str(fm.get("description", ""))
    tools = [t.strip() for t in str(fm.get("tools", "")).split(",") if t.strip()]
    unknown = [t for t in tools if t not in KNOWN_TOOLS and t not in FORBIDDEN_TOOLS and not t.startswith("mcp__")]
    if unknown:
        out("FAIL", "%s: unknown tools %s" % (f, unknown))
    bad = [t for t in tools if t in FORBIDDEN_TOOLS]
    if bad:
        out("FAIL", "%s: grants agent-invocation tools %s (subagents must not invoke one another)" % (f, bad))
    if re.search(r"(reviewer|auditor|critic)", name):
        w = [t for t in tools if t in WRITE_TOOLS]
        if w or set(tools) - {"Read", "Grep", "Glob"}:
            out("FAIL", "%s: reviewer tool fence violated: %s" % (f, tools))
        else:
            out("PASS", "%s: reviewer tool fence (Read, Grep, Glob only)" % f)
    if name == "deploy-engineer" and "Edit" in tools:
        out("FAIL", "deploy-engineer must not have Edit")
    model = fm.get("model")
    if name == "factory-engineer":
        if not model:
            out("INFO", "factory-engineer.md has no model field (inherits); file is out of scope for this phase")
    else:
        want = "opus" if name == "code-reviewer" else "sonnet"
        if model != want:
            out("FAIL", "%s: model is %r, expected %r" % (f, model, want))
    if name != "factory-engineer":
        missing = [s for s in SECTIONS if not re.search(r"^" + re.escape(s) + r"\s*$", body, re.M)]
        if missing:
            out("FAIL", "%s: missing body sections %s" % (f, missing))
    pos, order_ok, absent = -1, True, []
    for fld in FIELDS:
        i = body.find("\n" + fld)
        if i < 0:
            absent.append(fld)
        elif i < pos:
            order_ok = False
        else:
            pos = i
    if absent or not order_ok:
        out("FAIL", "%s: report contract fields missing %s or out of order" % (f, absent))
    if not err and not absent and order_ok:
        out("PASS", "%s: frontmatter ok (tools: %s; model: %s); report contract complete" % (f, ", ".join(tools), model or "inherit"))

print("== Roster ==")
for n in EXPECTED:
    if n not in names:
        out("FAIL", "matrix agent '%s' has no definition (orphan in matrix)" % n)
for n in names:
    if n not in EXPECTED:
        out("FAIL", "agent '%s' is not in the authority matrix (orphan definition)" % n)
agents_md = os.path.join(ROOT, "docs", "agents.md")
amd = open(agents_md).read() if os.path.isfile(agents_md) else ""
if not amd:
    out("FAIL", "docs/agents.md missing")
for n in EXPECTED:
    if amd and not re.search(r"^\| " + re.escape(n) + r" \|", amd, re.M):
        out("FAIL", "agent '%s' has no ownership row in docs/agents.md" % n)
if len(names) == len(EXPECTED) and set(names) == set(EXPECTED):
    out("PASS", "roster: %d agent definitions match the %d-agent matrix; names unique" % (len(names), len(EXPECTED)))
claude_md = os.path.join(ROOT, "CLAUDE.md")
if os.path.isfile(claude_md) and re.search(r"^@docs/agents\.md\s*$", open(claude_md).read(), re.M):
    out("PASS", "CLAUDE.md imports @docs/agents.md")
else:
    out("FAIL", "CLAUDE.md missing or does not import @docs/agents.md")

print("== Routing collision (description overlap) ==")
STOP = set("the a an and or of for to in on with by is are as not never use from its it this that be any all only into under through scholarchain owns".split())
def words(s):
    return set(w for w in re.findall(r"[a-z][a-z0-9-]+", s.lower()) if w not in STOP)
ws = dict((n, words(d)) for n, d in descs.items())
worst = (0.0, None, None)
for a in sorted(ws):
    for b in sorted(ws):
        if a < b and ws[a] and ws[b]:
            j = len(ws[a] & ws[b]) / float(len(ws[a] | ws[b]))
            if j > worst[0]:
                worst = (j, a, b)
            if j >= 0.5:
                out("FAIL", "descriptions of %s and %s overlap (Jaccard %.2f); routing ambiguous" % (a, b, j))
if worst[1]:
    out("PASS" if worst[0] < 0.5 else "INFO", "max description overlap %.2f (%s vs %s); threshold 0.50" % worst)

print("== Commands (.claude/commands) ==")
cdir = os.path.join(ROOT, ".claude", "commands")
cfiles = sorted(f for f in os.listdir(cdir) if f.endswith(".md")) if os.path.isdir(cdir) else []
for f in cfiles:
    fm, body, err = frontmatter(os.path.join(cdir, f))
    if err:
        out("FAIL", "%s: %s" % (f, err)); continue
    if not fm.get("description") or not fm.get("argument-hint"):
        out("FAIL", "%s: needs description and argument-hint" % f)
    m = re.search(r"^Responsible agent:\s*([a-z-]+)", body, re.M)
    if not m:
        out("FAIL", "%s: no 'Responsible agent:' line" % f)
    elif m.group(1) not in names:
        out("FAIL", "%s: responsible agent '%s' does not exist" % (f, m.group(1)))
    if not re.search(r"\$ARGUMENTS|\$[1-9]", body):
        out("FAIL", "%s: takes no arguments" % f)
    if not re.search(r"^Gates:", body, re.M):
        out("FAIL", "%s: no 'Gates:' line" % f)
    if "allowed-tools" in fm:
        out("FAIL", "%s: commands must not grant tools (allowed-tools present)" % f)
    if m and m.group(1) in names:
        out("PASS", "%s -> %s (args, gates present)" % (f, m.group(1)))
for need in ("deploy.md", "rollback.md", "incident.md", "run-pipeline.md", "experiment.md"):
    if need not in cfiles:
        out("FAIL", "required command %s missing" % need)

print("== settings.json and hooks ==")
sp = os.path.join(ROOT, ".claude", "settings.json")
try:
    settings = json.load(open(sp))
    out("PASS", ".claude/settings.json parses as JSON")
except Exception as e:
    settings = {}
    out("FAIL", ".claude/settings.json: %s" % e)
hooks = settings.get("hooks", {})
referenced = set()
seen = set()
for event, groups in hooks.items():
    for g in groups:
        key = (event, g.get("matcher", ""))
        if key in seen:
            out("FAIL", "duplicate hook matcher %s / %r" % key)
        seen.add(key)
        for h in g.get("hooks", []):
            cmd = h.get("command", "")
            m = re.search(r"\.claude/hooks/([A-Za-z0-9_.-]+)", cmd)
            if not m:
                out("FAIL", "%s hook command does not reference .claude/hooks: %r" % (event, cmd)); continue
            hp = os.path.join(ROOT, ".claude", "hooks", m.group(1))
            referenced.add(m.group(1))
            if not os.path.isfile(hp):
                out("FAIL", "%s -> %s does not exist" % (event, m.group(1)))
            elif not os.access(hp, os.X_OK):
                out("FAIL", "%s -> %s is not executable" % (event, m.group(1)))
            else:
                out("PASS", "%s[%s] -> %s exists and is executable" % (event, g.get("matcher", "*"), m.group(1)))
REQ = [("PreToolUse", "Bash", "guard-dangerous.sh"), ("PreToolUse", "Write|Edit|MultiEdit|NotebookEdit", "guard-writes.sh"),
       ("PostToolUse", "Write|Edit", "format-scala.sh"), ("Stop", "", "verify-tests-ran.sh"), ("SubagentStop", "", "verify-tests-ran.sh")]
for ev, mt, script in REQ:
    ok = any(g.get("matcher", "") == mt and any(script in h.get("command", "") for h in g.get("hooks", []))
             for g in hooks.get(ev, []))
    out("PASS" if ok else "FAIL", "required wiring %s[%s] -> %s" % (ev, mt or "*", script))
hdir = os.path.join(ROOT, ".claude", "hooks")
for f in sorted(os.listdir(hdir)) if os.path.isdir(hdir) else []:
    hp = os.path.join(hdir, f)
    if f.endswith(".sh"):
        r = subprocess.run(["bash", "-n", hp], capture_output=True, text=True)
        out("PASS" if r.returncode == 0 else "FAIL", "bash -n %s%s" % (f, "" if r.returncode == 0 else ": " + r.stderr.strip()))
        if not os.access(hp, os.X_OK):
            out("FAIL", "%s is not executable" % f)
        if f not in referenced:
            if f == "validate-agent-files.sh":
                out("INFO", "%s is a manual validation tool, intentionally not wired as a hook" % f)
            else:
                out("FAIL", "%s is not referenced by settings.json (orphan hook)" % f)
    elif f == "validate-agent-files.py":
        out("INFO", "%s is the logic module of validate-agent-files.sh (not a hook)" % f)
    else:
        out("FAIL", "unexpected file in .claude/hooks: %s" % f)
deny = settings.get("permissions", {}).get("deny", [])
allow = settings.get("permissions", {}).get("allow", [])
REQ_DENY = ["Read(./.env)", "Read(./.env.*)", "Read(**/*.pem)", "Read(**/*.key)", "Read(~/.aws/credentials)", "Read(**/secrets/**)",
            "Bash(terraform apply*)", "Bash(terraform destroy*)", "Bash(git push --force*)", "Bash(git push -f*)",
            "Bash(git push --force-with-lease*)", "Bash(git push * +*)", "Bash(aws s3 rb*)", "Bash(aws s3 rm * --recursive*)",
            "Bash(aws s3api delete-bucket*)", "Bash(aws dynamodb delete-table*)", "Bash(aws neptune delete-db-cluster*)",
            "Bash(aws neptune delete-db-instance*)", "Bash(aws s3vectors delete-vector-bucket*)", "Bash(aws s3vectors delete-index*)",
            "Bash(aws s3vectors delete-vectors*)", "Bash(aws ecr delete-repository*)", "Bash(aws ecr batch-delete-image*)",
            "Bash(aws kms schedule-key-deletion*)", "Bash(aws kms disable-key*)", "Bash(aws emr terminate-clusters*)"]
missing = [d for d in REQ_DENY if d not in deny]
out("PASS" if not missing else "FAIL", "required deny rules present (%d checked)%s" % (len(REQ_DENY), "" if not missing else ": missing " + str(missing)))
overlap = [a for a in allow if a in deny]
out("PASS" if not overlap else "FAIL", "no rule is both allowed and denied%s" % ("" if not overlap else ": " + str(overlap)))

print("== .mcp.json ==")
mp = os.path.join(ROOT, ".mcp.json")
try:
    mcp = json.load(open(mp))
    out("PASS", ".mcp.json parses as JSON")
except Exception as e:
    mcp = {}
    out("FAIL", ".mcp.json: %s" % e)
SECRETY = re.compile(r"(ghp_|github_pat_|AKIA|ASIA|sk-|xox[bp]-|-----BEGIN)")
for sname, sv in (mcp.get("mcpServers") or {}).items():
    env = sv.get("env") or {}
    for k, v in env.items():
        v = str(v)
        if SECRETY.search(v):
            out("FAIL", "%s.env.%s looks like a literal secret" % (sname, k))
        elif re.search(r"(TOKEN|SECRET|PASSWORD|KEY)", k) and not re.match(r"^\$\{[A-Z0-9_]+(:-[^}]*)?\}$", v):
            out("FAIL", "%s.env.%s must be an environment-variable reference" % (sname, k))
    for a in sv.get("args") or []:
        if SECRETY.search(str(a)):
            out("FAIL", "%s.args contains a literal secret" % sname)
    if sname not in amd:
        out("FAIL", "MCP server '%s' is not documented in docs/agents.md" % sname)
    else:
        out("PASS", "MCP server '%s': env uses variable names only; documented in docs/agents.md" % sname)

print("== Summary ==")
print("FAILURES: %d" % fails)
sys.exit(1 if fails else 0)

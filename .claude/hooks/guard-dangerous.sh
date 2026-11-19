#!/usr/bin/env bash
# ScholarChain PreToolUse guard for Bash and MCP tool calls.
# Constitutional file: owned by factory-engineer; changes require human ratification.
#
# Contract:
#   - Reads the Claude Code hook JSON from stdin.
#   - Inspects the command TEXT only. It never executes the command. For a command
#     that runs an existing script file, it may READ that script's text so it can
#     detect wrapped forms. It never runs the script.
#   - Exit 0 allows the call. Exit 2 blocks it, with an actionable message on stderr.
#   - Fail-closed: if the input cannot be parsed or python3 is missing, the call is blocked.
#
# Floor invariants enforced here: dangerous-command guard, secret-read denial (Bash
# side), Terraform apply/destroy denial, destructive AWS denial, force-push denial,
# plus Bash-side protection of constitutional files and the test-run marker.
# Compatible with bash 3.2: no associative arrays, no mapfile, no ${var,,}.

set -u

INPUT="$(cat)"

if ! command -v python3 >/dev/null 2>&1; then
  echo "BLOCKED by .claude/hooks/guard-dangerous.sh: python3 is not available, so the command could not be inspected (fail-closed). Approved path instead: install python3, or ask a human to run the command." >&2
  exit 2
fi

PY_CODE=""
read -r -d '' PY_CODE <<'PY'
import json, os, re, shlex, sys

MAX_DEPTH = 6
try:
    EMR_MAX = int(os.environ.get("SCHOLARCHAIN_EMR_MAX_INSTANCE_COUNT", "3"))
except ValueError:
    EMR_MAX = 3

CTX = []

def block(what, instead):
    where = (" [found inside " + " -> ".join(CTX) + "]") if CTX else ""
    sys.stderr.write(
        "BLOCKED by .claude/hooks/guard-dangerous.sh: " + what + where + "\n"
        "Approved path instead: " + instead + "\n"
        "(The guard inspected the command text only; nothing was executed.)\n")
    sys.exit(2)

raw_in = sys.stdin.read()
try:
    DATA = json.loads(raw_in) if raw_in.strip() else {}
    if not isinstance(DATA, dict):
        raise ValueError("not an object")
except Exception:
    block("the hook input was not valid JSON, so the command could not be inspected (fail-closed).",
          "retry the tool call; if this repeats, report it to factory-engineer through the orchestrator.")

TOOL = str(DATA.get("tool_name") or "")
TI = DATA.get("tool_input") or {}
if not isinstance(TI, dict):
    TI = {}
AGENT = str(DATA.get("agent_type") or DATA.get("agent_name") or "").strip()
FACTORY = AGENT == "factory-engineer" or os.environ.get("SCHOLARCHAIN_FACTORY_SESSION") == "1"
PROJ = os.path.realpath(os.environ.get("CLAUDE_PROJECT_DIR") or DATA.get("cwd") or os.getcwd())
HOME = os.path.realpath(os.path.expanduser("~"))

AWS_INSTEAD = ("destructive or secret-reading AWS operations are human-only. Escalate through the orchestrator "
               "(docs/agents.md, Escalation). Infrastructure changes go through an infra-engineer Terraform plan "
               "and a human apply.")
TF_INSTEAD = ("infra-engineer runs `terraform fmt -check`, `terraform validate` and `terraform plan -out=<file>`; "
              "a human reviews the plan and runs apply or destroy. See docs/agents.md, Escalation.")
PUSH_INSTEAD = ("push a new branch without force and let a human review it. History rewrites and remote-ref "
                "deletion are human-only.")
SECRET_INSTEAD = ("secrets come only from AWS Secrets Manager at runtime or approved local placeholder templates. "
                  "Do not read or print secret files; ask a human if a value is needed.")
CONST_INSTEAD = ("constitutional files (CLAUDE.md, docs/agents.md, .claude/**, .mcp.json) are written only by "
                 "factory-engineer through a human-ratified diff. Return STATUS: BLOCKED-ON with "
                 "BLOCKED-ON OWNER: factory-engineer.")
MARKER_CMD = 'sbt check; echo "exit=$?" > .claude/.last-test-run'

PUNCT = set(";&|()<>")

def is_punct(t):
    return bool(t) and all(c in PUNCT for c in t)

def is_sep(t):
    return is_punct(t) and "<" not in t and ">" not in t

def base(t):
    t = t.rstrip("/") if len(t) > 1 else t
    return os.path.basename(t)

def tokenize(cmd):
    s = cmd.replace("\r\n", "\n").replace("\r", "\n").replace("\n", " ; ")
    try:
        lx = shlex.shlex(s, posix=True, punctuation_chars=";&|()<>")
        lx.whitespace_split = True
        lx.commenters = ""
        toks = list(lx)
    except ValueError:
        s2 = re.sub(r"[\"'`]", " ", s)
        toks = re.findall(r"&&|\|\||>>|[;&|()<>]|[^\s;&|()<>]+", s2)
    out = []
    for t in toks:
        if not is_punct(t):
            t = t.strip("`")
            if t.startswith("$(") and len(t) > 2:
                t = t[2:]
        if t != "":
            out.append(t)
    return out

def segments(toks):
    segs, cur = [], []
    for t in toks:
        if is_sep(t):
            if cur:
                segs.append(cur)
            cur = []
        else:
            cur.append(t)
    if cur:
        segs.append(cur)
    return segs

ASSIGN = re.compile(r"^[A-Za-z_][A-Za-z0-9_]*=")
PREFIX = {"sudo", "doas", "env", "time", "nohup", "nice", "exec", "command", "builtin",
          "caffeinate", "stdbuf", "timeout", "gtimeout", "unbuffer"}

def effective(seg):
    i = 0
    while i < len(seg):
        t = seg[i]
        if ASSIGN.match(t) or is_punct(t):
            i += 1
            continue
        if base(t) in PREFIX:
            i += 1
            while i < len(seg) and (seg[i].startswith("-") or re.match(r"^\d+[smhd]?$", seg[i]) or ASSIGN.match(seg[i])):
                i += 1
            continue
        return i
    return None

SHELLS = {"bash", "sh", "zsh", "dash", "ksh", "fish", "csh", "tcsh"}
SINKS = SHELLS | {"eval", "source", ".", "xargs", "parallel"}
BENIGN = {"grep", "egrep", "fgrep", "rg", "ag", "ack", "echo", "printf"}

def benign(seg):
    k = effective(seg)
    if k is None:
        return True
    b = base(seg[k])
    if b in BENIGN:
        return True
    if b == "git":
        sub = [a for a in seg[k + 1:] if not a.startswith("-")]
        return bool(sub) and sub[0] in ("commit", "log", "tag", "notes", "grep", "show")
    return False

def ancestors(p):
    out = set()
    while True:
        out.add(p)
        q = os.path.dirname(p)
        if q == p:
            break
        p = q
    return out

PROTECTED_DIRS = ancestors(PROJ) | ancestors(HOME) | {os.path.join(PROJ, ".git")}

def expand(t):
    e = t.replace("${HOME}", HOME).replace("$HOME", HOME)
    if e.startswith("~"):
        e = os.path.expanduser(e)
    return e

def dangerous_target(t):
    if t in ("*", ".*", ".", "./", "..", "../", "./*", "../*", "~", "~/", "~/*", ".git", "./.git", ".git/"):
        return True
    if re.match(r"^\$\{?[A-Za-z_][A-Za-z0-9_]*\}?/?\*?$", t):
        return True  # unresolvable bare variable: an empty value would hit / or the cwd
    e = expand(t)
    if e.endswith("/*"):
        e = e[:-2] or "/"
    if not e:
        return False
    full = e if os.path.isabs(e) else os.path.join(PROJ, e)
    for p in (os.path.normpath(full), os.path.realpath(full)):
        if p in PROTECTED_DIRS or os.path.dirname(p) == "/":
            return True
    return False

FORK_RES = [re.compile(r":\s*\(\s*\)\s*\{[^}]*:\s*\|\s*:"),
            re.compile(r"\b([A-Za-z_]\w*)\s*\(\s*\)\s*\{[^}]*\b\1\s*\|\s*\1\b")]

SECRET_RES = [
    (re.compile(r"(^|[\s/'\"=:<(])\.env(?!\.example\b)(\.[A-Za-z0-9_.-]+)?(?=$|[\s'\";|&)<>])"), ".env files"),
    (re.compile(r"(^|[\s/'\"=:<(])\.envrc\b"), ".envrc files"),
    (re.compile(r"[A-Za-z0-9_./~${}-]*\.pem\b"), "PEM private keys or certificates"),
    (re.compile(r"[A-Za-z0-9_./~${}-]*\.key\b"), "private key files (*.key)"),
    (re.compile(r"\.aws/(credentials|sso/cache)"), "AWS credential files"),
    (re.compile(r"\.ssh/id_[A-Za-z0-9_]+"), "SSH private keys"),
    (re.compile(r"(^|[\s/'\"=])\.?secrets?/"), "secret directories"),
    (re.compile(r"\bsecurity\s+find-(generic|internet)-password\b"), "the macOS keychain"),
]

def check_raw(text):
    for rx in FORK_RES:
        if rx.search(text):
            block("fork bomb pattern detected.", "there is no approved use; remove it.")
    for rx, what in SECRET_RES:
        m = rx.search(text)
        if m:
            block("the command references %s (`%s`); secret reads are denied." % (what, m.group(0).strip()),
                  SECRET_INSTEAD)

def check_rm(seg):
    for i, t in enumerate(seg):
        if base(t) != "rm":
            continue
        args = seg[i + 1:]
        if "--no-preserve-root" in args:
            block("`rm --no-preserve-root`.", "delete specific paths inside the repository; anything broader needs a human.")
        rec = any(a == "--recursive" or (re.match(r"^-[A-Za-z]+$", a) and ("r" in a or "R" in a)) for a in args)
        if not rec:
            continue
        for a in args:
            if a.startswith("-") or is_punct(a):
                continue
            if dangerous_target(a):
                block("recursive delete of a protected location (`%s`): filesystem root, a top-level directory, home, "
                      "the repository, .git, an ancestor of these, a bare glob, or an unguarded variable." % a,
                      "delete a specific path inside the repository, e.g. `rm -rf modules/<module>/target`. In scripts, "
                      "guard variables as \"${VAR:?}\". Anything broader needs a human.")

def check_find(seg):
    for i, t in enumerate(seg):
        if base(t) != "find":
            continue
        args = seg[i + 1:]
        destructive = ("-delete" in args) or (("-exec" in args or "-execdir" in args) and
                                              any(base(a) in ("rm", "shred", "unlink") for a in args))
        if not destructive:
            continue
        starts = []
        for a in args:
            if a.startswith("-") or a in ("(", "!") or is_punct(a):
                break
            starts.append(a)
        if not starts:
            starts = ["."]
        for s in starts:
            if dangerous_target(s):
                block("`find %s ... -delete/-exec rm` over a protected location." % s,
                      "run find from a specific subdirectory inside the repository, or ask a human.")

DISKUTIL_BAD = {"erasedisk", "erasevolume", "partitiondisk", "zerodisk", "randomdisk", "secureerase",
                "reformat", "deletecontainer", "deletevolume"}

def check_disk(seg):
    for i, t in enumerate(seg):
        b = base(t)
        if b.startswith("mkfs") or b.startswith("newfs") or b in ("fdisk", "sfdisk", "wipefs"):
            block("filesystem creation or partitioning (`%s`)." % b, "disk operations are human-only.")
        if b == "dd" and any(a.startswith("of=/dev/") for a in seg[i + 1:]):
            block("`dd` writing to a device.", "disk operations are human-only.")
        if b == "diskutil" and any(a.lower() in DISKUTIL_BAD for a in seg[i + 1:]):
            block("destructive `diskutil` operation.", "disk operations are human-only.")
        if is_punct(t) and ">" in t and i + 1 < len(seg):
            tgt = seg[i + 1]
            if tgt.startswith("/dev/") and not re.match(r"^/dev/(null|stdout|stderr|tty|fd/\d+)$", tgt):
                block("redirect into a device (`%s`)." % tgt, "disk operations are human-only.")
        if b in ("chmod", "chown", "chgrp") and any(re.match(r"^-[A-Za-z]*R", a) for a in seg[i + 1:]):
            for a in seg[i + 1:]:
                if not a.startswith("-") and dangerous_target(a):
                    block("recursive `%s` over a protected location (`%s`)." % (b, a),
                          "change permissions on specific files inside the repository.")

TF = {"terraform", "tofu", "opentofu", "terragrunt", "tf"}
TF_ALIASES = set()

def collect_aliases(toks):
    for t in toks:
        m = re.match(r"^([A-Za-z_][\w-]*)=(.+)$", t)
        if m:
            first = m.group(2).strip().split()
            if first and base(first[0]) in (TF | TF_ALIASES):
                TF_ALIASES.add(m.group(1))
                TF_ALIASES.add("$" + m.group(1))
                TF_ALIASES.add("${" + m.group(1) + "}")

def check_tf(seg):
    for i, t in enumerate(seg):
        if base(t) not in TF and t not in TF_ALIASES:
            continue
        rest = [a.lower() for a in seg[i + 1:]]
        if "apply" in rest or "destroy" in rest:
            block("Terraform apply/destroy (`%s ... %s`). Apply and destroy are human-only, including wrapped, "
                  "aliased, -chdir, tofu and terragrunt forms." % (base(t), "apply" if "apply" in rest else "destroy"),
                  TF_INSTEAD)
        if "state" in rest:
            j = rest.index("state")
            if j + 1 < len(rest) and rest[j + 1] in ("rm", "push", "mv", "replace-provider"):
                block("Terraform state mutation (`state %s`)." % rest[j + 1], TF_INSTEAD)
        if "workspace" in rest and "delete" in rest:
            block("Terraform workspace deletion.", TF_INSTEAD)
        if "force-unlock" in rest:
            block("Terraform force-unlock.", TF_INSTEAD)

AWS_GLOBAL_WITH_VALUE = {"--region", "--profile", "--output", "--endpoint-url", "--query", "--color",
                         "--cli-read-timeout", "--cli-connect-timeout", "--ca-bundle"}

def aws_service_op(rest):
    vals = []
    skip = False
    for a in rest:
        if skip:
            skip = False
            continue
        if a.startswith("--"):
            if a in AWS_GLOBAL_WITH_VALUE:
                skip = True
            continue
        if a.startswith("-") or is_punct(a):
            continue
        vals.append(a.lower())
        if len(vals) == 2:
            break
    while len(vals) < 2:
        vals.append("")
    return vals[0], vals[1]

def check_aws(seg, read_only=False):
    for i, t in enumerate(seg):
        if base(t) != "aws":
            continue
        rest = seg[i + 1:]
        low = [a.lower() for a in rest]
        for a in low:
            if re.match(r"^(delete-|terminate-|batch-delete-|detach-)", a) or a in ("schedule-key-deletion", "disable-key", "purge-queue"):
                block("destructive AWS CLI operation (`aws ... %s`)." % a, AWS_INSTEAD)
        for j, a in enumerate(low):
            if a == "s3" and j + 1 < len(low):
                sub = low[j + 1]
                if sub == "rb":
                    block("S3 bucket removal (`aws s3 rb`).", AWS_INSTEAD)
                if sub == "rm" and "--recursive" in low:
                    block("recursive S3 delete (`aws s3 rm --recursive`).", AWS_INSTEAD)
                if sub == "sync" and "--delete" in low:
                    block("S3 sync with --delete.", AWS_INSTEAD)
                if sub == "mv" and "--recursive" in low:
                    block("recursive S3 move (deletes the source objects).", AWS_INSTEAD)
        if any(a in ("modify-instance-groups", "modify-instance-fleet", "put-managed-scaling-policy") for a in low):
            text = " ".join(rest)
            counts = [int(x) for x in re.findall(
                r"(?:InstanceCount|TargetOnDemandCapacity|TargetSpotCapacity|MaximumCapacityUnits)[\"']?\s*[=:]\s*(\d+)", text)]
            if not counts:
                block("EMR resize whose target size cannot be read from the command text (for example file:// input); "
                      "it is treated as a large scale-out.", "state the counts inline and keep each at or below %d, "
                      "or escalate the scale change to a human." % EMR_MAX)
            if max(counts) > EMR_MAX:
                block("large EMR scale-out (target %d exceeds the bounded limit of %d)." % (max(counts), EMR_MAX),
                      "expensive scale changes need human approval (docs/agents.md, Escalation).")
        if "secretsmanager" in low and "get-secret-value" in low:
            block("secret-store read (`aws secretsmanager get-secret-value`).", SECRET_INSTEAD)
        if "ssm" in low and any(a.startswith("get-parameter") for a in low) and "--with-decryption" in low:
            block("decrypted SSM parameter read.", SECRET_INSTEAD)
        if "configure" in low and ("export-credentials" in low or any("secret" in a for a in low)):
            block("AWS credential export or read.", SECRET_INSTEAD)
        if read_only:
            svc, op = aws_service_op(rest)
            ok = (op.startswith(("describe-", "list-", "get-", "head-", "search-", "lookup-", "batch-get-")) or
                  op in ("query", "scan", "filter-log-events", "validate-template") or (svc == "s3" and op == "ls"))
            if not ok:
                block("non-read AWS operation `aws %s %s` through the aws-api MCP server, which is inspection-only." % (svc, op),
                      "changes go through Terraform: an infra-engineer plan and a human apply.")

def check_git(seg):
    for i, t in enumerate(seg):
        if base(t) != "git":
            continue
        rest = seg[i + 1:]
        if "push" in rest:
            args = rest[rest.index("push") + 1:]
            for a in args:
                if (a in ("--force", "--force-with-lease", "--force-if-includes", "--mirror", "--delete", "-d")
                        or a.startswith("--force")
                        or (re.match(r"^-[A-Za-z]+$", a) and ("f" in a or "d" in a))
                        or (a.startswith("+") and len(a) > 1)
                        or (a.startswith(":") and len(a) > 1)):
                    block("force or ref-deleting push (`git push ... %s`)." % a, PUSH_INSTEAD)
        if "config" in rest and any(re.search(r"push\b.*(--force|\s-[A-Za-z]*f\b|\s\+)", a) for a in rest):
            block("git alias that force-pushes.", PUSH_INSTEAD)
        if "clean" in rest:
            args = rest[rest.index("clean") + 1:]
            flags = "".join(a[1:] for a in args if re.match(r"^-[A-Za-z]+$", a))
            if ("f" in flags or "--force" in args) and ("x" in flags or "X" in flags or "d" in flags):
                block("`git clean` with -d/-x/-X would delete untracked or ignored files, including datasets/raw/ "
                      "and uncommitted agent work or evidence.",
                      "remove specific build outputs, e.g. `rm -rf modules/<module>/target`, or ask a human.")

def rel_class(p):
    for g in (".claude", ".aws", ".ssh"):
        gd = os.path.join(HOME, g)
        if p == gd or p.startswith(gd + os.sep):
            return "global"
    if not p.startswith(PROJ + os.sep):
        return None
    rel = p[len(PROJ) + 1:]
    if rel == ".claude/.last-test-run":
        return "marker"
    if re.match(r"^docs/agent-runs/[^/]+/human-gate\.md$", rel):
        return "human"
    if rel in ("CLAUDE.md", "docs/agents.md", ".mcp.json") or rel == ".claude" or rel.startswith(".claude/"):
        return "const"
    return None

def classify(t):
    if t.startswith("of="):
        t = t[3:]
    if not t or is_punct(t):
        return None
    e = expand(t)
    full = os.path.normpath(e if os.path.isabs(e) else os.path.join(PROJ, e))
    for p in (full, os.path.realpath(full)):
        c = rel_class(p)
        if c:
            return c
    return None

WRITE_VERBS = {"tee", "truncate", "shred", "unlink", "rm", "rmdir", "chmod", "chown", "chgrp", "chflags",
               "touch", "ln", "install", "patch", "dd", "xattr", "mv"}
INPLACE = {"sed", "gsed", "perl", "ruby"}
TOP_CMD = ""

def marker_ok():
    rx = (r"^sbt(\s+-{1,2}[\w.=:-]+)*\s+check\s*;\s*echo\s+\"?exit=\$\?\"?\s*>\s*(\./|"
          + re.escape(PROJ) + r"/)?\.claude/\.last-test-run$")
    return re.match(rx, TOP_CMD.strip()) is not None

def check_protected_writes(seg):
    hits = []
    for i, t in enumerate(seg):
        if is_punct(t) and ">" in t and i + 1 < len(seg):
            c = classify(seg[i + 1])
            if c:
                hits.append((seg[i + 1], c))
    names = [base(t) for t in seg]
    prot = [(t, classify(t)) for t in seg if not t.startswith("-") or t.startswith("of=")]
    prot = [(t, c) for (t, c) in prot if c]
    if prot:
        verb = False
        if any(n in WRITE_VERBS for n in names):
            verb = True
        elif any(n in INPLACE for n in names) and any(a == "--in-place" or a.startswith("--in-place=") or re.match(r"^-[A-Za-z]*i", a) for a in seg):
            verb = True
        elif any(n in ("cp", "rsync", "scp", "ditto") for n in names):
            nonflag = [t for t in seg if not t.startswith("-") and not is_punct(t)]
            if nonflag and classify(nonflag[-1]):
                prot = [(nonflag[-1], classify(nonflag[-1]))]
                verb = True
        elif "git" in names:
            gi = names.index("git")
            sub = [s for s in seg[gi + 1:] if not s.startswith("-")]
            if sub and sub[0] in ("checkout", "restore", "rm", "mv"):
                verb = True
        if verb:
            hits.extend(prot)
    for t, c in hits:
        if c == "global":
            block("write to global Claude settings or credentials (`%s`), which no agent owns." % t,
                  "ask a human to change user-level configuration.")
        if c == "human":
            block("write to a human gate record (`%s`); human decisions are never rewritten by agents." % t,
                  "the human writes human-gate.md.")
        if c == "marker" and not marker_ok():
            block("the test-run marker `.claude/.last-test-run` may only be written by the canonical test command.",
                  "run exactly: " + MARKER_CMD)
        if c == "const" and not FACTORY:
            block("shell write to a constitutional file (`%s`)." % t, CONST_INSTEAD)

SCANNED = set()

def script_target(seg):
    k = effective(seg)
    if k is None:
        return None
    w = seg[k]
    b = base(w)
    cands = []
    if b in SHELLS or b in ("source", "."):
        args = seg[k + 1:]
        if any(re.match(r"^-[A-Za-z]*n[A-Za-z]*$", a) for a in args):
            return None  # syntax check only (bash -n)
        if any(re.match(r"^-[A-Za-z]*c[A-Za-z]*$", a) for a in args):
            return None  # inline string; inspected by recursion
        for a in args:
            if a.startswith("-") or is_punct(a):
                continue
            cands.append(a)
            break
    elif "/" in w:
        cands.append(w)
    for c in cands:
        e = expand(c)
        full = e if os.path.isabs(e) else os.path.join(PROJ, e)
        if os.path.isfile(full) and is_shell_script(full):
            rp = os.path.realpath(full)
            if rp.startswith(os.path.join(PROJ, ".claude", "hooks") + os.sep):
                return None  # ratified, write-protected hook code contains the very patterns it blocks
            return rp
    return None

def is_shell_script(path):
    # Only shell scripts are scanned: a .sh/.bash/.zsh extension or a shell shebang.
    if re.search(r"\.(sh|bash|zsh)$", path):
        return True
    try:
        with open(path, "rb") as fh:
            first = fh.readline(200)
    except OSError:
        return False
    return re.match(rb"^#!.*\b(ba|z|da|k)?sh\b", first) is not None

def read_script(path):
    try:
        with open(path, "rb") as fh:
            data = fh.read(600000)
    except OSError:
        return None
    if b"\x00" in data:
        return None
    text = data.decode("utf-8", "replace")
    lines = [ln for ln in text.splitlines() if not ln.lstrip().startswith("#")]
    return "\n".join(lines)

def check_command(cmd, depth):
    if depth > MAX_DEPTH:
        block("the command nests shells or strings too deeply to inspect (fail-closed).",
              "simplify the command, or put the logic in an approved script under scripts/.")
    check_raw(cmd)
    toks = tokenize(cmd)
    collect_aliases(toks)
    segs = segments(toks)
    conservative = False
    for s in segs:
        k = effective(s)
        if k is not None and base(s[k]) in SINKS:
            conservative = True
    for seg in segs:
        check_rm(seg)
        check_find(seg)
        check_disk(seg)
        check_tf(seg)
        check_aws(seg)
        check_git(seg)
        check_protected_writes(seg)
        st = script_target(seg)
        if st and st not in SCANNED:
            SCANNED.add(st)
            text = read_script(st)
            if text:
                CTX.append(os.path.relpath(st, PROJ) if st.startswith(PROJ + os.sep) else st)
                check_command(text, depth + 1)
                CTX.pop()
        if conservative or not benign(seg):
            for t in seg:
                if re.search(r"[\s;&|]", t):
                    check_command(t, depth + 1)

DEPLOY_SCRIPTS = {"deploy", "rollback", "smoke-test", "run-bounded-pipeline", "check", "package", "archive-evidence"}
DEPLOY_READONLY = {"ls", "cat", "head", "tail", "wc", "grep", "date", "pwd", "echo", "printf", "test", "[", "true",
                   "sha256sum", "shasum", "diff", "mkdir"}

def deploy_script_name(w):
    e = expand(w)
    if os.path.isabs(e):
        if not e.startswith(PROJ + os.sep):
            return None
        e = e[len(PROJ) + 1:]
    m = re.match(r"^(?:\./)?scripts/([\w.-]+?)(?:\.sh)?$", e)
    return m.group(1) if m else None

def check_deploy_scope(cmd):
    toks = tokenize(cmd)
    for i, t in enumerate(toks):
        if is_punct(t) and ">" in t and i + 1 < len(toks):
            tgt = toks[i + 1]
            ok = tgt == "/dev/null" or re.match(r"^(\./)?reports/deployments/", tgt) or tgt == ".claude/.last-test-run"
            if not ok:
                block("deploy-engineer may only redirect output into reports/deployments/** (got `%s`)." % tgt,
                      "write execution records under reports/deployments/.")
    for seg in segments(toks):
        k = effective(seg)
        if k is None:
            continue
        w = seg[k]
        b = base(w)
        name = deploy_script_name(w)
        if name is None and b in ("bash", "sh"):
            args = [a for a in seg[k + 1:] if not a.startswith("-")]
            if args:
                name = deploy_script_name(args[0])
        if name is not None:
            if name in DEPLOY_SCRIPTS:
                continue
            block("deploy-engineer may not execute scripts/%s (not on the approved execution list; cleanup and plan "
                  "scripts are human or infra-engineer territory)." % name,
                  "approved scripts: " + ", ".join("scripts/%s.sh" % s for s in sorted(DEPLOY_SCRIPTS)) + ".")
        if b == "git":
            sub = [a for a in seg[k + 1:] if not a.startswith("-")]
            if sub and sub[0] in ("status", "diff", "log", "rev-parse", "show", "describe"):
                continue
        elif b == "aws":
            svc, op = aws_service_op(seg[k + 1:])
            if op.startswith(("describe-", "list-", "get-")) or (svc == "s3" and op == "ls"):
                continue
        elif b == "sbt" and [a for a in seg[k + 1:] if not a.startswith("-")] == ["check"]:
            continue
        elif b == "mkdir":
            if all(a.startswith("-") or re.match(r"^(\./)?reports/deployments/", a) for a in seg[k + 1:]):
                continue
        elif b in DEPLOY_READONLY:
            continue
        block("deploy-engineer may execute only approved scripts under scripts/ and read-only inspection commands "
              "(got `%s`)." % w,
              "run an approved script (" + ", ".join("scripts/%s.sh" % s for s in sorted(DEPLOY_SCRIPTS)) +
              "), or return BLOCKED-ON naming infra-engineer if a script is missing.")

def mcp_strings(v, out):
    if isinstance(v, str):
        out.append(v)
    elif isinstance(v, dict):
        for x in v.values():
            mcp_strings(x, out)
    elif isinstance(v, list):
        for x in v:
            mcp_strings(x, out)

def check_mcp():
    parts = TOOL.split("__")
    server = parts[1] if len(parts) > 1 else ""
    name = "__".join(parts[2:])
    if server == "github":
        if re.match(r"^(create|update|delete|merge|push|fork|add|remove|assign|request|submit|dismiss|rerun|re_run|"
                    r"cancel|run|trigger|close|reopen|lock|unlock|mark|manage|set|edit|enable|disable)_", name):
            block("GitHub MCP write operation `%s`; the GitHub server is read-only by policy." % name,
                  "a human performs repository writes (pull requests, merges, issues) on GitHub.")
    elif server == "aws-api":
        cli = TI.get("cli_command")
        if isinstance(cli, str) and cli.strip():
            c = cli.strip()
            if not re.match(r"^aws\b", c):
                c = "aws " + c
            check_raw(c)
            for seg in segments(tokenize(c)):
                check_aws(seg, read_only=True)
        else:
            strs = []
            mcp_strings(TI, strs)
            for s in strs:
                if re.search(r"\baws\s+\S+", s):
                    for seg in segments(tokenize(s)):
                        check_aws(seg, read_only=True)

if TOOL == "Bash":
    cmd = TI.get("command", "")
    if not isinstance(cmd, str):
        block("the Bash command was not a string (fail-closed).", "retry with a plain command string.")
    TOP_CMD = cmd
    if AGENT == "deploy-engineer":
        check_deploy_scope(cmd)
    check_command(cmd, 0)
elif TOOL.startswith("mcp__"):
    check_mcp()
sys.exit(0)
PY

printf '%s' "$INPUT" | python3 -I -c "$PY_CODE"
rc=$?
if [ "$rc" -eq 0 ]; then
  exit 0
fi
if [ "$rc" -ne 2 ]; then
  echo "BLOCKED by .claude/hooks/guard-dangerous.sh: the inspector failed with exit $rc (fail-closed). Approved path instead: report this to factory-engineer through the orchestrator." >&2
fi
exit 2

"""Shared helpers for the ScholarChain experiment harness (Python standard library only).

Rules implemented here (Phase 15b):
  * raw files are write-once: open_new() uses O_CREAT|O_EXCL and refuses to overwrite (exit code via RawFileExists);
  * derived outputs use the same helper unless the caller passes force=True;
  * anything derived from SYNTHETIC input is labeled SYNTHETIC (file name prefix and a column/field).
"""
import hashlib
import json
import os
import re
import subprocess
import sys
from datetime import datetime, timezone

SYNTHETIC_PREFIX = "SYNTHETIC-"
TOKEN_RE = re.compile(r"^[A-Za-z0-9._-]+$")


class RawFileExists(Exception):
    pass


def repo_root():
    here = os.path.dirname(os.path.abspath(__file__))
    return os.path.realpath(os.path.join(here, "..", ".."))


def default_results_root():
    """The in-repository raw results root (experiments/results)."""
    return os.path.join(repo_root(), "experiments", "results")


def results_root():
    """Raw results root: $SC_RESULTS_ROOT when set (absolute or relative to the current directory), else experiments/results.

    Why it exists: raw outputs inside the repository make the working tree dirty and scripts/run-experiment.sh refuses a dirty tree
    (scripts/lib/common.sh SC_GENERATED_EXCLUDES covers only reports/*). See experiments/README.md, "Between experiments".
    """
    env = os.environ.get("SC_RESULTS_ROOT", "")
    return os.path.abspath(env) if env else default_results_root()


def derived_root(kind):
    """Default output directory for derived tables, plots and evaluation outputs when SC_RESULTS_ROOT is set: <root>-derived/<kind>, a SIBLING
    of the raw root (raw results stay raw-only), else None (the caller keeps its in-repository default)."""
    return os.path.join(results_root().rstrip(os.sep) + "-derived", kind) if os.environ.get("SC_RESULTS_ROOT") else None


def utc_iso(epoch=None):
    dt = datetime.now(timezone.utc) if epoch is None else datetime.fromtimestamp(epoch, timezone.utc)
    return dt.strftime("%Y-%m-%dT%H:%M:%S.") + "%03dZ" % (dt.microsecond // 1000)


def parse_iso(text):
    """ISO-8601 (Z or +-HH:MM, optional fraction) to epoch seconds (float)."""
    t = text.strip()
    if t.endswith("Z"):
        t = t[:-1] + "+00:00"
    return datetime.fromisoformat(t).timestamp()


def valid_token(value):
    return bool(TOKEN_RE.match(value or ""))


def open_new(path, mode="w", encoding="utf-8", newline=""):
    """Exclusive create: raises RawFileExists when the file is already there. Never truncates."""
    os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
    try:
        fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o644)
    except FileExistsError:
        raise RawFileExists("refusing to overwrite existing file: %s" % path)
    if "b" in mode:
        return os.fdopen(fd, mode)
    return os.fdopen(fd, mode, encoding=encoding, newline=newline)


def open_output(path, force=False, **kw):
    """Derived (reproducible) outputs: write-once by default; force=True replaces."""
    if force and os.path.exists(path):
        os.remove(path)
    return open_new(path, **kw)


def write_json_new(path, obj):
    with open_new(path, newline="\n") as f:
        json.dump(obj, f, indent=2, sort_keys=True)
        f.write("\n")


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def is_synthetic_name(path):
    return os.path.basename(path).startswith(SYNTHETIC_PREFIX)


def out_name(name, synthetic):
    return (SYNTHETIC_PREFIX + name) if synthetic and not name.startswith(SYNTHETIC_PREFIX) else name


def read_json(path):
    with open(path, "r", encoding="utf-8") as f:
        return json.load(f)


def read_jsonl(path):
    rows = []
    with open(path, "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line:
                rows.append(json.loads(line))
    return rows


def warn(msg):
    print("SC-WARN " + msg, file=sys.stderr)


def info(msg):
    print("SC-INFO " + msg)


def git_state():
    """(HEAD commit or 'unknown', dirty flag or None when git is unavailable). Dirty means any porcelain output for the whole tree."""
    root = repo_root()
    try:
        head = subprocess.run(["git", "-C", root, "rev-parse", "HEAD"], capture_output=True, text=True, timeout=10).stdout.strip() or "unknown"
        dirty = bool(subprocess.run(["git", "-C", root, "status", "--porcelain"], capture_output=True, text=True, timeout=30).stdout.strip())
        return head, dirty
    except (OSError, subprocess.SubprocessError):
        return "unknown", None


def provenance_path(output_path):
    return output_path + ".provenance.json"


def check_free(path, force=False):
    """Write-once pre-check for an output AND its provenance sidecar, so a refusal never leaves half a pair behind."""
    if not force:
        for p in (path, provenance_path(path)):
            if os.path.exists(p):
                raise RawFileExists("refusing to overwrite existing file: %s" % p)


def input_record(path):
    """{path, sha256} for an input file (sha256 is null with an error note when the file cannot be read: never invented)."""
    ap = os.path.abspath(path)
    root = repo_root()
    rec = {"path": os.path.relpath(ap, root) if ap.startswith(root + os.sep) else ap}
    try:
        rec["sha256"] = sha256_file(path)
    except OSError as e:
        rec["sha256"] = None
        rec["error"] = type(e).__name__
    return rec


def write_provenance(output_path, script, argv, inputs, synthetic, benchmark=None, extra=None, force=False):
    """Write <output>.provenance.json next to a derived output. inputs: list of paths; benchmark: path or None.
    Same write-once rule as the output itself (force replaces)."""
    head, dirty = git_state()
    rec = {"schema": "scholarchain-provenance.v1", "output": os.path.basename(output_path), "output_sha256": sha256_file(output_path),
           "script": os.path.relpath(os.path.abspath(script), repo_root()), "argv": list(argv), "inputs": [input_record(p) for p in inputs],
           "benchmark_sha256": sha256_file(benchmark) if benchmark else None, "git_head": head, "tree_dirty": dirty,
           "generated_at_utc": utc_iso(), "synthetic": bool(synthetic), "python": sys.version.split()[0]}
    if extra:
        rec["parameters"] = extra
    pp = provenance_path(output_path)
    with open_output(pp, force=force, newline="\n") as f:
        json.dump(rec, f, indent=2, sort_keys=True)
        f.write("\n")
    return pp

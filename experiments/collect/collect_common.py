"""Shared plumbing for the collectors: write-once raw files, run metadata, SYNTHETIC labeling (Python standard library only)."""
import argparse
import json
import os
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, ".."))
sys.path.insert(0, os.path.join(HERE, "..", "common"))
sys.path.insert(0, HERE)
import sc_common  # noqa: E402
import awscli  # noqa: E402

ROOT = sc_common.repo_root()


def base_parser(description):
    ap = argparse.ArgumentParser(description=description)
    ap.add_argument("--experiment-id", required=True, help="manifest id, for example exp-d-query-load")
    ap.add_argument("--run-id", required=True, help="token: letters, digits, . _ -")
    ap.add_argument("--region", help="default: AWS_REGION (us-east-2 for this project)")
    ap.add_argument("--out-dir", help="default <results root>/<experiment-id>/<run-id>/ (results root: $SC_RESULTS_ROOT or experiments/results)")
    ap.add_argument("--dry-run", action="store_true",
                    help="print the exact AWS CLI commands; runs them ONLY when the aws on PATH is the repository stub, never a real aws")
    return ap


def git_head():
    try:
        return subprocess.run(["git", "-C", ROOT, "rev-parse", "HEAD"], capture_output=True, text=True, timeout=10).stdout.strip() or "unknown"
    except (OSError, subprocess.SubprocessError):
        return "unknown"


class Saver(object):
    def __init__(self, args, collector, aws):
        for tok in (args.experiment_id, args.run_id):
            if not sc_common.valid_token(tok):
                print("SC-REFUSED ids may contain only letters, digits, . _ -", file=sys.stderr)
                sys.exit(2)
        self.args, self.collector, self.aws = args, collector, aws
        self.synthetic = bool(aws.stub)  # stub output is always SYNTHETIC
        self.out_dir = args.out_dir or os.path.join(sc_common.results_root(), args.experiment_id, args.run_id)
        self.files = []
        self.errors = []
        self.started = sc_common.utc_iso()

    def path(self, name):
        return os.path.join(self.out_dir, sc_common.out_name("%s-%s-%s" % (self.collector, self.args.run_id, name), self.synthetic))

    def save(self, name, text):
        """Write the raw CLI output byte-for-byte. Exclusive create: an existing file stops the collector (exit 3)."""
        p = self.path(name)
        try:
            with sc_common.open_new(p, newline="") as f:
                f.write(text)
        except sc_common.RawFileExists as e:
            print("SC-REFUSED %s" % e, file=sys.stderr)
            sys.exit(3)
        self.files.append({"file": os.path.basename(p), "sha256": sc_common.sha256_file(p)})
        print("SC-RAW %s" % p)
        return p

    def error(self, name, rc, stderr):
        """A failed AWS call is kept as its own file (never dropped); stderr only, no credentials are ever in it."""
        p = self.path(name + ".ERROR")
        with sc_common.open_new(p, newline="") as f:
            f.write('{"synthetic": %s, "rc": %s, "stderr": %s}\n' % ("true" if self.synthetic else "false", rc, json.dumps(stderr[:2000])))
        self.errors.append({"name": name, "rc": rc, "file": os.path.basename(p)})
        print("SC-WARN call failed (rc=%s): kept as %s" % (rc, p), file=sys.stderr)

    def finish(self, extra=None):
        if self.args.dry_run and not self.aws.stub:
            print("SC-DRYRUN done: %d command(s) printed, none executed, nothing written" % len(self.aws.log))
            return 0
        meta = {"collector": self.collector, "label": "SYNTHETIC" if self.synthetic else "REAL", "synthetic": self.synthetic,
                "experiment_id": self.args.experiment_id, "run_id": self.args.run_id, "region": self.aws.region, "commit": git_head(),
                "started_at": self.started, "ended_at": sc_common.utc_iso(), "dry_run": self.args.dry_run, "aws_binary_is_stub": self.aws.stub,
                "commands": self.aws.log, "files": self.files, "errors": self.errors, "python": sys.version.split()[0]}
        meta.update(extra or {})
        mp = os.path.join(self.out_dir, sc_common.out_name("%s-%s.meta.json" % (self.collector, self.args.run_id), self.synthetic))
        try:
            sc_common.write_json_new(mp, meta)
        except sc_common.RawFileExists as e:
            print("SC-REFUSED %s" % e, file=sys.stderr)
            return 3
        print("SC-DONE %s files=%d errors=%d label=%s" % (self.collector, len(self.files), len(self.errors), meta["label"]))
        return 0 if not self.errors else 1

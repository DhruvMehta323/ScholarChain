"""Thin, read-only wrapper around the AWS CLI for the collectors (Python standard library only).

Rules:
  * only the (service, operation) pairs in READ_ONLY are executed; anything else raises NotAllowed (the failure drill has its own, separate,
    explicitly human-triggered allowlist MUTATING);
  * --dry-run prints the exact command line. It EXECUTES the command only when the `aws` found on PATH is the repository stub
    (experiments/collect/testing/stub-bin/aws); a real aws binary is never run in dry-run mode;
  * raw stdout is returned byte-for-byte so the collector can store it unedited;
  * the wrapper never reads or prints credentials, and passes --region explicitly on every call.
"""
import json
import os
import shutil
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
STUB_DIR = os.path.realpath(os.path.join(HERE, "testing", "stub-bin"))

READ_ONLY = {
    ("cloudwatch", "get-metric-data"),
    ("cloudwatch", "list-metrics"),
    ("ecs", "describe-services"),
    ("ecs", "list-tasks"),
    ("ecs", "describe-tasks"),
    ("sqs", "get-queue-attributes"),
    ("emr", "describe-cluster"),
    ("emr", "list-steps"),
    ("emr", "list-instance-groups"),
    ("emr", "list-instances"),
    ("emr", "describe-step"),
    ("ce", "get-cost-and-usage"),
    ("logs", "start-query"),
    ("logs", "get-query-results"),
    ("sts", "get-caller-identity"),
}
MUTATING = {("sqs", "send-message"), ("ecs", "stop-task")}


class NotAllowed(Exception):
    pass


def aws_binary():
    return shutil.which("aws")


def is_stub(path=None):
    p = path or aws_binary()
    return bool(p) and os.path.realpath(p).startswith(STUB_DIR + os.sep)


class Aws(object):
    def __init__(self, region, dry_run=False, allow=None):
        self.region = region
        self.dry_run = dry_run
        self.allow = allow or READ_ONLY
        self.binary = aws_binary()
        self.stub = is_stub(self.binary)
        self.log = []  # command records for the meta file

    def command(self, service, op, args):
        if (service, op) not in self.allow:
            raise NotAllowed("%s %s is not an allowed operation for this collector" % (service, op))
        return ["aws", service, op] + list(args) + ["--region", self.region, "--output", "json"]

    def run(self, service, op, args, timeout=120):
        """Return (executed, rc, stdout, stderr). executed is False when a dry run did not run the command."""
        cmd = self.command(service, op, args)
        shown = " ".join(c if len(c) < 160 else c[:157] + "..." for c in cmd)
        if self.dry_run:
            print("SC-DRYRUN %s%s" % (shown, "  [stub aws: executed]" if self.stub else "  [not executed]"))
            if not self.stub:
                self.log.append({"command": shown, "executed": False})
                return False, None, "", ""
        if not self.binary:
            self.log.append({"command": shown, "executed": False, "error": "aws binary not found on PATH"})
            return False, 127, "", "aws binary not found on PATH"
        p = subprocess.run([self.binary] + cmd[1:], capture_output=True, text=True, timeout=timeout)
        self.log.append({"command": shown, "executed": True, "rc": p.returncode})
        return True, p.returncode, p.stdout, p.stderr


def require_region(value):
    region = value or os.environ.get("AWS_REGION", "")
    if not region:
        print("SC-REFUSED --region or AWS_REGION is required (Terraform output region; us-east-2 for this project)", file=sys.stderr)
        sys.exit(2)
    return region


def parse_json(text):
    try:
        return json.loads(text)
    except ValueError:
        return None

#!/usr/bin/env python3
"""Experiment F: ONE safe failure of the embedding worker. Prints a plan by default; mutates only with --execute --yes.

Usage:
  python3 -B experiments/collect/failure_drill.py --manifest experiments/manifests/exp-f-failure-recovery.json --run-id RUN \
      --action poison-message|stop-task --dry-run
  python3 -B experiments/collect/failure_drill.py --manifest ... --run-id RUN --action poison-message --queue-url URL --region us-east-2 --execute --yes
  python3 -B experiments/collect/failure_drill.py --manifest ... --run-id RUN --action stop-task --cluster-name C [--service embedding-worker] --execute --yes
  (--run-id may be omitted when SC_RUN_ID is set; execution is human only)
Exactly ONE failure per run (--action is required and the two actions are mutually exclusive):
  poison-message  sqs send-message: ONE poison message (a non-JSON marker body) to the embedding queue. Expected, NOT VERIFIED against the worker
                  code on main: the worker rejects it, retries (visibility 300 s, maxReceiveCount 4 per INFRA-CONTRACTS section 5) and it lands in the DLQ.
  stop-task       ecs list-tasks (read-only), then ecs stop-task of ONE running task of the embedding-worker service; the service starts a replacement.
Nothing is deleted or purged. Without --execute and --yes the script prints the commands and exits 0 without any AWS call.
With --dry-run it is the same plan print, and it only EXECUTES through the repository stub aws (labeled SYNTHETIC).

Record (write-once, append-only JSON Lines, <SYNTHETIC->failure_drill-<run>-record.jsonl under the results root):
  * BEFORE the first AWS call the script exclusively creates the record with a "start" event (plan, manifest sha256, commit, action). When the
    record already exists the script exits 3 and makes NO AWS call, so a second run id reuse can never repeat a mutation;
  * before each mutating call an "action_begin" event is appended (an interrupted process still leaves evidence of the attempt); right after
    each call an "action_end" event is appended with UTC times, rc, response, stderr. Failures and timeouts are recorded too;
  * a call that exceeds the 120 s limit (subprocess.TimeoutExpired) is recorded as outcome "outcome_unknown" (the mutation may or may not
    have happened): check the queue or the service before any retry, the script never retries;
  * a final "end" event carries the exit code.
Exit codes: 0 ok, 2 usage, 3 refused (record exists, --execute without --yes, not approved, wrong environment), 4 AWS call failed,
5 outcome unknown (timeout).
"""
import json
import os
import re
import subprocess
import sys

import collect_common as cc
import sc_common
from awscli import Aws, MUTATING, READ_ONLY, parse_json, require_region

ACTIONS = ("poison-message", "stop-task")


def append_event(path, event):
    """Append one JSON line (O_APPEND) and fsync it: each event is on disk before the next AWS call."""
    data = (json.dumps(event, sort_keys=True) + "\n").encode("utf-8")
    fd = os.open(path, os.O_WRONLY | os.O_APPEND)
    try:
        os.write(fd, data)
        os.fsync(fd)
    finally:
        os.close(fd)


def create_start_record(path, event):
    """Exclusive create + first line. Raises RawFileExists (nothing is touched) when the record exists."""
    with sc_common.open_new(path, newline="\n") as f:
        f.write(json.dumps(event, sort_keys=True) + "\n")
        f.flush()
        os.fsync(f.fileno())


def run_action(aws, record, name, svc, op, args):
    """Run one AWS call and record it. Returns (outcome, stdout) with outcome in ok | failed | outcome_unknown."""
    t0 = sc_common.utc_iso()
    if (svc, op) in MUTATING:
        append_event(record, {"event": "action_begin", "name": name, "t_utc": t0})
    try:
        ok, rc, out, err = aws.run(svc, op, args)
    except subprocess.TimeoutExpired:
        append_event(record, {"event": "action_end", "name": name, "t_start_utc": t0, "t_end_utc": sc_common.utc_iso(), "outcome": "outcome_unknown",
                              "rc": None, "response": None, "stderr": "awscli timeout after 120 s: the mutation may or may not have happened"})
        return "outcome_unknown", ""
    outcome = "ok" if rc == 0 else "failed"
    append_event(record, {"event": "action_end", "name": name, "t_start_utc": t0, "t_end_utc": sc_common.utc_iso(), "outcome": outcome, "rc": rc,
                          "response": parse_json(out) if rc == 0 else None, "stderr": (err or "")[:500] if rc else ""})
    return outcome, out


def main(argv):
    ap = cc.base_parser(__doc__.split("\n")[0])
    ap.set_defaults(experiment_id="exp-f-failure-recovery")
    for act in ap._actions:
        if act.dest == "experiment_id":
            act.required = False
        if act.dest == "run_id":
            act.required = False
            act.default = os.environ.get("SC_RUN_ID") or None
    ap.add_argument("--manifest", required=True)
    ap.add_argument("--action", required=True, choices=ACTIONS, help="the ONE failure to perform: poison-message or stop-task (mutually exclusive)")
    ap.add_argument("--queue-url")
    ap.add_argument("--cluster-name")
    ap.add_argument("--service", default="embedding-worker")
    ap.add_argument("--execute", action="store_true")
    ap.add_argument("--yes", action="store_true")
    a = ap.parse_args(argv)
    if not a.run_id:
        print("SC-REFUSED --run-id (or SC_RUN_ID) is required", file=sys.stderr)
        return 2
    m = sc_common.read_json(a.manifest)
    if m.get("letter") != "F":
        print("SC-REFUSED the manifest is not experiment F", file=sys.stderr)
        return 2
    if a.execute and not a.yes:
        print("SC-REFUSED --execute needs --yes (a human decision)", file=sys.stderr)
        return 3
    if a.execute and not (m.get("approved") and m.get("approved_by")):
        print("SC-REFUSED the manifest is not approved (approved and approved_by must be set by the human)", file=sys.stderr)
        return 3
    region = require_region(a.region)
    if region != m["environment"]["region"]:
        print("SC-REFUSED region %s is not the manifest region %s" % (region, m["environment"]["region"]), file=sys.stderr)
        return 3
    if a.queue_url and not re.match(r"^https://sqs\.[a-z0-9-]+\.amazonaws\.com/\d{12}/scholarchain-embedding-jobs$", a.queue_url):
        print("SC-REFUSED --queue-url must be the main embedding queue (scholarchain-embedding-jobs), never the DLQ", file=sys.stderr)
        return 3
    a.experiment_id = m["id"]
    aws = Aws(region, a.dry_run, allow=set(READ_ONLY) | set(MUTATING))
    sv = cc.Saver(a, "failure_drill", aws)
    if a.action == "poison-message":
        body = "SYNTHETIC-POISON-%s (not JSON on purpose)" % a.run_id
        steps = [("send-poison-message", "sqs", "send-message", ["--queue-url", a.queue_url or "<queue-url>", "--message-body", body])]
    else:
        if not a.cluster_name:
            print("SC-REFUSED --action stop-task needs --cluster-name", file=sys.stderr)
            return 2
        steps = [("list-worker-tasks", "ecs", "list-tasks", ["--cluster", a.cluster_name, "--service-name", a.service, "--desired-status", "RUNNING"]),
                 ("stop-one-worker-task", "ecs", "stop-task", ["--cluster", a.cluster_name, "--task", "<first task arn from list-worker-tasks>", "--reason", "experiment F drill"])]
    print("SC-PLAN experiment F, action %s: %d step(s); ONE failure only; nothing is deleted or purged" % (a.action, len(steps)))
    for name, svc, op, args in steps:
        print("SC-PLAN %s: aws %s %s %s" % (name, svc, op, " ".join(args)))
    live = a.execute or (a.dry_run and aws.stub)
    if not live:
        print("SC-DRYRUN no AWS call made (add --execute --yes to perform; a human decision)")
        return 0
    if a.action == "poison-message" and not a.queue_url and not aws.stub:
        print("SC-REFUSED --queue-url is required to execute", file=sys.stderr)
        return 2

    # The write-once record comes FIRST: no AWS call happens unless this exclusive create succeeds.
    record = sv.path("record.jsonl")
    start = {"event": "start", "label": "SYNTHETIC" if sv.synthetic else "REAL", "synthetic": sv.synthetic, "experiment_id": m["id"], "run_id": a.run_id,
             "region": region, "action": a.action, "manifest_sha256": sc_common.sha256_file(a.manifest), "commit": cc.git_head(), "t_utc": sc_common.utc_iso(),
             "plan": [{"name": n, "service": s, "operation": o} for n, s, o, _ in steps]}
    try:
        create_start_record(record, start)
    except sc_common.RawFileExists as e:
        print("SC-REFUSED %s (no AWS call was made)" % e, file=sys.stderr)
        return 3
    print("SC-RECORD %s (start event written before any AWS call)" % record)

    rc_final, task_arn = 0, None
    for name, svc, op, args in steps:
        if name == "stop-one-worker-task":
            if not task_arn:
                append_event(record, {"event": "action_end", "name": name, "outcome": "skipped", "t_utc": sc_common.utc_iso(), "reason": "no running task found"})
                break
            args = [x if not x.startswith("<") else task_arn for x in args]
        if name == "send-poison-message" and not a.queue_url:  # stub only
            args = [x if not x.startswith("<") else "https://sqs.us-east-2.amazonaws.com/000000000000/scholarchain-embedding-jobs" for x in args]
        outcome, out = run_action(aws, record, name, svc, op, args)
        if outcome == "ok" and name == "list-worker-tasks":
            arns = (parse_json(out) or {}).get("taskArns", [])
            task_arn = arns[0] if arns else None
        if outcome != "ok":
            rc_final = 5 if outcome == "outcome_unknown" else 4
            break
    append_event(record, {"event": "end", "t_utc": sc_common.utc_iso(), "exit_code": rc_final})
    print("SC-DONE record %s label=%s exit=%d" % (record, start["label"], rc_final))
    return rc_final


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))

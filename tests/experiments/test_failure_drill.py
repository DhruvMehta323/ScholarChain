"""M2/M3: experiment F failure drill. A fake `aws` on PATH (in-test, not the repository stub) counts every call; nothing touches AWS.

Covered: --action is required; an unapproved manifest never reaches aws; one approved poison-message run makes exactly one mutating call and an
ordered record (start, action_begin, action_end, end); a rerun with the same run id (either action) is refused with zero further aws calls; a
timeout is recorded as outcome_unknown with exit 5; the two actions cannot both be performed in one invocation.
"""
import glob
import json
import os
import subprocess
import sys

import pytest

from conftest import COLLECT, MANIFEST_DIR, clean_env, py

DRILL = os.path.join(COLLECT, "failure_drill.py")
COMMITTED_F = os.path.join(MANIFEST_DIR, "exp-f-failure-recovery.json")
QUEUE = "https://sqs.us-east-2.amazonaws.com/123456789012/scholarchain-embedding-jobs"
EXP = "exp-f-failure-recovery"


@pytest.fixture
def fake_aws(tmp_path):
    """A fake aws that appends 'service operation' to a call log and answers with minimal JSON. Not under the repository stub-bin."""
    d = tmp_path / "fakebin"
    d.mkdir()
    log = tmp_path / "aws-calls.log"
    script = d / "aws"
    script.write_text("#!/bin/sh\necho \"$1 $2\" >> '%s'\n"
                      "case \"$1 $2\" in\n"
                      "  'sqs send-message') echo '{\"MessageId\": \"m-fake\"}' ;;\n"
                      "  'ecs list-tasks') echo '{\"taskArns\": [\"arn:aws:ecs:us-east-2:123456789012:task/c/fake\"]}' ;;\n"
                      "  *) echo '{}' ;;\n"
                      "esac\n" % log)
    os.chmod(str(script), 0o755)

    class Fake(object):
        bindir = str(d)
        path = str(d) + ":/usr/bin:/bin"

        def calls(self):
            return open(str(log)).read().splitlines() if log.exists() else []

        def mutating(self):
            return [c for c in self.calls() if c in ("sqs send-message", "ecs stop-task")]
    return Fake()


@pytest.fixture
def approved_f(tmp_path):
    with open(COMMITTED_F) as f:
        m = json.load(f)
    m["approved"], m["approved_by"] = True, "test-human"
    d = tmp_path / "manifest"
    d.mkdir()
    p = d / "exp-f-failure-recovery.json"
    p.write_text(json.dumps(m))
    return str(p)


def drill_env(tmp_path, fake):
    return clean_env({"SC_RESULTS_ROOT": str(tmp_path / "results"), "AWS_REGION": "us-east-2"}, path=fake.path)


def drill(tmp_path, fake, manifest, args, run_id="f1"):
    return py(DRILL, ["--manifest", manifest, "--run-id", run_id] + list(args), env=drill_env(tmp_path, fake))


def record_path(tmp_path, run_id="f1"):
    found = glob.glob(os.path.join(str(tmp_path / "results"), EXP, run_id, "*record.jsonl"))
    return found[0] if found else None


def events(path):
    with open(path) as f:
        return [json.loads(l) for l in f if l.strip()]


POISON = ["--action", "poison-message", "--queue-url", QUEUE, "--execute", "--yes"]


def test_action_is_required(tmp_path, fake_aws, approved_f):
    p = drill(tmp_path, fake_aws, approved_f, ["--execute", "--yes"])
    assert p.returncode == 2 and "--action" in p.stderr
    assert fake_aws.calls() == [] and record_path(tmp_path) is None


def test_unknown_or_combined_action_value_is_rejected(tmp_path, fake_aws, approved_f):
    for bad in ("poison-message,stop-task", "both", "all"):
        p = drill(tmp_path, fake_aws, approved_f, ["--action", bad, "--queue-url", QUEUE, "--cluster-name", "c", "--execute", "--yes"], run_id="x" + str(len(bad)))
        assert p.returncode == 2 and "invalid choice" in p.stderr, bad
    assert fake_aws.calls() == []


def test_unapproved_manifest_with_execute_yes_is_refused_with_zero_aws_calls(tmp_path, fake_aws):
    p = drill(tmp_path, fake_aws, COMMITTED_F, POISON)
    assert p.returncode == 3 and "not approved" in p.stderr
    assert fake_aws.calls() == [] and record_path(tmp_path) is None


def test_execute_without_yes_is_refused(tmp_path, fake_aws, approved_f):
    p = drill(tmp_path, fake_aws, approved_f, ["--action", "poison-message", "--queue-url", QUEUE, "--execute"])
    assert p.returncode == 3 and "--yes" in p.stderr
    assert fake_aws.calls() == [] and record_path(tmp_path) is None


def test_plan_only_run_makes_no_aws_call_and_writes_nothing(tmp_path, fake_aws, approved_f):
    p = drill(tmp_path, fake_aws, approved_f, ["--action", "poison-message", "--queue-url", QUEUE])
    assert p.returncode == 0 and "SC-DRYRUN" in p.stdout
    assert fake_aws.calls() == [] and record_path(tmp_path) is None


def test_poison_message_run_makes_one_mutating_call_and_ordered_record(tmp_path, fake_aws, approved_f):
    p = drill(tmp_path, fake_aws, approved_f, POISON)
    assert p.returncode == 0, p.stdout + p.stderr
    assert fake_aws.calls() == ["sqs send-message"]  # exactly one call in total, and it is the one mutation
    assert fake_aws.mutating() == ["sqs send-message"]
    rec = record_path(tmp_path)
    assert rec is not None and not os.path.basename(rec).startswith("SYNTHETIC-")  # a fake aws is not the stub: labeled REAL
    ev = events(rec)
    assert [e["event"] for e in ev] == ["start", "action_begin", "action_end", "end"]
    assert ev[0]["action"] == "poison-message" and ev[0]["synthetic"] is False and ev[0]["run_id"] == "f1"
    assert ev[2]["outcome"] == "ok" and ev[2]["rc"] == 0 and ev[2]["response"] == {"MessageId": "m-fake"}
    assert ev[3]["exit_code"] == 0


@pytest.mark.parametrize("second", [
    ["--action", "poison-message", "--queue-url", QUEUE, "--execute", "--yes"],
    ["--action", "stop-task", "--cluster-name", "c1", "--execute", "--yes"],
])
def test_rerun_with_same_run_id_is_refused_and_makes_no_further_aws_call(tmp_path, fake_aws, approved_f, second):
    first = drill(tmp_path, fake_aws, approved_f, POISON)
    assert first.returncode == 0, first.stdout + first.stderr
    rec = record_path(tmp_path)
    before = open(rec, "rb").read()
    calls_before = fake_aws.calls()
    assert calls_before == ["sqs send-message"]
    again = drill(tmp_path, fake_aws, approved_f, second)
    assert again.returncode == 3, again.stdout + again.stderr
    assert "refusing to overwrite" in again.stderr and "no AWS call was made" in again.stderr
    assert fake_aws.calls() == calls_before  # zero additional calls, not even a read-only list-tasks
    assert open(rec, "rb").read() == before  # the record was not touched


def test_two_actions_cannot_both_be_performed_in_one_invocation(tmp_path, fake_aws, approved_f):
    # argparse keeps one value per option: whichever --action wins, the other failure is never performed in the same run
    p = drill(tmp_path, fake_aws, approved_f, ["--action", "poison-message", "--action", "stop-task", "--queue-url", QUEUE, "--cluster-name", "c1",
                                                 "--execute", "--yes"])
    calls = fake_aws.calls()
    assert not ("sqs send-message" in calls and "ecs stop-task" in calls), calls
    assert len(fake_aws.mutating()) <= 1, calls
    assert p.returncode == 0, p.stdout + p.stderr
    rec = events(record_path(tmp_path))
    assert rec[0]["action"] in ("poison-message", "stop-task")
    assert len([e for e in rec if e["event"] == "action_begin"]) == 1


def test_stop_task_run_makes_one_read_and_one_mutating_call(tmp_path, fake_aws, approved_f):
    p = drill(tmp_path, fake_aws, approved_f, ["--action", "stop-task", "--cluster-name", "c1", "--execute", "--yes"])
    assert p.returncode == 0, p.stdout + p.stderr
    assert fake_aws.calls() == ["ecs list-tasks", "ecs stop-task"]
    assert fake_aws.mutating() == ["ecs stop-task"]
    assert [e["event"] for e in events(record_path(tmp_path))] == ["start", "action_end", "action_begin", "action_end", "end"]


def test_queue_url_must_be_the_main_queue_not_the_dlq(tmp_path, fake_aws, approved_f):
    p = drill(tmp_path, fake_aws, approved_f, ["--action", "poison-message", "--queue-url", QUEUE + "-dlq", "--execute", "--yes"])
    assert p.returncode == 3 and "never the DLQ" in p.stderr
    assert fake_aws.calls() == []


def test_timeout_is_recorded_as_outcome_unknown_and_exits_5(tmp_path, fake_aws, approved_f, monkeypatch, capsys):
    # in-process: the 120 s limit cannot be waited out, so the aws subprocess call raises TimeoutExpired for the mutating call only
    monkeypatch.syspath_prepend(COLLECT)
    monkeypatch.setenv("SC_RESULTS_ROOT", str(tmp_path / "results"))
    monkeypatch.setenv("AWS_REGION", "us-east-2")
    monkeypatch.setenv("PATH", fake_aws.path)
    import failure_drill
    import awscli
    real_run = subprocess.run
    seen = []

    def fake_run(cmd, *a, **kw):
        if isinstance(cmd, list) and cmd and os.path.basename(str(cmd[0])) == "aws":
            seen.append(cmd[1:3])
            raise subprocess.TimeoutExpired(cmd, 120)
        return real_run(cmd, *a, **kw)
    monkeypatch.setattr(awscli.subprocess, "run", fake_run)
    rc = failure_drill.main(["--manifest", approved_f, "--run-id", "f1"] + POISON)
    assert rc == 5, capsys.readouterr()
    assert seen == [["sqs", "send-message"]]  # one attempt, never retried
    assert fake_aws.calls() == []  # the patched call never reached the fake binary
    ev = events(record_path(tmp_path))
    assert [e["event"] for e in ev] == ["start", "action_begin", "action_end", "end"]
    assert ev[2]["outcome"] == "outcome_unknown" and ev[2]["rc"] is None and "may or may not have happened" in ev[2]["stderr"]
    assert ev[3]["exit_code"] == 5

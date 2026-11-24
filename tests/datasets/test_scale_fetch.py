"""Phase 15a tests: batched, cache-first, resumable, capped fetchers (fake transports, no network).

Every HTTP call goes through scale_helpers.FakeTransport; urllib.request.urlopen is patched to
fail in each test, so a stray real request cannot pass silently.
Run: PYTHONDONTWRITEBYTECODE=1 python3 -m pytest -p no:cacheprovider -q tests/datasets
"""
import contextlib
import importlib
import io
import json
import math
import os
import shutil
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock
from urllib.parse import unquote

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import scale_helpers as H  # noqa: E402

fc = importlib.import_module("fetch_common")
crossref = importlib.import_module("fetch_crossref")
openalex = importlib.import_module("fetch_openalex")
orcid = importlib.import_module("fetch_orcid")

FAKE_EMAIL = "fake.person@example.invalid"
FAKE_KEY = "sk-fake-openalex-key-0123456789"


def no_real_network(*a, **k):
    raise AssertionError("a real network call was attempted")


class FetchCase(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp(prefix="scale-fetch-"))
        self.addCleanup(shutil.rmtree, self.tmp, ignore_errors=True)
        p = mock.patch("urllib.request.urlopen", side_effect=no_real_network)
        p.start()
        self.addCleanup(p.stop)
        env = mock.patch.dict(os.environ, {"SCHOLARCHAIN_CONTACT_EMAIL": FAKE_EMAIL,
                                           "OPENALEX_API_KEY": FAKE_KEY})
        env.start()
        self.addCleanup(env.stop)
        self.sleeps, self.logs = [], []
        self.out = self.tmp / "out"

    def selection(self, n):
        return H.write_selection(self.tmp / "sel.tsv", range(n))

    def run_main(self, module, n, transport, extra=(), batch=None):
        sel = self.selection(n)
        argv = ["--selection", str(sel), "--limit", str(n), "--allow-more", "--pause", "0",
                "--out", str(self.out)]
        if batch is not None:
            argv += ["--batch-size", str(batch)]
        argv += list(extra)
        so = io.StringIO()
        with contextlib.redirect_stdout(so):
            code = module.main(argv, transport=transport, sleep=self.sleeps.append, log=self.logs.append)
        out = so.getvalue()
        counts = None
        for line in reversed(out.splitlines()):
            if line.startswith("{"):
                counts = json.loads(line)
                break
        return code, counts, out

    def real_sleeps(self):
        return [s for s in self.sleeps if s > 0]


class BatchSizeTests(FetchCase):
    def test_crossref_batches_hold_at_most_50_dois(self):
        t = H.FakeTransport(H.crossref_ok)
        code, counts, _ = self.run_main(crossref, 120, t, extra=["--max-requests", "50"])
        self.assertEqual(code, 0)
        sizes = [len(H.crossref_filter_dois(u)) for u in t.urls]
        self.assertEqual(sizes, [50, 50, 20])
        self.assertEqual(counts["batch_requests"], 3)
        self.assertEqual(len(H.envelopes(self.out)), 120)

    def test_openalex_batches_hold_at_most_100_dois(self):
        t = H.FakeTransport(H.openalex_ok)
        code, counts, _ = self.run_main(openalex, 250, t, extra=["--max-requests", "50"])
        self.assertEqual(code, 0)
        sizes = [len(H.openalex_filter_dois(u)) for u in t.urls]
        self.assertEqual(sizes, [100, 100, 50])
        self.assertTrue(all("per_page=100" in u or "per_page=50" in u for u in t.urls))

    def test_openalex_refuses_a_batch_size_above_the_documented_limit(self):
        t = H.FakeTransport(H.openalex_ok)
        with self.assertRaises(SystemExit) as cm:
            self.run_main(openalex, 10, t, batch=101)
        self.assertIn("REFUSED", str(cm.exception))
        self.assertEqual(t.calls, [])

    def test_crossref_batch_size_one_uses_singleton_urls(self):
        t = H.FakeTransport(H.crossref_ok)
        code, counts, _ = self.run_main(crossref, 4, t, batch=1)
        self.assertEqual(code, 0)
        self.assertEqual(len(t.calls), 4)
        self.assertTrue(all("filter=" not in u for u in t.urls))


class CacheFirstAndResumeTests(FetchCase):
    def test_cached_doi_is_never_requested(self):
        cached = H.doi_of(2)
        fc.store_response(self.out, cached, "https://api.crossref.org/works/x", 200,
                          b'{"message": {"DOI": "cached"}}', {})
        before = (self.out / H.env_name(cached)).read_bytes()
        t = H.FakeTransport(H.crossref_ok)
        code, counts, _ = self.run_main(crossref, 6, t, batch=50)
        self.assertEqual(code, 0)
        self.assertEqual(counts["already_present"], 1)
        requested = {d for u in t.urls for d in H.crossref_filter_dois(u)}
        self.assertNotIn(cached, requested)
        self.assertEqual(len(requested), 5)
        self.assertEqual((self.out / H.env_name(cached)).read_bytes(), before)

    def test_fully_cached_run_makes_no_request_at_all(self):
        t1 = H.FakeTransport(H.crossref_ok)
        self.run_main(crossref, 8, t1, batch=50)
        self.assertGreater(len(t1.calls), 0)
        t2 = H.FakeTransport(H.crossref_ok)
        code, counts, _ = self.run_main(crossref, 8, t2, batch=50)
        self.assertEqual(code, 0)
        self.assertEqual(t2.calls, [])
        self.assertEqual(counts["already_present"], 8)
        self.assertEqual(counts["requests_made"], 0)

    def test_interrupted_run_resumes_without_refetching(self):
        state = {"n": 0}

        def handler(url, headers):
            state["n"] += 1
            if state["n"] == 3:
                raise KeyboardInterrupt  # killed while the third batch is in flight
            return H.crossref_ok(url, headers)

        t1 = H.FakeTransport(handler)
        with self.assertRaises(KeyboardInterrupt):
            self.run_main(crossref, 50, t1, batch=10)
        done = {d for u in t1.urls[:2] for d in H.crossref_filter_dois(u)}
        self.assertEqual(len(H.envelopes(self.out)), 20)
        t2 = H.FakeTransport(H.crossref_ok)
        code, counts, _ = self.run_main(crossref, 50, t2, batch=10)
        self.assertEqual(code, 0)
        again = {d for u in t2.urls for d in H.crossref_filter_dois(u)}
        self.assertFalse(done & again, "resume re-fetched DOIs that already had envelopes")
        self.assertEqual(len(again), 30)
        self.assertEqual(counts["already_present"], 20)
        self.assertEqual(len(H.envelopes(self.out)), 50)

    def test_cap_stopped_run_then_rerun_completes_each_doi_once(self):
        t1 = H.FakeTransport(H.crossref_ok)
        code, counts, _ = self.run_main(crossref, 30, t1, batch=1, extra=["--max-requests", "10"])
        self.assertEqual(code, 3)
        t2 = H.FakeTransport(H.crossref_ok)
        code, counts, _ = self.run_main(crossref, 30, t2, batch=1, extra=["--max-requests", "100"])
        self.assertEqual(code, 0)
        all_urls = t1.urls + t2.urls
        self.assertEqual(len(all_urls), 30)
        self.assertEqual(len(set(all_urls)), 30)


class BackoffAndCapTests(FetchCase):
    def test_429_honours_retry_after_via_injected_sleep(self):
        seq = [(429, b"slow down", {"Retry-After": "7"}), (429, b"", {"Retry-After": "7"})]

        def handler(url, headers):
            if seq:
                return seq.pop(0)
            return H.crossref_ok(url, headers)

        t = H.FakeTransport(handler)
        code, counts, _ = self.run_main(crossref, 1, t, batch=1)
        self.assertEqual(code, 0)
        self.assertEqual(len(t.calls), 3)
        self.assertEqual(self.real_sleeps(), [7.0, 7.0])
        self.assertEqual(counts["retrieved_ok"], 1)

    def test_large_retry_after_is_capped_at_60_seconds(self):
        self.assertEqual(fc.MAX_RETRY_AFTER_SECONDS, 60.0)
        seq = [(429, b"slow down", {"Retry-After": "100000"})]

        def handler(url, headers):
            return seq.pop(0) if seq else H.crossref_ok(url, headers)

        t = H.FakeTransport(handler)
        code, counts, _ = self.run_main(crossref, 1, t, batch=1)
        self.assertEqual(code, 0)
        self.assertEqual(self.real_sleeps(), [60.0])
        self.assertEqual(counts["retrieved_ok"], 1)

    def test_small_retry_after_passes_through_unchanged(self):
        seq = [(429, b"", {"Retry-After": "7"})]

        def handler(url, headers):
            return seq.pop(0) if seq else H.crossref_ok(url, headers)

        t = H.FakeTransport(handler)
        code, _, _ = self.run_main(crossref, 1, t, batch=1)
        self.assertEqual(code, 0)
        self.assertEqual(self.real_sleeps(), [7.0])

    def test_5xx_backs_off_exponentially_then_succeeds(self):
        seq = [(503, b"", {}), (500, b"", {}), (502, b"", {})]

        def handler(url, headers):
            return seq.pop(0) if seq else H.crossref_ok(url, headers)

        t = H.FakeTransport(handler)
        code, counts, _ = self.run_main(openalex, 1, t, batch=1)
        self.assertEqual(code, 0)
        s = self.real_sleeps()
        self.assertEqual(len(s), 3)
        for i, w in enumerate(s):  # base 2 s, doubling, plus at most 0.5 s jitter
            self.assertGreaterEqual(w, 2.0 * 2 ** i)
            self.assertLessEqual(w, 2.0 * 2 ** i + 0.5)

    def test_persistent_503_gives_up_after_retries_and_stores_nothing(self):
        t = H.FakeTransport(lambda u, h: (503, b"", {}))
        code, counts, _ = self.run_main(crossref, 1, t, batch=1, extra=["--max-requests", "100"])
        self.assertEqual(len(t.calls), 6)  # 1 try + 5 retries
        self.assertEqual(counts["errors"], 1)
        self.assertEqual(H.envelopes(self.out), [])  # an error is retried next run, never stored

    def test_hard_cap_stops_the_run_at_exactly_max_requests(self):
        t = H.FakeTransport(H.crossref_ok)
        code, counts, _ = self.run_main(crossref, 40, t, batch=1, extra=["--max-requests", "5"])
        self.assertEqual(code, 3)
        self.assertEqual(len(t.calls), 5)
        self.assertTrue(counts["aborted"])
        self.assertIn("hard request cap", counts["abort_reason"])
        self.assertEqual(counts["not_attempted"], 35)
        self.assertEqual(len(H.envelopes(self.out)), 5)

    def test_hard_cap_counts_retries(self):
        t = H.FakeTransport(lambda u, h: (503, b"", {}))
        code, counts, _ = self.run_main(crossref, 1, t, batch=1, extra=["--max-requests", "3"])
        self.assertEqual(len(t.calls), 3)
        self.assertEqual(code, 3)
        self.assertTrue(counts["aborted"])

    def test_request_budget_unit(self):
        b = fc.RequestBudget(2)
        b.consume()
        b.consume()
        with self.assertRaises(fc.RequestCapReached):
            b.consume()
        self.assertEqual(b.remaining, 0)


class PlanTests(FetchCase):
    def assert_plan_silent(self, module, extra=()):
        t = H.FakeTransport(lambda u, h: (_ for _ in ()).throw(AssertionError("network used by --plan")))
        code, counts, out = self.run_main(module, 30, t, extra=["--plan", *extra])
        self.assertEqual(code, 0)
        self.assertEqual(t.calls, [])
        self.assertIn("PLAN (no network request is made)", out)
        self.assertEqual(self.logs, [])
        self.assertFalse(self.out.exists() and any(self.out.iterdir()))

    def test_crossref_plan_makes_no_call_and_writes_nothing(self):
        self.assert_plan_silent(crossref)

    def test_openalex_plan_makes_no_call_and_writes_nothing(self):
        self.assert_plan_silent(openalex)

    def test_orcid_plan_makes_no_call_and_writes_nothing(self):
        cr, oa = self.tmp / "cr", self.tmp / "oa"
        cr.mkdir()
        oa.mkdir()
        ids = self.tmp / "ids.json"
        t = H.FakeTransport(lambda u, h: (_ for _ in ()).throw(AssertionError("network used by --plan")))
        so = io.StringIO()
        with contextlib.redirect_stdout(so):
            code = orcid.main(["--crossref-dir", str(cr), "--openalex-dir", str(oa), "--out", str(self.out),
                               "--ids-file", str(ids), "--plan"], transport=t,
                              sleep=self.sleeps.append, log=self.logs.append)
        self.assertEqual(code, 0)
        self.assertEqual(t.calls, [])
        self.assertIn("PLAN (no network request is made)", so.getvalue())
        self.assertFalse(ids.exists())

    def test_plan_arithmetic_uses_the_batch_size(self):
        so = io.StringIO()
        info = {"works_in_selection": 120, "works_considered": 120, "works_with_valid_doi": 120,
                "invalid_doi": 0, "distinct_dois": 120, "dois": [H.doi_of(i) for i in range(120)]}
        with contextlib.redirect_stdout(so):
            res = fc.plan_summary(source="x", info=info, out_dir=self.out, batch_size=50, max_requests=200,
                                  pause=0.5, latency=1.0, fallback_bytes=1000)
        # documented constant: 3% singleton allowance (an ASSUMPTION sized above the observed rates)
        self.assertEqual(fc.SINGLETON_ALLOWANCE_PCT, 3)
        expected_allowance = math.ceil(120 * fc.SINGLETON_ALLOWANCE_PCT / 100)  # ceil(3.6) = 4
        self.assertEqual(expected_allowance, 4)
        self.assertEqual((res["to_fetch"], res["requests"], res["allowance"]), (120, 3, expected_allowance))
        self.assertIn("3% allowance, an ASSUMPTION sized above the observed rates", so.getvalue())

    def test_plan_allowance_is_zero_for_singleton_batches(self):
        so = io.StringIO()
        info = {"works_in_selection": 120, "works_considered": 120, "works_with_valid_doi": 120,
                "invalid_doi": 0, "distinct_dois": 120, "dois": [H.doi_of(i) for i in range(120)]}
        with contextlib.redirect_stdout(so):
            res = fc.plan_summary(source="x", info=info, out_dir=self.out, batch_size=1, max_requests=200,
                                  pause=0.5, latency=1.0, fallback_bytes=1000)
        self.assertEqual((res["requests"], res["allowance"]), (120, 0))
        self.assertNotIn("ASSUMPTION", so.getvalue())

    def test_fetch_common_version_is_2_1_0(self):
        self.assertEqual(fc.FETCH_COMMON_VERSION, "2.1.0")


class AliasRecheckTests(FetchCase):
    def test_doi_absent_from_a_batch_is_rechecked_as_a_singleton_and_stored(self):
        missing = H.doi_of(1)
        t = H.FakeTransport(lambda u, h: H.crossref_ok(u, h, absent={missing}))
        code, counts, _ = self.run_main(crossref, 5, t, batch=50)
        self.assertEqual(code, 0)
        singles = [u for u in t.urls if "filter=" not in u]
        self.assertEqual([H.crossref_single_doi(u) for u in singles], [missing])
        self.assertEqual(counts["absent_from_batch"], 1)
        self.assertEqual(counts["retrieved_ok"], 5)
        env = json.loads((self.out / H.env_name(missing)).read_text())
        self.assertFalse(env["missing"])
        self.assertEqual(env["http_status"], 200)
        self.assertEqual(env["fetched_via"], "single_after_batch_miss")

    def test_alias_doi_returned_under_another_primary_doi_is_rechecked(self):
        alias = H.doi_of(0)
        t = H.FakeTransport(lambda u, h: H.openalex_ok(u, h, alias={alias: "10.9999/other.primary"}))
        code, counts, _ = self.run_main(openalex, 3, t, batch=100)
        self.assertEqual(code, 0)
        self.assertEqual(counts["absent_from_batch"], 1)
        self.assertEqual(sum(1 for u in t.urls if "filter=" not in u), 1)
        env = json.loads((self.out / H.env_name(alias)).read_text())
        self.assertEqual(env["fetched_via"], "single_after_batch_miss")

    def test_singleton_404_after_a_batch_miss_is_recorded_as_missing(self):
        gone = H.doi_of(2)

        def handler(url, headers):
            if "filter=" not in url and H.crossref_single_doi(url) == gone:
                return 404, b"Resource not found.", {}
            return H.crossref_ok(url, headers, absent={gone})

        t = H.FakeTransport(handler)
        code, counts, _ = self.run_main(crossref, 4, t, batch=50)
        self.assertEqual(code, 0)
        self.assertEqual(counts["missing_404"], 1)
        env = json.loads((self.out / H.env_name(gone)).read_text())
        self.assertTrue(env["missing"])
        self.assertEqual(env["fetched_via"], "single_after_batch_miss")

    def test_unverified_miss_envelope_from_the_smoke_run_is_looked_up_again(self):
        d = H.doi_of(0)
        self.out.mkdir()
        (self.out / H.env_name(d)).write_text(json.dumps(
            {"identifier": d, "missing": True, "missing_reason": fc.UNVERIFIED_MISS}), encoding="utf-8")
        self.assertFalse(fc.is_cached(self.out, d))
        t = H.FakeTransport(H.crossref_ok)
        code, counts, _ = self.run_main(crossref, 1, t, batch=1)
        self.assertEqual(len(t.calls), 1)
        self.assertEqual(json.loads((self.out / H.env_name(d)).read_text())["http_status"], 200)
        self.assertTrue(any(p.name.startswith(H.env_name(d) + ".superseded-") for p in self.out.iterdir()))


class NoSecretLeakTests(FetchCase):
    def all_stored_text(self):
        parts = []
        for p in self.out.rglob("*"):
            if p.is_file():
                parts.append(p.read_text(encoding="utf-8", errors="replace"))
        return "\n".join(parts)

    def assert_clean(self, text, what):
        self.assertNotIn(FAKE_EMAIL, text, what)
        self.assertNotIn(FAKE_KEY, text, what)
        self.assertNotIn("fake.person", text, what)

    def test_crossref_log_stdout_and_envelopes_hold_no_email(self):
        seq = [(503, b"", {})]
        t = H.FakeTransport(lambda u, h: seq.pop(0) if seq else H.crossref_ok(u, h))
        code, counts, out = self.run_main(crossref, 3, t, batch=50)
        # the email is really sent, in the User-Agent header only (test is not vacuous)
        self.assertIn(FAKE_EMAIL, t.calls[0][1]["User-Agent"])
        self.assertTrue(any("retry" in l for l in self.logs))
        self.assert_clean("\n".join(self.logs) + out, "log/stdout")
        self.assert_clean("\n".join(t.urls), "request urls")
        self.assert_clean(self.all_stored_text(), "stored envelopes")

    def test_openalex_key_is_header_only_and_never_logged_or_stored(self):
        t = H.FakeTransport(lambda u, h: (503, b"", {}) if len(t.calls) == 1 else H.openalex_ok(u, h))
        code, counts, out = self.run_main(openalex, 3, t, batch=100)
        self.assertEqual(t.calls[0][1]["Authorization"], f"Bearer {FAKE_KEY}")
        self.assert_clean("\n".join(self.logs) + out, "log/stdout")
        self.assert_clean("\n".join(t.urls), "request urls")
        self.assert_clean(self.all_stored_text(), "stored envelopes")

    def test_transport_errors_are_logged_and_stored_without_secrets(self):
        def boom(url, headers):
            raise OSError(f"connection reset talking to {url}")

        t = H.FakeTransport(boom)
        code, counts, out = self.run_main(crossref, 2, t, batch=50)
        self.assertTrue(self.logs)
        self.assert_clean("\n".join(self.logs) + out, "log/stdout")
        self.assert_clean(self.all_stored_text(), "errors.jsonl")

    def test_orcid_run_logs_no_secrets(self):
        # one stored Crossref record with an ORCID so the run fetches something
        cr, oa = self.tmp / "cr", self.tmp / "oa"
        oa.mkdir()
        d = H.doi_of(0)
        body = {"message": {"author": [{"ORCID": "http://orcid.org/0000-0002-1825-0097"}]}}
        fc.store_response(cr, d, "https://api.crossref.org/works/x", 200, json.dumps(body).encode(), {})
        sel = H.write_selection(self.tmp / "sel.tsv", [0])
        seq = [(503, b"", {})]
        t = H.FakeTransport(lambda u, h: seq.pop(0) if seq else (200, b'{"group": []}', {}))
        so = io.StringIO()
        with contextlib.redirect_stdout(so):
            code = orcid.main(["--crossref-dir", str(cr), "--openalex-dir", str(oa), "--selection", str(sel),
                               "--out", str(self.out), "--ids-file", str(self.tmp / "ids.json"),
                               "--pause", "0"], transport=t, sleep=self.sleeps.append, log=self.logs.append)
        self.assertEqual(code, 0)
        self.assertEqual(len(H.envelopes(self.out)), 1)
        self.assertIn(FAKE_EMAIL, t.calls[0][1]["User-Agent"])
        self.assert_clean("\n".join(self.logs) + so.getvalue(), "log/stdout")
        self.assert_clean(self.all_stored_text(), "orcid envelopes")

    def test_plan_output_reports_presence_only(self):
        t = H.FakeTransport(H.crossref_ok)
        code, counts, out = self.run_main(openalex, 5, t, extra=["--plan"])
        self.assertIn("OPENALEX_API_KEY=set", out)
        self.assert_clean(out, "plan output")

    def test_redact_url_removes_mailto_and_keys(self):
        u = fc.redact_url(f"https://x.test/works?mailto={FAKE_EMAIL}&api_key={FAKE_KEY}&filter=doi:a")
        self.assert_clean(u, "redacted url")
        self.assertIn("filter=doi:a", unquote(u))


if __name__ == "__main__":
    unittest.main()

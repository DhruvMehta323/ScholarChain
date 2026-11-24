"""Shared helpers for the Phase 15a scale tests (fake transports, synthetic selections).

No network: every HTTP call in these tests goes through a fake transport with the contract of
fetch_common.urllib_transport: callable(url, headers, timeout) -> (status, body bytes, headers).
"""
import hashlib
import json
import sys
from pathlib import Path
from urllib.parse import parse_qs, unquote, urlsplit

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[1]
sys.dont_write_bytecode = True
sys.path.insert(0, str(REPO / "datasets" / "scripts"))

SEL_HEADER = "key\ttype\tyear\tvenue\tdoi\tn_authors\ttitle"


def sel_row(i, title=None):
    year = 1990 + (i % 7)
    venue = f"conf/syn{i % 3}"
    title = title if title is not None else f"Synthetic title {i}"
    return "\t".join([f"{venue}/K{i:05d}", "inproceedings", str(year), venue,
                      f"10.1234/syn.{i:05d}", "2", title])


def write_selection(path, indices, titles=None):
    titles = titles or {}
    lines = [SEL_HEADER] + [sel_row(i, titles.get(i)) for i in indices]
    Path(path).write_text("\n".join(lines) + "\n", encoding="utf-8")
    return path


def doi_of(i):
    return f"10.1234/syn.{i:05d}"


def env_name(identifier):
    return hashlib.sha256(identifier.encode("utf-8")).hexdigest() + ".json"


def envelopes(directory):
    return sorted(p for p in Path(directory).glob("*.json"))


class FakeTransport:
    """Records every call. handler(url, headers) -> (status, body bytes, headers) or raises."""

    def __init__(self, handler):
        self.handler = handler
        self.calls = []

    def __call__(self, url, headers, timeout):
        self.calls.append((url, dict(headers)))
        return self.handler(url, dict(headers))

    @property
    def urls(self):
        return [u for u, _ in self.calls]


def crossref_filter_dois(url):
    q = parse_qs(urlsplit(url).query)
    return [p[len("doi:"):] for p in unquote(q["filter"][0]).split(",")]


def crossref_single_doi(url):
    return unquote(urlsplit(url).path.split("/works/", 1)[1])


def crossref_ok(url, headers, absent=(), alias=None):
    """200 for singletons and batches. DOIs in `absent` are left out of batch responses.
    alias={requested: primary} makes the list return the work under another DOI."""
    alias = alias or {}
    if "filter=" in url:
        items = []
        for d in crossref_filter_dois(url):
            if d in absent:
                continue
            items.append({"DOI": alias.get(d, d), "title": ["t"]})
        return 200, json.dumps({"message-version": "1.0.0", "message": {"items": items}}).encode(), {}
    d = crossref_single_doi(url)
    return 200, json.dumps({"status": "ok", "message": {"DOI": d, "title": ["single"]}}).encode(), {}


def openalex_filter_dois(url):
    q = parse_qs(urlsplit(url).query)
    vals = unquote(q["filter"][0])[len("doi:"):].split("|")
    return [v[len("https://doi.org/"):] for v in vals]


def openalex_ok(url, headers, absent=(), alias=None):
    alias = alias or {}
    if "filter=" in url:
        res = [{"id": "W1", "doi": "https://doi.org/" + alias.get(d, d)}
               for d in openalex_filter_dois(url) if d not in absent]
        return 200, json.dumps({"results": res}).encode(), {}
    return 200, json.dumps({"id": "Wsingle", "doi": "x"}).encode(), {}

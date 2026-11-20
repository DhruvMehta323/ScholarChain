"""Canonical DOI normalisation (stdlib only). THE canonical rule for the Python side.

Human-ratified rule (Phase 03 repair 02). The Scala core normaliser implements the same rule;
the shared test-vector file modules/core/src/test/resources/doi-normalization-vectors.tsv is the
single source of truth for both languages.

  1. Trim leading and trailing characters of the WS set: U+0009-000D, U+001C-001F, U+0020,
     U+0085, U+00A0, U+1680, U+2000-200A, U+2028, U+2029, U+202F, U+205F, U+3000.
     (Not str.strip() with no argument, not \\s.)
  2. Remove at most one prefix, matched ASCII-case-insensitively (no Unicode case folding):
     "https://doi.org/", "http://doi.org/", "https://dx.doi.org/", "http://dx.doi.org/"
     (URL form), or "doi:" followed by zero or more U+0020 only (not URL form).
     Bare input (no prefix) is not URL form.
  3. URL form only: strict percent-decode exactly once. Reject if any "%" is not followed by
     two hex digits; decode the bytes as strict UTF-8 (reject on error or lone surrogate).
     "+" stays "+". Bare and "doi:" input are never decoded; "%" there is a literal character.
  4. Lower-case ASCII A-Z only (never str.lower()).
  5. Reject if empty. The result must fully match "10." + 4 to 9 ASCII digits + "/" + one or
     more characters none of which is in WS or CTRL (CTRL = U+0000-001F and U+007F-009F).
     No Unicode normalisation, no query or fragment stripping; zero-width characters pass.

  0. (Before step 1) Reject any input containing a surrogate code point U+D800-DFFF.

Every rejection returns None. Never guess a repair. normalize_doi is idempotent:
normalize_doi(normalize_doi(x)) == normalize_doi(x) for every accepted x, because the output is
passed as bare input and bare input is never decoded. The caveat applies only if the output is
re-wrapped into a URL (a second URL-form pass would decode a literal "%"), which is why callers
must pass the raw source string exactly once.

Outgoing requests: encode the canonical DOI exactly once with doi_path_segment().
"""
from __future__ import annotations

import re
from urllib.parse import quote, unquote_to_bytes

_WS = ("\u0009-\u000d\u001c-\u001f \u0085   - "
       "    　")
_CTRL = "\u0000-\u001f\u007f-\u009f"
_WS_CHARS = "".join(chr(c) for c in
                    list(range(0x09, 0x0E)) + list(range(0x1C, 0x20)) +
                    [0x20, 0x85, 0xA0, 0x1680] + list(range(0x2000, 0x200B)) +
                    [0x2028, 0x2029, 0x202F, 0x205F, 0x3000])

_URL_PREFIX = re.compile(r"(?:https://doi\.org/|http://doi\.org/|https://dx\.doi\.org/|"
                         r"http://dx\.doi\.org/)", re.ASCII | re.IGNORECASE)
_DOI_PREFIX = re.compile(r"doi: *", re.ASCII | re.IGNORECASE)
_BAD_PERCENT = re.compile(r"%(?![0-9A-Fa-f]{2})")
_CANON = re.compile(r"10\.[0-9]{4,9}/[^" + _WS + _CTRL + r"]+")
_LOWER = {c: c + 32 for c in range(ord("A"), ord("Z") + 1)}


_SURROGATE = re.compile("[\ud800-\udfff]")


def _normalize(raw: str) -> tuple[str, bool] | None:
    if _SURROGATE.search(raw):  # lone surrogate anywhere in the input: reject first (as Scala)
        return None
    s = raw.strip(_WS_CHARS)
    url_form = False
    m = _URL_PREFIX.match(s)
    if m:
        s, url_form = s[m.end():], True
    else:
        m = _DOI_PREFIX.match(s)
        if m:
            s = s[m.end():]
    if url_form:
        if _BAD_PERCENT.search(s):
            return None
        try:
            s = unquote_to_bytes(s).decode("utf-8", "strict")
        except (UnicodeError, ValueError):
            return None
    s = s.translate(_LOWER)
    if not s or not _CANON.fullmatch(s):
        return None
    return s, url_form


def normalize_doi(raw: str) -> str | None:
    """Canonical DOI for raw, or None when the input is rejected."""
    r = _normalize(raw)
    return r[0] if r else None


def normalize_doi_url_form(raw: str) -> str | None:
    """Like normalize_doi but accepts only input that arrived in URL form (a doi.org URL).

    Used by the DBLP eligibility rule "at least one <ee> that is a doi.org URL".
    """
    r = _normalize(raw)
    return r[0] if r and r[1] else None


def doi_path_segment(doi: str) -> str:
    """Percent-encode a canonical DOI exactly once for an API URL path (quote(d, safe=""))."""
    if not normalize_doi_is_canonical(doi):
        raise ValueError(f"not a canonical DOI: {doi!r}")
    return quote(doi, safe="")


def normalize_doi_is_canonical(doi: str) -> bool:
    """True if doi is already in canonical lower-case bare form (stricter than normalize_doi:
    a '%' in it is a literal, so the bare re-normalisation must leave it unchanged)."""
    return normalize_doi(doi) == doi

"""Pure metric functions used by the analysis scripts and by tests/experiments (Python standard library only).

Definitions (fixed here so hand computation is possible):
  percentile(values, p)   nearest-rank: sorted ascending, rank = ceil(p/100 * n), value at rank (1-based); p in (0, 100].
  recall_at_k             |relevant intersect top-k| / |relevant|; 0.0 when relevant is empty is NOT assumed: returns None.
  reciprocal_rank         1 / rank of the first relevant item (1-based), 0.0 when none is returned; None when relevant is empty.
  ndcg_at_k               binary gain, DCG = sum_{i=1..k} rel_i / log2(i + 1); IDCG uses min(|relevant|, k) ones; None when relevant is empty.
  detect_scale_events     consecutive samples with a different value: one event per change (direction out/in).
"""
import math


def percentile(values, p):
    vals = sorted(values)
    if not vals:
        return None
    if not (0 < p <= 100):
        raise ValueError("p must be in (0, 100]")
    rank = max(1, math.ceil(p / 100.0 * len(vals)))
    return vals[rank - 1]


def recall_at_k(ranked, relevant, k):
    rel = set(relevant)
    if not rel:
        return None
    return len(rel.intersection(ranked[:k])) / float(len(rel))


def reciprocal_rank(ranked, relevant):
    rel = set(relevant)
    if not rel:
        return None
    for i, item in enumerate(ranked, start=1):
        if item in rel:
            return 1.0 / i
    return 0.0


def ndcg_at_k(ranked, relevant, k):
    rel = set(relevant)
    if not rel:
        return None
    seen = set()
    dcg = 0.0
    for i, item in enumerate(ranked[:k], start=1):
        if item in rel and item not in seen:
            dcg += 1.0 / math.log2(i + 1)
            seen.add(item)
    idcg = sum(1.0 / math.log2(i + 1) for i in range(1, min(len(rel), k) + 1))
    return dcg / idcg


def detect_scale_events(samples):
    """samples: list of (t_epoch_seconds, count) in any order. Returns list of dicts t, from, to, direction."""
    ordered = sorted(samples, key=lambda s: s[0])
    events = []
    for (t0, c0), (t1, c1) in zip(ordered, ordered[1:]):
        if c1 != c0:
            events.append({"t": t1, "from": c0, "to": c1, "direction": "out" if c1 > c0 else "in"})
    return events


def drain_summary(samples, empty_at_or_below=0):
    """samples: (t, depth). Returns peak depth, time of peak, first time at/below threshold after the peak, drain seconds."""
    ordered = sorted(samples, key=lambda s: s[0])
    if not ordered:
        return {"peak": None, "t_peak": None, "t_drained": None, "drain_seconds": None}
    peak_i = max(range(len(ordered)), key=lambda i: (ordered[i][1], -i))
    peak_t, peak = ordered[peak_i]
    t_drained = None
    for t, d in ordered[peak_i + 1:]:
        if d <= empty_at_or_below:
            t_drained = t
            break
    return {"peak": peak, "t_peak": peak_t, "t_drained": t_drained,
            "drain_seconds": (t_drained - peak_t) if t_drained is not None else None}


def mean(values):
    return (sum(values) / len(values)) if values else None

"""Shared helpers for the analysis scripts (Python standard library; matplotlib optional).

Behavior every script shares:
  * outputs go to --out-dir (default experiments/analysis/output/); file names get the SYNTHETIC- prefix when any input is synthetic;
  * every output table has a `synthetic` column and every plot title carries SYNTHETIC when applicable;
  * outputs are write-once; --force replaces a DERIVED output (raw inputs are never touched);
  * every output (table or plot) gets a sidecar <output>.provenance.json (script, argv, input paths with sha256, benchmark sha256, git HEAD and
    dirty flag, UTC time, synthetic flag). A script must call begin() once after parsing its arguments; writing without it raises;
  * with SC_RESULTS_ROOT set, the default --out-dir is <SC_RESULTS_ROOT>-derived/analysis (see experiments/README.md);
  * plots need matplotlib: when it cannot be imported the plot is skipped, the CSV is still written, and the script says so.
"""
import csv
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(HERE, "..", "common"))
import sc_common  # noqa: E402

DEFAULT_OUT = sc_common.derived_root("analysis") or os.path.join(HERE, "output")

_CTX = {}


def begin(script, argv, inputs, benchmark=None, extra=None):
    """Declare the provenance of every output this process writes: the script file, its argv, the input files, the benchmark file."""
    _CTX.clear()
    _CTX.update(script=script, argv=list(argv), inputs=[p for p in inputs if p], benchmark=benchmark, extra=extra)


def _provenance(path, synthetic, force):
    if not _CTX:
        raise RuntimeError("an_common.begin() was not called: refusing to write an output without provenance")
    return sc_common.write_provenance(path, _CTX["script"], _CTX["argv"], _CTX["inputs"], synthetic, _CTX["benchmark"], _CTX["extra"], force)


def _check_free(path, force):
    sc_common.check_free(path, force)


def read_csv_rows(path):
    with open(path, "r", encoding="utf-8", newline="") as f:
        return list(csv.DictReader(f))


def input_is_synthetic(path, rows=None):
    if sc_common.is_synthetic_name(path):
        return True
    if rows and any(str(r.get("synthetic", "")).lower() == "true" for r in rows[:5]):
        return True
    if path.endswith(".json") or path.endswith(".jsonl"):
        try:
            with open(path, "r", encoding="utf-8") as f:
                head = f.readline() if path.endswith(".jsonl") else f.read(4096)
            return '"synthetic": true' in head or '"SYNTHETIC": true' in head or '"synthetic":true' in head
        except OSError:
            return False
    return False


def write_table(out_dir, name, header, rows, synthetic, force=False):
    """CSV with a trailing synthetic column. Returns the path."""
    path = os.path.join(out_dir, sc_common.out_name(name, synthetic))
    _check_free(path, force)
    with sc_common.open_output(path, force=force, newline="") as f:
        w = csv.writer(f, lineterminator="\n")
        w.writerow(list(header) + ["synthetic"])
        for r in rows:
            w.writerow(list(r) + ["true" if synthetic else "false"])
    _provenance(path, synthetic, force)
    print("SC-TABLE %s" % path)
    return path


def plot_lines(out_dir, name, title, xlabel, ylabel, series, synthetic, force=False, step=False):
    """series: {label: ([x...], [y...])}. Returns the path or None when matplotlib is unavailable."""
    try:
        import matplotlib
        matplotlib.use("Agg")
        import matplotlib.pyplot as plt
    except Exception as e:  # ImportError or a broken backend: fall back to CSV only
        print("SC-INFO plot skipped (matplotlib unavailable: %s); the CSV holds the same data" % type(e).__name__)
        return None
    path = os.path.join(out_dir, sc_common.out_name(name, synthetic))
    _check_free(path, force)
    if force and os.path.exists(path):
        os.remove(path)
    os.makedirs(out_dir, exist_ok=True)
    fig, ax = plt.subplots(figsize=(8, 4.5))
    for label, (xs, ys) in series.items():
        (ax.step if step else ax.plot)(xs, ys, label=label, **({"where": "post"} if step else {"marker": "o"}))
    ax.set_title(("SYNTHETIC DATA, NOT A RESULT: " if synthetic else "") + title)
    ax.set_xlabel(xlabel)
    ax.set_ylabel(ylabel)
    ax.grid(True, alpha=0.3)
    ax.legend(fontsize=8)
    if synthetic:
        fig.text(0.5, 0.5, "SYNTHETIC", fontsize=60, color="red", alpha=0.12, ha="center", va="center", rotation=25)
    fig.tight_layout()
    fig.savefig(path, dpi=110)
    plt.close(fig)
    _provenance(path, synthetic, force)
    print("SC-PLOT %s" % path)
    return path


def fmt(v, nd=3):
    if v is None:
        return ""
    if isinstance(v, float):
        return ("%." + str(nd) + "f") % v
    return str(v)


def common_args(ap):
    ap.add_argument("--out-dir", default=DEFAULT_OUT)
    ap.add_argument("--force", action="store_true", help="replace an existing DERIVED output (never raw inputs)")
    ap.add_argument("--no-plot", action="store_true")
    return ap

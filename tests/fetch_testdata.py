#!/usr/bin/env python3
"""Download the inputs of the datasets in tests/datasets.yml into a local directory.

The dataset-driven nf-tests read inputs from RUSTQC_TESTDATA_DIR when a copy exists there
(tests/lib/datasets/Datasets.groovy) and fall back to the URL otherwise. CI runs this once per
test set and caches the directory (see .github/workflows/nf-test.yml); it is also handy for
offline or repeated local runs:

    python3 tests/fetch_testdata.py --out ~/.cache/rustqc-testdata --sets all
    RUSTQC_TESTDATA_DIR=~/.cache/rustqc-testdata RUSTQC_DATASET_SETS=all nf-test test --tag large

A URL is stored at <out>/<URL without its scheme>, the layout Datasets.cachedCopy() expects.
Files already present with the expected md5 are not downloaded again.

Needs PyYAML.
"""

import argparse
import hashlib
import json
import os
import re
import sys
import time
import urllib.request
from pathlib import Path

import yaml

MANIFEST = Path(__file__).resolve().parent / "datasets.yml"
SETS = ("small", "large", "fullsize")
ALL_SETS = ("small", "large")  # what `--sets all` means; the fullsize set is only fetched by name


def md5sum(path):
    h = hashlib.md5()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def cache_path(url):
    """Same mapping as Datasets.cachePath() in tests/lib/datasets/Datasets.groovy."""
    m = re.match(r"^[a-zA-Z][a-zA-Z0-9+.-]*://([^?#]+)", url)
    if not m:
        raise ValueError(f"Not a URL: {url}")
    path = m.group(1)
    if ".." in path.split("/"):
        raise ValueError(f"Refusing a URL with '..' segments: {url}")
    return path


def expand(template, sources):
    def sub(m):
        key = m.group(1)
        if key not in sources:
            raise KeyError("unknown source '{" + key + "}' in " + template)
        return sources[key]

    return re.sub(r"\{(\w+)\}", sub, template)


def download(url, target, retries=3):
    tmp = target.with_name(target.name + ".part")
    for attempt in range(1, retries + 1):
        try:
            with urllib.request.urlopen(url, timeout=120) as r, open(tmp, "wb") as f:
                while True:
                    chunk = r.read(1 << 20)
                    if not chunk:
                        break
                    f.write(chunk)
            os.replace(tmp, target)
            return
        except OSError as e:
            if attempt == retries:
                raise
            print(f"  retry {attempt}/{retries - 1} after error: {e}", file=sys.stderr)
            time.sleep(5 * attempt)


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--out", help="directory to download into (required unless --fingerprint or --params-json)")
    p.add_argument("--sets", default="small", help="comma-separated test sets (small, large, fullsize) or 'all' (small + large) [small]")
    p.add_argument("--datasets", default="", help="comma-separated dataset ids (default: every dataset of --sets)")
    p.add_argument("--inputs", default="", help="comma-separated input names (bam, fastq_1, gtf, ...) to handle (default: all)")
    p.add_argument(
        "--fingerprint",
        action="store_true",
        help="print a hash of the (selected) inputs of the selected datasets, URLs and md5s, and exit: "
        "the cache key of data derived from them (the full-size BAM and STAR index), which so changes "
        "only when those inputs do, not with the rest of the manifest",
    )
    p.add_argument(
        "--params-json",
        metavar="DATASET",
        help="print the parameters of the full-size run (`nextflow run . --fullsize`) for this dataset as JSON, "
        "and exit: sample_id, dataset, fastq_1, fastq_2, fasta and gtf. Inputs are their URLs, except that the "
        "public nf-core/oncoanalyser bucket is given as s3://, which Nextflow stages without going through the head job",
    )
    args = p.parse_args()
    if not (args.fingerprint or args.params_json) and not args.out:
        p.error("--out is required")

    manifest = yaml.safe_load(MANIFEST.read_text())
    sources = manifest.get("sources") or {}
    sets = set(ALL_SETS) if args.sets == "all" else {s.strip() for s in args.sets.split(",") if s.strip()}
    unknown = sets - set(SETS)
    if unknown:
        p.error(f"unknown sets {sorted(unknown)}; known: {', '.join(SETS)} or all")
    wanted = {d.strip() for d in args.datasets.split(",") if d.strip()}
    inputs_wanted = {i.strip() for i in args.inputs.split(",") if i.strip()}

    if args.params_json:
        ds = next((d for d in manifest.get("datasets") or [] if d["id"] == args.params_json), None)
        if ds is None:
            p.error(f"unknown dataset {args.params_json}")
        params = {"dataset": ds["id"], "sample_id": ds.get("sample", ds["id"])}
        for name in ("fastq_1", "fastq_2", "fasta", "gtf"):
            spec = (ds.get("inputs") or {}).get(name)
            spec = spec if isinstance(spec, dict) else {"url": spec}
            if not spec.get("url"):
                p.error(f"dataset {ds['id']} has no downloadable input {name}")
            url = expand(spec["url"], sources)
            params[name] = re.sub(r"^https://([a-z0-9.-]+)\.s3\.amazonaws\.com/", r"s3://\1/", url)
        print(json.dumps(params, indent=2))
        return

    if args.fingerprint:
        selected = []
        for ds in manifest.get("datasets") or []:
            if ds.get("set", "small") not in sets or (wanted and ds["id"] not in wanted):
                continue
            inputs = {}
            for name, spec in (ds.get("inputs") or {}).items():
                if inputs_wanted and name not in inputs_wanted:
                    continue
                spec = spec if isinstance(spec, dict) else {"url": spec}
                inputs[name] = {**spec, **({"url": expand(spec["url"], sources)} if spec.get("url") else {})}
            selected.append({"id": ds["id"], "inputs": inputs})
        print(hashlib.sha256(json.dumps(selected, sort_keys=True).encode()).hexdigest())
        return

    out = Path(args.out).expanduser().resolve()
    failures = []
    count = size = 0
    for ds in manifest.get("datasets") or []:
        if ds.get("set", "small") not in sets or (wanted and ds["id"] not in wanted):
            continue
        for name, spec in (ds.get("inputs") or {}).items():
            if inputs_wanted and name not in inputs_wanted:
                continue
            spec = spec if isinstance(spec, dict) else {"url": spec}
            if not spec.get("url"):
                continue  # in-repo only
            url = expand(spec["url"], sources)
            target = out / cache_path(url)
            md5 = spec.get("md5")
            if target.is_file() and (not md5 or md5sum(target) == md5):
                state = "cached"
            else:
                target.parent.mkdir(parents=True, exist_ok=True)
                download(url, target)
                state = "downloaded"
            actual = md5sum(target)
            if md5 and actual != md5:
                failures.append(f"{ds['id']} {name}: md5 {actual}, expected {md5} ({url})")
                target.unlink()
                state = "md5 MISMATCH"
            count += 1
            size += target.stat().st_size if target.is_file() else 0
            print(f"{state:>12}  {ds['id']} {name}  {url}")

    print(f"{count} input(s), {size / 1e6:.1f} MB in {out}")
    if failures:
        print("\n".join(["md5 check failed:"] + failures), file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    main()

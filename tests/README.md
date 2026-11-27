# RustQC Benchmarks — Testing Guide

This repository uses [nf-test](https://www.nf-test.com/) to validate RustQC outputs. Tests serve two distinct purposes:

1. **Regression testing** — detect unintended changes between RustQC versions
2. **Cross-tool validation** — confirm RustQC outputs match upstream tools (RSeQC, dupRadar, featureCounts, etc.)

## Quick Start

```bash
# Install nf-test (if not already installed)
curl -fsSL https://code.askimed.com/install/nf-test | bash

# Run all tests
nf-test test

# Run only regression tests (fast — no upstream tools needed)
nf-test test --tag regression

# Run only cross-tool validation tests
nf-test test --tag crosscheck

# Run only upstream tool tests
nf-test test --tag upstream

# Run tests for a specific tool
nf-test test --tag bam_stat

# Run a single test file
nf-test test tests/rna/rustqc/bam_stat.nf.test
```

The dataset matrix, the release regression, test sets and the test-data cache are described in [DATASETS.md](DATASETS.md).

## Test Architecture

### Directory Structure

```
tests/
├── README.md                  # This file
├── nextflow.config            # Shared Nextflow config for tests
├── lib/
│   └── CompareUtils.groovy    # Shared comparison utilities
├── rna/
│   ├── pipeline.nf.test       # Pipeline smoke tests
│   ├── rustqc/                # RustQC process tests (regression + crosscheck)
│   │   ├── bam_stat.nf.test
│   │   ├── bam_stat.nf.test.snap
│   │   ├── dupradar.nf.test
│   │   ├── ...
│   │   └── read_distribution.nf.test
│   └── upstream/              # Upstream tool tests (regression only)
│       ├── bam_stat.nf.test
│       ├── bam_stat.nf.test.snap
│       └── ...
├── default.nf.test            # Full pipeline snapshot test
snapshots/
├── rna/small/
│   ├── rseqc/                 # Upstream RSeQC reference outputs
│   ├── dupradar/              # Upstream dupRadar reference outputs
│   ├── featurecounts/         # Upstream featureCounts reference outputs
│   └── rustqc/                # RustQC reference outputs (for quick manual inspection)
```

### Test Types

#### Regression Tests (`--tag regression`)

Each RustQC tool has a regression test that snapshots the current output. When RustQC is updated, any change in output will cause a test failure showing a human-readable diff of exactly what changed.

- **What they compare:** Current RustQC output vs. the stored `.nf.test.snap` file
- **When they fail:** Any change in RustQC output (values, formatting, line count)
- **How to fix:** If the change is intentional, update snapshots (see [Updating Snapshots](#updating-snapshots))

Snapshots store the full file content (not just hashes), so failures show line-by-line diffs.

#### Cross-check Tests (`--tag crosscheck`)

Each RustQC tool has a cross-check test that compares against committed upstream reference files in `snapshots/rna/small/`.

- **What they compare:** Current RustQC output vs. upstream tool reference files
- **When they fail:** RustQC output diverges from upstream tools
- **How to fix:** Either fix the RustQC implementation, or update the upstream reference files if the upstream tools have changed

#### Upstream Tests (`--tag upstream`)

Each upstream tool (RSeQC, dupRadar, featureCounts) has its own regression test. These snapshot the upstream tool output so we can detect if the upstream tools themselves change.

- **What they compare:** Current upstream tool output vs. stored `.nf.test.snap`
- **When they fail:** The upstream tool produced different output (e.g., new version)
- **How to fix:** Update both the snapshots and the reference files in `snapshots/rna/small/`

Upstream tests must run each tool exactly as the reference files in `snapshots/rna/small/` were generated, i.e. as the
pipeline runs it with `-profile test`: reverse-stranded paired-end input (`strandedness: 'reverse'`), and a BED derived
from the GTF with `GTF2BED` where the BED's CDS (thickStart/thickEnd) matters (read_distribution). The reference file
headers often record the command (e.g. `featureCounts ... -s 2`).

#### Reference platform

The TIN reference (`snapshots/rna/small/rseqc/tin/`) must be made, and the upstream TIN test run, on an
x86-64 CPU with FMA and AVX2 (Intel Haswell / AMD Zen or newer) or on aarch64. `tin.py` takes `log()` and
`exp()` from the C library, and glibc picks its FMA build of them on those CPUs. The generic build it uses
on older x86-64 CPUs rounds differently in the last bit for about 1 in 10,000 inputs, which changes the
last digit of roughly one TIN in a thousand. RustQC reproduces the FMA build's results on every platform,
so the cross-check compares md5 sums. Do not set `GLIBC_TUNABLES` when regenerating the reference; on x86-64,
check that `grep -o -w -e fma -e avx2 /proc/cpuinfo` lists both flags.

### Tag System

Tests are tagged for selective execution:

| Tag                          | Description                                | Tests matched                    |
| ---------------------------- | ------------------------------------------ | -------------------------------- |
| `regression`                 | RustQC self-regression tests               | 15 tests                         |
| `crosscheck`                 | RustQC vs. upstream comparison             | 16 tests                         |
| `upstream`                   | Upstream tool regression tests             | 17 tests (16 + 1 pipeline smoke) |
| `rustqc`                     | All RustQC tests (regression + crosscheck) | 31 tests                         |
| `rna`                        | All RNA-related tests                      | All except `default.nf.test`     |
| `small`                      | Tests using the small dataset              | All except `default.nf.test`     |
| `bam_stat`, `dupradar`, etc. | Tests for a specific tool                  | 3 tests (`featurecounts`: 5)     |
| `pipeline`                   | Pipeline smoke and snapshot tests          | 4 tests                          |

Tags can be combined: `nf-test test --tag regression --tag bam_stat` runs only the bam_stat regression test.

## Understanding Test Output

### Passing Tests

```
Test RUSTQC bam_stat output > bam_stat - regression  PASSED (12s)
Test RUSTQC bam_stat output > bam_stat - crosscheck vs upstream  PASSED (12s)
```

### Snapshot Failures (Regression)

When a regression test fails, nf-test shows a side-by-side diff:

```
Test RUSTQC bam_stat output > bam_stat - regression  FAILED

Snapshot mismatch for 'bam_stat_regression':

  Expected:
    "Total records:                          52839"
  Actual:
    "Total records:                          52840"
```

This tells you exactly which line changed and how. Decide whether the change is expected:

- **Expected change** (e.g., bug fix, new feature): Update the snapshot
- **Unexpected change** (regression): Fix the RustQC code

### Cross-check Failures

When a cross-check test fails, the error message depends on the comparison method:

**Text mismatch (CompareUtils.textMatch):**

```
Line 5 mismatch:
  Actual:   "Unmapped reads      500"
  Expected: "Unmapped reads      499"
```

**TSV mismatch (CompareUtils.tsvMatch):**

```
Row 42, Col 3: value 0.12345679 differs from expected 0.12345678
  (abs diff: 1e-08, tolerance: 1e-08)
```

## Comparison Methods & Tolerances

Each crosscheck compares RustQC with the upstream tool exactly where RustQC reproduces the upstream bytes. The
allowances left are for what RustQC does not reproduce (the dupRadar logistic fit's last digits, junction_saturation's
random subsample points; see [Tolerance rationale](#tolerance-rationale)) and for an order that upstream takes from a
hash table (Qualimap's tied junction motifs).

| Tool                       | Regression method              | Crosscheck method                                                                 | Measured basis / notes                                                                                 |
| -------------------------- | ------------------------------ | --------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------ |
| **bam_stat**               | `snapshot(filteredLines)`      | **md5**                                                                           | Byte-identical                                                                                         |
| **infer_experiment**       | `snapshot(filteredLines)`      | **md5**                                                                           | Byte-identical, including the "This is PairEnd Data" line                                              |
| **flagstat**               | `snapshot(lines)`              | **md5**                                                                           | Byte-identical                                                                                         |
| **idxstats**               | `snapshot(lines)`              | **md5**                                                                           | Byte-identical                                                                                         |
| **samtools stats**         | `snapshot(non-# lines)`        | `exactMatch`, skipping `#` lines                                                  | All data lines identical, GC-depth (GCD) section included; only comment lines differ                   |
| **read_duplication**       | `snapshot({pos, seq})`         | **md5** (both files)                                                              | Byte-identical                                                                                         |
| **read_distribution**      | `snapshot(lines)`              | **md5**                                                                           | Byte-identical, trailing spaces of the table rows included                                             |
| **inner_distance**         | `snapshot({distance, freq})`   | **md5** (both files)                                                              | Byte-identical, row order included                                                                     |
| **junction_annotation**    | `snapshot({bed sorted, xls})`  | **md5** (BED, XLS) + `textMatch` (Interact BED, log)                              | BED/XLS byte-identical; Interact BED and log identical except lines naming the input BAM / annotation  |
| **junction_saturation**    | `snapshot(lines)`              | Exact: `x` array, 100% points, `z == y + w`, all other lines                      | Intermediate subsample points use different random streams (RSeQC's are unseeded), so are not compared |
| **dupradar**               | `snapshot({matrix, slope})`    | **md5** (matrix) + intercept/slope at 1e-8 relative (1e-9 absolute if degenerate) | Matrix byte-identical; fit agrees to ~2.6e-9 relative (RustQC's own logistic fit vs R's `glm()`)       |
| **featurecounts**          | `snapshot({counts, summary})`  | Exact biotype counts + **md5** (biotype summary)                                  | All 14 biotypes identical; biotype-level summary byte-identical to `-g gene_biotype`                   |
| **featurecounts per-gene** | (shared with featurecounts)    | `exactMatch` (all but `# Program:`), **md5** (summary)                            | Identical for all 2,905 genes, per-exon Chr/Start/End/Strand lists in Subread's order included         |
| **preseq**                 | `snapshot(lines)`              | **md5**                                                                           | Byte-identical on Linux (same C++ RNG, seed 408). Differs on macOS (libc++), so valid on Linux CI only |
| **qualimap**               | `snapshot({total, high, low})` | **md5** (3 coverage profiles) + `exactMatch` (`rnaseq_qc_results.txt`)            | Identical, 5'-3' bias included, except the input names; tied junction motifs compared as sets          |
| **tin**                    | `snapshot({summary, xls})`     | **md5** (tin.xls, summary)                                                        | Byte-identical on FMA-capable x86-64 and aarch64 (see "Reference platform")                            |

### Tolerance rationale

Comparisons fall into three categories:

1. **Byte-identical** (md5): `bam_stat`, `infer_experiment`, `flagstat`, `idxstats`, `read_duplication`,
   `read_distribution`, `inner_distance`, `junction_annotation` (BED/XLS), `dupradar` (matrix),
   `featurecounts` (summaries), `qualimap` (coverage profiles), `preseq` (Linux only), `tin`
   (Linux, FMA CPU), `split_bam`.
2. **Exact after excluding non-data lines** (`ExactCompare.exactMatch`, no trimming): `samtools stats`
   (`#` comments), `featurecounts` per-gene table (`# Program:` line), `qualimap` results (input names;
   tied junction motifs as sets), `junction_annotation` (Interact BED header, log annotation line),
   `featurecounts` biotype counts.
3. **Values RustQC does not reproduce to the last digit** (compared at a tolerance far below any real
   difference, with everything else exact):
   - `dupradar` intercept/slope: R fits the logistic curve with `glm()` (iteratively reweighted least squares
     through LINPACK/BLAS), RustQC with its own solver. They agree to ~2.6e-9 relative on the small dataset,
     so the values are compared at 1e-8 relative. When no read is duplicate-marked, the fit is degenerate
     (all duplication rates are 0, so the true intercept and slope are 0) and both tools return a
     round-off residue of different size (about 1e-10 vs 3e-12); a relative comparison of two numbers
     that are zero in exact arithmetic is meaningless, so those are compared at 1e-9 absolute.
   - `junction_saturation`: RSeQC shuffles the splice reads without a seed, so its intermediate sampling
     points change from run to run; only what is deterministic is compared.

**Reference files are upstream bytes.** `snapshots/` is excluded from the whitespace pre-commit hooks
and from Prettier, so committed references keep the upstream tools' trailing spaces and final newlines.
Regenerate a reference with its upstream tool (or the `refgen` CI mode), never by editing it.

## Updating Snapshots

### After an intentional RustQC change

```bash
# Update all regression snapshots
nf-test test --tag regression --update-snapshot

# Update snapshots for a specific tool
nf-test test --tag regression --tag bam_stat --update-snapshot
```

Review the diff in git to confirm the changes are expected:

```bash
git diff tests/rna/rustqc/*.nf.test.snap
```

### After an upstream tool update

1. Run the upstream tests to regenerate their snapshots:

   ```bash
   nf-test test --tag upstream --update-snapshot
   ```

2. Copy the new upstream outputs to the reference directory:

   ```bash
   # The exact paths depend on which tool changed.
   # Check the nf-test work directory for the new outputs.
   cp .nf-test/tests/.../rseqc/bam_stat/bam_stat.txt snapshots/rna/small/rseqc/bam_stat/
   ```

3. Run cross-check tests to verify RustQC still matches:
   ```bash
   nf-test test --tag crosscheck
   ```

### Cleaning up obsolete snapshots

If you rename or remove tests, old snapshot entries remain in `.nf.test.snap` files. Clean them up:

```bash
nf-test test --clean-snapshot
```

## split_bam and featureCounts `-M` / `-O` tests

These tests are kept in their own section and tags:

| Tag                | Tests                                           | Tests matched |
| ------------------ | ----------------------------------------------- | ------------- |
| `split_bam`        | RSeQC `split_bam.py` (rRNA and synthetic BED12) | 5 tests       |
| `featurecounts_mo` | featureCounts `-M`, `-O` and `-M -O`            | 12 tests      |

| Tool                 | Regression method             | Crosscheck method          | Crosscheck tolerance / notes                                       |
| -------------------- | ----------------------------- | -------------------------- | ------------------------------------------------------------------ |
| **split_bam**        | `snapshot(lines)`             | **md5 hash** + `textMatch` | Byte-identical to `split_bam.py` stdout (rRNA and synthetic BED12) |
| **featurecounts_mo** | `snapshot({counts, summary})` | `exactMatch` + biotype map | Per-gene tables (exon lists included) and summaries exact          |

`tests/rna/rustqc/split_bam.nf.test` and `featurecounts_mo.nf.test` load
`tests/rna/rustqc/rustqc_extra_args.config`, which appends `params.rustqc_extra_args` (set in each
test's `params` block) to the `RUSTQC_RNA` `ext.args` from `conf/modules.config`. The split_bam
BED is staged next to the index in the input tuple's `bai` slot, so `--split-bam-bed` can name it
by file name.

split_bam and featureCounts `-M` / `-O` come from RustQC PR #191. Until it is merged and
`ghcr.io/seqeralabs/rustqc:dev` is rebuilt, the RustQC tests for them fail on the default image.
Run them against an image built from the PR with:

```bash
RUSTQC_IMAGE=<registry>/rustqc:<tag> nf-test test --tag split_bam
RUSTQC_IMAGE=<registry>/rustqc:<tag> nf-test test --tag featurecounts_mo
```

The upstream references and how they were made are described in
`snapshots/rna/small/rseqc/split_bam/README.md` and `snapshots/rna/small/featurecounts_mo/README.md`.

## CI / GitHub Actions

Tests run automatically on pull requests via `.github/workflows/nf-test.yml`.

### Automatic runs (on PR)

All tests run across a matrix of profiles (docker, conda, singularity) and Nextflow versions.

### Manual runs (workflow_dispatch)

From the GitHub Actions tab, you can trigger a manual run with a specific test mode:

| Mode              | What runs               | Use case                                    |
| ----------------- | ----------------------- | ------------------------------------------- |
| **all** (default) | Every test              | Full validation                             |
| **regression**    | Only `--tag regression` | Quick check that RustQC hasn't regressed    |
| **crosscheck**    | Only `--tag crosscheck` | Validate RustQC matches upstream tools      |
| **upstream**      | Only `--tag upstream`   | Re-run upstream tools to check their output |

### Understanding CI failures

1. Check the failing test name — it tells you the type:
   - `bam_stat - regression` → RustQC output changed
   - `bam_stat - crosscheck vs upstream` → RustQC diverged from upstream
   - Upstream test → upstream tool itself changed

2. Read the assertion diff in the CI log

3. If the change is intentional:
   ```bash
   nf-test test --update-snapshot
   git add tests/rna/rustqc/*.nf.test.snap
   git commit -m "Update nf-test snapshots after <change description>"
   ```

## Shared Utilities

### CompareUtils (`tests/lib/CompareUtils.groovy`)

Automatically loaded by nf-test via the `libDir` config. Provides:

- **`CompareUtils.textMatch(actualLines, expectedLines, ignorePrefixes=[])`**
  Line-by-line text comparison. Skips blank lines and lines matching any prefix in `ignorePrefixes`. Fails with a clear message showing the first mismatched line.

- **`CompareUtils.tsvMatch(actualLines, expectedLines, options)`**
  Column-aware TSV comparison with numeric tolerance. Options:
  - `tolerance` — absolute difference allowed (default: 0.0)
  - `relTolerance` — relative difference allowed (optional). Pass if EITHER tolerance is satisfied.
  - `skipPrefixes` — line prefixes to ignore (e.g., `['#']` for comments)
  - `skipColumns` — column indices to skip (`Set<Integer>`)
  - `delimiter` — field separator (default: `\t`)
  - `maxErrors` — stop after N mismatches (default: 10)

  Special handling:
  - `NA`/`NaN` values are treated as `0` for numeric comparison (common in R output)
  - Exact numeric matches (e.g., `0.0 == 0.0`) always pass regardless of tolerance settings

- **`CompareUtils.fileMinSize(file, minBytes)`**
  Assert a file exists and is at least `minBytes` in size.

- **`CompareUtils.extractLines(file, from, to)`**
  Extract a range of lines from a file.

### ExactCompare (`tests/lib/ExactCompare.groovy`)

- **`ExactCompare.exactMatch(actualLines, expectedLines, skipPrefixes=[])`**
  Line-by-line comparison with nothing trimmed and blank lines kept; only lines starting with one of
  `skipPrefixes` (version / command-line headers) are dropped from both sides.

- **`ExactCompare.qualimapMotifTiesAsSets(file, lines)`**
  Qualimap junction-motif rows with each run of tied percentages sorted by name (and masked when the
  top-11 list cuts through it), for comparing against Qualimap's hash-table order.

### nf-test Plugins

Configured in `nf-test.config`:

- **[nft-utils](https://nf-co.re/nft-utils)** (`0.0.9`) — nf-core test utilities for sanitizing output, listing files, etc.
- **[nft-csv](https://github.com/lukfor/nft-csv)** (`0.1.0`) — CSV/TSV parsing and comparison with `assertTableEquals(actual, expected, precision)`. Available for future use where column-aware comparison is needed.

## Adding Tests for New Tools

When RustQC adds support for a new tool:

1. **Add upstream reference files** to `snapshots/rna/small/<tool>/`

2. **Create an upstream test** at `tests/rna/upstream/<tool>.nf.test`:
   - Run the upstream tool's nf-core module
   - Snapshot the output for regression

3. **Create a RustQC test** at `tests/rna/rustqc/<tool>.nf.test` with two test blocks:
   - **Regression test** (tag: `regression`) — snapshot the RustQC output
   - **Crosscheck test** (tag: `crosscheck`) — compare against `snapshots/rna/small/<tool>/`

4. **Choose the right comparison method** (from tightest to most relaxed):
   - **md5 hash** → Use when outputs are byte-identical (e.g., `assert path(file).md5 == path(ref).md5`)
   - **Exact text** → `CompareUtils.textMatch()` — for text output with deterministic content
   - **Exact TSV** → `CompareUtils.tsvMatch()` with `tolerance: 0.0` — for tabular data with sorting
   - **Toleranced TSV** → `CompareUtils.tsvMatch()` with `tolerance` / `relTolerance` — for numeric differences
   - **Structural** → Custom assertions on row labels, column counts, value ranges — for algorithmically different outputs
   - **Binary/plot** → `CompareUtils.fileMinSize()` — verify file exists and is non-trivial

5. **Set the tolerance** based on empirical testing. Start with exact comparison and relax only where needed:
   - Run crosscheck first with `tolerance: 0.0`
   - If it fails, analyze the actual differences (sort issues? float precision? algorithmic?)
   - Choose the tightest tolerance that reliably passes
   - Document the rationale in the test comment

6. **Generate initial snapshots:**

   ```bash
   nf-test test tests/rna/rustqc/<tool>.nf.test --update-snapshot
   ```

7. **Verify cross-check passes:**
   ```bash
   nf-test test tests/rna/rustqc/<tool>.nf.test --tag crosscheck
   ```

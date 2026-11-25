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

### bigWig tests (requires RustQC #114)

The bigWig coverage-track tests (`--tag bigwig`) depend on [RustQC #114](https://github.com/seqeralabs/RustQC/pull/114),
which adds nf-core/rnaseq-compatible bigWig output (`bigwig/{sample}.bigWig`,
`{sample}.forward.bigWig`, `{sample}.reverse.bigWig`) to `rustqc rna`.

Until `ghcr.io/seqeralabs/rustqc:dev` is rebuilt after #114 merges, point the
tests at a RustQC container that already includes bigWig support via the
`RUSTQC_IMAGE` environment variable:

```bash
RUSTQC_IMAGE=ghcr.io/seqeralabs/rustqc:<tag> nf-test test --tag bigwig
```

If the bigWig files are missing, the tests fail with a clear message
(_"does the RustQC image include bigWig support?"_).

Binary `.bigWig` files are **not** bit-identical to UCSC output, so the tests
never compare them directly — they decode each track to bedGraph intervals
(via host `bigWigToBedGraph`, falling back to a Docker container) and compare
those against committed `bedtools genomecov` + `bedClip` references. The decode
runs in the nf-test `then` block, never as docker-in-docker inside a Nextflow
process.

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

### Tag System

Tests are tagged for selective execution:

| Tag                          | Description                                | Tests matched |
| ---------------------------- | ------------------------------------------ | ------------- |
| `regression`                 | RustQC self-regression tests               | 9 tests       |
| `crosscheck`                 | RustQC vs. upstream comparison             | 9 tests       |
| `upstream`                   | Upstream tool regression tests             | 9 tests       |
| `rustqc`                     | All RustQC tests (regression + crosscheck) | 18 tests      |
| `rna`                        | All RNA-related tests                      | All           |
| `small`                      | Tests using the small dataset              | All           |
| `bam_stat`, `dupradar`, etc. | Tests for a specific tool                  | 2-3 tests     |
| `pipeline`                   | Pipeline smoke tests                       | 3 tests       |

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

Each tool uses comparison methods matched to its output characteristics:

| Tool                    | Regression method             | Crosscheck method                            | Crosscheck tolerance / notes                                                                                       |
| ----------------------- | ----------------------------- | -------------------------------------------- | ------------------------------------------------------------------------------------------------------------------ |
| **bam_stat**            | `snapshot(filteredLines)`     | `textMatch` (skip headers)                   | Exact (after filtering `Load BAM` / `processing` prefixes)                                                         |
| **infer_experiment**    | `snapshot(filteredLines)`     | `textMatch` (skip "This is")                 | Exact (after filtering header line)                                                                                |
| **read_distribution**   | `snapshot(lines)`             | Structural: row labels + column count        | Algorithmic differences too large for numeric comparison (UTR 3.5x)                                                |
| **read_duplication**    | `snapshot({pos, seq})`        | **md5 hash** (byte-identical)                | Gold standard — files are identical between RustQC and upstream                                                    |
| **dupradar**            | `snapshot({matrix, slope})`   | `tsvMatch` (count cols only) + slope parsing | Abs: 10, Rel: 2% for counts; skip rate/RPKM cols; slope within 10%                                                 |
| **featurecounts**       | `snapshot({counts, summary})` | Biotype-to-biotype comparison + summary      | 20% per-biotype tolerance; ambiguity resolution differs (~6% total)                                                |
| **inner_distance**      | `snapshot({distance, freq})`  | Read ID set match + `tsvMatch` (freq)        | Read IDs must match; freq histogram: 15% relative tolerance                                                        |
| **junction_annotation** | `snapshot({bed sorted, xls})` | `tsvMatch` (sorted BED + sorted XLS)         | Exact after sorting (identical content, different iteration order)                                                 |
| **junction_saturation** | `snapshot(lines)`             | 100% sample values + R script structure      | Deterministic values only; stochastic intermediate points skipped                                                  |
| **bigwig**              | `snapshot({lines, md5})`      | `bedGraphMatch` (decoded intervals)          | Exact — decode bigWig to bedGraph; intervals match bedtools + bedClip (binary bigWig is not bit-identical to UCSC) |

### Tolerance rationale

Tolerances were determined empirically by comparing actual RustQC outputs against upstream tools on the small test dataset. The tools fall into three categories:

1. **Byte-identical** — `read_duplication`, `junction_annotation` (after sort), `bigwig` (after decoding to bedGraph): Use exact comparison or md5
2. **Near-identical** — `bam_stat`, `infer_experiment`, `junction_saturation` (100% values): Use exact comparison (after filtering non-deterministic headers/paths)
3. **Algorithmically different** — `dupradar` (multi-mapper handling), `featurecounts` (ambiguity resolution differs ~6% due to `-g gene_biotype` vs per-gene aggregation), `inner_distance` (~2% of reads classified differently), `read_distribution` (gene model resolution): Use tolerance-based or structural comparison

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

### nf-test Plugins

Configured in `nf-test.config`:

- **[nft-utils](https://nf-co.re/nft-utils)** (`0.0.9`) — nf-core test utilities for sanitizing output, listing files, etc.
- **[nft-csv](https://github.com/lukfor/nft-csv)** (`0.1.0`) — CSV/TSV parsing and comparison with `assertTableEquals(actual, expected, precision)`. Available for future use where column-aware comparison is needed.

## Adding Tests for New Tools

When RustQC adds support for a new tool (e.g., samtools, qualimap, preseq):

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

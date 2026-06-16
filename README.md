# RustQC Benchmarks

[![nf-test](https://img.shields.io/badge/unit_tests-nf--test-337ab7.svg)](https://www.nf-test.com)
[![Nextflow](https://img.shields.io/badge/version-%E2%89%A525.04.0-green?style=flat&logo=nextflow&logoColor=white&color=%230DC09D)](https://www.nextflow.io/)

Validation suite for [RustQC](https://github.com/seqeralabs/RustQC) -- comparing its outputs against the upstream bioinformatics tools it reimplements.

## What this repo does

RustQC reimplements common RNA-seq QC tools in Rust. This repository:

1. **Generates reference outputs** from the original upstream tools (RSeQC, dupRadar, featureCounts, Qualimap, preseq, samtools)
2. **Runs RustQC** on the same input data
3. **Compares outputs** between RustQC and upstream tools, with per-tool tolerance rules
4. **Tracks regressions** via nf-test snapshots -- if RustQC output changes, the snapshot test fails

All upstream tools are run via standard [nf-core modules](https://nf-co.re/modules), so reference outputs match what users get from [nf-core/rnaseq](https://nf-co.re/rnaseq).

### Tools compared (RNA suite)

| RustQC output       | Upstream tool                                                            | Comparison                         |
| ------------------- | ------------------------------------------------------------------------ | ---------------------------------- |
| dupRadar            | [dupRadar](https://bioconductor.org/packages/dupRadar/) (R/Bioconductor) | TSV match, float tolerance 1e-10   |
| featureCounts       | [Subread featureCounts](http://subread.sourceforge.net/)                 | Column subset match (gene + count) |
| bam_stat            | [RSeQC bam_stat.py](http://rseqc.sourceforge.net/)                       | Text match, skip log headers       |
| infer_experiment    | [RSeQC infer_experiment.py](http://rseqc.sourceforge.net/)               | Text match, skip info headers      |
| read_duplication    | [RSeQC read_duplication.py](http://rseqc.sourceforge.net/)               | TSV exact match                    |
| read_distribution   | [RSeQC read_distribution.py](http://rseqc.sourceforge.net/)              | TSV match, 0.5 relative tolerance  |
| junction_annotation | [RSeQC junction_annotation.py](http://rseqc.sourceforge.net/)            | Row-sorted TSV + BED comparison    |
| junction_saturation | [RSeQC junction_saturation.py](http://rseqc.sourceforge.net/)            | Structural check (stochastic tool) |
| inner_distance      | [RSeQC inner_distance.py](http://rseqc.sourceforge.net/)                 | TSV match, 0.1 relative tolerance  |
| tin                 | [RSeQC tin.py](http://rseqc.sourceforge.net/)                            | Summary TSV, 5% relative tolerance |
| qualimap            | [Qualimap rnaseq](http://qualimap.conesalab.org/)                        | Gene-body coverage TSV, 5% rel tol |
| preseq              | [preseq lc_extrap](http://smithlabresearch.org/software/preseq/)         | lc_extrap TSV, 5% rel tolerance    |
| samtools flagstat   | [samtools flagstat](http://www.htslib.org/doc/samtools-flagstat.html)    | Exact text match                   |
| samtools idxstats   | [samtools idxstats](http://www.htslib.org/doc/samtools-idxstats.html)    | Exact text match                   |
| samtools stats      | [samtools stats](http://www.htslib.org/doc/samtools-stats.html)          | Text match, skip `#` comment lines |

## How it works

There are two layers of tests, both using [nf-test](https://www.nf-test.com):

### GTF→BED conversion

Both BAM and GTF are required inputs. The pipeline automatically derives a BED gene model from the GTF annotation using the **GTF2BED** local module. This BED file is used by the upstream RSeQC Python tools that require a BED gene model (read_distribution, inner_distance, junction_annotation, junction_saturation, infer_experiment, tin). RustQC does not need a BED file — it works directly from the GTF annotation.

### Upstream tests (`tests/rna/upstream/`)

Each test runs one nf-core module (e.g. `RSEQC_BAMSTAT`) against the small test dataset and snapshots the output. This captures what the upstream tool produces so we can detect if _upstream_ changes.

### RustQC tests (`tests/rna/rustqc/`)

Each test runs the `RUSTQC_RNA` process and does two things:

1. **Cross-comparison** -- uses `CompareUtils` to compare RustQC output against the reference files in `snapshots/rna/small/`, with per-tool tolerance rules
2. **Regression snapshot** -- calls `snapshot()` on the RustQC output, so any future change to RustQC output is caught

RustQC output files are found by **suffix pattern** (e.g. `endsWith('bam_stat.txt')`), making the tests resilient to output directory structure changes.

## Repository layout

```
test-data/rna/small/          Small test BAM + annotations (~7 MB, committed)
snapshots/rna/small/          Reference outputs (committed, plots gitignored)
  dupradar/                     Upstream dupRadar output
  featurecounts/                Upstream featureCounts output
  preseq/                       Upstream preseq lc_extrap output
  qualimap/                     Upstream Qualimap rnaseq output (raw_data_qualimapReport)
  samtools/                     Upstream samtools output (test.flagstat, test.idxstats, test.stats)
  rseqc/                        Upstream RSeQC output, one subdir per tool
    bam_stat/                     bam_stat.txt
    infer_experiment/             infer_experiment.txt
    read_distribution/            read_distribution.txt
    read_duplication/             pos.DupRate.xls, seq.DupRate.xls, ...
    inner_distance/               inner_distance.txt, inner_distance_freq.txt, ...
    junction_annotation/          junction.bed, junction.xls, ...
    junction_saturation/          junctionSaturation_plot.r
    tin/                          test.summary.txt, test.tin.xls
  rustqc/                       RustQC output, same tool subdirectory structure
    dupradar/                     test_dupMatrix.txt, test_intercept_slope.txt, ...
    featurecounts/                test.featureCounts.tsv, ...
    rseqc/bam_stat/               test.bam_stat.txt
    rseqc/infer_experiment/       test.infer_experiment.txt
    ...                           (mirrors upstream structure)
tests/
  lib/CompareUtils.groovy     Shared comparison utilities (tsvMatch, textMatch, etc.)
  rna/upstream/               9 nf-test files, one per upstream tool
  rna/rustqc/                 9 nf-test files, one per RustQC tool output
  rna/pipeline.nf.test        Smoke test for the full workflow
modules/local/rustqc_rna.nf  RustQC Nextflow process definition
modules/local/gtf2bed/       GTF2BED module (converts GTF to BED gene model)
bin/gtf2bed                  GTF2BED conversion script
modules/nf-core/              14 upstream tool modules (dupradar, qualimap, rseqc/*, subread, samtools)
workflows/rustqc-benchmarks.nf  Main pipeline workflow
conf/
  rna_test.config             Small dataset parameters
  rna_test_full.config        Large dataset parameters (S3, incomplete)
  modules.config              Per-module publishDir and ext.args settings
```

## Prerequisites

- [Nextflow](https://www.nextflow.io/) >= 25.04.0
- [nf-test](https://www.nf-test.com) >= 0.9.2
- Docker (or Singularity/Apptainer)

## Running the tests

The `nf-test.config` already sets the `test,docker` profiles, so no `--profile` flag is needed.

### Run all RNA tests

```bash
nf-test test tests/rna/upstream/ tests/rna/rustqc/
```

### Run by tag

```bash
# All upstream reference tests
nf-test test --tag upstream

# All RustQC comparison tests
nf-test test --tag rustqc

# A single tool (runs both upstream + rustqc for that tool)
nf-test test --tag bam_stat

# Everything tagged rna (upstream + rustqc + pipeline)
nf-test test --tag rna
```

### Run with verbose output

```bash
nf-test test --tag rna --verbose
```

### Available tags

Every test has multiple tags so you can slice in different ways:

| Tag                                                      | What it selects                              |
| -------------------------------------------------------- | -------------------------------------------- |
| `upstream`                                               | All 9 upstream nf-core module tests          |
| `rustqc`                                                 | All 9 RustQC comparison tests                |
| `rna`                                                    | All RNA tests (upstream + rustqc + pipeline) |
| `small`                                                  | Small dataset tests                          |
| `bam_stat`, `dupradar`, `featurecounts`, `qualimap`, ... | Both upstream + rustqc tests for that tool   |
| `pipeline`                                               | Pipeline-level smoke test                    |

## Running the pipeline directly

The Nextflow pipeline can also be run standalone (e.g. on Seqera Platform for benchmarking):

```bash
# RustQC only (default)
nextflow run main.nf -profile rna_test,docker

# Upstream tools only
nextflow run main.nf -profile rna_test,docker --run_upstream --run_rustqc false

# Both
nextflow run main.nf -profile rna_test,docker --run_upstream
```

### Running on Seqera Platform

Launch the pipeline from Seqera Platform using the pre-configured test profiles.
Both profiles set `strandedness = 'reverse'` (matching the library prep of the bundled test data).

**Small test** (local test data, ~7 MB BAM):

```
Pipeline:    https://github.com/seqeralabs/rustqc-benchmarks
Revision:    main
Profile:     rna_test,docker
Parameters:  --run_upstream true
```

**Large test** (GM12878 markdup-sorted BAM from nf-core/rnaseq megatests, ~8 GB):

```
Pipeline:    https://github.com/seqeralabs/rustqc-benchmarks
Revision:    main
Profile:     rna_test_full,docker
Parameters:  --run_upstream true
```

> **Strandedness is not auto-detected.**
> This pipeline takes a pre-aligned BAM as input, so there is no Salmon-based strandedness
> inference like nf-core/rnaseq. The test profiles default to `reverse`.
> When running with your own data, set `--strandedness` to match your library prep
> (`reverse`, `forward`, or `unstranded`) — this affects Qualimap, dupRadar, and RustQC output.

### Use a local RustQC binary

```bash
nextflow run main.nf -profile rna_test,docker \
    --rustqc_image '' \
    --rustqc_binary /path/to/rustqc
```

> **Building RustQC from source.**
> Since the [noodles migration](https://github.com/seqeralabs/RustQC/issues/113), RustQC uses
> pure-Rust BAM/SAM/CRAM I/O and **no longer requires `htslib` or `cmake`** to build — the only
> system dependencies are `g++` and `libfontconfig1-dev`. The minimum supported Rust version
> (MSRV) is **1.89**.

### Key parameters

| Parameter         | Default                         | Description                                                         |
| ----------------- | ------------------------------- | ------------------------------------------------------------------- |
| `--run_rustqc`    | `true`                          | Run RustQC                                                          |
| `--run_upstream`  | `false`                         | Run upstream reference tools                                        |
| `--rustqc_image`  | `ghcr.io/seqeralabs/rustqc:dev` | RustQC Docker image (tracks latest `main`, incl. noodles migration) |
| `--rustqc_binary` | `null`                          | Local RustQC binary (overrides Docker)                              |
| `--bam` / `--bai` | _(from profile)_                | Input BAM and index **(required)**                                  |
| `--gtf`           | _(from profile)_                | GTF annotation file **(required)**                                  |
| `--sample_id`     | `test`                          | Sample identifier (used in output filenames)                        |
| `--paired`        | `true`                          | Paired-end data                                                     |
| `--strandedness`  | `unstranded`                    | Library strandedness                                                |
| `--outdir`        | `results`                       | Output directory                                                    |

## Updating snapshots

### After an intentional RustQC change

If RustQC output intentionally changes, the regression snapshots need updating:

```bash
# Re-run RustQC tests and update their .snap files
nf-test test --tag rustqc --update-snapshot

# Review the diff
git diff tests/rna/rustqc/*.nf.test.snap

# If the changes look correct, also update the committed RustQC snapshots.
# Find an output dir from the nf-test work directory and copy files
# into the matching subdirectory structure under snapshots/rna/small/rustqc/.
# For example:
SRC=".nf-test/tests/<hash>/work/<hash>/output"
cp "$SRC"/test_dupMatrix.txt snapshots/rna/small/rustqc/dupradar/
cp "$SRC"/test.featureCounts.tsv snapshots/rna/small/rustqc/featurecounts/
cp "$SRC"/test.bam_stat.txt snapshots/rna/small/rustqc/rseqc/bam_stat/
# ... etc for each tool

# Commit
git add tests/rna/rustqc/*.nf.test.snap snapshots/rna/small/rustqc/
git commit -m "Update RustQC snapshots for <reason>"
```

### After an upstream tool update

If nf-core modules are updated and upstream tool output changes:

```bash
# Re-run upstream tests and update their .snap files
nf-test test --tag upstream --update-snapshot

# Copy fresh upstream outputs to the reference snapshots directory
# (the rustqc tests read files from snapshots/rna/small/ for comparison)

# dupradar (note: uses test_ prefix in filenames)
cp .nf-test/tests/<hash>/work/<hash>/test_dupMatrix.txt snapshots/rna/small/dupradar/dupMatrix.txt
cp .nf-test/tests/<hash>/work/<hash>/test_intercept_slope.txt snapshots/rna/small/dupradar/intercept_slope.txt

# featurecounts
cp .nf-test/tests/<hash>/work/<hash>/test.featureCounts.tsv snapshots/rna/small/featurecounts/
cp .nf-test/tests/<hash>/work/<hash>/test.featureCounts.tsv.summary snapshots/rna/small/featurecounts/

# rseqc -- each tool has its own subdirectory
cp .nf-test/tests/<hash>/work/<hash>/test.bam_stat.txt snapshots/rna/small/rseqc/bam_stat/bam_stat.txt
cp .nf-test/tests/<hash>/work/<hash>/test.infer_experiment.txt snapshots/rna/small/rseqc/infer_experiment/
cp .nf-test/tests/<hash>/work/<hash>/test.pos.DupRate.xls snapshots/rna/small/rseqc/read_duplication/pos.DupRate.xls
# ... etc for each tool

# samtools -- exact-match reference outputs (flagstat, idxstats, stats)
cp .nf-test/tests/<hash>/work/<hash>/test.flagstat snapshots/rna/small/samtools/
cp .nf-test/tests/<hash>/work/<hash>/test.idxstats snapshots/rna/small/samtools/
cp .nf-test/tests/<hash>/work/<hash>/test.stats    snapshots/rna/small/samtools/

# preseq
cp .nf-test/tests/<hash>/work/<hash>/test.lc_extrap.txt snapshots/rna/small/preseq/

# qualimap -- gene-body coverage profiles
cp .nf-test/tests/<hash>/work/<hash>/test/raw_data_qualimapReport/*.txt snapshots/rna/small/qualimap/raw_data_qualimapReport/

# Re-run rustqc tests to check if comparisons still hold
nf-test test --tag rustqc

# Commit
git add snapshots/ tests/rna/upstream/*.nf.test.snap
git commit -m "Regenerate upstream reference snapshots"
```

## CompareUtils reference

The shared comparison library (`tests/lib/CompareUtils.groovy`) provides:

### `CompareUtils.tsvMatch(actual, expected, opts)`

Line-by-line TSV comparison with configurable tolerance.

```groovy
CompareUtils.tsvMatch(
    path(actualFile).readLines(),
    path(expectedFile).readLines(),
    [
        tolerance: 1e-10,        // absolute numeric tolerance
        relTolerance: 0.02,      // relative numeric tolerance (passes if EITHER is met)
        skipPrefixes: ['#'],     // ignore lines starting with these
        skipColumns: [1,2] as Set, // ignore specific columns
        delimiter: '\t',         // column delimiter (default: tab)
    ]
)
```

### `CompareUtils.textMatch(actual, expected, ignorePrefixes)`

Exact line-by-line text comparison, filtering lines by prefix.

```groovy
CompareUtils.textMatch(
    path(actualFile).readLines(),
    path(expectedFile).readLines(),
    ['Load BAM', 'processing']  // ignore lines starting with these
)
```

### `CompareUtils.fileMinSize(file, minBytes)`

Asserts a file exists and meets a minimum size. Useful for plot files.

```groovy
CompareUtils.fileMinSize(path(plotFile), 1000)
```

## Adding a new benchmark suite

This repo is organized by RustQC subcommand. To add a new suite (e.g. `rustqc dna`):

1. Create `modules/local/rustqc_dna.nf`
2. Install relevant nf-core modules (`nf-core modules install ...`)
3. Add test data to `test-data/dna/small/`
4. Add `conf/dna_test.config` with input paths
5. Write upstream tests in `tests/dna/upstream/`
6. Run upstream tests, copy outputs to `snapshots/dna/small/`
7. Write RustQC comparison tests in `tests/dna/rustqc/`
8. Extend the workflow or create `workflows/dna.nf`

Nothing in the RNA suite is touched.

## Credits

Built with the [nf-core](https://nf-co.re) pipeline template and community modules.

> Philip Ewels, Alexander Peltzer, Sven Fillinger, Harshil Patel, Johannes Alneberg, Andreas Wilm, Maxime Ulysse Garcia, Paolo Di Tommaso & Sven Nahnsen.
> _Nat Biotechnol._ 2020 Feb 13. doi: [10.1038/s41587-020-0439-x](https://dx.doi.org/10.1038/s41587-020-0439-x).

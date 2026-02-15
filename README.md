# RustQC Benchmarks

[![nf-test](https://img.shields.io/badge/unit_tests-nf--test-337ab7.svg)](https://www.nf-test.com)
[![Nextflow](https://img.shields.io/badge/version-%E2%89%A525.04.0-green?style=flat&logo=nextflow&logoColor=white&color=%230DC09D)](https://www.nextflow.io/)

Benchmark suite for validating [RustQC](https://github.com/ewels/RustQC) outputs against upstream bioinformatics tools.

## Overview

RustQC reimplements common RNA-seq QC tools in Rust for performance. This repository provides automated correctness validation: it runs RustQC and compares its outputs against reference outputs from the original tools, using configurable comparison rules (exact match, numeric tolerance, line filtering).

Built as an nf-core-style Nextflow pipeline with [nf-test](https://www.nf-test.com) for assertions.

### Tools Compared (RNA suite)

| RustQC output | Upstream tool | Comparison method |
|---|---|---|
| dupRadar | [dupRadar](https://bioconductor.org/packages/dupRadar/) (R/Bioconductor) | TSV match (exact + float tolerance) |
| featureCounts | [Subread featureCounts](http://subread.sourceforge.net/) | TSV match (skip comment headers) |
| bam_stat | [RSeQC bam_stat.py](http://rseqc.sourceforge.net/) | Text match (skip log headers) |
| infer_experiment | [RSeQC infer_experiment.py](http://rseqc.sourceforge.net/) | Text match (skip info headers) |
| read_duplication | [RSeQC read_duplication.py](http://rseqc.sourceforge.net/) | TSV exact match |
| read_distribution | [RSeQC read_distribution.py](http://rseqc.sourceforge.net/) | Text match (known minor diffs) |
| junction_annotation | [RSeQC junction_annotation.py](http://rseqc.sourceforge.net/) | Text + TSV match |
| junction_saturation | [RSeQC junction_saturation.py](http://rseqc.sourceforge.net/) | Text match (R script data) |
| inner_distance | [RSeQC inner_distance.py](http://rseqc.sourceforge.net/) | TSV exact match |

All upstream tools are run via standard [nf-core modules](https://nf-co.re/modules), so reference outputs match what users get from [nf-core/rnaseq](https://nf-co.re/rnaseq).

## Architecture

```
Suite-based organization (extensible for future RustQC commands):

test-data/rna/small/     -- Small test BAM + annotations (in repo, ~7MB)
snapshots/rna/small/     -- Reference outputs from upstream tools (in repo)
tests/rna/               -- nf-test files comparing RustQC vs snapshots
tests/lib/               -- Shared Groovy comparison utilities
modules/local/           -- Custom RUSTQC_RNA process
modules/nf-core/         -- 12 upstream tool modules (installed via nf-core)
conf/rna_test.config     -- Small dataset parameters
conf/rna_test_full.config -- Large dataset parameters (S3)
```

## Quick Start

### Prerequisites

- [Nextflow](https://www.nextflow.io/) >= 25.04.0
- [nf-test](https://www.nf-test.com) >= 0.9.0
- Docker (or Singularity/Apptainer)

### Run correctness tests (small dataset)

```bash
# All RNA tools
nf-test test --tag rna --profile docker

# Single tool
nf-test test --tag bam_stat --profile docker

# With verbose output
nf-test test --tag rna --profile docker --verbose
```

### Run the pipeline directly

```bash
# RustQC only (default)
nextflow run main.nf -profile rna_test,docker

# Upstream tools only (to regenerate reference snapshots)
nextflow run main.nf -profile rna_test,docker --run_upstream --run_rustqc false

# Both (full comparison)
nextflow run main.nf -profile rna_test,docker --run_upstream
```

### Large dataset (S3)

```bash
# Tests
nf-test test --tag large --profile docker

# Pipeline
nextflow run main.nf -profile rna_test_full,docker
```

### Use a local RustQC binary

```bash
nextflow run main.nf -profile rna_test,docker \
    --rustqc_image '' \
    --rustqc_binary /path/to/rustqc
```

## Pipeline Parameters

| Parameter | Default | Description |
|---|---|---|
| `--bam` | (from profile) | Input BAM file |
| `--bai` | (from profile) | BAM index file |
| `--gtf` | (from profile) | GTF annotation |
| `--bed` | (from profile) | BED gene model |
| `--sample_id` | `test` | Sample identifier |
| `--paired` | `true` | Paired-end data |
| `--strandedness` | `unstranded` | Library strandedness |
| `--run_rustqc` | `true` | Run RustQC |
| `--run_upstream` | `false` | Run upstream reference tools |
| `--rustqc_image` | `ghcr.io/ewels/rustqc:latest` | RustQC Docker image |
| `--rustqc_binary` | `null` | Path to local RustQC binary (overrides Docker) |
| `--skip_dup_check` | `false` | Skip duplication check in RustQC |
| `--biotype_attribute` | `null` | GTF biotype attribute name |
| `--outdir` | `results` | Output directory |

## Managing Snapshots

Reference snapshots in `snapshots/` are outputs from upstream tools, committed to git. They rarely need updating.

### Regenerate upstream snapshots

```bash
# Run upstream tools
nextflow run main.nf -profile rna_test,docker --run_upstream --run_rustqc false --outdir reference_outputs

# Review the outputs, then copy to snapshots/
cp -r reference_outputs/dupradar/gene_data/* snapshots/rna/small/dupradar/
cp -r reference_outputs/featurecounts/* snapshots/rna/small/featurecounts/
cp -r reference_outputs/rseqc/*/*.txt snapshots/rna/small/rseqc/
# ... etc

# Commit
git add snapshots/ && git commit -m "Regenerate upstream reference snapshots"
```

## Adding a New Benchmark Suite

This repo is organized by RustQC subcommand. To add a new suite (e.g., `rustqc dna`):

1. Create `modules/local/rustqc_dna.nf`
2. Install relevant nf-core modules (`nf-core modules install ...`)
3. Create `workflows/dna.nf` or extend the main workflow
4. Add `conf/dna_test.config` and `conf/dna_test_full.config`
5. Add test data to `test-data/dna/small/`
6. Generate upstream snapshots in `snapshots/dna/small/`
7. Write nf-test files in `tests/dna/`
8. Add `withName` blocks to `conf/modules.config`

Nothing in the RNA suite is touched.

## Credits

Built with the [nf-core](https://nf-co.re) pipeline template and community modules.

> Philip Ewels, Alexander Peltzer, Sven Fillinger, Harshil Patel, Johannes Alneberg, Andreas Wilm, Maxime Ulysse Garcia, Paolo Di Tommaso & Sven Nahnsen.
> _Nat Biotechnol._ 2020 Feb 13. doi: [10.1038/s41587-020-0439-x](https://dx.doi.org/10.1038/s41587-020-0439-x).

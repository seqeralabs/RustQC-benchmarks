# seqeralabs/rustqc-benchmarks: Changelog

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/)
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## v1.0.0dev - [date]

Initial release of seqeralabs/rustqc-benchmarks, created with the [nf-core](https://nf-co.re/) template.

### `Added`

- Add Qualimap rnaseq upstream module with name-sorted BAM (via `SAMTOOLS_SORT_QUALIMAP`) to the benchmarking pipeline
- Add GTF2BED local module (`modules/local/gtf2bed/`, `bin/gtf2bed`) to auto-derive BED gene model from GTF annotation
- Add shared `ch_bed` channel used by all BED-dependent upstream RSeQC tools (read_distribution, inner_distance, junction_annotation, junction_saturation, infer_experiment, tin)
- Add bigWig coverage track nf-test (`tests/rna/rustqc/bigwig.nf.test`) with regression + crosscheck blocks; decodes bigWig to bedGraph and compares against committed `bedtools v2.31.1` + UCSC `bedClip` references in `snapshots/rna/small/bigwig/`. Requires RustQC [#114](https://github.com/seqeralabs/RustQC/pull/114)
- Add `CompareUtils.bedGraphFromBigWig`, `bedGraphMatch`, and `md5BedGraphLines` helpers for bigWig validation
- Add `RUSTQC_IMAGE` environment override in `tests/nextflow.config` for pre-release RustQC image testing
- Update all comparison report templates to reflect new module outputs

### `Changed`

- Remove `bed` parameter — BED is always auto-derived from GTF via GTF2BED (no user-provided BED input)
- Make `--gtf` a required parameter (like `--bam`)
- Refactor workflow to share a single GUNZIP_GTF process across upstream and RustQC branches
- Refactor workflow to use unified `ch_bed` channel for all BED-dependent tools

### `Fixed`

### `Dependencies`

### `Deprecated`

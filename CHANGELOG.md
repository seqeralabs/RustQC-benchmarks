# seqeralabs/rustqc-benchmarks: Changelog

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/)
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## v1.0.0dev - [date]

Initial release of seqeralabs/rustqc-benchmarks, created with the [nf-core](https://nf-co.re/) template.

### `Added`

- Add Qualimap rnaseq upstream module with name-sorted BAM (via `SAMTOOLS_SORT_QUALIMAP`) to the benchmarking pipeline
- Add GTF2BED local module (`modules/local/gtf2bed/`, `bin/gtf2bed`) to auto-derive BED gene model from GTF annotation
- Add `--bed` flag to RUSTQC_RNA module for read_distribution parity with upstream RSeQC
- Add shared `ch_bed` channel used by all BED-dependent RSeQC tools (read_distribution, inner_distance, junction_annotation, junction_saturation)
- Update all comparison report templates to reflect new module outputs

### `Changed`

- Remove `bed` parameter requirement — BED is now optional and auto-derived from GTF via GTF2BED when not provided
- Refactor workflow to share a single GUNZIP_GTF process across upstream and RustQC branches
- Refactor workflow to use unified `ch_bed` channel for all BED-dependent tools

### `Fixed`

### `Dependencies`

### `Deprecated`

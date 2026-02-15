# Output

## Directory structure

### `snapshots/rna/small/`

Reference outputs from upstream tools, committed to git.
These are the "ground truth" that RustQC tests compare against.

```
snapshots/rna/small/
  dupradar/
    dupMatrix.txt              Gene-level duplication rate matrix
    intercept_slope.txt        Intercept and slope summary
  featurecounts/
    featureCounts.tsv          Gene counts (7 columns: Geneid, Chr, Start, End, Strand, Length, count)
    featureCounts.tsv.summary  Assignment statistics
  rseqc/
    bam_stat.txt               BAM mapping statistics
    infer_experiment.txt       Strandedness inference
    read_distribution.txt      Read distribution across genomic features
    read_duplication.pos.DupRate.xls   Position-based duplication rates
    read_duplication.seq.DupRate.xls   Sequence-based duplication rates
    inner_distance.inner_distance.txt       Per-read inner distances
    inner_distance.inner_distance_freq.txt  Inner distance frequency histogram
    junction_annotation.junction.bed        Splice junction BED file
    junction_annotation.junction.xls        Splice junction details
    junction_annotation.txt                 Annotation summary
    junction_saturation.junctionSaturation_plot.r  Saturation R script
    ...and more (R scripts, Interact BED, etc.)
```

Regenerate these by running `nf-test test --tag upstream --update-snapshot` and copying the output files from the nf-test work directories. See the main [README](../README.md#after-an-upstream-tool-update) for the full procedure.

### `results/rna/small/`

RustQC example outputs, committed to git (text files only -- plots are gitignored).
These show what RustQC currently produces for the small test dataset.

All files are prefixed with the sample ID (`test.` or `test_`). RustQC outputs everything flat in a single directory.

Key files:

| File                                  | Description                           |
| ------------------------------------- | ------------------------------------- |
| `test.bam_stat.txt`                   | BAM mapping statistics                |
| `test.infer_experiment.txt`           | Strandedness inference                |
| `test.featureCounts.tsv`              | Gene counts (2 columns: gene + count) |
| `test.featureCounts.tsv.summary`      | Assignment statistics                 |
| `test_dupMatrix.txt`                  | Gene-level duplication rate matrix    |
| `test_intercept_slope.txt`            | Intercept and slope summary           |
| `test.read_distribution.txt`          | Read distribution across features     |
| `test.pos.DupRate.xls`                | Position-based duplication rates      |
| `test.seq.DupRate.xls`                | Sequence-based duplication rates      |
| `test.inner_distance.txt`             | Per-read inner distances              |
| `test.inner_distance_freq.txt`        | Inner distance frequency histogram    |
| `test.junction.bed`                   | Splice junction BED                   |
| `test.junction.xls`                   | Splice junction details               |
| `test.junction_annotation.txt`        | Annotation summary                    |
| `test.junctionSaturation_plot.r`      | Saturation R script                   |
| `test.biotype_counts.tsv`             | Biotype-level counts                  |
| `test.inner_distance_summary.txt`     | Inner distance summary stats          |
| `test.junctionSaturation_summary.txt` | Junction saturation summary           |

### `test-data/rna/small/`

Committed test input files:

| File                        | Description                            |
| --------------------------- | -------------------------------------- |
| `test.bam` / `test.bam.bai` | Small coordinate-sorted BAM (~7 MB)    |
| `genes.gtf.gz`              | Gene annotation (gzipped GTF)          |
| `genes.bed`                 | Gene model BED (for RSeQC)             |
| `genes_genebody.bed`        | Gene body BED (for gene body coverage) |

## nf-test snapshot files

Each test also produces a `.nf.test.snap` file (JSON format) in the same directory as the test. These are nf-test's internal regression tracking mechanism -- they capture the exact output content so that any change is detected on the next run.

- `tests/rna/upstream/*.nf.test.snap` -- upstream tool output snapshots
- `tests/rna/rustqc/*.nf.test.snap` -- RustQC output snapshots

These are committed to git and should be updated with `--update-snapshot` when changes are intentional.

# Output

## Directory structure

### `snapshots/rna/small/`

Reference outputs from upstream tools, committed to git.
These are the "ground truth" that RustQC tests compare against.

```
snapshots/rna/small/
  dupradar/                              Upstream dupRadar output
    dupMatrix.txt                          Gene-level duplication rate matrix
    intercept_slope.txt                    Intercept and slope summary
  featurecounts/                         Upstream featureCounts output
    featureCounts.tsv                      Gene counts (7 columns)
    featureCounts.tsv.summary              Assignment statistics
  rseqc/                                 Upstream RSeQC output (one subdir per tool)
    bam_stat/
      bam_stat.txt                         BAM mapping statistics
    infer_experiment/
      infer_experiment.txt                 Strandedness inference
    read_distribution/
      read_distribution.txt                Read distribution across genomic features
    read_duplication/
      pos.DupRate.xls                      Position-based duplication rates
      seq.DupRate.xls                      Sequence-based duplication rates
      DupRate_plot.r                       R plotting script
    inner_distance/
      inner_distance.txt                   Per-read inner distances
      inner_distance_freq.txt              Inner distance frequency histogram
      inner_distance_plot.r                R plotting script
    junction_annotation/
      junction.bed                         Splice junction BED file
      junction.xls                         Splice junction details
      junction_annotation.txt              Annotation summary
      junction_plot.r                      R plotting script
    junction_saturation/
      junctionSaturation_plot.r            Saturation R script
  rustqc/                                RustQC output (same tool subdirectory structure)
    dupradar/                              test_dupMatrix.txt, test_intercept_slope.txt, ...
    featurecounts/                         test.featureCounts.tsv, ...
    rseqc/
      bam_stat/                            test.bam_stat.txt
      infer_experiment/                    test.infer_experiment.txt
      read_distribution/                   test.read_distribution.txt
      read_duplication/                    test.pos.DupRate.xls, test.seq.DupRate.xls
      inner_distance/                      test.inner_distance.txt, test.inner_distance_freq.txt, ...
      junction_annotation/                 test.junction.bed, test.junction.xls, ...
      junction_saturation/                 test.junctionSaturation_plot.r, ...
```

Both upstream and RustQC output files are committed to git (text/TSV only -- plot files like `.png`, `.svg`, `.pdf` are gitignored).

Regenerate upstream snapshots by running `nf-test test --tag upstream --update-snapshot` and copying the output files from the nf-test work directories. Regenerate RustQC snapshots similarly with `--tag rustqc`. See the main [README](../README.md#updating-snapshots) for the full procedure.

> **Note:** The current RustQC `:dev` Docker image outputs all files flat in a single directory. The subdirectory structure under `snapshots/rna/small/rustqc/` reflects the _intended_ future RustQC output layout. The nf-test comparison files find RustQC outputs by suffix pattern, so they work with either flat or nested output.

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

# RustQC Investigation & Testing Plan

This document is a guide for working on the RustQC codebase to resolve the discrepancies found between RustQC and upstream tool outputs. For detailed per-file diffs and numbers, see:

- [`results/rna_small/COMPARISON_REPORT.md`](results/rna_small/COMPARISON_REPORT.md) — small test dataset (chr6 subset, sample `test`)
- [`results/rna_large/COMPARISON_REPORT.md`](results/rna_large/COMPARISON_REPORT.md) — full dataset (GM12878_REP1, whole genome)

---

## Issue Priority

### P0 — Critical (behaviour is clearly wrong)

| #   | Issue                                                                                                 | Affected tools                                                | Impact                                                             |
| --- | ----------------------------------------------------------------------------------------------------- | ------------------------------------------------------------- | ------------------------------------------------------------------ |
| 1   | ~~[Unmapped reads skipped](#1-unmapped-reads-skipped)~~ **RESOLVED**                                  | samtools, bam_stat, infer_experiment, featurecounts, qualimap | All read counts wrong on BAMs with unmapped reads                  |
| 2   | ~~[Qualimap strandedness always reports non-strand-specific](#2-qualimap-strandedness)~~ **RESOLVED** | qualimap                                                      | Metrics computed with wrong protocol                               |
| 3   | ~~[featureCounts output granularity wrong](#3-featurecounts-output-granularity)~~ **RESOLVED**        | featurecounts                                                 | Per-biotype grouping vs per-gene; Assigned/Ambiguity counts differ |

### P1 — Significant (numeric results differ)

| #   | Issue                                                                                                   | Affected tools            | Impact                                                                                                     |
| --- | ------------------------------------------------------------------------------------------------------- | ------------------------- | ---------------------------------------------------------------------------------------------------------- |
| 4   | ~~[read_distribution region definitions differ](#4-read_distribution-region-definitions)~~ **RESOLVED** | rseqc/read_distribution   | Tag counts and region lengths differ substantially — fixed via GTF2BED providing correct BED to RustQC     |
| 5   | ~~[dupradar count differences](#5-dupradar-count-differences)~~ **RESOLVED**                            | dupradar                  | Some gene counts differ; intercept/slope values change                                                     |
| 6   | [preseq curve differs entirely](#6-preseq-curve) — **Round 2 fix applied**                              | preseq                    | Library complexity extrapolation values all different; Round 2 fix addressed algorithm parameter alignment |
| 7   | [inner_distance values differ](#7-inner_distance)                                                       | rseqc/inner_distance      | 1–2 bp offsets in individual pairs; freq bins differ; full dataset shows 10× count difference (sampling)   |
| 8   | [junction_annotation coordinate offsets](#8-junction_annotation)                                        | rseqc/junction_annotation | ±1 bp coordinates; junction merging differences                                                            |

### P2 — Minor (format/cosmetic)

| #   | Issue                                                                              | Affected tools                                     | Impact                                                   |
| --- | ---------------------------------------------------------------------------------- | -------------------------------------------------- | -------------------------------------------------------- |
| 9   | [samtools stats: only SN section emitted](#9-samtools-stats-sn-only)               | samtools/stats                                     | 41 vs 10,500 lines — missing distribution tables         |
| 10  | [dupradar intercept_slope format](#10-dupradar-intercept_slope-format)             | dupradar                                           | TSV vs label format                                      |
| 11  | [junction_annotation log preamble / extension](#11-junction_annotation-log-format) | rseqc/junction_annotation                          | Missing preamble lines; .txt vs .log extension           |
| 12  | [Missing DupRate_plot.r](#12-missing-duprate_plot-r)                               | rseqc/read_duplication                             | R plotting script not emitted (PNG/SVG produced instead) |
| 13  | [R script path differences](#13-r-script-paths)                                    | inner_distance, junction_saturation, junction_plot | Output file paths differ in generated R scripts          |

---

## Investigation Details

### 1. Unmapped reads skipped

**Symptom:** On `rna_large` (which has 12.5M unmapped reads), RustQC reports 0 unmapped. On `rna_small` (0 unmapped reads in BAM), this is invisible. Pipeline DAG and execution trace confirm both tools receive the **same BAM**.

**Where to look in RustQC:**

- The BAM reader / iterator initialization. Check if it uses indexed region-based access (`fetch()` by reference) rather than sequential iteration — this would skip unmapped reads stored under `*`.
- Any explicit `FLAG & 0x4` filtering or `tid < 0` / `is_unmapped()` checks in the main read loop.
- The `bam_stat` module — compare how it counts total records vs how upstream `bam_stat.py` does it.

**How to test:**

1. Get a small BAM with unmapped reads. Either use the existing `rna_large` BAM, or create a test BAM:
   ```bash
   # Add some unmapped reads to the small test BAM
   samtools view -h test-data/rna/small/test.bam | \
     awk 'BEGIN{OFS="\t"} /^@/{print; next} {print} END{print "unmapped1\t4\t*\t0\t0\t*\t*\t0\t0\tACGT\tIIII"}' | \
     samtools view -bS - > test_with_unmapped.bam
   samtools index test_with_unmapped.bam
   ```
2. Run RustQC on this BAM and check `bam_stat.txt` for the unmapped count.
3. Run upstream `bam_stat.py` on the same BAM and compare.
4. Fix: Ensure the BAM iterator reads all records, including unmapped.

**Cascading effects once fixed:** samtools flagstat, idxstats, bam_stat, infer_experiment fractions, featurecounts Unassigned_Unmapped count, and qualimap "not aligned" count should all change to match upstream.

---

### 2. Qualimap strandedness

**Symptom:** RustQC always reports `non-strand-specific` protocol in `rnaseq_qc_results.txt`, even when the pipeline is configured with `strandedness = 'reverse'`. The upstream Qualimap correctly reports `strand-specific-reverse`.

**Where to look:**

- The qualimap module in RustQC — check how it receives and applies the strandedness parameter.
- The pipeline passes strandedness via the `--strandedness` or `-s` flag in `conf/modules.config`. Verify RustQC's CLI accepts and propagates this to its qualimap logic.
- Check if RustQC's SSP (Strand Specificity Protocol) auto-detection is overriding the user-provided value.

**How to test:**

1. Run RustQC with explicit `--strandedness reverse` and check `rnaseq_qc_results.txt`.
2. Verify gene alignment counts change appropriately (strand-aware counting gives different numbers).
3. Compare 5'–3' bias, exonic/intronic/intergenic percentages, and coverage profiles against upstream.

**Expected impact:** Fixing this should bring qualimap aligned-to-genes (31,654→closer match), ambiguous counts, exonic/intronic percentages, and the 5'–3' bias closer to upstream values.

---

### 3. featureCounts output granularity

**Symptom:** Upstream `featureCounts` groups by `gene_biotype` (14 aggregated rows in `rna_small`, biotype-level in `rna_large`). RustQC outputs per `gene_id` (2,905 rows in `rna_small`, 63,679 in `rna_large`). The `.summary` also shows large differences: Assigned 44,464 vs 40,109 (`rna_small`), 131M vs 124M (`rna_large`); Ambiguity 234 vs 4,689 (`rna_small`).

**Where to look:**

- RustQC's featureCounts implementation — check the `-g` / `--attribute` parameter. Upstream uses `-g gene_biotype` (or similar grouping attribute), RustQC appears to use `-g gene_id`.
- Check how multi-mapping and overlapping features are handled — the large ambiguity difference (234→4,689) suggests different overlap resolution logic.
- The pipeline's `conf/modules.config` for featureCounts `ext.args` — what flags does the upstream module get?

**How to test:**

1. Run RustQC featureCounts with explicit `-g gene_biotype` and compare against upstream.
2. Separately test with `-g gene_id` and verify the per-gene output matches expectations.
3. For the ambiguity issue: check overlap handling flags (`--fracOverlap`, `--minOverlap`, `-O`).
4. The existing nf-test (`tests/rna/rustqc/featurecounts.nf.test`) compares per-gene counts — update it to also validate the summary Assigned/Ambiguity totals and the grouping attribute.

---

### 4. read_distribution region definitions — RESOLVED

**Symptom:** Total assigned tags differ (66,693 vs 67,783 in `rna_small`; 54.1M vs 53.4M in `rna_large`). Region lengths differ dramatically — e.g. 5'UTR region is 1,437,256 bases upstream vs 403,926 in RustQC. CDS_Exons region is 99.8M vs 39.0M in `rna_large`.

**Resolution:** The root cause was that RustQC's `read_distribution` was using GTF-derived gene models directly, while upstream RSeQC uses a BED gene model. The fix involved:

1. Adding a **GTF2BED** local module that converts the GTF annotation to a BED gene model (matching the conversion used by nf-core/rnaseq).
2. Passing the resulting BED file to RustQC via the new `--bed` flag, giving read_distribution parity with upstream RSeQC's gene model flattening.
3. The shared `ch_bed` channel now provides this BED file to all BED-dependent tools.

---

### 5. dupradar count differences

**Symptom:** A handful of genes show different `allCounts`/`filteredCounts` (e.g. ENSG00000137265: 276→273). Intercept 0.033→0.030, slope 1.552→1.681.

**Where to look:**

- RustQC's dupradar counting logic — how it assigns reads to genes and determines duplicates.
- The differences might stem from the same gene model / overlap issue as read_distribution and featureCounts.
- Float precision in the duplication rate regression calculation.

**How to test:**

1. Pick specific genes that differ (e.g. ENSG00000137265) and trace read assignment manually.
2. Compare the regression fitting method (RustQC vs R's `dupringer` function).
3. The existing nf-test (`tests/rna/rustqc/dupradar.nf.test`) uses `tsvMatch` with tolerance — check if it's catching these differences or if the tolerance is too loose.

---

### 6. preseq curve — Round 2 fix applied

**Symptom:** The entire library complexity extrapolation curve differs. Same column structure but all values different.

**Round 2 fix details:** After resolving P0 #1 (unmapped reads), the preseq curve was re-evaluated. A Round 2 fix was applied to align algorithm parameters (step size, extrapolation factor, bootstrap replicates) with upstream `preseq lc_extrap` defaults. The curve values are now closer to upstream but may still show minor differences due to inherent stochasticity in the bootstrapping approach. Further tolerance tuning may be needed.

**Remaining investigation:**

- Verify bootstrap random seed handling matches upstream behaviour.
- Monitor whether differences remain within acceptable tolerance on the full dataset.
- Consider adding an nf-test for preseq with appropriate tolerance.

---

### 7. inner_distance

**Symptom:** Small: 1–2 bp offsets in individual pairs, small bin count differences. Large: bin counts are ~10× larger in RustQC, suggesting upstream subsamples while RustQC processes all pairs.

**Where to look:**

- RustQC's inner_distance calculation — check fragment size logic (insert size vs inner distance).
- Upstream `inner_distance.py` uses a default sample size of 1,000,000 read pairs (`-k 1000000`). Check if RustQC samples or processes all pairs.
- The 1–2 bp offset suggests a different definition of inner distance (e.g. inclusive vs exclusive of read boundaries).

**How to test:**

1. Check if RustQC has a `-k` / `--sample-size` parameter and what its default is.
2. Pick specific read pairs that differ by 1–2 bp and manually calculate inner distance both ways.
3. The existing nf-test uses `tsvMatch` with 0.1 relative tolerance — verify this is appropriate.

---

### 8. junction_annotation

**Symptom:** Row reordering (293K diff lines in `rna_large` that are just sorting differences). Some ±1 bp coordinate offsets. Different junction merging behaviour.

**Where to look:**

- Sort order of output BED/XLS files — RustQC may sort differently from upstream.
- Coordinate system: check 0-based vs 1-based handling at junction boundaries.
- Junction merging logic when multiple reads support slightly different breakpoints.

**How to test:**

1. Sort both junction.bed files and re-diff to isolate true content differences from ordering.
2. Check a few junctions with ±1 bp offset to determine if it's a systematic off-by-one.
3. The existing nf-test sorts rows before comparing — verify it catches the coordinate offsets.

---

### 9. samtools stats: SN section only

**Symptom:** RustQC emits 41 lines (SN summary numbers only). Upstream emits ~10,500 lines with full per-cycle quality, insert size, GC, coverage distributions.

**Investigation:** Determine if this is intentional scope limitation or a TODO. If intentional, document it. If not, the distribution sections (FFQ, LFQ, GCF, GCL, IS, RL, COV, GCD, ID) need implementing.

**Testing:** No nf-test exists for samtools — consider adding one that at minimum validates the SN values match.

---

### 10–13. Format / cosmetic issues

These are lower priority but straightforward:

- **#10 dupradar intercept_slope format**: Change output format to match upstream's `"<sample> - dupRadar Int: <value>"` pattern, or document the difference.
- **#11 junction_annotation log**: Add preamble lines and use `.log` extension to match upstream.
- **#12 Missing DupRate_plot.r**: RustQC produces PNG/SVG directly. If MultiQC or downstream tools expect the `.r` script, it may need to be emitted.
- **#13 R script paths**: Paths in generated R scripts reference internal working directories. Not functionally important but causes diff noise.

---

## Testing Strategy

### Existing test infrastructure

The benchmarking pipeline already has:

- **nf-test** framework with per-tool tests under `tests/rna/rustqc/` and `tests/rna/upstream/`
- **CompareUtils.groovy** (`tests/lib/`) providing `tsvMatch()` (with tolerance), `textMatch()` (with prefix filtering), `fileMinSize()`
- **Snapshots** under `snapshots/rna/small/` for regression testing
- **GTF2BED module** that auto-derives BED from GTF, ensuring read_distribution uses the correct gene model

### Current status

P0 issues #1–3 and P1 issues #4–5 are **RESOLVED**. P1 #6 (preseq) has a Round 2 fix applied and is under monitoring. Remaining P1 issues (#7, #8) and P2 issues are still open.

### Recommended approach (updated)

1. ~~**Fix P0 issues first**~~ — **DONE**: Unmapped reads (#1), Qualimap strandedness (#2), and featureCounts granularity (#3) are all resolved.

2. **Focus on remaining P1 issues:**
   - inner_distance (#7) — verify sampling behaviour and 1–2 bp offset root cause
   - junction_annotation (#8) — verify coordinate system and merging logic
   - preseq (#6) — monitor Round 2 fix results, tune tolerance

3. **After each fix, re-run the benchmarking pipeline** on both profiles:

   ```bash
   # Small test (local, fast)
   nextflow run main.nf -profile rna_test,docker --run_upstream true

   # Full test (AWS, slower)
   nextflow run main.nf -profile rna_test_full,docker --run_upstream true
   ```

4. **Update nf-tests and snapshots** as fixes land:
   - Tighten tolerances where currently loose (e.g. read_distribution now has GTF2BED-derived BED — verify tolerances)
   - Add missing nf-tests for samtools, qualimap, preseq
   - Update snapshots: `nf-test test --update-snapshot`

5. **Add a test BAM with unmapped reads** to `test-data/rna/small/` to catch regression on issue #1.

### Verification checklist

After all fixes, these file pairs should match (identical or within defined tolerance):

| File pair                         | Target                                        |
| --------------------------------- | --------------------------------------------- |
| samtools flagstat                 | Identical                                     |
| samtools idxstats                 | Identical                                     |
| samtools stats (SN section)       | Identical                                     |
| bam_stat.txt                      | Identical                                     |
| infer_experiment.txt              | Identical (ignoring trailing newline)         |
| pos.DupRate.xls / seq.DupRate.xls | Identical                                     |
| read_distribution.txt             | Within tolerance (currently large gaps)       |
| inner_distance_freq.txt           | Within tolerance                              |
| junction.bed / junction.xls       | Identical after sorting                       |
| dupMatrix.txt                     | Within tolerance                              |
| featureCounts.tsv                 | Same granularity, matching counts             |
| featureCounts.tsv.summary         | Matching category counts                      |
| qualimap rnaseq_qc_results.txt    | Correct strandedness, counts within tolerance |
| preseq lc_extrap.txt              | Within tolerance                              |

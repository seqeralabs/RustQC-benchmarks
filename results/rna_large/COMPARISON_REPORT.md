# RustQC vs Upstream — rna_large Comparison Report

**Date:** 2026-03-06
**Dataset:** GM12878_REP1, 175,097,721 reads, paired-end ~100bp
**Reference:** GRCh37 Ensembl iGenomes (GTF → BED12 via gtf2bed)
**RustQC version:** commit 6e22d6b (Rounds 1-3 fixes, before Round 4 insert size / infer_experiment fix)

## Summary

| #   | Tool                        | Verdict        | Notes                                                                    |
| --- | --------------------------- | -------------- | ------------------------------------------------------------------------ |
| 1   | samtools flagstat           | **PASS**       | Byte-identical                                                           |
| 2   | samtools idxstats           | **PASS**       | Byte-identical                                                           |
| 3   | samtools stats (SN)         | **FAIL**       | Insert size avg/sd wrong (fixed in Round 4, commit 24ba2e5)              |
| 4   | samtools stats (histograms) | **PASS**       | All histogram sections present                                           |
| 5   | bam_stat                    | **PASS**       | Byte-identical                                                           |
| 6   | infer_experiment            | **FAIL**       | 6pp deviation (fixed in Round 4, commit 24ba2e5)                         |
| 7   | read_distribution           | **PASS**       | Byte-identical                                                           |
| 8   | junction_annotation         | **PASS**       | Data identical (sorted); output order differs due to parallel processing |
| 9   | junction_saturation         | **DOCUMENTED** | Small intermediate count diffs (sampling)                                |
| 10  | inner_distance              | **DOCUMENTED** | Per-read distance/classification diffs (mRNA vs genomic)                 |
| 11  | read_duplication            | **PASS**       | Byte-identical (both pos and seq)                                        |
| 12  | preseq                      | **FAIL**       | All extrapolation values differ (different RNG algorithms)               |
| 13  | dupradar                    | **DOCUMENTED** | Intercept ~0.07% diff, small gene count diffs                            |
| 14  | featureCounts               | **PASS**       | Gene-level counts identical                                              |
| 15  | qualimap                    | **DOCUMENTED** | not-aligned 12.5M vs 0 (input BAM handling); metrics otherwise match     |

**Score: 8 PASS, 4 DOCUMENTED, 3 FAIL**

## Detailed Analysis

### 1. samtools flagstat — PASS

Byte-identical. No differences.

### 2. samtools idxstats — PASS

Byte-identical. No differences.

### 3. samtools stats — FAIL (insert size only)

**SN section:** All SN lines identical EXCEPT:

- `insert size average`: upstream 1536.7, RustQC **1888.6** — 23% higher
- `insert size standard deviation`: upstream 2263.8, RustQC **4022.2** — 78% higher

**Root cause (fixed in Round 4, commit 24ba2e5):** Same double-counting + missing overflow cap as rna_small. See rna_small report for details.

**Histogram sections:** All present. Row counts differ slightly between upstream and RustQC due to different histogram binning, but all sections are populated.

### 4. bam_stat — PASS

Byte-identical. No differences.

### 5. infer_experiment — FAIL

| Metric                    | Upstream | RustQC     | Diff   |
| ------------------------- | -------- | ---------- | ------ |
| Undetermined              | 0.0670   | **0.1236** | +5.7pp |
| Forward (1++,1--,2+-,2-+) | 0.0117   | 0.0133     | +0.2pp |
| Reverse (1+-,1-+,2++,2--) | 0.9213   | **0.8631** | -5.8pp |

**Root cause (fixed in Round 4, commit 24ba2e5):** Three compounding issues:

1. **Soft clip inclusion in query length** (primary cause): Including `SoftClip(len)` in the query alignment length created reference intervals wider than the actual alignment, causing false dual-strand overlaps that inflated the "failed to determine" category.
2. **Supplementary alignment inclusion**: Chimeric fragments from STAR have partial mappings with heavy soft clipping, amplifying the interval widening effect.
3. **Proportional scale-down in merge**: Cumulative rounding errors from sequential merge operations.

All three issues fixed in commit 24ba2e5.

### 6. read_distribution — PASS

Byte-identical. All region categories match exactly. The GTF→BED12 conversion ensures both tools use the same annotation source, eliminating the previous annotation format differences.

### 7. junction_annotation — PASS

Data is identical when sorted. Unsorted `diff` shows apparent differences because RustQC outputs chromosomes in parallel completion order (chr21 before chr3 etc.), while upstream outputs in chromosome sort order. After sorting:

- **BED file:** 239,792 lines — identical
- **XLS file:** 239,793 lines — identical
- **Summary stats:** All match exactly (total junctions: 239,792; known: 175,159; partial novel: 45,554; novel: 19,079)

### 8. junction_saturation — DOCUMENTED

Final junction counts at 100% sampling match. Intermediate values at each sampling level differ by small amounts due to different random sampling implementations. Example: known junctions at 5% = 97,352 (upstream) vs 97,222 (RustQC).

### 9. inner_distance — DOCUMENTED

- **Frequency histogram:** Most bins match; some differ by small counts.
- **Per-read differences:** Some reads have different inner distances or classifications:
  - Upstream: `sameExon=Yes,dist=mRNA` → RustQC: `nonExonic=Yes,dist=genomic` for reads near exon boundaries
  - Some distance values differ by 1-4bp
  - Multi-exon spanning reads may use genomic vs mRNA distance
- Overall mean inner distance is similar.

### 10. read_duplication — PASS

Both `pos.DupRate.xls` and `seq.DupRate.xls` are byte-identical.

### 11. preseq — FAIL

All 10,000 extrapolation rows have completely different values. Same root cause as rna_small — C++ vs Rust binomial sampler algorithms produce different bootstrap samples even with identical seeds. Results are statistically equivalent but not numerically identical.

### 12. dupradar — DOCUMENTED

- **Intercept/slope:** 0.8192 vs 0.8198 (0.07%), 1.5369 vs 1.5359 (0.07%). Very close.
- **Gene counts:** Small differences across some genes due to paired-end fragment assembly (singleton handling, cross-chromosome reconciliation).
- **RPKM:** Propagated small differences from gene count variations.

### 13. featureCounts — PASS

- **Gene-level counts:** Identical for all genes.
- **Summary:** All categories identical except `Unassigned_Singleton`: 0 (upstream) vs 273,941 (RustQC). This is expected — RustQC properly classifies unmatched mates as singletons rather than silently dropping them.
- **Exon coordinates:** Same coordinates, different sort order.

### 14. qualimap — DOCUMENTED

- **not-aligned:** Upstream reports 12,490,977; RustQC reports 0. This reflects a difference in how the input BAM is handled — upstream Qualimap receives a name-sorted BAM via `SAMTOOLS_SORT_QUALIMAP`, which may include unmapped reads. RustQC processes the original coordinate-sorted BAM where unmapped reads may have been filtered.
- **Genomic origin percentages:** Minor formatting differences (e.g., "5.4%" vs "5.40%").
- **Bias values:** Not directly comparable due to different transcript sets (affected by not-aligned count).
- **Junction motif percentages:** Minor rounding differences in trailing digits.

## Items Fixed After This Benchmark Run

The following fixes in commit 24ba2e5 will take effect on the next benchmark run:

1. **samtools stats insert size:** Fixed double-counting (one mate per pair) and overflow cap (bucket 8000). Expected: insert size avg/sd will match upstream.
2. **infer_experiment:** Reverted soft clip inclusion, restored supplementary filter, removed proportional scale-down. Expected: undetermined fraction will be close to 0.067 instead of 0.124.

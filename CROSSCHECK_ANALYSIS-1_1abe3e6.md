# RustQC Crosscheck Analysis Report

## Run metadata

- **Date**: 2026-03-09
- **RustQC version**: v0.1.0 (commit unknown, built 2026-03-08T22:20:09Z)
- **RustQC Docker image**: `ghcr.io/seqeralabs/rustqc:dev` (ID: `1abe3e6`)
- **Benchmarks repo commit**: `00c3201`
- **nf-test version**: 0.9.4
- **Nextflow version**: 26.01.1-edge
- **Dataset**: `rna/small` (test BAM + GRCh38 annotations)

## Summary

| Tool                    | Verdict                 | Details                                                                                        |
| ----------------------- | ----------------------- | ---------------------------------------------------------------------------------------------- |
| **bam_stat**            | IDENTICAL               | All values match after filtering timing headers                                                |
| **infer_experiment**    | IDENTICAL               | All values match after filtering version header                                                |
| **read_duplication**    | BYTE-IDENTICAL          | md5 hash match on both pos.DupRate.xls and seq.DupRate.xls                                     |
| **dupradar**            | NEAR-IDENTICAL          | Matrix: 0/40,670 cells differ. Slope: <0.01% relative diff                                     |
| **junction_annotation** | IDENTICAL               | BED and XLS files identical after sorting (line order differs)                                 |
| **inner_distance**      | IDENTICAL               | All 20,861 read pairs match. Frequency histogram identical                                     |
| **junction_saturation** | DETERMINISTIC MATCH     | 100% sample values identical. Intermediate points differ ~2-10% (expected stochastic variance) |
| **featurecounts**       | SIGNIFICANT DIFFERENCES | Incompatible output formats. Summary shows 6.1% difference in Assigned reads                   |
| **read_distribution**   | SIGNIFICANT DIFFERENCES | CDS/UTR region definitions differ substantially (up to 126%)                                   |

### Overall: 7 of 9 tools produce identical or near-identical results. 2 tools have significant algorithmic differences.

---

## Detailed analysis per tool

### 1. bam_stat

**Verdict: IDENTICAL**

RustQC and upstream RSeQC `bam_stat.py` produce byte-identical output content. The only differences are non-deterministic header lines (`Load BAM file...`, `processing...`) that contain file paths and are filtered before comparison.

All integer counts (total records, QC-failed, PCR duplicates, mapped reads, MAPQ stats, paired-end stats) are exactly the same.

**Crosscheck method**: `textMatch` with prefix filtering — effectively exact match.

---

### 2. infer_experiment

**Verdict: IDENTICAL**

All fraction values match exactly after filtering the `This is PairEnd Data` header line. Output:

```
Fraction of reads failed to determine: 0.0543
Fraction of reads explained by "1++,1--,2+-,2-+": 0.4714
Fraction of reads explained by "1+-,1-+,2++,2--": 0.4743
```

**Crosscheck method**: `textMatch` with prefix filtering — effectively exact match.

---

### 3. read_duplication

**Verdict: BYTE-IDENTICAL**

Both `pos.DupRate.xls` and `seq.DupRate.xls` produce identical files (md5 hash match). This is the strongest possible crosscheck — no filtering, sorting, or tolerance needed.

**Crosscheck method**: md5 hash comparison (gold standard).

---

### 4. dupradar

**Verdict: NEAR-IDENTICAL**

#### dupMatrix.txt

- **Line count**: 2,906 vs 2,906 (match)
- **Cell differences**: 0 / 40,670 (0.0%)
- **All 14 columns match exactly** — including count columns (allCounts, filteredCounts, allCountsMulti, filteredCountsMulti) and derived rate columns (dupRate, RPK, RPKM, etc.)

This is a major improvement from the previous RustQC build (March 4th), which had ~3% of cells differing due to multi-mapper handling differences.

#### intercept_slope.txt

Both tools now output the same format:

```
test - dupRadar Int (duprate at low read counts): <value>
test - dupRadar Sl (progression of the duplication rate): <value>
```

| Metric    | RustQC             | Upstream           | Relative diff |
| --------- | ------------------ | ------------------ | ------------- |
| Intercept | 0.0318589340787866 | 0.0318589339924277 | 0.00%         |
| Slope     | 1.6018851317990064 | 1.60188513430409   | 0.00%         |

The differences are at the 10th significant figure — well within float64 precision.

**Crosscheck method**: `tsvMatch` with tolerance on count columns + custom slope parsing with 10% relative tolerance (much wider than needed; could be tightened to 0.01%).

**Recommendation**: Could be tightened to exact match on matrix. Slope comparison could use tolerance 1e-6.

---

### 5. featurecounts

**Verdict: SIGNIFICANT DIFFERENCES**

#### Output format incompatibility

RustQC and upstream featureCounts produce fundamentally different output formats:

- **RustQC**: Biotype-level summary (`Biotype\tCount`, 15 lines including header)
- **Upstream**: Per-gene counts (`Geneid\tChr\tStart\tEnd\tStrand\tLength\ttest.bam`, 2,906 gene rows)

These cannot be directly compared — this is a known design difference, not a bug.

#### Summary file comparison

The `.tsv.summary` files share the same schema (`Status\ttest.bam`):

| Status                   | RustQC | Upstream | Difference | Rel diff   |
| ------------------------ | ------ | -------- | ---------- | ---------- |
| **Assigned**             | 40,109 | 42,725   | -2,616     | **6.1%**   |
| Unassigned_MultiMapping  | 5,496  | 5,496    | 0          | 0.0%       |
| Unassigned_NoFeatures    | 2,545  | 2,545    | 0          | 0.0%       |
| **Unassigned_Ambiguity** | 4,689  | 2,073    | +2,616     | **126.2%** |
| All other categories     | 0      | 0        | 0          | 0.0%       |

**Key finding**: RustQC classifies exactly 2,616 more reads as `Unassigned_Ambiguity` and correspondingly fewer as `Assigned`. This is exactly balanced — the total read count is the same.

This suggests RustQC uses a stricter overlap-resolution strategy than upstream featureCounts. When a read maps to overlapping features (ambiguous assignment), RustQC marks it as ambiguous while upstream featureCounts assigns it to one feature.

**Action needed**: Investigate RustQC's feature-assignment logic for ambiguous reads. The 6.1% difference in Assigned reads is significant and could affect downstream analyses that depend on accurate gene-level quantification.

**Crosscheck method**: Structural comparison (same labels, same schema, zero-value agreement).

---

### 6. inner_distance

**Verdict: IDENTICAL**

- **Read pairs**: 20,861 in both outputs (100% read ID overlap)
- **Per-read values**: All 20,861 read pairs have identical inner distance values, fragment types, and annotations
- **Frequency histogram**: All 100 bins match exactly

This is a major improvement from the previous build (March 4th), which had 2.2% of reads classified differently.

**Crosscheck method**: Read ID set comparison + exact `tsvMatch` on frequency histogram.

**Recommendation**: Could be tightened to md5 hash comparison (files may be byte-identical).

---

### 7. junction_annotation

**Verdict: IDENTICAL (after sorting)**

- **BED file**: 3,261 lines — identical content after sorting (different BAM iteration order produces different line order)
- **XLS file**: 3,262 lines (including header) — identical content after sorting data rows

Both tools produce the same junction classifications (annotated, partial_novel, complete_novel) with the same read counts.

**Crosscheck method**: `tsvMatch` with `tolerance: 0.0` on sorted lines.

**Recommendation**: Could potentially use md5 if both tools produced the same line order (would require RustQC to match upstream's iteration order).

---

### 8. junction_saturation

**Verdict: DETERMINISTIC VALUES MATCH, STOCHASTIC VARIANCE EXPECTED**

Junction saturation uses random subsampling at 5%, 10%, ..., 95%, 100% of reads. The 100% values (all reads used, no randomness) are deterministic and match exactly:

| Category                      | RustQC | Upstream | Match |
| ----------------------------- | ------ | -------- | ----- |
| Known junctions               | 2,944  | 2,944    | YES   |
| All junctions                 | 3,261  | 3,261    | YES   |
| Novel (splice-site) junctions | 317    | 317      | YES   |

Intermediate sampling points show expected stochastic variance:

| Category                        | Max relative diff across sampling points |
| ------------------------------- | ---------------------------------------- |
| Known junctions (y)             | 1.88%                                    |
| All junctions (z)               | 2.31%                                    |
| Novel splice-site junctions (w) | 9.78%                                    |

The novel splice-site category shows higher variance because the absolute counts are small (40-317), so each randomly excluded junction has a larger proportional effect.

Additionally, the R script's `pdf()` path differs (RustQC includes subdirectory prefix) — this is cosmetic and does not affect the saturation curves.

**Crosscheck method**: Compare only 100% values (deterministic) + verify R script structure.

---

### 9. read_distribution

**Verdict: SIGNIFICANT DIFFERENCES**

RustQC and upstream RSeQC `read_distribution.py` agree on total read counts but disagree substantially on how reads are distributed across genomic regions:

#### Matching values

| Metric        | Both tools                                   |
| ------------- | -------------------------------------------- |
| Total Reads   | 43,476                                       |
| Total Tags    | 68,660                                       |
| Introns       | 76,001,214 bases / 2,905 tags / 0.04 Tags/Kb |
| TSS_up_1kb    | 1,653,858 bases / 20 tags / 0.01 Tags/Kb     |
| TSS_up_5kb    | 7,587,428 bases / 79 tags / 0.01 Tags/Kb     |
| TSS_up_10kb   | 13,916,043 bases / 112 tags / 0.01 Tags/Kb   |
| TES_down_1kb  | 1,776,688 bases / 106 tags / 0.06 Tags/Kb    |
| TES_down_5kb  | 7,973,699 bases / 185 tags / 0.02 Tags/Kb    |
| TES_down_10kb | 14,299,868 bases / 210 tags / 0.01 Tags/Kb   |

#### Differing values

| Region                  | Metric      | RustQC    | Upstream  | Rel diff   |
| ----------------------- | ----------- | --------- | --------- | ---------- |
| **Total Assigned Tags** | —           | 68,134    | 66,693    | 2.2%       |
| **CDS_Exons**           | Total_bases | 4,044,880 | 1,785,696 | **126.5%** |
| **CDS_Exons**           | Tag_count   | 56,186    | 49,589    | 13.3%      |
| **CDS_Exons**           | Tags/Kb     | 13.89     | 27.77     | **50.0%**  |
| **5'UTR_Exons**         | Total_bases | 330,435   | 1,437,256 | **77.0%**  |
| **5'UTR_Exons**         | Tag_count   | 1,226     | 2,439     | **49.7%**  |
| **5'UTR_Exons**         | Tags/Kb     | 3.71      | 1.70      | **118.2%** |
| **3'UTR_Exons**         | Total_bases | 1,612,537 | 2,907,837 | **44.5%**  |
| **3'UTR_Exons**         | Tag_count   | 7,495     | 11,438    | **34.5%**  |
| **3'UTR_Exons**         | Tags/Kb     | 4.65      | 3.93      | 18.3%      |

**Root cause analysis**:

The differences concentrate in CDS, 5'UTR, and 3'UTR regions. The `Total_bases` columns differ by factors of 2-4x, indicating fundamentally different approaches to resolving overlapping gene features:

1. **CDS Total_bases**: RustQC reports 4.04M bp, upstream reports 1.79M bp (2.3x larger in RustQC). This suggests RustQC counts each transcript's CDS contribution separately (double-counting overlapping CDS regions), while upstream merges overlapping CDS into non-redundant intervals.

2. **5'UTR Total_bases**: RustQC reports 330K bp, upstream reports 1.44M bp (4.4x smaller in RustQC). This is the opposite direction — RustQC may be assigning some UTR bases to CDS instead.

3. **Tag_count differences**: Follow from the Total_bases differences — a read mapped to a region counted as CDS by RustQC but UTR by upstream will be counted differently.

4. **Introns and TSS/TES**: Match exactly, suggesting the disagreement is specifically in how exonic sub-regions (CDS vs UTR) are defined from the GTF annotation.

**Action needed**: Investigate how RustQC defines CDS, 5'UTR, and 3'UTR boundaries from the GTF file. The upstream RSeQC tool appears to merge overlapping exonic features into non-redundant intervals before classification, while RustQC may use a different strategy. This is the most significant algorithmic difference across all tools.

**Crosscheck method**: Structural comparison only (same row labels, same column format). Numeric comparison is not meaningful given the algorithmic differences.

---

## Recommendations for RustQC development

### High priority (algorithmic differences)

1. **featurecounts ambiguity resolution**: Investigate why RustQC classifies 2,616 more reads as ambiguous (6.1% of total). This directly affects gene quantification accuracy.

2. **read_distribution gene model resolution**: The CDS/UTR base-counting differs by 2-4x. Investigate how overlapping exonic features are merged and how reads are assigned to CDS vs UTR regions.

### Low priority (already excellent)

3. **dupradar slope precision**: Current float precision is at 10th significant figure. Consider whether matching upstream's exact format/precision matters.

4. **junction_saturation random seed**: Consider adding a fixed random seed option for reproducible intermediate sampling points (100% values already match).

### Potential tightening

Several crosscheck tests use wider tolerances than currently needed:

| Tool                | Current tolerance                | Could tighten to                 |
| ------------------- | -------------------------------- | -------------------------------- |
| dupradar matrix     | abs: 10, rel: 2%, skip rate cols | **Exact match (0 diffs found)**  |
| dupradar slope      | 10% relative                     | **0.01% relative**               |
| inner_distance      | read ID set match + 15% freq     | **Exact match or md5**           |
| junction_saturation | 100% values only                 | Already appropriate (stochastic) |

These could be tightened in a follow-up if desired, though keeping some tolerance margin protects against minor regressions.

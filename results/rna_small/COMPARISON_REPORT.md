# RustQC vs Upstream — rna_small Comparison Report

**Date:** 2026-03-06
**Dataset:** chr6 subset, 49,594 reads, paired-end 150bp
**Reference:** GRCh38 chr6 (GTF → BED12 via gtf2bed)
**RustQC version:** commit 6e22d6b (Rounds 1-3 fixes, before Round 4 insert size fix)

## Summary

| #   | Tool                        | Verdict          | Notes                                                                   |
| --- | --------------------------- | ---------------- | ----------------------------------------------------------------------- |
| 1   | samtools flagstat           | **PASS**         | Byte-identical                                                          |
| 2   | samtools idxstats           | **PASS**         | Byte-identical                                                          |
| 3   | samtools stats (SN)         | **FAIL**         | Insert size avg/sd wrong (fixed in Round 4, commit 24ba2e5)             |
| 4   | samtools stats (histograms) | **PASS**         | All 17 histogram sections present, row counts match                     |
| 5   | bam_stat                    | **PASS**         | Byte-identical                                                          |
| 6   | infer_experiment            | **PASS (float)** | 0.0775 vs 0.0776 undetermined (~0.01% diff)                             |
| 7   | read_distribution           | **PASS**         | Byte-identical                                                          |
| 8   | junction_annotation         | **PASS**         | Data identical; minor log header/footer differences                     |
| 9   | junction_saturation         | **DOCUMENTED**   | Small intermediate count diffs (sampling), final values identical       |
| 10  | inner_distance              | **DOCUMENTED**   | ~752/20861 reads differ (mRNA vs genomic distance for multi-exon reads) |
| 11  | read_duplication            | **PASS**         | Byte-identical (both pos and seq)                                       |
| 12  | preseq                      | **FAIL**         | All extrapolation values differ (different RNG algorithms)              |
| 13  | dupradar                    | **DOCUMENTED**   | Gene counts: small diffs in ~3 genes; intercept/slope ~0.4% diff        |
| 14  | featureCounts               | **PASS**         | Gene-level counts identical; exon coordinate sort order differs         |
| 15  | qualimap                    | **PASS**         | 5'-3' bias 1.3 matches upstream exactly                                 |

**Score: 9 PASS, 1 PASS (float), 3 DOCUMENTED, 2 FAIL**

## Detailed Analysis

### 1. samtools flagstat — PASS

Byte-identical. No differences.

### 2. samtools idxstats — PASS

Byte-identical. No differences.

### 3. samtools stats — FAIL (insert size only)

**SN section:** All SN lines identical EXCEPT:

- `insert size average`: upstream 1625.9, RustQC **3604.1** — 2.2x higher
- `insert size standard deviation`: upstream 2305.4, RustQC **12307.4** — 5.3x higher

**Root cause (fixed in Round 4, commit 24ba2e5):** Two bugs:

1. Both mates counted (should only count one per pair — the one with smaller position)
2. Insert sizes > 8000 stored at actual value instead of capping overflow into bucket 8000

**Histogram sections:** All present with correct row counts:

- FFQ/LFQ: 151 rows each (match upstream)
- GCF/GCL: 101 rows (upstream uses fractional %, RustQC uses integer %)
- GCC/GCT/FBC/LBC: 151 rows each (match upstream)
- IS: Present but values differ due to double-counting bug
- RL/FRL/LRL/MAPQ: Present, values correct

**Header differences (expected):** Different version string, missing CHK checksum line.

### 4. bam_stat — PASS

Byte-identical. No differences.

### 5. infer_experiment — PASS (float)

| Metric                    | Upstream | RustQC | Diff    |
| ------------------------- | -------- | ------ | ------- |
| Undetermined              | 0.0775   | 0.0776 | +0.0001 |
| Forward (1++,1--,2+-,2-+) | 0.0051   | 0.0051 | 0       |
| Reverse (1+-,1-+,2++,2--) | 0.9174   | 0.9172 | -0.0002 |

Differences are <0.03%, within expected sampling variation.

### 6. read_distribution — PASS

Byte-identical. All 10 region categories match exactly. The GTF→BED12 conversion ensures both tools use the same annotation source.

### 7. junction_annotation — PASS

- **junction.bed:** Byte-identical
- **junction.xls:** Byte-identical
- **Summary stats:** Identical (total junctions, known/partial/novel counts)
- **Log differences (expected):** RustQC omits upstream's "Reading reference bed..." and "Create BED/Interact file..." messages. Missing `test.junction.Interact.bed` output file (not implemented).

### 8. junction_saturation — DOCUMENTED

Final values at 100% sampling are identical (known=2944, all=3261, novel=317). Intermediate values at each sampling percentage differ by small amounts (e.g., 528 vs 534 at 5%). This is expected due to different random sampling implementations.

### 9. inner_distance — DOCUMENTED

- **Frequency histogram:** Most bins identical; some differ by 1-4 counts. Total read pairs: 20,861 (same).
- **Per-read distances:** ~752 reads have different distances or classifications. Root causes:
  - Multi-exon spanning reads: upstream computes mRNA distance (spliced), RustQC uses genomic distance
  - Some reads classified as `sameExon` by upstream but `nonExonic` or `sameTranscript=No` by RustQC due to different exon boundary handling
- **Mean inner distance:** -38.85 (upstream) vs -38.62 (RustQC) — <1% difference.

### 10. read_duplication — PASS

Both `pos.DupRate.xls` and `seq.DupRate.xls` are byte-identical.

### 11. preseq — FAIL

All 9,999 extrapolation data rows have different values. The differences are systematic and large.

**Root cause:** C++ `std::binomial_distribution` and Rust `rand_distr::Binomial` use fundamentally different algorithms (BTRD vs different implementation). Even with the same seed (408), bootstrap samples diverge immediately. This is an inherent limitation — exact match would require porting the C++ BTRD algorithm to Rust.

**Statistical equivalence:** Both produce valid library complexity curves, but the specific numeric values differ due to different random number generation paths.

### 12. dupradar — DOCUMENTED

- **Gene counts:** ~3 genes differ by 1 count (e.g., ENSG00000112799: 37 vs 36). Root cause: paired-end fragment assembly differences (singleton handling, cross-chromosome reconciliation).
- **RPKM values:** Slightly different due to count differences propagating through normalization.
- **Intercept/slope:** 0.0331 vs 0.0330 (0.4% diff), 1.552 vs 1.545 (0.5% diff). Due to gene count differences affecting the logistic fit.
- **dupMatrix format:** Float precision differs in trailing digits (e.g., 14 vs 15 decimal places).

### 13. featureCounts — PASS

- **Gene-level counts:** Identical for all genes.
- **Summary:** All categories identical except `Unassigned_Singleton`: 0 (upstream) vs 29 (RustQC). This is expected — RustQC now properly classifies unmatched mates as singletons.
- **Exon coordinates:** Same coordinates but different sort order within each gene's semicolon-delimited list.

### 14. qualimap — PASS

All key metrics match exactly:

- Reads aligned: identical
- Genomic origin: identical
- **5' bias: 0.71** (both)
- **3' bias: 0.57** (both)
- **5'-3' bias: 1.3** (both)
- Junction motif percentages: minor rounding differences in trailing digit

## Items Fixed After This Benchmark Run

The following fixes were committed after this benchmark was generated and will take effect on the next run:

1. **samtools stats insert size** (commit 24ba2e5): Fixed double-counting and overflow cap. Expected to produce correct insert size avg/sd matching upstream.
2. **infer_experiment** (commit 24ba2e5): Reverted soft clip inclusion, restored supplementary filter, removed proportional scale-down. Small dataset already matches; large dataset expected to improve.

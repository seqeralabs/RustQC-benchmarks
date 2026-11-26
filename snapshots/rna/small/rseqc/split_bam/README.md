# split_bam upstream references

Reference outputs of RSeQC 5.0.4 `split_bam.py` on `test-data/rna/small/test.bam`, used by
`tests/rna/rustqc/split_bam.nf.test` (byte-identical crosscheck). Each
`<bed name>/test.split_bam.txt` is the stdout of `split_bam.py`, the same file that the patched
`RSEQC_SPLITBAM` module (`modules/nf-core/rseqc/splitbam`, `-o ${meta.id}`) writes.

| Directory              | BED (`test-data/rna/small/`) | Total  | in    | ex     | junk |
| ---------------------- | ---------------------------- | -----: | ----: | -----: | ---: |
| `rRNA_GRCh37_chr6/`    | `rRNA_GRCh37_chr6.bed`       | 52,839 | 0     | 52,839 | 0    |
| `split_bam_synthetic/` | `split_bam_synthetic.bed`    | 52,839 | 9,114 | 43,725 | 0    |

## How they were generated

RSeQC 5.0.4 (`split_bam.py --version` prints `split_bam.py 5.0.4`) from a local Python
virtualenv, run in a directory where `test.bam`, `test.bam.bai` and the BED are symlinked under
their own names, the way Nextflow stages them:

```bash
split_bam.py -i test.bam -r rRNA_GRCh37_chr6.bed -o test > test.split_bam.txt
split_bam.py -i test.bam -r split_bam_synthetic.bed -o test > test.split_bam.txt
```

This is the `RSEQC_SPLITBAM` command line with no `ext.args`. To regenerate them from the
pipeline's container instead, run `nf-test test tests/rna/upstream/split_bam.nf.test` and copy
`test.split_bam.txt` from each test's work directory.

## The BED files

- `rRNA_GRCh37_chr6.bed`: the 107 chromosome 6 records of RSeQC's `hg19_rRNA.bed`
  (<https://sourceforge.net/projects/rseqc/files/BED/Human_Homo_sapiens/hg19_rRNA.bed.gz/download>),
  with the `chr` prefix removed to match the Ensembl GRCh37 names (`6`) in `test.bam`:

  ```bash
  gzip -dc hg19_rRNA.bed.gz | awk -F'\t' '$1=="chr6"' | sed 's/^chr6\t/6\t/' > rRNA_GRCh37_chr6.bed
  ```

  None of the test reads start in an rRNA locus, so every record is `ex`. This still checks a
  real rRNA BED and the chromosome name match.
- `split_bam_synthetic.bed`: 9 BED12 records chosen so that the `in` count is non-trivial. It
  has six multi-block transcripts taken from `chr6.bed` (reads that start in an intron are `ex`),
  a single-block region inside one of them, a gapped 4-block region, and a record on a contig
  that is not in the BAM (`chrUn_synthetic`).

## BAM outputs

The `.in.bam`, `.ex.bam` and `.junk.bam` files are not committed. When they were generated, RustQC
(`--split-bam-write-bams`) and `split_bam.py` wrote the same header and records for the
synthetic BED: `samtools view -h --no-PG` output was identical for all three files. The BGZF
bytes differ, so the nf-tests only check that the files exist.

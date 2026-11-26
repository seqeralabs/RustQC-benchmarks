# featureCounts -M / -O upstream references

Reference outputs of Subread featureCounts with `-M` (count multi-mapping alignments), `-O`
(assign reads to every overlapping feature) and both, on `test-data/rna/small/test.bam`. They are
used by `tests/rna/rustqc/featurecounts_mo.nf.test`, and the crosschecks there are exact.

```
featurecounts_mo/<mode>/<attribute>/test.featureCounts.tsv{,.summary}
  mode:      M (-M), O (-O), MO (-M -O)
  attribute: gene_id (-g gene_id), gene_biotype (-g gene_biotype)
```

| Mode     | Assigned | Unassigned_MultiMapping | Unassigned_NoFeatures | Unassigned_Ambiguity |
| -------- | -------: | ----------------------: | --------------------: | -------------------: |
| `-M`     |   46,885 |                       0 |                 2,719 |                3,235 |
| `-O`     |   44,698 |                   5,496 |                 2,645 |                    0 |
| `-M -O`  |   50,120 |                       0 |                 2,719 |                    0 |

(gene_id summaries. The gene_biotype summaries have the same totals, but more reads are Assigned
because two overlapping genes with the same biotype are not ambiguous at biotype level.)

## How they were generated

Subread featureCounts **v2.1.1**, built from source, run in a directory where `test.bam`,
`test.bam.bai` and `chr6.gtf.gz` are symlinked under their own names, the way Nextflow stages
them. The arguments are the `SUBREAD_FEATURECOUNTS` command line
(`featureCounts ${args} -p -T ${task.cpus} -a <gtf> -s <strand> -o ${prefix}.featureCounts.tsv <bam>`)
with the `ext.args` from `tests/rna/upstream/featurecounts_mo.config` and
`strandedness: 'reverse'`:

```bash
for mode in M O MO; do
  case $mode in M) mo="-M";; O) mo="-O";; MO) mo="-M -O";; esac
  for attr in gene_id gene_biotype; do
    # in featurecounts_mo/$mode/$attr/
    featureCounts -B -C -g $attr -t exon -s 2 $mo -p -T 2 -a chr6.gtf.gz -s 2 \
      -o test.featureCounts.tsv test.bam
  done
done
```

The `# Program:` header line of each `.tsv` records the exact command. The tests skip that line.

## Subread version

The pipeline's `SUBREAD_FEATURECOUNTS` container is Subread **2.0.6**, but these references
come from 2.1.1. On this dataset the default-mode outputs of the two versions agree; this was
not checked separately for `-M` / `-O`. If `tests/rna/upstream/featurecounts_mo.nf.test` ever
disagrees with these files, regenerate them from that test's outputs.

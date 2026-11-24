package checks

/**
 * featureCounts -g gene_id (per-gene counts). Moved from the
 * "featurecounts - crosscheck per-gene counts vs upstream" test in
 * tests/rna/rustqc/featurecounts.nf.test, with the crosscheck as tightened in PR #52.
 */
class FeatureCountsGeneId extends ToolCheck {

    String getName() { 'featurecounts_geneid' }

    List<String> getOutputs() {
        ['featurecounts/{sample}.featureCounts.tsv', 'featurecounts/{sample}.featureCounts.tsv.summary']
    }

    void crosscheck(Run run) {
        def actualLines   = run.lines(run.output(outputs[0]))
        def expectedLines = run.referenceLines('featurecounts_geneid/{sample}.featureCounts.tsv')

        // ── Per-gene counts ───────────────────────────────────────────
        // Columns: 0=Geneid, 1=Chr, 2=Start, 3=End, 4=Strand, 5=Length, 6=count.
        // Measured on hs_chr6_pe_rev: Geneid, Length and count identical for all 2,905
        // genes. Compared exactly; the '#' line is the program/command header.
        CompareUtils.tsvMatch(actualLines, expectedLines, [
            skipColumns:  [1, 2, 3, 4] as Set,  // checked as exon sets below
            skipPrefixes: ['#'],
        ])

        // KNOWN DISCREPANCY (cosmetic): Chr/Start/End/Strand hold one ';'-separated entry
        // per exon. RustQC lists exons in GTF order, Subread sorts them by position. Compare
        // each gene's exons as a sorted list of chr:start:end:strand.
        def exonSets = { List<String> lines ->
            lines.findAll { !it.startsWith('#') && !it.startsWith('Geneid') }.collectEntries { line ->
                def cols  = line.split('\t')
                def parts = (1..4).collect { cols[it].split(';') }
                def exons = (0..<parts[0].size()).collect { i -> parts.collect { it[i] }.join(':') }
                [(cols[0]): exons.sort()]
            }
        }
        def actualExons   = exonSets(actualLines)
        def expectedExons = exonSets(expectedLines)
        def exonDiffs = expectedExons.keySet().findAll { actualExons[it] != expectedExons[it] }
        assert !exonDiffs : \
            "Exon annotation differs beyond ordering for ${exonDiffs.size()} genes, e.g. ${exonDiffs.take(5)}"

        // ── Per-gene summary ──────────────────────────────────────────
        // Measured on hs_chr6_pe_rev: byte-identical to featureCounts -g gene_id (the
        // header echoes the BAM name, normalised by Run).
        run.assertIdentical(outputs[1], 'featurecounts_geneid/{sample}.featureCounts.tsv.summary',
            'featureCounts.tsv.summary')
    }

    // The summary in full; the ~3,000-line count table as an md5 of everything but the
    // '# Program:' line, which embeds the RustQC version and command line.
    Object regressionView(Run run) {
        def counts = run.lines(run.output(outputs[0])).findAll { !it.startsWith('#') }
        return [
            counts_md5: Run.md5(counts),
            genes:      counts.size() - 1,
            summary:    run.lines(run.output(outputs[1])),
        ]
    }
}

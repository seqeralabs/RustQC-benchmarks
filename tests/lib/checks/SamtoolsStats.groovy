package checks

/** samtools stats. Moved from tests/rna/rustqc/stats.nf.test (crosscheck as tightened in PR #52). */
class SamtoolsStats extends ToolCheck {

    String getName() { 'samtools_stats' }

    List<String> getOutputs() { ['samtools/{sample}.stats'] }

    Map<String, String> getReferences() { ['samtools/{sample}.stats': 'samtools/{sample}.stats'] }

    // Every data line (CHK checksums, SN summary, all histograms, the GC-depth section) identical
    // to samtools stats 1.22.1 (RustQC #198). Only '#' comment lines differ (version string,
    // command line, section help text), so those are skipped and everything else must match
    // exactly, nothing trimmed.
    void crosscheck(Run run) {
        ExactCompare.exactMatch(
            run.lines(run.output(outputs[0])),
            run.referenceLines('samtools/{sample}.stats'),
            ['#']
        )
    }

    // The SN summary in full; every other data line as an md5.
    Object regressionView(Run run) {
        def data = run.lines(run.output(outputs[0])).findAll { !it.startsWith('#') }
        return [
            summary:  data.findAll { it.startsWith('SN\t') },
            data_md5: Run.md5(data),
            rows:     data.size(),
        ]
    }
}

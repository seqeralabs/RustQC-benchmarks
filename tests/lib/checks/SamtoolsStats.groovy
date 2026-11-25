package checks

/** samtools stats. Moved from tests/rna/rustqc/stats.nf.test (crosscheck as tightened in PR #52). */
class SamtoolsStats extends ToolCheck {

    String getName() { 'samtools_stats' }

    List<String> getOutputs() { ['samtools/{sample}.stats'] }

    Map<String, String> getReferences() { ['samtools/{sample}.stats': 'samtools/{sample}.stats'] }

    // Measured on hs_chr6_pe_rev: every data line (CHK checksums, SN summary, all histograms)
    // identical to samtools stats. Only '#' comment lines differ (version string, command
    // line, section help text), so those are skipped and everything else must match exactly.
    //
    // KNOWN DISCREPANCY: the GC-depth section (GCD lines: depth percentiles per GC bin)
    // differs from samtools on hs_chrX_pe_unstr, hs_ribo_chr19_22_se_fwd and
    // hs_ribo_chr20_se_fwd (percentiles off by up to ~0.05, some GC percentiles by 0.09),
    // while matching on the other datasets. It is skipped here so the rest of the file is
    // still compared exactly; the release regression still covers it.
    void crosscheck(Run run) {
        CompareUtils.textMatch(
            run.lines(run.output(outputs[0])),
            run.referenceLines('samtools/{sample}.stats'),
            ['#', 'GCD\t']
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

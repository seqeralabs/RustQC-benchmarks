package checks

/** RSeQC read_distribution.py. Moved from tests/rna/rustqc/read_distribution.nf.test (crosscheck as tightened in PR #52). */
class ReadDistribution extends ToolCheck {

    String getName() { 'read_distribution' }

    List<String> getOutputs() { ['rseqc/read_distribution/{sample}.read_distribution.txt'] }

    Map<String, String> getReferences() {
        ['rseqc/read_distribution/read_distribution.txt': 'rseqc/read_distribution/{sample}.read_distribution.txt']
    }

    // Measured on hs_chr6_pe_rev: every line and value identical to RSeQC read_distribution.py
    // (run on the GTF2BED-derived BED, as the pipeline does). textMatch trims each line, as the
    // committed reference of that dataset had its trailing whitespace stripped.
    void crosscheck(Run run) {
        CompareUtils.textMatch(
            run.lines(run.output(outputs[0])),
            run.referenceLines('rseqc/read_distribution/read_distribution.txt')
        )
    }

    Object regressionView(Run run) {
        return run.lines(run.output(outputs[0])).findAll { it.trim() }.collect { it.trim() }
    }
}

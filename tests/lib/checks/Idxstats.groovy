package checks

/** samtools idxstats. Moved from tests/rna/rustqc/idxstats.nf.test (crosscheck as tightened in PR #52). */
class Idxstats extends ToolCheck {

    String getName() { 'idxstats' }

    List<String> getOutputs() { ['samtools/{sample}.idxstats'] }

    Map<String, String> getReferences() { ['samtools/{sample}.idxstats': 'samtools/{sample}.idxstats'] }

    // Measured on hs_chr6_pe_rev: byte-identical to samtools idxstats.
    void crosscheck(Run run) {
        run.assertIdentical(outputs[0], 'samtools/{sample}.idxstats', 'idxstats')
    }

    Object regressionView(Run run) {
        return run.lines(run.output(outputs[0]))
    }
}

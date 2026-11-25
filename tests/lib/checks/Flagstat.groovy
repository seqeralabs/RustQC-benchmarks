package checks

/** samtools flagstat. Moved from tests/rna/rustqc/flagstat.nf.test (crosscheck as tightened in PR #52). */
class Flagstat extends ToolCheck {

    String getName() { 'flagstat' }

    List<String> getOutputs() { ['samtools/{sample}.flagstat'] }

    Map<String, String> getReferences() { ['samtools/{sample}.flagstat': 'samtools/{sample}.flagstat'] }

    // Measured on hs_chr6_pe_rev: byte-identical to samtools flagstat.
    void crosscheck(Run run) {
        run.assertIdentical(outputs[0], 'samtools/{sample}.flagstat', 'flagstat')
    }

    Object regressionView(Run run) {
        return run.lines(run.output(outputs[0]))
    }
}

package checks

/** RSeQC infer_experiment.py. Moved from tests/rna/rustqc/infer_experiment.nf.test (crosscheck as tightened in PR #52). */
class InferExperiment extends ToolCheck {

    String getName() { 'infer_experiment' }

    List<String> getOutputs() { ['rseqc/infer_experiment/{sample}.infer_experiment.txt'] }

    // Measured on hs_chr6_pe_rev: byte-identical to RSeQC infer_experiment.py, including the
    // "This is PairEnd Data" line, so the whole file is compared.
    void crosscheck(Run run) {
        run.assertIdentical(outputs[0], 'rseqc/infer_experiment/infer_experiment.txt', 'infer_experiment.txt')
    }

    Object regressionView(Run run) {
        return run.lines(run.output(outputs[0])).findAll { line -> line.trim() != '' }
    }
}

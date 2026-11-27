package checks

/** dupRadar. Moved from tests/rna/rustqc/dupradar.nf.test (crosscheck as tightened in PR #52). */
class DupRadar extends ToolCheck {

    String getName() { 'dupradar' }

    List<String> getOutputs() { ['dupradar/{sample}_dupMatrix.txt', 'dupradar/{sample}_intercept_slope.txt'] }

    Map<String, String> getReferences() {
        [
            'dupradar/dupMatrix.txt':       'dupradar/gene_data/{sample}_dupMatrix.txt',
            'dupradar/intercept_slope.txt': 'dupradar/intercepts_slope/{sample}_intercept_slope.txt',
        ]
    }

    void crosscheck(Run run) {
        // Measured on hs_chr6_pe_rev: dupMatrix byte-identical to dupRadar (all 14 columns,
        // counts and the derived dupRate/RPK/RPKM columns in R's number formatting).
        run.assertIdentical(outputs[0], 'dupradar/dupMatrix.txt', 'dupMatrix.txt')

        // intercept/slope: same labels; 1e-8 relative (RustQC's own logistic fit vs R's glm()),
        // or 1e-9 absolute for a degenerate fit, which is every dataset but hs_chr6_pe_rev: with no
        // read marked duplicate the duplication rate is 0 for every gene, so the true intercept is 0
        // and both tools return a round-off residue (RustQC ~1e-10, R ~3e-12). See
        // ExactCompare.dupradarFitMatch.
        ExactCompare.dupradarFitMatch(
            run.lines(run.output(outputs[1])),
            run.referenceLines('dupradar/intercept_slope.txt')
        )
    }

    // dupMatrix as md5 + row count; intercept/slope in full.
    Object regressionView(Run run) {
        def matrix = run.lines(run.output(outputs[0]))
        return [
            matrix_md5: Run.md5(matrix),
            genes:      matrix.size() - 1,
            slope:      run.lines(run.output(outputs[1])),
        ]
    }
}

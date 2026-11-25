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

        // intercept/slope: same labels; RustQC's logistic fit and R's glm() agree to ~2.6e-9
        // relative (measured), so allow 1e-7 relative.
        //
        // KNOWN DISCREPANCY (degenerate fit): with no read marked duplicate (every dataset
        // except hs_chr6_pe_rev), the duplication rate is 0 for every gene and the logistic
        // fit has no finite solution. R's glm() stops at its iteration limit with an intercept
        // of ~2.9e-12, RustQC's fit stops at ~1e-10. Both mean "no duplication", so an
        // upstream value below 1e-9 only requires RustQC's to be below 1e-9 too.
        def parse = { List<String> lines ->
            lines.findAll { it.trim() }.collectEntries { line ->
                def i = line.lastIndexOf(':')
                [(line.substring(0, i)): line.substring(i + 1).trim() as Double]
            }
        }
        def actual   = parse(run.lines(run.output(outputs[1])))
        def expected = parse(run.referenceLines('dupradar/intercept_slope.txt'))
        assert actual.keySet() == expected.keySet() : \
            "intercept_slope labels differ: RustQC=${actual.keySet()} upstream=${expected.keySet()}"
        expected.each { label, value ->
            if (Math.abs(value) < 1e-9) {
                assert Math.abs(actual[label]) < 1e-9 : \
                    "${label}: RustQC=${actual[label]} upstream=${value} (upstream fit is degenerate, RustQC's is not)"
                return
            }
            def relDiff = Math.abs(actual[label] - value) / Math.abs(value)
            assert relDiff < 1e-7 : "${label}: RustQC=${actual[label]} upstream=${value} (relative diff ${relDiff})"
        }
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

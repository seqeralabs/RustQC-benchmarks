package checks

/** RSeQC read_duplication.py. Moved from tests/rna/rustqc/read_duplication.nf.test. */
class ReadDuplication extends ToolCheck {

    String getName() { 'read_duplication' }

    List<String> getOutputs() {
        ['rseqc/read_duplication/{sample}.pos.DupRate.xls', 'rseqc/read_duplication/{sample}.seq.DupRate.xls']
    }

    Map<String, String> getReferences() {
        [
            'rseqc/read_duplication/pos.DupRate.xls': 'rseqc/read_duplication/xls/{sample}.pos.DupRate.xls',
            'rseqc/read_duplication/seq.DupRate.xls': 'rseqc/read_duplication/xls/{sample}.seq.DupRate.xls',
        ]
    }

    // Measured on hs_chr6_pe_rev: both histograms byte-identical to RSeQC read_duplication.py.
    void crosscheck(Run run) {
        run.assertIdentical(outputs[0], 'rseqc/read_duplication/pos.DupRate.xls', 'pos.DupRate.xls')
        run.assertIdentical(outputs[1], 'rseqc/read_duplication/seq.DupRate.xls', 'seq.DupRate.xls')
    }

    // md5 + row count: the histograms grow with the duplication level of the input.
    Object regressionView(Run run) {
        return outputs.collectEntries { rel ->
            def lines = run.lines(run.output(rel))
            [(rel.contains('.pos.') ? 'pos' : 'seq'): [md5: Run.md5(lines), rows: lines.size()]]
        }
    }
}

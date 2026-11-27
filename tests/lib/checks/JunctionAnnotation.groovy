package checks

/** RSeQC junction_annotation.py. Moved from tests/rna/rustqc/junction_annotation.nf.test (crosscheck as tightened in PR #52). */
class JunctionAnnotation extends ToolCheck {

    String getName() { 'junction_annotation' }

    List<String> getOutputs() {
        ['rseqc/junction_annotation/{sample}.junction.bed', 'rseqc/junction_annotation/{sample}.junction.xls']
    }

    Map<String, String> getReferences() {
        [
            'rseqc/junction_annotation/junction.bed': 'rseqc/junction_annotation/bed/{sample}.junction.bed',
            'rseqc/junction_annotation/junction.xls': 'rseqc/junction_annotation/xls/{sample}.junction.xls',
        ]
    }

    // Measured on hs_chr6_pe_rev: BED and XLS byte-identical to RSeQC junction_annotation.py,
    // row order included.
    void crosscheck(Run run) {
        run.assertIdentical(outputs[0], 'rseqc/junction_annotation/junction.bed', 'junction.bed')
        run.assertIdentical(outputs[1], 'rseqc/junction_annotation/junction.xls', 'junction.xls')
    }

    Object regressionView(Run run) {
        return outputs.collectEntries { rel ->
            def lines = run.lines(run.output(rel))
            [(rel.tokenize('.')[-1]): [md5: Run.md5(lines), rows: lines.size()]]
        }
    }
}

package checks

/** RSeQC bam_stat.py. Moved from tests/rna/rustqc/bam_stat.nf.test (crosscheck as tightened in PR #52). */
class BamStat extends ToolCheck {

    String getName() { 'bam_stat' }

    List<String> getOutputs() { ['rseqc/bam_stat/{sample}.bam_stat.txt'] }

    // Measured on hs_chr6_pe_rev: byte-identical to RSeQC bam_stat.py. Neither file contains
    // the "Load BAM"/"processing" progress lines, so nothing needs filtering.
    void crosscheck(Run run) {
        run.assertIdentical(outputs[0], 'rseqc/bam_stat/bam_stat.txt', 'bam_stat.txt')
    }

    Object regressionView(Run run) {
        return run.lines(run.output(outputs[0])).findAll { line ->
            !line.startsWith('Load BAM') && !line.startsWith('processing') && line.trim() != ''
        }
    }
}

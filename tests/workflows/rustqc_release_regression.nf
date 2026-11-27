/*
 * Test-only workflow for tests/rna/regression/release.nf.test.
 *
 * Runs RUSTQC_RNA twice on the same inputs: once in the CURRENT subworkflow (container
 * params.rustqc_image, as everywhere else) and once in BASELINE, whose container is set to
 * the last released image by tests/rna/regression/release.config. Using two subworkflows
 * instead of process aliases keeps the process name RUSTQC_RNA, so the ext.args in
 * conf/modules.config (fixed seeds) apply to both runs unchanged.
 */

include { RUSTQC_RNA } from '../../modules/local/rustqc_rna'

workflow CURRENT {
    take:
    ch_bam  // [ meta, bam, bai ]
    ch_gtf  // gtf

    main:
    RUSTQC_RNA(ch_bam, ch_gtf)

    emit:
    results  = RUSTQC_RNA.out.results
    versions = RUSTQC_RNA.out.versions
}

workflow BASELINE {
    take:
    ch_bam  // [ meta, bam, bai ]
    ch_gtf  // gtf

    main:
    RUSTQC_RNA(ch_bam, ch_gtf)

    emit:
    results  = RUSTQC_RNA.out.results
    versions = RUSTQC_RNA.out.versions
}

workflow RUSTQC_RELEASE_REGRESSION {
    take:
    ch_bam  // [ meta, bam, bai ]
    ch_gtf  // gtf

    main:
    CURRENT(ch_bam, ch_gtf)
    BASELINE(ch_bam, ch_gtf)

    emit:
    current           = CURRENT.out.results
    baseline          = BASELINE.out.results
    current_versions  = CURRENT.out.versions
    baseline_versions = BASELINE.out.versions
}

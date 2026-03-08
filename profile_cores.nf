#!/usr/bin/env nextflow
/*
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    profile_cores.nf — CPU core profiling pipeline for RustQC
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    Runs RustQC RNA with varying CPU counts to benchmark performance scaling.
    Each core count is run sequentially (maxForks 1) with configurable replicates.

    Usage:
        nextflow run profile_cores.nf -profile docker \
            --bam input.bam --gtf genes.gtf \
            --cores '1,2,4,8,16,32' --replicates 3 \
            --outdir results_profile
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
*/

nextflow.enable.dsl = 2

/*
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    IMPORT MODULES
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
*/

include { SAMTOOLS_INDEX     } from './modules/nf-core/samtools/index/main'
include { RUSTQC_RNA_PROFILE } from './modules/local/rustqc_rna_profile'

/*
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    WORKFLOW
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
*/

workflow {

    // ---- Build meta map from params (same as main workflow) ----
    def meta = [
        id:            params.sample_id ?: file(params.bam).baseName,
        single_end:    !params.paired,
        strandedness:  params.strandedness ?: 'unstranded',
    ]

    def bam_file = file(params.bam, checkIfExists: true)
    def bai_file = params.bai ? file(params.bai, checkIfExists: true) : null
    def gtf_file = params.gtf ? file(params.gtf, checkIfExists: true) : null

    if (!gtf_file) {
        error "ERROR: --gtf is required for RustQC RNA profiling"
    }

    // ---- BAM channel ----
    ch_bam = channel.value([ meta, bam_file ])

    // ---- BAM + BAI channel (index if BAI not provided) ----
    if (bai_file) {
        ch_bam_bai = channel.value([ meta, bam_file, bai_file ])
    } else {
        SAMTOOLS_INDEX(ch_bam)
        ch_bam_bai = SAMTOOLS_INDEX.out.bai
            .map{ _meta, bai -> [ meta, bam_file, bai ] }
            .first()  // convert queue channel to value channel so it repeats for all runs
    }

    // ---- Parse cores param into channel of integers ----
    ch_cores = channel
        .fromList(params.cores.toString().tokenize(',').collect{ it.trim() as int })

    // ---- Generate replicate numbers ----
    ch_replicates = channel
        .fromList(1..params.replicates)

    // ---- Combine: each (cores, replicate) pair ----
    ch_runs = ch_cores
        .combine(ch_replicates)

    // ---- Run RustQC for each (cores, replicate) combination ----
    RUSTQC_RNA_PROFILE(
        ch_bam_bai,
        gtf_file,
        ch_runs.map{ cores, _rep -> cores },
        ch_runs.map{ _cores, rep -> rep },
    )
}

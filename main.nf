#!/usr/bin/env nextflow
/*
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    seqeralabs/rustqc-benchmarks
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    Github : https://github.com/seqeralabs/RustQC-benchmarks
----------------------------------------------------------------------------------------
*/

nextflow.enable.dsl = 2

/*
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    IMPORT WORKFLOWS
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
*/

include { RUSTQC_BENCHMARKS } from './workflows/rustqc-benchmarks'
include { FULLSIZE          } from './workflows/fullsize'
include { SAMTOOLS_INDEX    } from './modules/nf-core/samtools/index/main'

/*
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    RUN MAIN WORKFLOW
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
*/

workflow {
    if (params.fullsize) {
        // Full-size run (tests/DATASETS.md#full-size-tier): FASTQ -> BAM -> RustQC and the
        // upstream tools -> comparison. Run on Seqera Platform before each RustQC release.
        FULLSIZE()
    } else {
        // --bam is required here, but not with --fullsize (which aligns FASTQ files)
        if (!params.bam) {
            error("--bam is required (unless --fullsize is set)")
        }

        //
        // Build input channels from params
        //
        def meta = [
            id:            params.sample_id ?: file(params.bam).baseName,
            single_end:    !params.paired,
            strandedness:  params.strandedness ?: 'unstranded',
        ]

        def bam_file = file(params.bam, checkIfExists: true)
        def bai_file = params.bai ? file(params.bai, checkIfExists: true) : null
        def gtf_file = file(params.gtf, checkIfExists: true)

        ch_bam = channel.value([ meta, bam_file ])

        //
        // Index the BAM if no BAI provided
        //
        if (bai_file) {
            ch_bam_bai = channel.value([ meta, bam_file, bai_file ])
        } else {
            SAMTOOLS_INDEX(ch_bam)
            ch_bam_bai = ch_bam
                .combine(SAMTOOLS_INDEX.out.bai.map{ _m, bai -> bai })
                .map{ m, bam, bai -> [ m, bam, bai ] }
        }

        RUSTQC_BENCHMARKS(ch_bam_bai, channel.value(gtf_file))
    }
}

/*
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    THE END
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
*/

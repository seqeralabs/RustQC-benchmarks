/*
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    IMPORT MODULES / SUBWORKFLOWS / FUNCTIONS
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
*/
include { softwareVersionsToYAML } from '../subworkflows/nf-core/utils_nfcore_pipeline'

// Local modules
include { RUSTQC_RNA             } from '../modules/local/rustqc_rna'

// nf-core modules: upstream reference tools
include { DUPRADAR                   } from '../modules/nf-core/dupradar/main'
include { SUBREAD_FEATURECOUNTS      } from '../modules/nf-core/subread/featurecounts/main'
include { RSEQC_BAMSTAT              } from '../modules/nf-core/rseqc/bamstat/main'
include { RSEQC_INFEREXPERIMENT      } from '../modules/nf-core/rseqc/inferexperiment/main'
include { RSEQC_READDUPLICATION      } from '../modules/nf-core/rseqc/readduplication/main'
include { RSEQC_READDISTRIBUTION     } from '../modules/nf-core/rseqc/readdistribution/main'
include { RSEQC_JUNCTIONANNOTATION   } from '../modules/nf-core/rseqc/junctionannotation/main'
include { RSEQC_JUNCTIONSATURATION   } from '../modules/nf-core/rseqc/junctionsaturation/main'
include { RSEQC_INNERDISTANCE        } from '../modules/nf-core/rseqc/innerdistance/main'
include { SAMTOOLS_FLAGSTAT          } from '../modules/nf-core/samtools/flagstat/main'
include { SAMTOOLS_IDXSTATS          } from '../modules/nf-core/samtools/idxstats/main'
include { SAMTOOLS_STATS             } from '../modules/nf-core/samtools/stats/main'

/*
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    RUN MAIN WORKFLOW
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
*/

workflow RUSTQC_BENCHMARKS {

    main:

    //
    // Build input channels from params
    //
    def meta = [
        id:            params.sample_id ?: file(params.bam).baseName,
        single_end:    !params.paired,
        strandedness:  params.strandedness ?: 'unstranded',
    ]

    def bam_file = file(params.bam, checkIfExists: true)
    def bai_file = file(params.bai, checkIfExists: true)
    def gtf_file = file(params.gtf, checkIfExists: true)
    def bed_file = file(params.bed, checkIfExists: true)

    ch_versions = channel.empty()

    //
    // MODULE: RustQC RNA (single-pass, all tools)
    //
    if (params.run_rustqc) {
        RUSTQC_RNA(
            channel.value([ meta, bam_file, bai_file ]),
            gtf_file,
        )
        ch_versions = ch_versions.mix(RUSTQC_RNA.out.versions)
    }

    //
    // MODULES: Upstream reference tools
    //
    if (params.run_upstream) {
        // Use channel.value() so all processes can consume the same channel
        ch_bam_bai = channel.value([ meta, bam_file, bai_file ])
        ch_bam     = channel.value([ meta, bam_file ])

        // dupRadar: tuple(meta, bam) + tuple(meta, gtf)
        DUPRADAR(ch_bam, channel.value([ meta, gtf_file ]))

        // featureCounts: tuple(meta, bams, annotation) — all in one tuple
        SUBREAD_FEATURECOUNTS(channel.value([ meta, bam_file, gtf_file ]))

        // RSeQC tools needing BAM + BAI only
        RSEQC_BAMSTAT(ch_bam_bai)
        RSEQC_READDUPLICATION(ch_bam_bai)

        // RSeQC tools needing BAM + BAI + BED
        RSEQC_INFEREXPERIMENT(ch_bam_bai, bed_file)
        RSEQC_READDISTRIBUTION(ch_bam_bai, bed_file)
        RSEQC_JUNCTIONANNOTATION(ch_bam_bai, bed_file)
        RSEQC_JUNCTIONSATURATION(ch_bam_bai, bed_file)
        RSEQC_INNERDISTANCE(ch_bam_bai, bed_file)

        // samtools tools
        SAMTOOLS_FLAGSTAT(ch_bam_bai)
        SAMTOOLS_IDXSTATS(ch_bam_bai)

        // samtools stats: needs tuple(meta, bam, bai) + tuple(meta, fasta) — fasta optional
        SAMTOOLS_STATS(ch_bam_bai, channel.value([ meta, [] ]))

        ch_versions = ch_versions.mix(DUPRADAR.out.versions)
    }

    //
    // Collate and save software versions
    //
    softwareVersionsToYAML(ch_versions)
        .collectFile(
            storeDir: "${params.outdir}/pipeline_info",
            name:  'rustqc_benchmarks_software_versions.yml',
            sort: true,
            newLine: true
        )

    emit:
    versions = ch_versions
}

/*
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    THE END
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
*/

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

    ch_versions = Channel.empty()

    //
    // Build input channels from params
    //
    def meta = [
        id:            params.sample_id ?: file(params.bam).baseName,
        single_end:    !params.paired,
        strandedness:  params.strandedness ?: 'unstranded',
    ]

    // BAM + BAI tuple (for rseqc, samtools, rustqc)
    ch_bam_bai = Channel.of([ meta, file(params.bam, checkIfExists: true), file(params.bai, checkIfExists: true) ])

    // BAM-only tuple (for dupradar)
    ch_bam = ch_bam_bai.map { m, bam, bai -> [ m, bam ] }

    // Annotation files
    ch_gtf = Channel.value(file(params.gtf, checkIfExists: true))
    ch_bed = Channel.value(file(params.bed, checkIfExists: true))

    //
    // MODULE: RustQC RNA (single-pass, all tools)
    //
    if (params.run_rustqc) {
        RUSTQC_RNA(ch_bam_bai, ch_gtf)
        ch_versions = ch_versions.mix(RUSTQC_RNA.out.versions)
    }

    //
    // MODULES: Upstream reference tools (optional, for snapshot regeneration)
    //
    if (params.run_upstream) {

        // dupRadar: tuple(meta, bam) + tuple(meta, gtf)
        ch_meta_gtf = ch_bam.map { m, bam -> [ m, file(params.gtf) ] }
        DUPRADAR(ch_bam, ch_meta_gtf)
        ch_versions = ch_versions.mix(DUPRADAR.out.versions)

        // featureCounts: tuple(meta, bams, annotation) — all in one tuple
        ch_featurecounts_input = ch_bam.map { m, bam -> [ m, bam, file(params.gtf) ] }
        SUBREAD_FEATURECOUNTS(ch_featurecounts_input)
        ch_versions = ch_versions.mix(SUBREAD_FEATURECOUNTS.out.versions)

        // RSeQC tools needing BAM + BAI only
        RSEQC_BAMSTAT(ch_bam_bai)
        ch_versions = ch_versions.mix(RSEQC_BAMSTAT.out.versions)

        RSEQC_READDUPLICATION(ch_bam_bai)
        ch_versions = ch_versions.mix(RSEQC_READDUPLICATION.out.versions)

        // RSeQC tools needing BAM + BAI + BED
        RSEQC_INFEREXPERIMENT(ch_bam_bai, ch_bed)
        ch_versions = ch_versions.mix(RSEQC_INFEREXPERIMENT.out.versions)

        RSEQC_READDISTRIBUTION(ch_bam_bai, ch_bed)
        ch_versions = ch_versions.mix(RSEQC_READDISTRIBUTION.out.versions)

        RSEQC_JUNCTIONANNOTATION(ch_bam_bai, ch_bed)
        ch_versions = ch_versions.mix(RSEQC_JUNCTIONANNOTATION.out.versions)

        RSEQC_JUNCTIONSATURATION(ch_bam_bai, ch_bed)
        ch_versions = ch_versions.mix(RSEQC_JUNCTIONSATURATION.out.versions)

        RSEQC_INNERDISTANCE(ch_bam_bai, ch_bed)
        ch_versions = ch_versions.mix(RSEQC_INNERDISTANCE.out.versions)

        // samtools: tuple(meta, bam, bai) + tuple(meta, fasta)
        SAMTOOLS_FLAGSTAT(ch_bam_bai)
        ch_versions = ch_versions.mix(SAMTOOLS_FLAGSTAT.out.versions)

        SAMTOOLS_IDXSTATS(ch_bam_bai)
        ch_versions = ch_versions.mix(SAMTOOLS_IDXSTATS.out.versions)

        // samtools stats: needs tuple(meta, bam, bai) + tuple(meta, fasta) — fasta optional
        ch_empty_fasta = ch_bam.map { m, bam -> [ m, [] ] }
        SAMTOOLS_STATS(ch_bam_bai, ch_empty_fasta)
        ch_versions = ch_versions.mix(SAMTOOLS_STATS.out.versions)
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

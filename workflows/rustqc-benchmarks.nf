/*
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    IMPORT MODULES / SUBWORKFLOWS / FUNCTIONS
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
*/
include { paramsSummaryMap        } from 'plugin/nf-schema'
include { paramsSummaryMultiqc;
          softwareVersionsToYAML } from '../subworkflows/nf-core/utils_nfcore_pipeline'

// Local modules
include { GTF2BED                } from '../modules/local/gtf2bed'
include { RUSTQC_RNA             } from '../modules/local/rustqc_rna'

// nf-core modules
include { MULTIQC                                  } from '../modules/nf-core/multiqc/main'

// nf-core modules: upstream reference tools
include { GUNZIP as GUNZIP_GTF                    } from '../modules/nf-core/gunzip/main'
include { DUPRADAR                                } from '../modules/nf-core/dupradar/main'
include { PRESEQ_LCEXTRAP                         } from '../modules/nf-core/preseq/lcextrap/main'
include { QUALIMAP_RNASEQ                         } from '../modules/nf-core/qualimap/rnaseq/main'
include { SUBREAD_FEATURECOUNTS                   } from '../modules/nf-core/subread/featurecounts/main'
include { RSEQC_BAMSTAT                           } from '../modules/nf-core/rseqc/bamstat/main'
include { RSEQC_INFEREXPERIMENT                   } from '../modules/nf-core/rseqc/inferexperiment/main'
include { RSEQC_READDUPLICATION                   } from '../modules/nf-core/rseqc/readduplication/main'
include { RSEQC_READDISTRIBUTION                  } from '../modules/nf-core/rseqc/readdistribution/main'
include { RSEQC_JUNCTIONANNOTATION                } from '../modules/nf-core/rseqc/junctionannotation/main'
include { RSEQC_JUNCTIONSATURATION                } from '../modules/nf-core/rseqc/junctionsaturation/main'
include { RSEQC_INNERDISTANCE                     } from '../modules/nf-core/rseqc/innerdistance/main'
include { SAMTOOLS_FLAGSTAT                       } from '../modules/nf-core/samtools/flagstat/main'
include { SAMTOOLS_IDXSTATS                       } from '../modules/nf-core/samtools/idxstats/main'
include { SAMTOOLS_INDEX                          } from '../modules/nf-core/samtools/index/main'
include { SAMTOOLS_SORT as SAMTOOLS_SORT_QUALIMAP } from '../modules/nf-core/samtools/sort/main'
include { SAMTOOLS_STATS                          } from '../modules/nf-core/samtools/stats/main'

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
    def bai_file = params.bai ? file(params.bai, checkIfExists: true) : null
    def gtf_file = params.gtf ? file(params.gtf, checkIfExists: true) : null
    def bed_file = params.bed ? file(params.bed, checkIfExists: true) : null

    ch_versions       = channel.empty()
    ch_multiqc_files  = channel.empty()
    ch_bam = channel.value([ meta, bam_file ])

    //
    // Index the BAM if no BAI provided
    //
    if (bai_file) {
        ch_bam_bai = channel.value([ meta, bam_file, bai_file ])
    } else {
        SAMTOOLS_INDEX(ch_bam)
        ch_bam_bai = ch_bam
            .combine(SAMTOOLS_INDEX.out.bai.map{ m, bai -> bai })
            .map{ m, bam, bai -> [ m, bam, bai ] }
    }

    //
    // Decompress GTF if gzipped (shared across all GTF consumers)
    //
    if (gtf_file && gtf_file.toString().endsWith('.gz')) {
        GUNZIP_GTF(channel.value([ [:], gtf_file ]))
        ch_plain_gtf = GUNZIP_GTF.out.gunzip  // tuple(meta, gtf)
    } else if (gtf_file) {
        ch_plain_gtf = channel.value([ [:], gtf_file ])
    } else {
        ch_plain_gtf = channel.empty()
    }

    //
    // Convert GTF to BED12 if no BED provided (same as nf-core/rnaseq)
    //
    if (bed_file) {
        ch_bed = channel.value(bed_file)
    } else if (gtf_file) {
        GTF2BED(ch_plain_gtf.map{ _meta, f -> f })
        ch_bed = GTF2BED.out.bed
        ch_versions = ch_versions.mix(GTF2BED.out.versions)
    } else {
        ch_bed = channel.empty()
    }

    //
    // MODULE: RustQC RNA (single-pass, all tools)
    // When both GTF and BED are available, pass --bed so read_distribution
    // uses the same BED12 model as upstream RSeQC.
    //
    if (params.run_rustqc && gtf_file) {
        RUSTQC_RNA(
            ch_bam_bai,
            gtf_file,
            ch_bed,
        )
        ch_versions      = ch_versions.mix(RUSTQC_RNA.out.versions)
        ch_multiqc_files = ch_multiqc_files.mix(RUSTQC_RNA.out.results.map{ _meta, files -> files })
    }

    //
    // MODULES: Upstream reference tools
    //
    if (params.run_upstream) {

        // Tools that only need BAM (no GTF/BED/BAI required)
        //

        // preseq: tuple(meta, bam) — coordinate-sorted BAM, same as nf-core/rnaseq
        PRESEQ_LCEXTRAP(ch_bam)

        // Tools that require BAM + BAI
        //

        // RSeQC tools needing BAM + BAI only
        RSEQC_BAMSTAT(ch_bam_bai)
        RSEQC_READDUPLICATION(ch_bam_bai)

        // samtools tools
        SAMTOOLS_FLAGSTAT(ch_bam_bai)
        SAMTOOLS_IDXSTATS(ch_bam_bai)

        // samtools stats: needs tuple(meta, bam, bai) + tuple(meta, fasta) — fasta optional
        SAMTOOLS_STATS(ch_bam_bai, channel.value([ meta, [] ]))

        // Tools that require GTF annotation
        //
        if (gtf_file) {
            // dupRadar: tuple(meta, bam) + tuple(meta, gtf)
            DUPRADAR(ch_bam, channel.value([ meta, gtf_file ]))
            ch_versions = ch_versions.mix(DUPRADAR.out.versions)

            // featureCounts: tuple(meta, bams, annotation) — all in one tuple
            SUBREAD_FEATURECOUNTS(channel.value([ meta, bam_file, gtf_file ]))

            // Qualimap: name-sort BAM, then run qualimap rnaseq
            // Mirrors nf-core/rnaseq: GUNZIP_GTF -> SAMTOOLS_SORT_QUALIMAP -> QUALIMAP_RNASEQ
            // (GUNZIP_GTF already handled above for all GTF consumers)
            SAMTOOLS_SORT_QUALIMAP(
                ch_bam,
                channel.value([ [:], [] ]),
                ''
            )
            QUALIMAP_RNASEQ(
                SAMTOOLS_SORT_QUALIMAP.out.bam,
                ch_plain_gtf
            )
        }

        // Tools that require BAI + BED gene model
        // Uses ch_bed which is either the user-provided BED or converted from GTF
        //
        RSEQC_INFEREXPERIMENT(ch_bam_bai, ch_bed)
        RSEQC_READDISTRIBUTION(ch_bam_bai, ch_bed)
        RSEQC_JUNCTIONANNOTATION(ch_bam_bai, ch_bed)
        RSEQC_JUNCTIONSATURATION(ch_bam_bai, ch_bed)
        RSEQC_INNERDISTANCE(ch_bam_bai, ch_bed)

        //
        // Collect upstream tool outputs for MultiQC
        //
        ch_multiqc_files = ch_multiqc_files.mix(PRESEQ_LCEXTRAP.out.lc_extrap.collect{ _meta, f -> f })
        ch_multiqc_files = ch_multiqc_files.mix(RSEQC_BAMSTAT.out.txt.collect{ _meta, f -> f })
        ch_multiqc_files = ch_multiqc_files.mix(RSEQC_READDUPLICATION.out.pos_xls.collect{ _meta, f -> f })
        ch_multiqc_files = ch_multiqc_files.mix(RSEQC_READDUPLICATION.out.seq_xls.collect{ _meta, f -> f })
        ch_multiqc_files = ch_multiqc_files.mix(SAMTOOLS_FLAGSTAT.out.flagstat.collect{ _meta, f -> f })
        ch_multiqc_files = ch_multiqc_files.mix(SAMTOOLS_IDXSTATS.out.idxstats.collect{ _meta, f -> f })
        ch_multiqc_files = ch_multiqc_files.mix(SAMTOOLS_STATS.out.stats.collect{ _meta, f -> f })

        if (gtf_file) {
            ch_multiqc_files = ch_multiqc_files.mix(DUPRADAR.out.multiqc.collect{ _meta, f -> f })
            ch_multiqc_files = ch_multiqc_files.mix(SUBREAD_FEATURECOUNTS.out.summary.collect{ _meta, f -> f })
            ch_multiqc_files = ch_multiqc_files.mix(QUALIMAP_RNASEQ.out.results.collect{ _meta, f -> f })
        }

        // BED-dependent RSeQC tools: ch_bed is either user-provided or GTF-derived
        ch_multiqc_files = ch_multiqc_files.mix(RSEQC_INFEREXPERIMENT.out.txt.collect{ _meta, f -> f })
        ch_multiqc_files = ch_multiqc_files.mix(RSEQC_READDISTRIBUTION.out.txt.collect{ _meta, f -> f })
        ch_multiqc_files = ch_multiqc_files.mix(RSEQC_JUNCTIONANNOTATION.out.log.collect{ _meta, f -> f })
        ch_multiqc_files = ch_multiqc_files.mix(RSEQC_JUNCTIONSATURATION.out.rscript.collect{ _meta, f -> f })
        ch_multiqc_files = ch_multiqc_files.mix(RSEQC_INNERDISTANCE.out.freq.collect{ _meta, f -> f })
    }

    //
    // Collate and save software versions
    //
    ch_collated_versions = softwareVersionsToYAML(ch_versions)
        .collectFile(
            storeDir: "${params.outdir}/pipeline_info",
            name:  'rustqc_benchmarks_software_versions.yml',
            sort: true,
            newLine: true
        )

    //
    // MODULE: MultiQC
    //
    ch_multiqc_config        = channel.fromPath("$projectDir/assets/multiqc_config.yml", checkIfExists: true)
    ch_multiqc_custom_config = params.multiqc_config ? channel.fromPath(params.multiqc_config) : channel.empty()
    ch_multiqc_logo          = params.multiqc_logo   ? channel.fromPath(params.multiqc_logo)   : channel.empty()

    ch_workflow_summary = channel.value(
        paramsSummaryMultiqc(
            paramsSummaryMap(workflow, parameters_schema: "nextflow_schema.json")
        )
    ).collectFile(name: 'workflow_summary_mqc.yaml')

    ch_multiqc_files = ch_multiqc_files
        .mix(ch_collated_versions)
        .mix(ch_workflow_summary)

    ch_multiqc_input = Channel.of( [id: 'multiqc'] )
        .combine( ch_multiqc_files.collect().map{ [it] } )
        .combine( ch_multiqc_config.mix(ch_multiqc_custom_config).collect().map{ [it] }.ifEmpty([[]] ) )
        .combine( ch_multiqc_logo.collect().map{ [it] }.ifEmpty([[]] ) )
        .map { mqc_meta, files, configs, logo ->
            [ mqc_meta, files, configs, logo, [], [] ]
        }
    MULTIQC( ch_multiqc_input )

    emit:
    multiqc_report = MULTIQC.out.report
    versions       = ch_versions
}

/*
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    THE END
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
*/

/*
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    ALIGN_MARKDUP: FASTQ -> coordinate-sorted, duplicate-marked, indexed BAM

    Subworkflow of the full-size run (workflows/fullsize.nf) (tests/DATASETS.md): it makes the input
    BAM the way nf-core/rnaseq does (STAR two-pass, samtools sort, Picard MarkDuplicates,
    samtools index, arguments from rnaseq 3.28.0 conf/modules/align_star.config and
    conf/modules/markduplicates.config), and infers the library strandedness with RSeQC
    infer_experiment.py as rnaseq does. The tool versions are those pinned by the nf-core
    modules in modules.json. See conf/fullsize.config for the arguments.
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
*/

include { GUNZIP as GUNZIP_FASTA     } from '../../../modules/nf-core/gunzip/main'
include { GUNZIP as GUNZIP_GTF       } from '../../../modules/nf-core/gunzip/main'
include { STAR_GENOMEGENERATE        } from '../../../modules/nf-core/star/genomegenerate/main'
include { STAR_ALIGN                 } from '../../../modules/nf-core/star/align/main'
include { SAMTOOLS_SORT              } from '../../../modules/nf-core/samtools/sort/main'
include { PICARD_MARKDUPLICATES      } from '../../../modules/nf-core/picard/markduplicates/main'
include { SAMTOOLS_INDEX             } from '../../../modules/nf-core/samtools/index/main'
include { RSEQC_INFEREXPERIMENT      } from '../../../modules/nf-core/rseqc/inferexperiment/main'
include { GTF2BED                    } from '../../../modules/local/gtf2bed'

/*
 * Library strandedness from the text of an infer_experiment.py report, with the thresholds
 * of nf-core/rnaseq's getInferexperimentStrandedness (cutoff 30): `forward` when at least
 * 70% of reads are explained by the sense layout, `reverse` for the antisense one,
 * `unstranded` when both are at least 30% and within 10 points of each other.
 */
def inferStrandedness(String report) {
    def sense     = null
    def antisense = null
    report.readLines().each { String line ->
        def m = line =~ /Fraction of reads explained by "([^"]+)": ([0-9.]+)/
        if (m.find()) {
            def value = 100 * (m.group(2) as BigDecimal)
            // PE: "1++,1--,2+-,2-+" is sense; SE: "++,--" is sense
            if (m.group(1) in ['1++,1--,2+-,2-+', '++,--']) sense = value
            else if (m.group(1) in ['1+-,1-+,2++,2--', '+-,-+']) antisense = value
        }
    }
    assert sense != null && antisense != null : "Cannot read the strand fractions from the infer_experiment report:\n${report}"
    if (sense >= 70)     return 'forward'
    if (antisense >= 70) return 'reverse'
    if (sense >= 30 && antisense >= 30 && (sense - antisense).abs() <= 10) return 'unstranded'
    error "Strandedness is undetermined (sense ${sense}%, antisense ${antisense}%)"
}

workflow ALIGN_MARKDUP {

    take:
    ch_reads       // channel: [ meta, [ fastq_1, fastq_2 ] ]   meta.single_end = false
    ch_fasta       // channel: [ meta, genome.fa(.gz) ]   (empty when ch_star_index is given)
    ch_gtf         // channel: [ meta, annotation.gtf(.gz) ]
    ch_star_index  // channel: [ meta, star/ ] of a built index to reuse (empty: it is built from ch_fasta)

    main:

    // STAR and gtf2bed want plain text
    ch_gtf_plain = ch_gtf.filter { _meta, f -> !f.name.endsWith('.gz') }
    GUNZIP_GTF(ch_gtf.filter { _meta, f -> f.name.endsWith('.gz') })
    ch_gtf_in = ch_gtf_plain.mix(GUNZIP_GTF.out.gunzip).first()

    //
    // Genome index: reused when given, else built (and published, conf/fullsize.config)
    //
    ch_fasta_plain = ch_fasta.filter { _meta, f -> !f.name.endsWith('.gz') }
    GUNZIP_FASTA(ch_fasta.filter { _meta, f -> f.name.endsWith('.gz') })
    ch_fasta_in = ch_fasta_plain.mix(GUNZIP_FASTA.out.gunzip)

    STAR_GENOMEGENERATE(ch_fasta_in, ch_gtf_in)
    ch_index = ch_star_index.mix(STAR_GENOMEGENERATE.out.index).first()

    //
    // Align (two-pass, as nf-core/rnaseq), sort, mark duplicates, index
    //
    STAR_ALIGN(ch_reads, ch_index, ch_gtf_in, false)

    SAMTOOLS_SORT(STAR_ALIGN.out.bam, channel.value([ [:], [] ]), '')

    PICARD_MARKDUPLICATES(SAMTOOLS_SORT.out.bam, channel.value([ [:], [], [] ]))

    SAMTOOLS_INDEX(PICARD_MARKDUPLICATES.out.bam)

    ch_bam_bai = PICARD_MARKDUPLICATES.out.bam.join(SAMTOOLS_INDEX.out.bai)

    //
    // Strandedness
    //
    GTF2BED(ch_gtf_in.map { _meta, f -> f })
    RSEQC_INFEREXPERIMENT(ch_bam_bai, GTF2BED.out.bed)
    ch_strandedness = RSEQC_INFEREXPERIMENT.out.txt.map { meta, report -> inferStrandedness(report.text) }

    emit:
    bam          = PICARD_MARKDUPLICATES.out.bam    // [ meta, bam ]
    bai          = SAMTOOLS_INDEX.out.bai           // [ meta, bai ]
    metrics      = PICARD_MARKDUPLICATES.out.metrics
    star_log     = STAR_ALIGN.out.log_final
    infer_report = RSEQC_INFEREXPERIMENT.out.txt
    strandedness = ch_strandedness                  // 'forward' | 'reverse' | 'unstranded'
}

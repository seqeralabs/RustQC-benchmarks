/*
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    The full-size run (tests/DATASETS.md#full-size-tier), run on Seqera Platform before each
    RustQC release by .github/workflows/fullsize-seqera.yml:

      FASTQ -> STAR (two-pass) -> sort -> Picard MarkDuplicates -> BAM   (align_markdup)
      BAM   -> RustQC and the upstream tools                              (rustqc-benchmarks)
            -> FULLSIZE_COMPARE: RustQC against the upstream tools, a pass/fail report
            -> FULLSIZE_VERDICT: the run fails if the comparison does

    Two things are kept outside the run and reused by the next one instead of being rebuilt:
      <star_index_dir>/star/                the STAR index of the genome
      <bam_dir>/<sample>.markdup.sorted.bam the aligned BAM (+ .bai and the infer_experiment
                                            report holding its strandedness)
    Both default to <outdir>/cache/...; the Platform run points them at directories keyed on the
    inputs and the alignment code, so a change of either starts afresh. A run whose --bam_dir
    holds the BAM does not align at all; one whose --star_index_dir holds the index only skips
    STAR_GENOMEGENERATE.

    Run from the repository root (bin/ on the tasks' PATH):

      nextflow run . --fullsize -profile fullsize,docker --outdir results \
          $(tests/fetch_testdata.py --params-json hs_hcc1395_wts_fullsize | <as --key value>)
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
*/

include { ALIGN_MARKDUP; inferStrandedness } from '../subworkflows/local/align_markdup/main'
include { RUSTQC_BENCHMARKS                } from './rustqc-benchmarks'
include { FULLSIZE_COMPARE; FULLSIZE_VERDICT } from '../modules/local/fullsize_compare/main'

workflow FULLSIZE {

    main:

    ['sample_id', 'gtf', 'outdir', 'dataset'].each { name ->
        if (!params[name]) error "--${name} is required with --fullsize"
    }

    def sample = params.sample_id as String
    def index_dir = params.star_index_dir ?: "${params.outdir}/cache/star_index"
    def bam_dir   = params.bam_dir ?: "${params.outdir}/cache/bam"
    def meta   = [ id: sample, single_end: false ]

    ch_gtf = channel.value(file(params.gtf, checkIfExists: true))

    // The infer_experiment report is published last: its presence means the cache is complete
    def bam_cached   = file("${bam_dir}/${sample}.infer_experiment.txt")
    def index_cached = file("${index_dir}/star/SAindex")

    if (bam_cached.exists()) {
        //
        // The aligned BAM of an earlier run: no alignment
        //
        log.info "Reusing the aligned BAM in ${bam_dir} (delete it to align again)"
        def strandedness = inferStrandedness(bam_cached.text)
        ch_bam_bai = channel.value([
            meta + [ strandedness: strandedness ],
            file("${bam_dir}/${sample}.markdup.sorted.bam", checkIfExists: true),
            file("${bam_dir}/${sample}.markdup.sorted.bam.bai", checkIfExists: true),
        ])
    } else {
        //
        // Align: STAR index (reused if published), STAR two-pass, sort, Picard MarkDuplicates
        //
        if (!params.fastq_1 || !params.fastq_2) error "--fastq_1 and --fastq_2 are required to align (no BAM in ${bam_dir})"

        ch_reads = channel.value([ meta, [
            file(params.fastq_1, checkIfExists: true),
            file(params.fastq_2, checkIfExists: true),
        ] ])
        if (index_cached.exists()) {
            log.info "Reusing the STAR index in ${index_dir}"
            ch_fasta      = channel.empty()
            ch_star_index = channel.value([ [ id: 'star_index' ], file("${index_dir}/star", checkIfExists: true) ])
        } else {
            if (!params.fasta) error "--fasta is required to build the STAR index (none in ${index_dir})"
            ch_fasta      = channel.value([ [ id: 'genome' ], file(params.fasta, checkIfExists: true) ])
            ch_star_index = channel.empty()
        }

        ALIGN_MARKDUP(ch_reads, ch_fasta, ch_gtf.map { gtf -> [ [ id: 'genome' ], gtf ] }, ch_star_index)

        ch_bam_bai = ALIGN_MARKDUP.out.bam
            .join(ALIGN_MARKDUP.out.bai)
            .combine(ALIGN_MARKDUP.out.strandedness)
            .map { m, bam, bai, strandedness -> [ m + [ strandedness: strandedness ], bam, bai ] }
            .first()
    }

    //
    // RustQC and the upstream tools on the BAM (--run_upstream, set by the fullsize profile)
    //
    RUSTQC_BENCHMARKS(ch_bam_bai, ch_gtf)

    //
    // RustQC against the upstream tools
    //
    FULLSIZE_COMPARE(
        RUSTQC_BENCHMARKS.out.rustqc_results,
        RUSTQC_BENCHMARKS.out.upstream_refs.collect(),
        file("${projectDir}/tests", type: 'dir'),
        params.dataset,
    )
    FULLSIZE_VERDICT(FULLSIZE_COMPARE.out.status)
}

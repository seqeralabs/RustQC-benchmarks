process RUSTQC_RNA {
    tag "$meta.id"
    label 'process_high'

    container "${params.rustqc_image ?: 'ghcr.io/seqeralabs/rustqc:dev'}"

    input:
    tuple val(meta), path(bam), path(bai)
    path gtf

    output:
    tuple val(meta), path("rustqc"), emit: results
    path "versions.yml",                emit: versions

    when:
    task.ext.when == null || task.ext.when

    script:
    def args           = task.ext.args ?: ''
    def prefix         = task.ext.prefix ?: "${meta.id}"
    def paired_flag    = meta.single_end ? '' : '--paired'
    def dup_flag       = params.skip_dup_check ? '--skip-dup-check' : ''
    def config_flag    = params.rustqc_config ? "--config ${params.rustqc_config}" : ''
    def biotype_flag   = params.biotype_attribute ? "--biotype-attribute ${params.biotype_attribute}" : ''
    def stranded_flag  = meta.strandedness == 'forward' ? '--stranded forward' : meta.strandedness == 'reverse' ? '--stranded reverse' : '--stranded unstranded'
    """
    rustqc rna \\
        ${bam} \\
        --gtf ${gtf} \\
        --sample-name ${prefix} \\
        ${paired_flag} \\
        ${stranded_flag} \\
        --threads ${task.cpus} \\
        --outdir rustqc \\
        ${dup_flag} \\
        ${config_flag} \\
        ${biotype_flag} \\
        ${args}

    cat <<-END_VERSIONS > versions.yml
    "${task.process}":
        rustqc: \$(rustqc --version | sed 's/rustqc //')
    END_VERSIONS
    """

    stub:
    def prefix = task.ext.prefix ?: "${meta.id}"
    """
    mkdir -p rustqc/dupradar rustqc/featurecounts rustqc/qualimap rustqc/bigwig \\
        rustqc/rseqc/bam_stat rustqc/rseqc/infer_experiment \\
        rustqc/rseqc/read_duplication rustqc/rseqc/read_distribution \\
        rustqc/rseqc/junction_annotation rustqc/rseqc/junction_saturation \\
        rustqc/rseqc/inner_distance

    touch rustqc/bigwig/${prefix}.bigWig
    touch rustqc/bigwig/${prefix}.forward.bigWig
    touch rustqc/bigwig/${prefix}.reverse.bigWig
    touch rustqc/dupradar/${prefix}_dupMatrix.txt
    touch rustqc/dupradar/${prefix}_intercept_slope.txt
    touch rustqc/featurecounts/${prefix}.featureCounts.tsv
    touch rustqc/featurecounts/${prefix}.featureCounts.tsv.summary
    touch rustqc/qualimap/rnaseq_qc_results.txt
    touch rustqc/rseqc/bam_stat/${prefix}.bam_stat.txt
    touch rustqc/rseqc/infer_experiment/${prefix}.infer_experiment.txt
    touch rustqc/rseqc/read_duplication/${prefix}.pos.DupRate.xls
    touch rustqc/rseqc/read_duplication/${prefix}.seq.DupRate.xls
    touch rustqc/rseqc/read_distribution/${prefix}.read_distribution.txt
    touch rustqc/rseqc/junction_annotation/${prefix}.junction.bed
    touch rustqc/rseqc/junction_annotation/${prefix}.junction.xls
    touch rustqc/rseqc/junction_saturation/${prefix}.junctionSaturation_plot.r
    touch rustqc/rseqc/inner_distance/${prefix}.inner_distance.txt
    touch rustqc/rseqc/inner_distance/${prefix}.inner_distance_freq.txt

    cat <<-END_VERSIONS > versions.yml
    "${task.process}":
        rustqc: 0.0.0-stub
    END_VERSIONS
    """
}

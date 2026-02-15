process RUSTQC_RNA {
    tag "$meta.id"
    label 'process_medium'

    container "${params.rustqc_image ?: 'ghcr.io/ewels/rustqc:dev'}"

    input:
    tuple val(meta), path(bam), path(bai)
    path gtf

    output:
    tuple val(meta), path("output/**"), emit: results
    path "versions.yml",                emit: versions

    when:
    task.ext.when == null || task.ext.when

    script:
    def args           = task.ext.args ?: ''
    def prefix         = task.ext.prefix ?: "${meta.id}"
    def paired_flag    = meta.single_end ? '' : '-p'
    def dup_flag       = params.skip_dup_check ? '--skip-dup-check' : ''
    def config_flag    = params.rustqc_config ? "-c ${params.rustqc_config}" : ''
    def biotype_flag   = params.biotype_attribute ? "--biotype-attribute ${params.biotype_attribute}" : ''
    """
    rustqc rna \\
        ${bam} \\
        --gtf ${gtf} \\
        ${paired_flag} \\
        -t ${task.cpus} \\
        -o output \\
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
    mkdir -p output/dupradar output/featurecounts \\
        output/rseqc/bam_stat output/rseqc/infer_experiment \\
        output/rseqc/read_duplication output/rseqc/read_distribution \\
        output/rseqc/junction_annotation output/rseqc/junction_saturation \\
        output/rseqc/inner_distance

    touch output/dupradar/${prefix}_dupMatrix.txt
    touch output/dupradar/${prefix}_intercept_slope.txt
    touch output/featurecounts/${prefix}.featureCounts.tsv
    touch output/featurecounts/${prefix}.featureCounts.tsv.summary
    touch output/rseqc/bam_stat/${prefix}.bam_stat.txt
    touch output/rseqc/infer_experiment/${prefix}.infer_experiment.txt
    touch output/rseqc/read_duplication/${prefix}.pos.DupRate.xls
    touch output/rseqc/read_duplication/${prefix}.seq.DupRate.xls
    touch output/rseqc/read_distribution/${prefix}.read_distribution.txt
    touch output/rseqc/junction_annotation/${prefix}.junction.bed
    touch output/rseqc/junction_annotation/${prefix}.junction.xls
    touch output/rseqc/junction_saturation/${prefix}.junctionSaturation_plot.r
    touch output/rseqc/inner_distance/${prefix}.inner_distance.txt
    touch output/rseqc/inner_distance/${prefix}.inner_distance_freq.txt

    cat <<-END_VERSIONS > versions.yml
    "${task.process}":
        rustqc: 0.0.0-stub
    END_VERSIONS
    """
}

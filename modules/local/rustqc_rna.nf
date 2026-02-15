process RUSTQC_RNA {
    tag "$meta.id"
    label 'process_medium'

    container "${params.rustqc_image ?: 'ghcr.io/ewels/rustqc:latest'}"

    input:
    tuple val(meta), path(bam), path(bai)
    path gtf

    output:
    tuple val(meta), path("dupradar/*"),       emit: dupradar
    tuple val(meta), path("featurecounts/*"),   emit: featurecounts
    tuple val(meta), path("rseqc/**"),          emit: rseqc
    path "versions.yml",                        emit: versions

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
        -o . \\
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
    mkdir -p dupradar featurecounts rseqc/bam_stat rseqc/infer_experiment \\
        rseqc/read_duplication rseqc/read_distribution rseqc/junction_annotation \\
        rseqc/junction_saturation rseqc/inner_distance

    touch dupradar/${prefix}_dupMatrix.txt
    touch dupradar/${prefix}_intercept_slope.txt
    touch featurecounts/${prefix}_featureCounts.tsv
    touch featurecounts/${prefix}_featureCounts.tsv.summary
    touch rseqc/bam_stat/${prefix}.bam_stat.txt
    touch rseqc/infer_experiment/${prefix}.infer_experiment.txt
    touch rseqc/read_duplication/${prefix}.pos.DupRate.xls
    touch rseqc/read_duplication/${prefix}.seq.DupRate.xls
    touch rseqc/read_distribution/${prefix}.read_distribution.txt
    touch rseqc/junction_annotation/${prefix}.junction.bed
    touch rseqc/junction_annotation/${prefix}.junction.xls
    touch rseqc/junction_saturation/${prefix}.junctionSaturation_plot.r
    touch rseqc/inner_distance/${prefix}.inner_distance.txt
    touch rseqc/inner_distance/${prefix}.inner_distance_freq.txt

    cat <<-END_VERSIONS > versions.yml
    "${task.process}":
        rustqc: 0.0.0-stub
    END_VERSIONS
    """
}

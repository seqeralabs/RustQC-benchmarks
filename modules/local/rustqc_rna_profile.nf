process RUSTQC_RNA_PROFILE {
    tag { "${meta.id}_${ncpus}cores_rep${replicate}" }

    container "${params.rustqc_image ?: 'ghcr.io/seqeralabs/rustqc:dev'}"

    cpus { ncpus }
    memory '28.GB'
    time '2.h'

    publishDir "${params.outdir}", mode: params.publish_dir_mode, saveAs: { filename -> filename == 'versions.yml' ? null : filename }

    input:
    tuple val(meta), path(bam), path(bai)
    path gtf
    val(ncpus)
    val(replicate)

    output:
    tuple val(meta), val(ncpus), val(replicate), path("rustqc_${ncpus}cores_rep${replicate}/**"), emit: results
    path "versions.yml",                                                                          emit: versions

    when:
    task.ext.when == null || task.ext.when

    script:
    def args           = task.ext.args ?: ''
    def paired_flag    = meta.single_end ? '' : '--paired'
    def dup_flag       = params.skip_dup_check ? '--skip-dup-check' : ''
    def config_flag    = params.rustqc_config ? "--config ${params.rustqc_config}" : ''
    def biotype_flag   = params.biotype_attribute ? "--biotype-attribute ${params.biotype_attribute}" : ''
    def stranded_flag  = meta.strandedness == 'forward' ? '--stranded forward' : meta.strandedness == 'reverse' ? '--stranded reverse' : '--stranded unstranded'
    """
    rustqc rna \\
        ${bam} \\
        --gtf ${gtf} \\
        ${paired_flag} \\
        ${stranded_flag} \\
        --threads ${task.cpus} \\
        --outdir rustqc_${ncpus}cores_rep${replicate} \\
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
    """
    mkdir -p rustqc_${ncpus}cores_rep${replicate}/dupradar rustqc_${ncpus}cores_rep${replicate}/featurecounts rustqc_${ncpus}cores_rep${replicate}/qualimap \\
        rustqc_${ncpus}cores_rep${replicate}/rseqc/bam_stat rustqc_${ncpus}cores_rep${replicate}/rseqc/infer_experiment \\
        rustqc_${ncpus}cores_rep${replicate}/rseqc/read_duplication rustqc_${ncpus}cores_rep${replicate}/rseqc/read_distribution \\
        rustqc_${ncpus}cores_rep${replicate}/rseqc/junction_annotation rustqc_${ncpus}cores_rep${replicate}/rseqc/junction_saturation \\
        rustqc_${ncpus}cores_rep${replicate}/rseqc/inner_distance

    cat <<-END_VERSIONS > versions.yml
    "${task.process}":
        rustqc: 0.0.0-stub
    END_VERSIONS
    """
}

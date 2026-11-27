process FULLSIZE_COMPARE {
    tag "$meta.id"
    label 'process_single'

    conda "conda-forge::groovy=4.0.22 conda-forge::openjdk=21"
    container 'docker.io/library/groovy:4.0.33-jdk21'

    input:
    tuple val(meta), path(rustqc)
    path upstream, stageAs: 'upstream/*'
    path tests
    val dataset

    output:
    tuple val(meta), path("report"), emit: report
    path "report/status.txt"       , emit: status

    when:
    task.ext.when == null || task.ext.when

    script:
    """
    export RUSTQC_DATASET_SETS=fullsize
    groovy -cp tests/lib tests/lib/checks/FullsizeCompare.groovy \\
        ${dataset} ${rustqc} upstream report
    """

    stub:
    """
    mkdir report
    echo PASS > report/status.txt
    """
}

/*
 * Fails the run when the comparison did not pass. A separate step after FULLSIZE_COMPARE so
 * that the report is published first: `finish` lets the pending publishing complete.
 */
process FULLSIZE_VERDICT {
    errorStrategy 'finish'

    input:
    path status

    exec:
    def verdict = status.text.trim()
    if (verdict != 'PASS') {
        error "RustQC differs from the upstream tools on the full-size dataset (${verdict}): see report/comparison.md in the output directory"
    }
}

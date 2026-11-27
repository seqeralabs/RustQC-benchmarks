/**
 * Copies the upstream outputs of one pipeline run (main.nf with --run_upstream) into a
 * dataset's references.dir, under the names the checks read (ToolCheck.references).
 * Used by tests/rna/refgen/references.nf.test.
 *
 * Files are copied as they are, except that the staged BAM name is replaced by
 * references.bam_name where the two differ (see Run.lines()).
 *
 * Only the files of registered tools are exported; a tool whose upstream output is missing
 * (the upstream process failed) is reported, so the dataset can mark it `upstream_error`.
 *
 * Environment:
 *   RUSTQC_REFGEN_OUT=dir   write under <dir>/<references.dir> instead of the repository
 */
package checks

import java.nio.file.Files
import java.nio.file.StandardCopyOption

class Refgen {

    /** Copy one dataset's references; returns the tools that had no upstream output. */
    static List<String> export(def ds, def pipelineOutdir) {
        File outdir = new File(pipelineOutdir.toString())
        assert ds.references.dir : "Dataset '${ds.id}' has no references.dir in tests/datasets.yml"
        def root = System.getenv('RUSTQC_REFGEN_OUT') ? new File(System.getenv('RUSTQC_REFGEN_OUT')) : ds.projectDir
        File dest = new File(root as File, ds.references.dir as String)

        // Outputs that echo the BAM name (featureCounts) are written with references.bam_name,
        // as Run normalises RustQC's outputs to it.
        String fromName = ds.bamName() as String
        String toName   = ds.referenceBamName() as String

        List<String> missingTools = []
        List<String> written = []
        Checks.TOOLS.each { ToolCheck check ->
            if (ds.expectFor(check.name) == 'absent') return
            def pairs = check.references.collect { ref, src ->
                [ref.replace('{sample}', ds.sample as String), src.replace('{sample}', ds.sample as String)]
            }
            def missing = pairs.findAll { ref, src -> !new File(outdir, src).isFile() }
            if (missing) {
                missingTools << check.name
                println "refgen [${ds.id}] ${check.name}: no upstream output ${missing*.get(1)}"
                return
            }
            pairs.each { ref, src ->
                def target = new File(dest, ref)
                target.parentFile.mkdirs()
                def source = new File(outdir, src)
                if (fromName == toName) {
                    Files.copy(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                } else {
                    target.text = source.text.replace(fromName, toName)
                }
                written << ref
            }
        }
        println "refgen [${ds.id}]: wrote ${written.size()} reference file(s) to ${dest}" +
            (written ? "\n  " + written.join('\n  ') : '')
        if (missingTools) {
            println "refgen [${ds.id}]: no upstream output for ${missingTools}; if the upstream tool " +
                "cannot run on this dataset, set `expect: {<tool>: upstream_error}` in tests/datasets.yml"
        }
        return missingTools
    }
}

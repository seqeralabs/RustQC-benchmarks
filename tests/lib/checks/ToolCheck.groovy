/**
 * Base class of a per-tool check. One subclass per RustQC tool, holding what used to be
 * the `then` blocks of tests/rna/rustqc/<tool>.nf.test:
 *
 *   crosscheck(run)      compare RustQC against the dataset's upstream reference
 *                        (the old `crosscheck` test)
 *   regressionView(run)  small, deterministic view of the output for snapshot()
 *                        (the old `regression` test)
 *
 * Register new subclasses in Checks.TOOLS.
 */
package checks

abstract class ToolCheck {

    /** Tool name, as used in `expect:` in tests/datasets.yml and in RUSTQC_CHECK_TOOLS. */
    abstract String getName()

    /** Outputs this tool writes, relative to `rustqc/`; `{sample}` is the sample name. */
    abstract List<String> getOutputs()

    abstract void crosscheck(Run run)

    abstract Object regressionView(Run run)

    void assertPresent(Run run) {
        def missing = outputs.findAll { !run.output(it).isFile() }
        assert !missing : "RustQC did not write: ${missing} (in ${run.outDir})"
    }

    void assertAbsent(Run run) {
        def present = outputs.findAll { run.output(it).exists() }
        assert !present : "Expected no ${name} output for this dataset, found: ${present}"
    }
}

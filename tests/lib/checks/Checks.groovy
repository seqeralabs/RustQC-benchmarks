/**
 * Runs every registered per-tool check on one RustQC run.
 *
 *     Checks.crosscheck(ds, outDir)                  // vs upstream; all failures reported together
 *     snapshot(Checks.regressionView(ds, outDir))    // small per-tool views for nf-test snapshots
 *
 * `ds` is a datasets.Dataset; it is untyped here so this package does not depend on the
 * datasets package at compile time.
 *
 * What a tool's crosscheck does depends on the dataset's `expect:` entry for it:
 *   match           outputs must exist and match the upstream reference
 *   regression      outputs must exist; no upstream comparison (no equivalent upstream)
 *   absent          outputs must not exist
 *   upstream_error  no assertion: upstream failed, so there is no reference; RustQC output
 *                   is still covered by the snapshot and the release-regression test
 *
 * Set RUSTQC_CHECK_TOOLS=bam_stat,flagstat to crosscheck only some tools while iterating.
 */
package checks

class Checks {

    /** Converted tools. Tools not listed here are still checked by tests/rna/rustqc/<tool>.nf.test. */
    static final List<ToolCheck> TOOLS = [
        new BamStat(),
        new InferExperiment(),
        new Flagstat(),
        new Idxstats(),
        new FeatureCountsGeneId(),
        new SamtoolsStats(),
        new ReadDistribution(),
        new ReadDuplication(),
        new JunctionAnnotation(),
        new DupRadar(),
    ].asImmutable()

    static List<String> toolNames() {
        return TOOLS*.name
    }

    static void crosscheck(def ds, def outDir) {
        Map<String, Map> report = crosscheckReport(ds, outDir)
        Map<String, String> failures = report.findAll { tool, r -> r.status == 'fail' }.collectEntries { tool, r -> [(tool): r.message] }
        if (failures) {
            throw new AssertionError(
                "Crosscheck failed for ${failures.size()} tool(s) on dataset '${ds.id}': ${failures.keySet().join(', ')}\n\n" +
                failures.collect { tool, msg -> "-- ${tool} --\n${msg}" }.join('\n\n')
            )
        }
    }

    /**
     * The same checks as crosscheck(), reported instead of thrown: tool name -> a map with
     * `expect` (the dataset's `expect:` entry), `status` (pass | fail | skipped: no assertion
     * for `upstream_error`) and, for a failure, `message`. Used by checks.FullsizeCompare.
     */
    static Map<String, Map> crosscheckReport(def ds, def outDir) {
        def run = new Run(ds, outDir)
        Map<String, Map> report = [:]
        selected().each { ToolCheck check ->
            def expect = ds.expectFor(check.name)
            try {
                switch (expect) {
                    case 'match':
                        check.assertPresent(run)
                        check.crosscheck(run)
                        break
                    case 'regression':
                        check.assertPresent(run)
                        break
                    case 'absent':
                        check.assertAbsent(run)
                        break
                    case 'upstream_error':
                        report[check.name] = [expect: expect, status: 'skipped']
                        return
                }
                report[check.name] = [expect: expect, status: 'pass']
            } catch (AssertionError | Exception e) {
                report[check.name] = [expect: expect, status: 'fail', message: e.message ?: e.toString()]
            }
        }
        return report
    }

    /**
     * Per-tool regression views, keyed by tool name. Tools expected `absent` are left out;
     * an `upstream_error` tool is recorded as `null` when RustQC wrote nothing for it.
     */
    static Map regressionView(def ds, def outDir) {
        def run = new Run(ds, outDir)
        return TOOLS.findAll { ds.expectFor(it.name) != 'absent' }.collectEntries { ToolCheck check ->
            def wrote = check.outputs.every { run.output(it).isFile() }
            def view = (ds.expectFor(check.name) == 'upstream_error' && !wrote) ? null : check.regressionView(run)
            [(check.name): view]
        }
    }

    private static List<ToolCheck> selected() {
        def only = System.getenv('RUSTQC_CHECK_TOOLS')
        if (!only) return TOOLS
        def wanted = only.split(',')*.trim() as Set
        def unknown = wanted - (toolNames() as Set)
        assert !unknown : "RUSTQC_CHECK_TOOLS names unknown tools ${unknown}; known: ${toolNames()}"
        return TOOLS.findAll { it.name in wanted }
    }
}

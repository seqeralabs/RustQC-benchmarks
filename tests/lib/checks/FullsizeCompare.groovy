/**
 * The comparison step of the full-size run (modules/local/fullsize_compare.nf): RustQC's
 * outputs against the upstream tools' on the same BAM, with the checks library of the nf-test
 * suites (Checks), and a report instead of a failed test.
 *
 *     groovy -cp tests/lib tests/lib/checks/FullsizeCompare.groovy \
 *         <dataset id> <rustqc dir> <dir with the upstream outputs> <report dir>
 *
 * The upstream outputs are given as plain files in one directory (their names are unique);
 * they are laid out as the pipeline publishes them, and checks.Refgen copies them into the
 * dataset's references.dir, exactly as `refgen` does for the committed references. Run it in
 * a directory that holds tests/datasets.yml (the dataset's references.dir is resolved there).
 *
 * Writes <report dir>/comparison.md, comparison.json and, per failing tool, <tool>.txt with the
 * differences; <report dir>/status.txt is PASS or FAIL. Exits 0 either way (the pipeline
 * decides what a FAIL means), 1 only if the comparison itself cannot run.
 */
package checks

import groovy.json.JsonOutput

class FullsizeCompare {

    static void main(String[] args) {
        assert args.length == 4 : 'usage: FullsizeCompare <dataset id> <rustqc dir> <upstream dir> <report dir>'
        def (String id, String rustqc, String upstream, String report) = args as List
        System.exit(run(id, new File(rustqc), new File(upstream), new File(report)))
    }

    static int run(String id, File rustqcDir, File upstreamDir, File reportDir) {
        reportDir.mkdirs()
        def ds = datasets.Datasets.get(id)
        assert rustqcDir.isDirectory() : "No RustQC output directory ${rustqcDir}"

        // Lay the flat upstream files out as the pipeline publishes them (the `references` of
        // each tool: reference name -> published path)
        File layout = new File(reportDir.absoluteFile.parentFile, 'upstream-layout')
        Checks.TOOLS.each { ToolCheck check ->
            check.references.each { ref, src ->
                def name = new File(src.replace('{sample}', ds.sample as String)).name
                def found = new File(upstreamDir, name)
                if (!found.isFile()) return
                def link = new File(layout, src.replace('{sample}', ds.sample as String))
                link.parentFile.mkdirs()
                java.nio.file.Files.createSymbolicLink(link.toPath(), found.toPath().toAbsolutePath())
            }
        }
        def missing = Refgen.export(ds, layout)

        Map<String, Map> tools = Checks.crosscheckReport(ds, rustqcDir)
        missing.each { tool ->
            if (tools[tool]?.status == 'pass') tools[tool] = [expect: tools[tool].expect, status: 'fail', message: "no upstream output to compare with"]
        }
        boolean pass = !tools.any { tool, r -> r.status == 'fail' }

        tools.each { tool, r ->
            if (r.status == 'fail') new File(reportDir, "${tool}.txt").text = r.message + '\n'
        }
        new File(reportDir, 'status.txt').text = (pass ? 'PASS' : 'FAIL') + '\n'
        new File(reportDir, 'comparison.json').text = JsonOutput.prettyPrint(JsonOutput.toJson([
            dataset: ds.id,
            sample : ds.sample,
            status : pass ? 'PASS' : 'FAIL',
            tools  : tools,
        ])) + '\n'

        def rows = tools.collect { tool, r ->
            "| ${tool} | ${r.expect} | ${r.status == 'pass' ? 'pass' : r.status == 'skipped' ? 'skipped (upstream_error)' : '**FAIL**'} |"
        }
        def md = ["# RustQC against the upstream tools: ${ds.id}", '',
            "Result: **${pass ? 'PASS' : 'FAIL'}**  (sample `${ds.sample}`)", '',
            '| Tool | Expectation | Result |', '| --- | --- | --- |'] + rows
        tools.findAll { tool, r -> r.status == 'fail' }.each { tool, r ->
            md += ['', "## ${tool}", '', '```', abbreviate(r.message as String), '```', '', "Full text: `${tool}.txt`"]
        }
        new File(reportDir, 'comparison.md').text = md.join('\n') + '\n'

        println md.take(6 + rows.size()).join('\n')
        return 0
    }

    private static String abbreviate(String text, int maxLines = 40) {
        def lines = text.readLines()
        return lines.size() <= maxLines ? text : (lines.take(maxLines) + "... (${lines.size() - maxLines} more lines)").join('\n')
    }
}

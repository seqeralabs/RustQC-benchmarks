/**
 * Shared comparison utilities for RustQC benchmark nf-tests.
 *
 * Provides methods for comparing RustQC outputs against upstream tool
 * reference snapshots with configurable tolerance and filtering.
 *
 * Auto-loaded by nf-test via the tests/lib/ classpath convention.
 */
class CompareUtils {

    /**
     * Compare two TSV/tabular files line-by-line with configurable rules.
     *
     * @param actual    Lines from the RustQC output file
     * @param expected  Lines from the upstream reference snapshot
     * @param opts      Map with optional keys:
     *   - tolerance     (double):        absolute numeric tolerance (default: 0.0 = exact)
     *   - relTolerance  (double):        relative numeric tolerance (default: 0.0 = unused)
     *                                    When both tolerance and relTolerance are set, a value
     *                                    passes if it satisfies EITHER check (lenient mode).
     *   - skipPrefixes  (List<String>):  skip lines starting with these prefixes
     *   - skipColumns   (Set<Integer>):  column indices to ignore in comparison
     *   - trimLines     (boolean):       trim whitespace from lines (default: true)
     *   - delimiter     (String):        column delimiter (default: '\t')
     *   - skipBlank     (boolean):       skip blank lines (default: true)
     *   - maxErrors     (int):           max mismatches to report (default: 20)
     *
     * @throws AssertionError with detailed mismatch report
     */
    static void tsvMatch(List<String> actual, List<String> expected, Map opts = [:]) {
        double tolerance    = opts.tolerance    ?: 0.0
        double relTolerance = opts.relTolerance ?: 0.0
        List<String> skipPrefixes = opts.skipPrefixes ?: []
        Set<Integer> skipColumns  = (opts.skipColumns ?: []) as Set
        boolean trimLines = opts.containsKey('trimLines') ? opts.trimLines : true
        String delimiter  = opts.delimiter ?: '\t'
        boolean skipBlank = opts.containsKey('skipBlank') ? opts.skipBlank : true
        int maxErrors     = opts.maxErrors ?: 20

        // Pre-process lines
        actual   = prepareLines(actual, skipPrefixes, trimLines, skipBlank)
        expected = prepareLines(expected, skipPrefixes, trimLines, skipBlank)

        assert actual.size() == expected.size() :
            "Line count mismatch: actual=${actual.size()}, expected=${expected.size()}\n" +
            "First divergence around line ${Math.min(actual.size(), expected.size())}"

        List<String> errors = []

        actual.eachWithIndex { line, i ->
            def aCols = line.split(delimiter, -1)
            def eCols = expected[i].split(delimiter, -1)

            if (aCols.length != eCols.length) {
                errors << "Line ${i}: column count ${aCols.length} vs ${eCols.length}"
                return
            }

            aCols.eachWithIndex { val, j ->
                if (j in skipColumns) return

                // Try numeric comparison if tolerance is set
                if (tolerance > 0 || relTolerance > 0) {
                    try {
                        // Treat NA/NaN as 0 for numeric comparison (common in R output)
                        def aStr = val.trim().toUpperCase()
                        def eStr = eCols[j].trim().toUpperCase()
                        double aNum = (aStr == 'NA' || aStr == 'NAN') ? 0.0 : val.toDouble()
                        double eNum = (eStr == 'NA' || eStr == 'NAN') ? 0.0 : eCols[j].toDouble()

                        // Exact match — always pass (handles 0.0 vs 0.0 edge case)
                        if (aNum == eNum) return

                        boolean withinAbsolute = (tolerance > 0) ? Math.abs(aNum - eNum) <= tolerance : false
                        boolean withinRelative = false
                        if (relTolerance > 0 && eNum != 0.0) {
                            withinRelative = Math.abs((aNum - eNum) / eNum) <= relTolerance
                        }

                        // Pass if EITHER tolerance is satisfied (lenient when both set)
                        if (!withinAbsolute && !withinRelative) {
                            def details = []
                            if (tolerance > 0) details << "abs diff ${Math.abs(aNum - eNum)}, tolerance ${tolerance}"
                            if (relTolerance > 0 && eNum != 0.0) details << "rel diff ${Math.abs((aNum - eNum) / eNum)}, tolerance ${relTolerance}"
                            errors << "Line ${i}, col ${j}: ${aNum} vs ${eNum} (${details.join('; ')})"
                        }
                        return
                    } catch (NumberFormatException e) {
                        // Fall through to string comparison
                    }
                }

                // String comparison
                if (val != eCols[j]) {
                    errors << "Line ${i}, col ${j}: '${val}' vs '${eCols[j]}'"
                }
            }
        }

        if (errors) {
            int shown = Math.min(errors.size(), maxErrors)
            String report = "TSV comparison failed with ${errors.size()} mismatches"
            if (errors.size() > maxErrors) {
                report += " (showing first ${maxErrors})"
            }
            report += ":\n" + errors.take(maxErrors).collect { "  - ${it}" }.join('\n')
            assert false : report
        }
    }

    /**
     * Compare text reports line-by-line, ignoring header/log lines.
     *
     * @param actual          Lines from the RustQC output
     * @param expected        Lines from the upstream reference
     * @param ignorePrefixes  Line prefixes to filter out (e.g. ['Load BAM', 'processing'])
     *
     * @throws AssertionError with line-by-line diff
     */
    static void textMatch(List<String> actual, List<String> expected,
                          List<String> ignorePrefixes = []) {
        actual   = prepareLines(actual, ignorePrefixes, true, true)
        expected = prepareLines(expected, ignorePrefixes, true, true)

        assert actual.size() == expected.size() :
            "Line count mismatch: actual=${actual.size()}, expected=${expected.size()}"

        List<String> errors = []
        actual.eachWithIndex { line, i ->
            if (line != expected[i]) {
                errors << "Line ${i}:\n    actual:   '${line}'\n    expected: '${expected[i]}'"
            }
        }

        if (errors) {
            int shown = Math.min(errors.size(), 20)
            assert false : "Text comparison failed with ${errors.size()} mismatches:\n" +
                errors.take(20).join('\n')
        }
    }

    /**
     * Assert a file exists and has at least the given size in bytes.
     * Useful for checking plot files (PNG/PDF/SVG) without comparing content.
     */
    static void fileMinSize(java.nio.file.Path file, long minBytes) {
        assert java.nio.file.Files.exists(file) :
            "File not found: ${file}"
        long size = java.nio.file.Files.size(file)
        assert size >= minBytes :
            "File ${file.fileName} too small: ${size} bytes (minimum: ${minBytes})"
    }

    /**
     * Extract a range of lines from a file, useful for comparing
     * specific sections (e.g. data rows, skipping headers).
     */
    static List<String> extractLines(java.nio.file.Path file, int from, int to) {
        def lines = file.readLines()
        int end = Math.min(to, lines.size())
        return (from < end) ? lines.subList(from, end) : []
    }

    // ── bigWig / bedGraph helpers ─────────────────────────────────────

    /**
     * Container image used to decode bigWig files when no host
     * `bigWigToBedGraph` binary is available. The UCSC tools version is
     * irrelevant to correctness here — decoding reproduces the coverage
     * intervals regardless of the version that wrote the file.
     */
    static final String BIGWIGTOBEDGRAPH_IMAGE =
        'quay.io/biocontainers/ucsc-bigwigtobedgraph:469--h664eb37_1'

    /**
     * Decode a bigWig file to bedGraph intervals.
     *
     * Binary .bigWig files are NOT bit-identical to UCSC output, so we
     * never compare them directly. Instead we decode to bedGraph (which
     * yields the exact coverage intervals) and compare those.
     *
     * Uses the host `bigWigToBedGraph` binary if present, otherwise falls
     * back to running it inside the UCSC tools Docker container. The
     * decode runs here in the nf-test `then` block — never as a
     * docker-in-docker step inside a Nextflow process.
     *
     * @param bigWig  Path to the .bigWig file to decode
     * @return        bedGraph lines (chrom, start, end, value), sorted by
     *                coordinate as emitted by bigWigToBedGraph
     */
    static List<String> bedGraphFromBigWig(java.nio.file.Path bigWig) {
        def bw  = bigWig.toAbsolutePath()
        assert java.nio.file.Files.exists(bw) : "bigWig not found: ${bw}"

        def out = java.io.File.createTempFile('rustqc_bigwig_', '.bedGraph')
        out.deleteOnExit()

        List<String> cmd
        if (hasCommand('bigWigToBedGraph')) {
            cmd = ['bigWigToBedGraph', bw.toString(), out.absolutePath]
        } else {
            def bwDir  = bw.parent.toString()
            def outDir = out.parentFile.absolutePath
            cmd = ['docker', 'run', '--rm',
                   '-v', "${bwDir}:${bwDir}",
                   '-v', "${outDir}:${outDir}",
                   BIGWIGTOBEDGRAPH_IMAGE,
                   'bigWigToBedGraph', bw.toString(), out.absolutePath]
        }

        runOrThrow(cmd)
        def lines = out.readLines()
        out.delete()
        return lines
    }

    /**
     * Exact 4-column comparison of two bedGraph interval lists
     * (chrom, start, end, value). No tolerance — intervals must match
     * bedtools + bedClip output exactly.
     */
    static void bedGraphMatch(List<String> actual, List<String> expected) {
        tsvMatch(actual, expected, [tolerance: 0.0])
    }

    /**
     * MD5 of bedGraph content, computed as `join('\n', lines) + '\n'` so
     * the result matches `md5sum` of a file ending in a trailing newline.
     */
    static String md5BedGraphLines(List<String> lines) {
        String content = lines.join('\n') + '\n'
        def digest = java.security.MessageDigest.getInstance('MD5')
        byte[] hash = digest.digest(content.getBytes('UTF-8'))
        return hash.collect { String.format('%02x', it) }.join('')
    }

    // ── Internal helpers ──────────────────────────────────────────────

    /**
     * Filter and clean lines: remove blank lines, trim whitespace,
     * remove lines matching any of the given prefixes.
     */
    private static List<String> prepareLines(List<String> lines,
                                              List<String> skipPrefixes,
                                              boolean trim,
                                              boolean skipBlank) {
        def result = lines
        if (trim) {
            result = result.collect { it.trim() }
        }
        if (skipBlank) {
            result = result.findAll { it != '' }
        }
        if (skipPrefixes) {
            result = result.findAll { line ->
                !skipPrefixes.any { prefix -> line.startsWith(prefix) }
            }
        }
        return result
    }

    /** Return true if `cmd` is on the PATH of the host. */
    private static boolean hasCommand(String cmd) {
        try {
            def proc = ['bash', '-lc', "command -v ${cmd} >/dev/null 2>&1"].execute()
            proc.waitFor()
            return proc.exitValue() == 0
        } catch (Exception e) {
            return false
        }
    }

    /** Run a command, capturing combined stdout/stderr; assert exit code 0. */
    private static void runOrThrow(List<String> cmd) {
        // Coerce GStrings to plain Strings so ProcessBuilder's String[] holds
        def pb = new ProcessBuilder(cmd.collect { it.toString() })
        pb.redirectErrorStream(true)
        def proc = pb.start()
        String output = proc.inputStream.text
        proc.waitFor()
        assert proc.exitValue() == 0 :
            "Command failed (exit ${proc.exitValue()}): ${cmd.join(' ')}\n${output}"
    }
}

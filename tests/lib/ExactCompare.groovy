/**
 * Exact comparisons for the crosschecks, where RustQC reproduces the upstream bytes.
 *
 * Unlike CompareUtils.textMatch / tsvMatch, nothing is trimmed or skipped implicitly: lines are
 * compared as they are, except the prefixes a caller names (version / command-line headers).
 */
class ExactCompare {

    /**
     * Compare two files line by line, exactly: no trimming, blank lines kept. Lines starting
     * with one of `skipPrefixes` are dropped from both sides first (e.g. version/command-line
     * headers). Use this, or md5, wherever the upstream bytes are reproduced.
     */
    static void exactMatch(List<String> actual, List<String> expected, List<String> skipPrefixes = []) {
        def keep = { List<String> lines -> lines.findAll { line -> !skipPrefixes.any { line.startsWith(it) } } }
        actual   = keep(actual)
        expected = keep(expected)
        if (actual == expected) return
        List<String> errors = []
        int n = Math.max(actual.size(), expected.size())
        for (int i = 0; i < n && errors.size() < 20; i++) {
            def a = i < actual.size() ? actual[i] : '<none>'
            def e = i < expected.size() ? expected[i] : '<none>'
            if (a != e) errors << "Line ${i}:\n    actual:   '${a}'\n    expected: '${e}'"
        }
        assert false : "Exact comparison failed (actual ${actual.size()} lines, expected ${expected.size()}):\n" +
            errors.join('\n')
    }

    /** Relative tolerance for dupRadar's fitted intercept and slope (measured ~2.6e-9 on the small dataset). */
    static final double DUPRADAR_FIT_REL_TOL = 1e-8

    /**
     * Below this magnitude an upstream intercept/slope is a round-off residue: the fit is degenerate
     * (see dupradarFitMatch) and the true value is 0.
     */
    static final double DUPRADAR_FIT_ZERO = 1e-6

    /** Absolute tolerance for a degenerate fit's intercept/slope (RustQC ~1e-10, R ~3e-12 in that case). */
    static final double DUPRADAR_FIT_ABS_TOL = 1e-9

    /**
     * Compare dupRadar's `*_intercept_slope.txt`: same number of lines and labels, and the two fitted
     * values close.
     *
     * RustQC fits the logistic curve with its own solver, R with glm() (IRLS through LINPACK/BLAS), so
     * the last digits differ: ~2.6e-9 relative on the small dataset. Values are compared at
     * DUPRADAR_FIT_REL_TOL relative.
     *
     * If no read is duplicate-marked, every duplication rate is 0 and the fit is degenerate: the true
     * intercept is 0 and both tools return a round-off residue (RustQC ~1e-10, R ~3e-12) whose size
     * depends on the solver's stopping rule. A relative comparison of two zeros is meaningless, so an
     * expected value below DUPRADAR_FIT_ZERO is compared on an absolute scale instead
     * (DUPRADAR_FIT_ABS_TOL).
     */
    static void dupradarFitMatch(List<String> actual, List<String> expected) {
        def parse = { List<String> lines ->
            lines.findAll { it.trim() }.collect { line ->
                int i = line.lastIndexOf(':')
                assert i >= 0 : "Not an intercept/slope line: '${line}'"
                [label: line.substring(0, i), value: line.substring(i + 1).trim() as Double]
            }
        }
        def a = parse(actual)
        def e = parse(expected)
        assert e.size() == 2 : "Expected an intercept and a slope line in the upstream output, got ${e.size()} lines"
        assert a*.label == e*.label : "intercept_slope labels differ: RustQC=${a*.label} upstream=${e*.label}"
        [a, e].transpose().each { pair ->
            def (x, y) = pair
            double diff = Math.abs((x.value as double) - (y.value as double))
            if (Math.abs(y.value as double) < DUPRADAR_FIT_ZERO) {
                assert diff <= DUPRADAR_FIT_ABS_TOL : \
                    "${y.label}: RustQC=${x.value} upstream=${y.value} (degenerate fit; absolute diff ${diff}, " +
                    "tolerance ${DUPRADAR_FIT_ABS_TOL})"
            } else {
                double rel = diff / Math.abs(y.value as double)
                assert rel <= DUPRADAR_FIT_REL_TOL : \
                    "${y.label}: RustQC=${x.value} upstream=${y.value} (relative diff ${rel}, " +
                    "tolerance ${DUPRADAR_FIT_REL_TOL})"
            }
        }
    }

    /** Qualimap junction-motif rows: "    ACCT : 10.89%" in rnaseq_qc_results.txt, table rows in qualimapReport.html. */
    static final Map<String, java.util.regex.Pattern> QUALIMAP_MOTIF_ROWS = [
        'rnaseq_qc_results.txt': ~/^(\s+)([ACGTN]{4})( : )([\d.]+)(%)$/,
        'qualimapReport.html'  : ~/^(<td class="column1">)([ACGTN]{4})(<\/td><td class="column2">)([\d.]+)(%<\/td>)$/,
    ].asImmutable()

    /** Qualimap lists at most this many junction motifs. */
    static final int QUALIMAP_MOTIF_LIMIT = 11

    /**
     * The junction-motif rows of a Qualimap output (`file`: a key of QUALIMAP_MOTIF_ROWS) with
     * each run of equal percentages sorted by motif name, and the names of the last run masked
     * when the list is full (QUALIMAP_MOTIF_LIMIT rows: the cut may fall inside a tied run).
     * Percentages and the order of the runs are kept. Other lines are returned unchanged.
     *
     * Qualimap 2.3 orders tied motifs by reverse java.util.HashMap iteration order
     * (TranscriptDataHandler.computeSortedJunctionsMap, then the backwards top-11 loop in
     * RNASeqQCAnalysis), which depends on the table size and on the order the motifs were
     * first seen in its own read order. RustQC sorts tied motifs by name (RustQC #204), which is
     * not Qualimap's HashMap order, so only the set of motifs in each tied run can be compared.
     */
    static List<String> qualimapMotifTiesAsSets(String file, List<String> lines) {
        java.util.regex.Pattern pattern = QUALIMAP_MOTIF_ROWS[file]
        if (pattern == null) return lines
        List<String> out = new ArrayList<String>(lines)
        List<Integer> rows = []
        List<java.util.regex.Matcher> found = []
        for (int i = 0; i < lines.size(); i++) {
            def m = pattern.matcher(lines[i])
            if (m.matches()) {
                rows << i
                found << m
            }
        }
        int start = 0
        while (start < rows.size()) {
            int end = start
            while (end + 1 < rows.size() && found[end + 1].group(4) == found[start].group(4)) end++
            boolean cutOff = rows.size() == QUALIMAP_MOTIF_LIMIT && end == rows.size() - 1
            List<String> names = (start..end).collect { found[it].group(2) }.sort()
            for (int k = start; k <= end; k++) {
                def m = found[k]
                String name = cutOff ? '<tied motif>' : names[k - start]
                out[rows[k]] = m.group(1) + name + m.group(3) + m.group(4) + m.group(5)
            }
            start = end + 1
        }
        return out
    }
}

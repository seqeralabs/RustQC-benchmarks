/**
 * Loads tests/datasets.yml, the manifest of test datasets, and resolves each dataset's
 * inputs to a URL (nf-core/test-datasets at a pinned SHA) or a local path.
 *
 * Used at the top level of a test file to generate one test() per dataset:
 *
 *     import datasets.Datasets
 *     nextflow_process {
 *         Datasets.ofClass('content').each { ds -> test(ds.id) { ... } }
 *     }
 *
 * Environment:
 *   RUSTQC_DATASET_SETS=small  which test sets to generate tests for: small (default), large,
 *                              fullsize, a comma-separated mix, or all (= small,large). Large
 *                              datasets only run where asked for (CI: main, releases and the
 *                              `large` dispatch mode); the fullsize set only by
 *                              checks.FullsizeCompare (the full-size run).
 *   RUSTQC_DATASETS=a,b        only generate tests for these dataset ids
 *   RUSTQC_TESTDATA_DIR=dir    directory of pre-downloaded inputs (tests/fetch_testdata.py, or
 *                              the CI test-data cache). An input whose copy exists there is
 *                              used instead of its URL; anything missing falls back to the URL.
 *   RUSTQC_TESTDATA=local      use each input's `local` copy instead of its `url`
 *   RUSTQC_BASELINE_IMAGE=...  override release_regression.baseline_image
 *   RUSTQC_REFGEN=1            generate the upstream-reference tests (tests/rna/refgen/)
 */
package datasets

import groovy.yaml.YamlSlurper

class Datasets {

    static final String MANIFEST = 'tests/datasets.yml'

    private static Map manifest
    private static List<Dataset> loaded

    /**
     * The datasets to generate tests for, in manifest order: those of the selected sets
     * (RUSTQC_DATASET_SETS), narrowed by RUSTQC_DATASETS.
     */
    static synchronized List<Dataset> all() {
        if (loaded == null) {
            def raw = manifest()
            def sources = (raw.sources ?: [:]) as Map
            loaded = (raw.datasets ?: []).collect { new Dataset(it as Map, sources, projectDir()) }
            def ids = loaded*.id
            def dupes = ids.findAll { id -> ids.count(id) > 1 }.unique()
            assert !dupes : "Duplicate dataset ids in ${MANIFEST}: ${dupes}"
        }
        def sets = selectedSets()
        def selected = loaded.findAll { it.set in sets }
        def only = System.getenv('RUSTQC_DATASETS')
        if (!only?.trim()) return selected
        def wanted = only.split(',')*.trim().findAll() as Set
        def unknown = wanted - (loaded*.id as Set)
        assert !unknown : "RUSTQC_DATASETS names unknown datasets ${unknown}; known: ${loaded*.id}"
        return selected.findAll { it.id in wanted }
    }

    /** Test sets selected by RUSTQC_DATASET_SETS (default: small). */
    static Set<String> selectedSets() {
        def value = System.getenv('RUSTQC_DATASET_SETS')?.trim()
        if (!value) return ['small'] as Set
        if (value == 'all') return Dataset.ALL_SETS as Set
        def sets = value.split(',')*.trim().findAll() as Set
        def unknown = sets - (Dataset.SETS as Set)
        assert !unknown : "RUSTQC_DATASET_SETS names unknown sets ${unknown}; known: ${Dataset.SETS} or all (= ${Dataset.ALL_SETS})"
        return sets
    }

    /** Datasets to generate upstream references for: none unless RUSTQC_REFGEN is set. */
    static List<Dataset> forRefgen() {
        def flag = System.getenv('RUSTQC_REFGEN')
        if (!flag || flag in ['0', 'false']) return []
        return all().findAll { it.references.dir }
    }

    /**
     * The pre-downloaded copy of `url` under RUSTQC_TESTDATA_DIR, or null. A URL maps to
     * <dir>/<url without its scheme>, e.g.
     * https://raw.githubusercontent.com/nf-core/test-datasets/<sha>/data/x.bam ->
     * <dir>/raw.githubusercontent.com/nf-core/test-datasets/<sha>/data/x.bam
     * (tests/fetch_testdata.py writes the same layout). A copy whose md5 differs from the
     * manifest's is ignored, so a stale or tampered cache can never change a test's input.
     */
    static File cachedCopy(String url, String md5 = null) {
        def dir = System.getenv('RUSTQC_TESTDATA_DIR')
        if (!dir) return null
        def f = new File(new File(dir).absoluteFile, cachePath(url))
        if (!f.isFile()) {
            System.err.println("RUSTQC_TESTDATA_DIR: no copy of ${url} in ${dir}, using the URL")
            return null
        }
        if (md5 && md5sum(f) != md5) {
            System.err.println("RUSTQC_TESTDATA_DIR: ${f} does not have md5 ${md5}, using the URL")
            return null
        }
        return f
    }

    private static final Map<String, String> MD5_CACHE = [:]

    private static synchronized String md5sum(File f) {
        return MD5_CACHE.computeIfAbsent(f.path) {
            def digest = java.security.MessageDigest.getInstance('MD5')
            f.withInputStream { is ->
                byte[] buf = new byte[1 << 20]
                int n
                while ((n = is.read(buf)) > 0) digest.update(buf, 0, n)
            }
            digest.digest().encodeHex().toString()
        }
    }

    static String cachePath(String url) {
        def m = url =~ /^[a-zA-Z][a-zA-Z0-9+.-]*:\/\/([^?#]+)/
        assert m.find() : "Not a URL: ${url}"
        def path = m.group(1)
        assert !path.split('/').any { it == '..' } : "Refusing a URL with '..' segments: ${url}"
        return path
    }

    static List<Dataset> ofClass(String cls) {
        return all().findAll { it.cls == cls }
    }

    static Dataset get(String id) {
        def ds = all().find { it.id == id }
        assert ds : "Dataset '${id}' not found in ${MANIFEST}"
        return ds
    }

    /** RustQC image of the last release, which the release-regression test compares against. */
    static String baselineImage() {
        def image = System.getenv('RUSTQC_BASELINE_IMAGE') ?: manifest().release_regression?.baseline_image
        assert image : "release_regression.baseline_image is not set in ${MANIFEST}"
        return image
    }

    /** True when RUSTQC_TESTDATA=local: resolve inputs to their in-repo copies. */
    static boolean useLocal() {
        return System.getenv('RUSTQC_TESTDATA') == 'local'
    }

    static synchronized Map manifest() {
        if (manifest == null) {
            manifest = new YamlSlurper().parse(new File(projectDir(), MANIFEST)) as Map
        }
        return manifest
    }

    /**
     * Repository root. nf-test runs from the directory holding nf-test.config; walk up from
     * the working directory in case it is started from a subdirectory.
     */
    static File projectDir() {
        def dir = new File(System.getProperty('user.dir')).absoluteFile
        while (dir != null) {
            if (new File(dir, MANIFEST).isFile()) return dir
            dir = dir.parentFile
        }
        throw new IllegalStateException("Cannot find ${MANIFEST} above ${System.getProperty('user.dir')}")
    }
}

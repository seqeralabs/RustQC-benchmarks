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
 *   RUSTQC_TESTDATA=local      use each input's `local` copy instead of its `url`
 *   RUSTQC_DATASETS=a,b        only generate tests for these dataset ids
 *   RUSTQC_BASELINE_IMAGE=...  override release_regression.baseline_image
 */
package datasets

import groovy.yaml.YamlSlurper

class Datasets {

    static final String MANIFEST = 'tests/datasets.yml'

    private static Map manifest
    private static List<Dataset> loaded

    /** Every dataset in the manifest, in manifest order (filtered by RUSTQC_DATASETS). */
    static synchronized List<Dataset> all() {
        if (loaded == null) {
            def raw = manifest()
            def sources = (raw.sources ?: [:]) as Map
            loaded = (raw.datasets ?: []).collect { new Dataset(it as Map, sources, projectDir()) }
            def ids = loaded*.id
            def dupes = ids.findAll { id -> ids.count(id) > 1 }.unique()
            assert !dupes : "Duplicate dataset ids in ${MANIFEST}: ${dupes}"
        }
        def only = System.getenv('RUSTQC_DATASETS')
        if (!only) return loaded
        def wanted = only.split(',')*.trim() as Set
        return loaded.findAll { it.id in wanted }
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

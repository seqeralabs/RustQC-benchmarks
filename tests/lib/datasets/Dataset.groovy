/**
 * One entry of tests/datasets.yml, with its inputs resolved. See Datasets.
 */
package datasets

class Dataset {

    /** Test sets: `small` runs on every PR, `large` only on main, the weekly schedule and on demand. */
    static final List<String> SETS = ['small', 'large'].asImmutable()

    /** nf-test tags a manifest entry may not use: CI selects jobs by them. */
    static final List<String> RESERVED_TAGS = ['upstream', 'refgen'].asImmutable()

    String id
    String cls
    String set
    String description
    String sample
    Map<String, Map> inputSpecs = [:]
    private Map sources
    private Map<String, String> resolvedInputs
    Map meta
    Map rustqc
    Map references
    Map expect
    List<String> tags
    File projectDir

    Dataset(Map raw, Map sources, File projectDir) {
        this.projectDir  = projectDir
        this.id          = raw.id
        assert id ==~ /[A-Za-z0-9_]+/ : "Dataset id '${id}' must be alphanumeric/underscore (it is used as an nf-test tag)"
        this.cls         = raw['class'] ?: 'content'
        this.set         = raw.set ?: 'small'
        assert set in SETS : "Dataset '${id}': unknown set '${set}' (one of ${SETS})"
        this.description = raw.description ?: ''
        this.sample      = raw.sample ?: id
        this.meta        = (raw.meta ?: [:]) as Map
        this.rustqc      = (raw.rustqc ?: [:]) as Map
        this.references  = (raw.references ?: [:]) as Map
        this.expect      = (raw.expect ?: [:]) as Map
        this.tags        = (raw.tags ?: []).collect { it as String }
        // Conda CI jobs run only `--tag upstream`, which never runs RustQC; a dataset tag must
        // not pull RustQC tests into them.
        def reserved = tags.intersect(RESERVED_TAGS + SETS)
        assert !reserved : "Dataset '${id}': tags ${reserved} are reserved (sets are added from `set:`)"
        this.sources     = sources
        (raw.inputs ?: [:]).each { String name, spec ->
            inputSpecs[name] = spec instanceof Map ? spec : [url: spec]
        }
        assert inputSpecs.bam && inputSpecs.gtf : "Dataset '${id}' needs at least `bam` and `gtf` inputs"
    }

    /**
     * Input name -> URL or local path. Resolved on first use, so only the datasets a run
     * generates tests for look for (and checksum) a pre-downloaded copy.
     */
    synchronized Map<String, String> getInputs() {
        if (resolvedInputs == null) {
            resolvedInputs = inputSpecs.collectEntries { name, spec -> [(name): resolve(name, spec, sources)] }
        }
        return resolvedInputs
    }

    private String resolve(String name, Map spec, Map sources) {
        String url   = spec.url ? expand(spec.url as String, sources) : null
        String local = spec.local ? new File(projectDir, spec.local as String).absolutePath : null
        if (Datasets.useLocal() && local) return local
        if (url) {
            // A pre-downloaded copy (CI cache, or tests/fetch_testdata.py) wins over the URL,
            // if it has the manifest md5.
            def cached = Datasets.cachedCopy(url, spec.md5 as String)
            if (cached) return cached.absolutePath
            return url
        }
        assert local : "Dataset '${id}' input '${name}' has neither `url` nor `local`"
        return local
    }

    private String expand(String template, Map sources) {
        return template.replaceAll(/\{(\w+)\}/) { all, key ->
            assert sources.containsKey(key) : "Dataset '${id}': unknown source '{${key}}' in ${template}"
            sources[key] as String
        }
    }

    /** Value for RUSTQC_RNA's `meta`, as Groovy source for the process `when` block. */
    String metaLiteral() {
        def m = [id: sample] + meta
        return '[ ' + m.collect { k, v -> "${k}: ${literal(v)}" }.join(', ') + ' ]'
    }

    private static String literal(def v) {
        if (v == null) return 'null'
        if (v instanceof Boolean || v instanceof Number) return v.toString()
        return "'" + v.toString().replace('\\', '\\\\').replace("'", "\\'") + "'"
    }

    /** Basename the BAM is staged under in the task directory. */
    String bamName() {
        return inputs.bam.tokenize('/').last()
    }

    /** Basename of the BAM the upstream references were generated from. */
    String referenceBamName() {
        return references.bam_name ?: bamName()
    }

    File referencesDir() {
        return references.dir ? new File(projectDir, references.dir as String) : null
    }

    /** match | regression | absent | upstream_error */
    String expectFor(String tool) {
        def e = (expect[tool] ?: 'match') as String
        assert e in ['match', 'regression', 'absent', 'upstream_error'] : \
            "Dataset '${id}': unknown expectation '${e}' for ${tool}"
        return e
    }

    /** nf-test tags for this dataset: ds_<id>, its set (small/large) and the manifest tags. */
    List<String> allTags() {
        return ["ds_${id}".toString(), set] + tags
    }

    String toString() { "Dataset(${id})" }
}

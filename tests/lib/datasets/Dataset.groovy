/**
 * One entry of tests/datasets.yml, with its inputs resolved. See Datasets.
 */
package datasets

class Dataset {

    String id
    String cls
    String description
    String sample
    Map<String, String> inputs = [:]
    Map<String, Map> inputSpecs = [:]
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
        this.description = raw.description ?: ''
        this.sample      = raw.sample ?: id
        this.meta        = (raw.meta ?: [:]) as Map
        this.rustqc      = (raw.rustqc ?: [:]) as Map
        this.references  = (raw.references ?: [:]) as Map
        this.expect      = (raw.expect ?: [:]) as Map
        this.tags        = (raw.tags ?: []).collect { it as String }
        (raw.inputs ?: [:]).each { String name, spec ->
            def s = spec instanceof Map ? spec : [url: spec]
            inputSpecs[name] = s
            inputs[name] = resolve(name, s, sources)
        }
        assert inputs.bam && inputs.gtf : "Dataset '${id}' needs at least `bam` and `gtf` inputs"
    }

    private String resolve(String name, Map spec, Map sources) {
        String url   = spec.url ? expand(spec.url as String, sources) : null
        String local = spec.local ? new File(projectDir, spec.local as String).absolutePath : null
        if (Datasets.useLocal() && local) return local
        if (url) return url
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

    /** nf-test tags for this dataset: ds_<id> plus the manifest tags. */
    List<String> allTags() {
        return ["ds_${id}".toString()] + tags
    }

    String toString() { "Dataset(${id})" }
}

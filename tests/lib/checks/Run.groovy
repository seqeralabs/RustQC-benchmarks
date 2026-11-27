/**
 * One RustQC run on one dataset, as seen by the checks: where its outputs and the
 * dataset's upstream references live, plus helpers to read them.
 */
package checks

import java.security.MessageDigest

class Run {

    /** The datasets.Dataset this run used (untyped: see Checks). */
    final def ds
    /** The `rustqc/` output directory of RUSTQC_RNA. */
    final File outDir

    Run(def ds, def outDir) {
        this.ds = ds
        this.outDir = new File(outDir.toString())
    }

    /** A RustQC output; `{sample}` in `rel` is replaced by the sample name. */
    File output(String rel) {
        return new File(outDir, rel.replace('{sample}', ds.sample as String))
    }

    /** An upstream reference file of this dataset; `{sample}` as for output(). */
    File reference(String rel) {
        def dir = ds.referencesDir()
        assert dir : "Dataset '${ds.id}' has no references.dir in tests/datasets.yml"
        return new File(dir, rel.replace('{sample}', ds.sample as String))
    }

    /**
     * Lines of a RustQC output, with the staged BAM basename replaced by the one the
     * references were generated from. featureCounts, TIN and qualimap echo the input
     * name, and a dataset pulled from nf-core is staged under its nf-core file name.
     */
    List<String> lines(File f) {
        assert f.isFile() : "Missing RustQC output: ${f}"
        def from = ds.bamName() as String
        def to   = ds.referenceBamName() as String
        def lines = f.readLines()
        return from == to ? lines : lines.collect { it.replace(from, to) }
    }

    /**
     * Assert a RustQC output is byte-identical to an upstream reference, after the BAM-name
     * normalisation of lines(). Equivalent to comparing md5s when the names already agree.
     */
    void assertIdentical(String outputRel, String referenceRel, String what) {
        def out = output(outputRel)
        def ref = reference(referenceRel)
        assert out.isFile() : "Missing RustQC output: ${out}"
        assert ref.isFile() : "Missing upstream reference: ${ref}"
        def from = ds.bamName() as String
        def to   = ds.referenceBamName() as String
        def actual = from == to ? out.text : out.text.replace(from, to)
        if (actual == ref.text) return
        // Not identical: let textMatch report the differing lines (it ignores blank lines and
        // surrounding whitespace, so fall back to a generic message if it finds none).
        CompareUtils.textMatch(lines(out), ref.readLines(), [])
        assert false : "${what} differs from upstream (${ref}) in whitespace or line endings only"
    }

    /** Lines of an upstream reference file. */
    List<String> referenceLines(String rel) {
        def f = reference(rel)
        assert f.isFile() : "Missing upstream reference: ${f}"
        return f.readLines()
    }

    static String md5(List<String> lines) {
        def digest = MessageDigest.getInstance('MD5')
        lines.each { digest.update((it + '\n').getBytes('UTF-8')) }
        return digest.digest().encodeHex().toString()
    }
}

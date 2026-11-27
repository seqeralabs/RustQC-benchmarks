/**
 * Unqualified alias for checks.CompareUtils, kept so per-tool tests can keep calling
 * `CompareUtils.textMatch(...)` / `CompareUtils.tsvMatch(...)` without an import.
 *
 * The implementation moved to tests/lib/checks/CompareUtils.groovy because the shared
 * checks library (package `checks`) cannot reference default-package classes.
 * Groovy resolves the static methods through the superclass.
 */
class CompareUtils extends checks.CompareUtils {
}

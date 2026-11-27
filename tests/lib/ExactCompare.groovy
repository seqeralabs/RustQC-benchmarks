/**
 * Unqualified alias for checks.ExactCompare, kept so per-tool tests can call
 * `ExactCompare.exactMatch(...)` without an import (the implementation lives in the `checks`
 * package so the shared checks library can use it too; see tests/lib/CompareUtils.groovy).
 */
class ExactCompare extends checks.ExactCompare {
}

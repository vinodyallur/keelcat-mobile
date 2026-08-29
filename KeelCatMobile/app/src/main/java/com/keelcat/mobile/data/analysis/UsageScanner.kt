package com.keelcat.mobile.data.analysis

import com.keelcat.mobile.domain.AffectedFile
import com.keelcat.mobile.domain.BreakingChange

/**
 * Finds files that reference changed symbols. A cheap, deterministic pre-filter
 * so the on-device LLM only has to reason about files that actually matter.
 */
object UsageScanner {

    private val codeExtensions = setOf(
        "kt", "java", "js", "jsx", "ts", "tsx", "py", "go", "rb", "php",
        "swift", "c", "cc", "cpp", "cs", "rs", "scala",
    )

    fun isSourceFile(path: String): Boolean =
        path.substringAfterLast('.', "").lowercase() in codeExtensions

    /** Return the subset of [files] that use any of the changed symbols. */
    fun scan(
        files: List<Pair<String, String>>, // (path, content)
        changes: List<BreakingChange>,
    ): List<AffectedFile> {
        val symbols = changes.map { it.symbol }.filter { it.isNotBlank() }
        if (symbols.isEmpty()) return emptyList()

        return files.mapNotNull { (path, content) ->
            val matched = symbols.filter { sym -> containsSymbol(content, sym) }
            if (matched.isEmpty()) null
            else AffectedFile(path = path, content = content, matchedSymbols = matched)
        }
    }

    /** Whole-word match so "getUser" doesn't match "getUserName". */
    private fun containsSymbol(content: String, symbol: String): Boolean {
        val re = Regex("(?<![A-Za-z0-9_])${Regex.escape(symbol)}(?![A-Za-z0-9_])")
        return re.containsMatchIn(content)
    }
}

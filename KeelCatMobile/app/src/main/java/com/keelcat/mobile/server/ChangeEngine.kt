package com.keelcat.mobile.server

import org.json.JSONArray
import org.json.JSONObject

/**
 * Turns a changelog into structured changes, and applies renames to payment_method
 * deterministically — mirroring the web backend's behaviour so the on-device
 * flow is reliable even without a heavyweight model. The LLM is an optional
 * assist for free-form changelogs.
 *
 * ChangeSpec JSON: { provider?, symbol, kind, from?, to?, impact, description }
 */
object ChangeEngine {

    // Dotted identifiers (customers.create) but each segment is a real ident,
    // so a trailing sentence period isn't captured.
    private const val ID = "[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)*"

    // `A` -> `B` / A → B / A => B
    private val ARROW = Regex("[`\"]?($ID)[`\"]?\\s*(?:->|\u2192|=>)\\s*[`\"]?($ID)[`\"]?")
    // renamed/replaced A to/with B
    private val RENAMED = Regex("(?:renamed?|replaced)\\s+[`\"]?($ID)[`\"]?\\s+(?:to|with|by)\\s+[`\"]?($ID)[`\"]?", RegexOption.IGNORE_CASE)
    // A is now B / A renamed to B / A now called B
    private val IS_NOW = Regex("[`\"]?($ID)[`\"]?\\s+(?:is now|now called|renamed to|has been renamed to)\\s+[`\"]?($ID)[`\"]?", RegexOption.IGNORE_CASE)
    // removed/deleted/dropped A  |  A was removed
    private val REMOVED_PRE = Regex("(?:removed|deleted|dropped)\\s+[`\"]?($ID)[`\"]?", RegexOption.IGNORE_CASE)
    private val REMOVED_POST = Regex("[`\"]?($ID)[`\"]?\\s+(?:was|is|has been)\\s+(?:removed|deleted|dropped)", RegexOption.IGNORE_CASE)
    // deprecated A  |  A is deprecated
    private val DEPRECATED_PRE = Regex("deprecated\\s+[`\"]?($ID)[`\"]?", RegexOption.IGNORE_CASE)
    private val DEPRECATED_POST = Regex("[`\"]?($ID)[`\"]?\\s+is\\s+deprecated", RegexOption.IGNORE_CASE)

    /**
     * Deterministic parse first (fast, reliable). If it finds nothing and an
     * LLM is available, fall back to on-device inference.
     */
    fun parse(text: String, llm: OnDeviceLlm?): JSONArray {
        val det = parseDeterministic(text)
        if (det.length() > 0) return det
        if (llm != null && llm.isReady()) {
            runCatching { return llm.parseChangelog(text) }
        }
        return det
    }

    fun parseDeterministic(text: String): JSONArray {
        val seen = HashSet<String>()
        val out = JSONArray()

        fun add(symbol: String, kind: String, from: String?, to: String?, impact: String, desc: String) {
            if (symbol.isBlank()) return
            val key = "$kind:$symbol:${to ?: ""}"
            if (!seen.add(key)) return
            val o = JSONObject()
                .put("symbol", symbol)
                .put("kind", kind)
                .put("impact", impact)
                .put("description", desc.trim().take(200))
            if (from != null) o.put("from", from)
            if (to != null) o.put("to", to)
            out.put(o)
        }

        for (rawLine in text.lines()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue

            RENAMED.find(line)?.let { m ->
                add(m.groupValues[1], kindForRename(m.groupValues[1], m.groupValues[2]), m.groupValues[1], m.groupValues[2], "BREAKING", line)
            }
            IS_NOW.find(line)?.let { m ->
                add(m.groupValues[1], kindForRename(m.groupValues[1], m.groupValues[2]), m.groupValues[1], m.groupValues[2], "BREAKING", line)
            }
            // Arrow only if line hints at a rename/change (avoid matching prose arrows)
            if (line.contains("->") || line.contains("\u2192") || line.contains("=>")) {
                ARROW.find(line)?.let { m ->
                    if (m.groupValues[1] != m.groupValues[2]) {
                        add(m.groupValues[1], kindForRename(m.groupValues[1], m.groupValues[2]), m.groupValues[1], m.groupValues[2], "BREAKING", line)
                    }
                }
            }
            REMOVED_PRE.find(line)?.let { m -> add(m.groupValues[1], "SYMBOL_REMOVED", null, null, "BREAKING", line) }
            REMOVED_POST.find(line)?.let { m -> add(m.groupValues[1], "SYMBOL_REMOVED", null, null, "BREAKING", line) }
            DEPRECATED_PRE.find(line)?.let { m -> add(m.groupValues[1], "SYMBOL_DEPRECATED", null, null, "DEPRECATION", line) }
            DEPRECATED_POST.find(line)?.let { m -> add(m.groupValues[1], "SYMBOL_DEPRECATED", null, null, "DEPRECATION", line) }
        }
        return out
    }

    private fun kindForRename(from: String, to: String): String {
        // Heuristic: snake/lower single words that read like params -> PARAM_RENAME
        val looksLikeParam = from.none { it == '.' } && from == from.lowercase() && to == to.lowercase()
        return if (looksLikeParam) "PARAM_RENAME" else "SYMBOL_RENAME"
    }

    /**
     * Apply a single change to file content. Returns (newContent, replacements).
     * Only renames (from/to present) are auto-applied; removals/deprecations are
     * left for human review (reported as unresolved by the pipeline).
     */
    fun apply(content: String, change: JSONObject): Pair<String, Int> {
        val from = change.optString("from")
        val to = change.optString("to")
        if (from.isBlank() || to.isBlank()) return content to 0

        val simpleId = Regex("^[A-Za-z_][A-Za-z0-9_]*$")
        return if (simpleId.matches(from)) {
            // whole-word replace so getUser doesn't hit getUserName
            val re = Regex("(?<![A-Za-z0-9_])${Regex.escape(from)}(?![A-Za-z0-9_])")
            var count = 0
            val newContent = re.replace(content) { count++; to }
            newContent to count
        } else {
            // dotted/complex: replace the last segment as a whole word
            val fromLast = from.substringAfterLast('.')
            val toLast = to.substringAfterLast('.')
            if (simpleId.matches(fromLast) && toLast.isNotBlank()) {
                val re = Regex("(?<![A-Za-z0-9_])${Regex.escape(fromLast)}(?![A-Za-z0-9_])")
                var count = 0
                val newContent = re.replace(content) { count++; toLast }
                newContent to count
            } else content to 0
        }
    }

    /** Whether any change mentions a symbol present in the content (pre-filter). */
    fun mentions(content: String, change: JSONObject): Boolean {
        val symbols = listOfNotNull(
            change.optString("from").ifBlank { null },
            change.optString("symbol").ifBlank { null },
        )
        return symbols.any { sym ->
            val target = if (sym.contains('.')) sym.substringAfterLast('.') else sym
            target.isNotBlank() && Regex("(?<![A-Za-z0-9_])${Regex.escape(target)}(?![A-Za-z0-9_])").containsMatchIn(content)
        }
    }
}

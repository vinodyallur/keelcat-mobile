package com.keelcat.mobile.server

import org.json.JSONArray
import org.json.JSONObject

/**
 * Turns a changelog into structured changes, and applies renames to source
 * deterministically — mirroring the web backend's behaviour so the on-device
 * flow is reliable even without a heavyweight model. The LLM is an optional
 * assist for free-form changelogs.
 *
 * ChangeSpec JSON: { provider?, symbol, kind, from?, to?, impact, description }
 */
object ChangeEngine {

    // A "strong" identifier: quoted ("x"), backticked (`x`) or single-quoted,
    // optionally dotted (customers.create). These are the real API symbols in a
    // changelog; plain English words (parameter, method, in, and) are never quoted.
    private val QUOTED_ID = Regex("[`\"']([A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)*)[`\"']")
    // A bare identifier token (fallback when nothing is quoted).
    private val BARE_ID = Regex("([A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)*)")
    // Phrases that join the old name (left) to the new name (right) in a rename.
    private val RENAME_CONNECTORS = listOf(" to ", " with ", "->", "\u2192", "=>", "is now", "now called")

    // Does a bare token look like code (dotted / camelCase / snake_case / has a
    // digit)?  Filters out ordinary words so "parameter"/"method"/"in" are ignored.
    private fun looksLikeCode(t: String): Boolean =
        t.contains('.') || t.contains('_') || t.any { it.isDigit() } || Regex("[a-z][A-Z]").containsMatchIn(t)

    // The primary symbol on a line: a quoted id if present, else the first
    // code-like bare token.
    private fun firstIdentifier(line: String, quoted: List<String>): String? =
        quoted.firstOrNull()
            ?: BARE_ID.findAll(line).map { it.groupValues[1] }.firstOrNull { looksLikeCode(it) }

    // Earliest rename-connector position in the line as (index, length), or (-1, 0).
    private fun connectorAt(line: String): Pair<Int, Int> {
        var best = -1; var len = 0
        val lower = line.lowercase()
        for (c in RENAME_CONNECTORS) {
            val i = lower.indexOf(c)
            if (i >= 0 && (best < 0 || i < best)) { best = i; len = c.length }
        }
        return best to len
    }

    // (from, to) for a rename line: the id just before the connector -> the id
    // just after it. Prefers quoted ids; falls back to code-like bare tokens.
    private fun renamePair(line: String): Pair<String, String>? {
        val q = QUOTED_ID.findAll(line).map { it.range.first to it.groupValues[1] }.toList()
        val (idx, len) = connectorAt(line)
        if (q.size >= 2) {
            if (idx >= 0) {
                val from = q.lastOrNull { it.first < idx }?.second
                val to = q.firstOrNull { it.first > idx }?.second
                if (from != null && to != null) return from to to
            }
            return q.first().second to q.last().second
        }
        if (idx >= 0) {
            val from = BARE_ID.findAll(line.substring(0, idx)).map { it.groupValues[1] }.lastOrNull { looksLikeCode(it) }
            val to = BARE_ID.findAll(line.substring(idx + len)).map { it.groupValues[1] }.firstOrNull { looksLikeCode(it) }
            if (from != null && to != null) return from to to
        }
        return null
    }

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

            val quoted = QUOTED_ID.findAll(line).map { it.groupValues[1] }.toList()
            val lower = line.lowercase()
            val isRename = lower.contains("renam") || lower.contains("is now") ||
                lower.contains("now called") || lower.contains("replaced") ||
                line.contains("->") || line.contains("\u2192") || line.contains("=>")
            val isDeprecated = lower.contains("deprecat")
            val isRemoved = lower.contains("removed") || lower.contains("deleted") || lower.contains("dropped")

            // Deprecation wins over a "will be removed" mention on the same line.
            when {
                isDeprecated -> firstIdentifier(line, quoted)?.let {
                    add(it, "SYMBOL_DEPRECATED", null, null, "DEPRECATION", line)
                }
                isRename -> {
                    val pair = renamePair(line)
                    if (pair != null && pair.first != pair.second) {
                        add(pair.first, kindForRename(pair.first, pair.second), pair.first, pair.second, "BREAKING", line)
                    } else if (isRemoved) {
                        firstIdentifier(line, quoted)?.let { add(it, "SYMBOL_REMOVED", null, null, "BREAKING", line) }
                    }
                }
                isRemoved -> firstIdentifier(line, quoted)?.let {
                    add(it, "SYMBOL_REMOVED", null, null, "BREAKING", line)
                }
            }
        }
        return out
    }

    private fun kindForRename(from: String, to: String): String {
        // Heuristic: snake/lower single words that read like params -> PARAM_RENAME
        val looksLikeParam = from.none { it == '.' } && from == from.lowercase() && to == to.lowercase()
        return if (looksLikeParam) "PARAM_RENAME" else "SYMBOL_RENAME"
    }

    // ---- enum canonicalization (parity with the keelcat.in web backend) ----
    // The web UI colors changes by their exact `impact` value
    // (BREAKING/DEPRECATION/NEW_FEATURE) and expects a fixed set of `kind`
    // values. The deterministic parser already emits these, but an LLM (on-device
    // or cloud) can return "deprecated", lowercase, or odd kinds. We map every
    // change onto the canonical enums so the mobile UI colors them like desktop.
    private val CANONICAL_KINDS = setOf(
        "PARAM_RENAME", "SYMBOL_RENAME", "PARAM_REMOVED", "PARAM_ADDED_REQUIRED",
        "SYMBOL_REMOVED", "SYMBOL_DEPRECATED", "SYMBOL_ADDED", "OTHER"
    )

    fun normalizeImpact(raw: String): String {
        val s = raw.trim().uppercase()
        return when {
            s.contains("DEPREC") -> "DEPRECATION"
            s.contains("BREAK") -> "BREAKING"
            s.contains("FEATURE") || s.contains("NEW") || s.contains("ADD") -> "NEW_FEATURE"
            else -> "BREAKING"
        }
    }

    fun normalizeKind(raw: String, impact: String, hasFromTo: Boolean): String {
        val s = raw.trim().uppercase().replace('-', '_').replace(' ', '_')
        if (s in CANONICAL_KINDS) return s
        return when {
            s.contains("DEPREC") -> "SYMBOL_DEPRECATED"
            s.contains("PARAM") && (s.contains("REMOV") || s.contains("DELET")) -> "PARAM_REMOVED"
            s.contains("PARAM") && s.contains("ADD") -> "PARAM_ADDED_REQUIRED"
            s.contains("PARAM") && s.contains("RENAM") -> "PARAM_RENAME"
            s.contains("REMOV") || s.contains("DELET") || s.contains("DROP") -> "SYMBOL_REMOVED"
            s.contains("RENAM") -> "SYMBOL_RENAME"
            s.contains("ADD") -> "SYMBOL_ADDED"
            impact == "DEPRECATION" -> "SYMBOL_DEPRECATED"
            hasFromTo -> "SYMBOL_RENAME"
            else -> "OTHER"
        }
    }

    /** Canonicalize impact/kind on every change so the UI colors them like desktop. */
    fun normalizeAll(arr: JSONArray): JSONArray {
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val impact = normalizeImpact(o.optString("impact"))
            val hasFromTo = o.optString("from").isNotBlank() && o.optString("to").isNotBlank()
            o.put("impact", impact)
            o.put("kind", normalizeKind(o.optString("kind"), impact, hasFromTo))
        }
        return arr
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

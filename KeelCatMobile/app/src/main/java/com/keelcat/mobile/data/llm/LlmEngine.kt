package com.keelcat.mobile.data.llm

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.keelcat.mobile.domain.AffectedFile
import com.keelcat.mobile.domain.BreakingChange
import com.keelcat.mobile.domain.FixProposal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * On-device LLM. All inference runs on the phone (NPU/GPU/CPU via MediaPipe),
 * so changelogs and source code never leave the device.
 *
 * Point [modelPath] at a small instruction-tuned model deployed to the device,
 * e.g. /data/local/tmp/llm/gemma.task pushed with `adb push`.
 */
class LlmEngine(
    private val context: Context,
    private val modelPath: String,
) {
    private var llm: LlmInference? = null

    fun load() {
        if (llm != null) return
        val options = LlmInference.LlmInferenceOptions.builder()
            .setModelPath(modelPath)
            .setMaxTokens(1024)
            .setTopK(40)
            .setTemperature(0.2f)
            .build()
        llm = LlmInference.createFromOptions(context, options)
    }

    fun close() {
        llm?.close()
        llm = null
    }

    private fun infer(prompt: String): String {
        val engine = llm ?: error("LlmEngine.load() must be called first")
        return engine.generateResponse(prompt)
    }

    /** Extract breaking changes from a raw changelog. Runs fully on-device. */
    suspend fun parseChangelog(changelog: String): List<BreakingChange> = withContext(Dispatchers.Default) {
        val prompt = """
            You are an API-compatibility analyzer. Read the changelog and list ONLY
            breaking changes that affect calling code. Respond with STRICT JSON:
            an array of objects with keys: symbol, kind, replacement, summary.
            kind is one of: renamed, removed, signature-changed, moved.
            If nothing breaks, return [].

            Changelog:
            ---
            $changelog
            ---
            JSON:
        """.trimIndent()

        val json = extractJsonArray(infer(prompt))
        buildList {
            for (i in 0 until json.length()) {
                val o = json.optJSONObject(i) ?: continue
                add(
                    BreakingChange(
                        symbol = o.optString("symbol"),
                        kind = o.optString("kind", "renamed"),
                        replacement = o.optString("replacement").ifBlank { null },
                        summary = o.optString("summary"),
                    )
                )
            }
        }
    }

    /**
     * Produce the full updated content of [file] migrated for [change].
     * Full-file output is what the GitHub Contents API and the runner consume.
     */
    suspend fun generateFix(file: AffectedFile, change: BreakingChange): FixProposal =
        withContext(Dispatchers.Default) {
            val prompt = """
                You migrate code for breaking API changes. Rewrite the ENTIRE file
                below so it works with this change. Keep everything else identical.
                Output ONLY the full updated file contents, no prose, no code fences.

                Breaking change: ${change.kind} ${change.symbol}${
                change.replacement?.let { " -> $it" } ?: ""
            }. ${change.summary}

                File path: ${file.path}
                --- FILE ---
                ${file.content}
                --- END FILE ---
            """.trimIndent()

            val updated = stripFences(infer(prompt)).trim() + "\n"
            FixProposal(
                summary = "Migrate ${change.symbol} in ${file.path}",
                path = file.path,
                originalContent = file.content,
                newContent = updated,
            )
        }

    companion object {
        /** Pull the first JSON array out of a model response. */
        fun extractJsonArray(text: String): JSONArray {
            val start = text.indexOf('[')
            val end = text.lastIndexOf(']')
            if (start in 0 until end) {
                runCatching { return JSONArray(text.substring(start, end + 1)) }
            }
            // Some models return a single object; wrap it.
            val objStart = text.indexOf('{')
            val objEnd = text.lastIndexOf('}')
            if (objStart in 0 until objEnd) {
                runCatching {
                    return JSONArray().put(JSONObject(text.substring(objStart, objEnd + 1)))
                }
            }
            return JSONArray()
        }

        fun stripFences(text: String): String =
            text.replace(Regex("```[a-zA-Z]*\\n?"), "").replace("```", "")
    }
}

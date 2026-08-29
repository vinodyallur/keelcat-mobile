package com.keelcat.mobile.server

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Crash-safe wrapper around MediaPipe's on-device LLM. Loads lazily from a
 * model file on the device; if the model is absent or the runtime fails, it
 * reports not-ready and the pipeline falls back to deterministic parsing.
 *
 * This is the "runs on the phone's CPU/NPU" piece — no cloud, no API key.
 * Uses the current tasks-genai session API: the engine holds the model, and a
 * short-lived session carries the sampling options (topK/temperature).
 */
class OnDeviceLlm(private val context: Context, private val modelPath: String) {

    @Volatile private var llm: LlmInference? = null
    @Volatile private var triedLoad = false
    @Volatile private var loadFailed = false

    @Synchronized
    private fun ensureLoaded() {
        if (triedLoad) return
        triedLoad = true
        try {
            if (!File(modelPath).exists()) { loadFailed = true; return }
            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelPath)
                .setMaxTokens(1024)
                .build()
            llm = LlmInference.createFromOptions(context, options)
        } catch (t: Throwable) {
            loadFailed = true
        }
    }

    fun isReady(): Boolean {
        ensureLoaded()
        return llm != null && !loadFailed
    }

    fun parseChangelog(text: String): JSONArray {
        val engine = llm ?: return JSONArray()
        val prompt = """
            You are an API-compatibility analyzer. Read the changelog and list ONLY
            breaking changes that affect calling code. Respond with STRICT JSON: an
            array of objects with keys: symbol, kind, from, to, impact, description.
            kind is one of: SYMBOL_RENAME, PARAM_RENAME, SYMBOL_REMOVED, SYMBOL_DEPRECATED.
            impact is one of: BREAKING, DEPRECATION, NEW_FEATURE.
            For a rename, "from" is the OLD symbol name and "to" is the NEW symbol name
            (not version numbers). If nothing breaks, return [].

            Changelog:
            ---
            $text
            ---
            JSON:
        """.trimIndent()

        val raw = runCatching {
            val sessionOptions = LlmInferenceSession.LlmInferenceSessionOptions.builder()
                .setTopK(40)
                .setTemperature(0.2f)
                .build()
            LlmInferenceSession.createFromOptions(engine, sessionOptions).use { session ->
                session.addQueryChunk(prompt)
                session.generateResponse()
            }
        }.getOrNull() ?: return JSONArray()
        return extractArray(raw)
    }

    private fun extractArray(text: String): JSONArray {
        val start = text.indexOf('[')
        val end = text.lastIndexOf(']')
        if (start in 0 until end) {
            runCatching { return JSONArray(text.substring(start, end + 1)) }
        }
        return JSONArray()
    }
}

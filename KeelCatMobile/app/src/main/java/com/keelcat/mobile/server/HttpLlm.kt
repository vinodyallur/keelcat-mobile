package com.keelcat.mobile.server

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * OpenAI-compatible chat client. One client covers both a LOCAL LLM (e.g.
 * Ollama at http://host:11434/v1, no key) and a CLOUD LLM (OpenRouter/OpenAI,
 * with a key). The changelog is turned into structured changes by the model.
 */
class HttpLlm(baseUrl: String, private val apiKey: String, private val model: String) {

    private val base = baseUrl.trim().trimEnd('/')
    private val http = OkHttpClient.Builder()
        .callTimeout(120, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()
    private val jsonMedia = "application/json".toMediaType()

    private fun chat(prompt: String): String {
        val payload = JSONObject()
            .put("model", model)
            .put("temperature", 0.2)
            .put("stream", false)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))

        val builder = Request.Builder()
            .url("$base/chat/completions")
            .post(payload.toString().toRequestBody(jsonMedia))
            .header("Content-Type", "application/json")
        if (apiKey.isNotBlank()) builder.header("Authorization", "Bearer $apiKey")

        http.newCall(builder.build()).execute().use { r ->
            val body = r.body?.string().orEmpty()
            if (!r.isSuccessful) error("LLM ${r.code}: ${body.take(300)}")
            val json = JSONObject(body)
            val choices = json.optJSONArray("choices") ?: return ""
            if (choices.length() == 0) return ""
            return choices.getJSONObject(0).optJSONObject("message")?.optString("content").orEmpty()
        }
    }

    /** Quick connectivity/sanity check. Returns a short sample of the reply. */
    fun ping(): String {
        val reply = chat("Reply with exactly: OK")
        return reply.trim().ifBlank { "(empty reply)" }.take(80)
    }

    fun parseChangelog(text: String): JSONArray {
        val prompt = """
            You are an API-compatibility analyzer. Read the changelog and list ONLY
            breaking changes that affect calling code. Respond with STRICT JSON: an
            array of objects with keys: symbol, kind, from, to, impact, description.
            kind is one of: SYMBOL_RENAME, PARAM_RENAME, SYMBOL_REMOVED, SYMBOL_DEPRECATED.
            impact is one of: BREAKING, DEPRECATION, NEW_FEATURE.
            If nothing breaks, return [].

            Changelog:
            ---
            $text
            ---
            JSON:
        """.trimIndent()
        return extractArray(chat(prompt))
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

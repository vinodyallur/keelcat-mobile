package com.keelcat.mobile.data.officekit

import com.keelcat.mobile.domain.FixProposal
import com.keelcat.mobile.domain.MonitoredRepo
import com.keelcat.mobile.domain.VerifyResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Talks to the laptop-side runner over the Office Kit bridge (same LAN).
 * This is the "heavy compute" leg: the laptop applies the fix and runs the
 * test suite, which is where the Office Kit usage telemetry comes from.
 *
 * [baseUrl] is the laptop address, e.g. "http://192.168.1.42:8787".
 */
class OfficeKitRunner(private val baseUrl: String) {

    private val http = OkHttpClient.Builder()
        .callTimeout(10, TimeUnit.MINUTES)
        .readTimeout(10, TimeUnit.MINUTES)
        .build()
    private val json = "application/json".toMediaType()

    suspend fun health(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder().url("$baseUrl/health").get().build()
            http.newCall(req).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    suspend fun verify(
        repo: MonitoredRepo,
        fixes: List<FixProposal>,
        testCommand: String,
    ): VerifyResult = withContext(Dispatchers.IO) {
        val files = JSONArray()
        for (fix in fixes) {
            files.put(JSONObject().put("path", fix.path).put("content", fix.newContent))
        }
        val payload = JSONObject()
            .put("payment_method", repo.cloneUrl)
            .put("ref", repo.defaultBranch)
            .put("files", files)
            .put("testCommand", testCommand)

        val req = Request.Builder().url("$baseUrl/verify")
            .post(payload.toString().toRequestBody(json)).build()

        http.newCall(req).execute().use { r ->
            val body = r.body?.string().orEmpty()
            val o = JSONObject(body)
            val steps = o.optJSONArray("steps") ?: JSONArray()
            val log = buildString {
                for (i in 0 until steps.length()) {
                    val s = steps.getJSONObject(i)
                    appendLine("[${s.optString("step")}] exit=${s.optInt("code")}")
                    appendLine(s.optString("log"))
                }
            }
            VerifyResult(
                applied = o.optBoolean("applied"),
                passed = o.optBoolean("passed"),
                stage = o.optString("stage"),
                log = log.trim(),
            )
        }
    }
}

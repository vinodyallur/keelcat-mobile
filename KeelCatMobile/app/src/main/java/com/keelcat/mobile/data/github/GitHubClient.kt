package com.keelcat.mobile.data.github

import android.util.Base64
import com.keelcat.mobile.domain.FixProposal
import com.keelcat.mobile.domain.MonitoredRepo
import com.keelcat.mobile.domain.PrResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Minimal GitHub REST client. Authenticates with a personal access token
 * (fast path for the hackathon; OAuth device flow is a stretch goal).
 */
class GitHubClient(private val token: String) {

    private val http = OkHttpClient()
    private val json = "application/json".toMediaType()
    private val api = "https://api.github.com"

    private fun get(url: String): JSONObject {
        val req = Request.Builder().url(url).header("Authorization", "Bearer $token")
            .header("Accept", "application/vnd.github+json").build()
        http.newCall(req).execute().use { r ->
            val body = r.body?.string().orEmpty()
            if (!r.isSuccessful) error("GET $url -> ${r.code}: $body")
            return JSONObject(body)
        }
    }

    private fun getArray(url: String): JSONArray {
        val req = Request.Builder().url(url).header("Authorization", "Bearer $token")
            .header("Accept", "application/vnd.github+json").build()
        http.newCall(req).execute().use { r ->
            val body = r.body?.string().orEmpty()
            if (!r.isSuccessful) error("GET $url -> ${r.code}: $body")
            return JSONArray(body)
        }
    }

    private fun post(url: String, payload: JSONObject): JSONObject {
        val req = Request.Builder().url(url).header("Authorization", "Bearer $token")
            .header("Accept", "application/vnd.github+json")
            .post(payload.toString().toRequestBody(json)).build()
        http.newCall(req).execute().use { r ->
            val body = r.body?.string().orEmpty()
            if (!r.isSuccessful) error("POST $url -> ${r.code}: $body")
            return JSONObject(body)
        }
    }

    private fun put(url: String, payload: JSONObject): JSONObject {
        val req = Request.Builder().url(url).header("Authorization", "Bearer $token")
            .header("Accept", "application/vnd.github+json")
            .put(payload.toString().toRequestBody(json)).build()
        http.newCall(req).execute().use { r ->
            val body = r.body?.string().orEmpty()
            if (!r.isSuccessful) error("PUT $url -> ${r.code}: $body")
            return JSONObject(body)
        }
    }

    /** List payment_method file paths in the repo (recursive tree of the default branch). */
    suspend fun listSourceFiles(repo: MonitoredRepo): List<String> = withContext(Dispatchers.IO) {
        val tree = get("$api/repos/${repo.slug}/git/trees/${repo.defaultBranch}?recursive=1")
        val arr = tree.optJSONArray("tree") ?: JSONArray()
        buildList {
            for (i in 0 until arr.length()) {
                val node = arr.getJSONObject(i)
                if (node.optString("type") == "blob") add(node.optString("path"))
            }
        }
    }

    /** Fetch and decode a single file's content. */
    suspend fun getFileContent(repo: MonitoredRepo, path: String): String = withContext(Dispatchers.IO) {
        val o = get("$api/repos/${repo.slug}/contents/$path?ref=${repo.defaultBranch}")
        decodeBase64(o.optString("content"))
    }

    private fun currentSha(repo: MonitoredRepo, path: String, branch: String): String? =
        runCatching { get("$api/repos/${repo.slug}/contents/$path?ref=$branch").optString("sha") }
            .getOrNull()

    /**
     * Open a PR: create a branch off the default branch, commit each updated
     * file via the Contents API, then open the pull request.
     */
    suspend fun openPr(
        repo: MonitoredRepo,
        branch: String,
        title: String,
        body: String,
        fixes: List<FixProposal>,
    ): PrResult = withContext(Dispatchers.IO) {
        val baseRef = get("$api/repos/${repo.slug}/git/ref/heads/${repo.defaultBranch}")
        val baseSha = baseRef.getJSONObject("object").getString("sha")

        post(
            "$api/repos/${repo.slug}/git/refs",
            JSONObject().put("ref", "refs/heads/$branch").put("sha", baseSha),
        )

        for (fix in fixes) {
            val payload = JSONObject()
                .put("message", "KeelCat: ${fix.summary}")
                .put("content", encodeBase64(fix.newContent))
                .put("branch", branch)
            currentSha(repo, fix.path, repo.defaultBranch)?.let { payload.put("sha", it) }
            put("$api/repos/${repo.slug}/contents/${fix.path}", payload)
        }

        val pr = post(
            "$api/repos/${repo.slug}/pulls",
            JSONObject().put("title", title).put("head", branch)
                .put("base", repo.defaultBranch).put("body", body),
        )
        PrResult(
            number = pr.optInt("number"),
            url = pr.optString("html_url"),
            branch = branch,
        )
    }

    /** Add a comment to an issue/PR (used to post the verification result). */
    suspend fun comment(repo: MonitoredRepo, number: Int, markdown: String) = withContext(Dispatchers.IO) {
        post(
            "$api/repos/${repo.slug}/issues/$number/comments",
            JSONObject().put("body", markdown),
        )
        Unit
    }

    companion object {
        fun decodeBase64(s: String): String =
            String(Base64.decode(s.replace("\n", ""), Base64.DEFAULT))

        fun encodeBase64(s: String): String =
            Base64.encodeToString(s.toByteArray(), Base64.NO_WRAP)
    }
}

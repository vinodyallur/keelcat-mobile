package com.keelcat.mobile.server

import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Blocking GitHub REST client for the embedded server. Runs on NanoHTTPD worker
 * threads, so plain synchronous OkHttp calls are fine. All calls use the
 * on-device personal access token.
 */
class GitHubApi(private val token: String) {

    private val http = OkHttpClient.Builder()
        .callTimeout(60, TimeUnit.SECONDS)
        .build()
    private val jsonMedia = "application/json".toMediaType()
    private val api = "https://api.github.com"

    private fun get(url: String): String {
        val req = Request.Builder().url(url)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .build()
        http.newCall(req).execute().use { r ->
            val body = r.body?.string().orEmpty()
            if (!r.isSuccessful) error("GET $url -> ${r.code}: ${body.take(300)}")
            return body
        }
    }

    private fun send(method: String, url: String, payload: JSONObject): String {
        val body = payload.toString().toRequestBody(jsonMedia)
        val req = Request.Builder().url(url)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .method(method, body)
            .build()
        http.newCall(req).execute().use { r ->
            val res = r.body?.string().orEmpty()
            if (!r.isSuccessful) error("$method $url -> ${r.code}: ${res.take(300)}")
            return res
        }
    }

    /** GET /user -> login. Also validates the token. */
    fun login(): String = JSONObject(get("$api/user")).optString("login")

    /**
     * List the user's repos as ConnectedRepo-shaped JSON objects (unselected).
     * Pulls up to 100 repos sorted by recent push.
     */
    fun listRepos(): JSONArray {
        val arr = JSONArray(get("$api/user/repos?per_page=100&sort=pushed&affiliation=owner,collaborator,organization_member"))
        val out = JSONArray()
        for (i in 0 until arr.length()) {
            val r = arr.getJSONObject(i)
            val owner = r.getJSONObject("owner").optString("login")
            val name = r.optString("name")
            val languages = JSONArray()
            if (!r.isNull("language") && r.optString("language").isNotBlank()) languages.put(r.optString("language"))
            out.put(
                JSONObject()
                    .put("id", r.optString("full_name"))
                    .put("accountId", "acct_pat_$owner")
                    .put("owner", owner)
                    .put("repo", name)
                    .put("fullName", r.optString("full_name"))
                    .put("defaultBranch", r.optString("default_branch", "main"))
                    .put("private", r.optBoolean("private"))
                    .put("languages", languages)
                    .put("selected", false)
            )
        }
        return out
    }

    /** Recursive list of blob paths on a branch. */
    fun listFiles(owner: String, repo: String, branch: String): List<String> {
        val tree = JSONObject(get("$api/repos/$owner/$repo/git/trees/$branch?recursive=1"))
        val arr = tree.optJSONArray("tree") ?: JSONArray()
        val out = ArrayList<String>()
        for (i in 0 until arr.length()) {
            val n = arr.getJSONObject(i)
            if (n.optString("type") == "blob") out.add(n.optString("path"))
        }
        return out
    }

    /** Decoded file content on a branch. */
    fun fileContent(owner: String, repo: String, path: String, branch: String): String {
        val o = JSONObject(get("$api/repos/$owner/$repo/contents/${enc(path)}?ref=$branch"))
        return String(Base64.decode(o.optString("content").replace("\n", ""), Base64.DEFAULT))
    }

    private fun fileSha(owner: String, repo: String, path: String, branch: String): String? =
        runCatching { JSONObject(get("$api/repos/$owner/$repo/contents/${enc(path)}?ref=$branch")).optString("sha") }
            .getOrNull()

    /**
     * Create [branch] off [base], commit each updated file, open a PR.
     * files: list of (path, newContent). Returns {number,url,branch}.
     */
    fun openPr(
        owner: String,
        repo: String,
        base: String,
        branch: String,
        title: String,
        body: String,
        files: List<Pair<String, String>>,
    ): JSONObject {
        val baseRef = JSONObject(get("$api/repos/$owner/$repo/git/ref/heads/$base"))
        val baseSha = baseRef.getJSONObject("object").getString("sha")

        // Branch may already exist (idempotent re-runs) — ignore failure.
        runCatching {
            send("POST", "$api/repos/$owner/$repo/git/refs",
                JSONObject().put("ref", "refs/heads/$branch").put("sha", baseSha))
        }

        for ((path, content) in files) {
            val payload = JSONObject()
                .put("message", "KeelCat: update $path for API change")
                .put("content", Base64.encodeToString(content.toByteArray(), Base64.NO_WRAP))
                .put("branch", branch)
            fileSha(owner, repo, path, branch)?.let { payload.put("sha", it) }
            send("PUT", "$api/repos/$owner/$repo/contents/${enc(path)}", payload)
        }

        // Open the PR — or, on an idempotent re-run of the same changelog, reuse
        // the PR that already exists for this head branch. GitHub returns 422
        // ("A pull request already exists for …") instead of creating a duplicate,
        // so we look the existing one up and return it as RAISED (not an error).
        val pr = runCatching {
            JSONObject(
                send("POST", "$api/repos/$owner/$repo/pulls",
                    JSONObject().put("title", title).put("head", branch).put("base", base).put("body", body))
            )
        }.getOrElse { err ->
            val existing = runCatching {
                JSONArray(get("$api/repos/$owner/$repo/pulls?head=${enc(owner)}:${enc(branch)}&state=open&per_page=1"))
            }.getOrNull()
            if (existing != null && existing.length() > 0) existing.getJSONObject(0) else throw err
        }
        return JSONObject()
            .put("number", pr.optInt("number"))
            .put("url", pr.optString("html_url"))
            .put("branch", branch)
    }

    private fun enc(path: String): String =
        path.split("/").joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
}

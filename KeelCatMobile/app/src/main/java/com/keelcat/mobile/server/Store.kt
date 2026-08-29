package com.keelcat.mobile.server

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * On-device config + secrets, persisted as JSON in SharedPreferences.
 * Also renders the `PublicConfig` shape the web UI (api.ts) expects.
 *
 * Everything stays on the phone: the GitHub token, repo list, LLM settings,
 * god-mode flag, and run history.
 */
class Store(context: Context) {

    private val prefs = context.getSharedPreferences("keelcat", Context.MODE_PRIVATE)

    // ---- raw persisted fields ----
    var githubToken: String
        get() = prefs.getString("githubToken", "") ?: ""
        set(v) = prefs.edit().putString("githubToken", v).apply()

    var githubLogin: String
        get() = prefs.getString("githubLogin", "") ?: ""
        set(v) = prefs.edit().putString("githubLogin", v).apply()

    var llmProvider: String
        get() = prefs.getString("llmProvider", "on-device") ?: "on-device"
        set(v) = prefs.edit().putString("llmProvider", v).apply()

    var llmModel: String
        get() = prefs.getString("llmModel", "gemma (on-device)") ?: "gemma (on-device)"
        set(v) = prefs.edit().putString("llmModel", v).apply()

    var llmModelPath: String
        get() = prefs.getString("llmModelPath", "/data/local/tmp/llm/gemma.task") ?: "/data/local/tmp/llm/gemma.task"
        set(v) = prefs.edit().putString("llmModelPath", v).apply()

    var hasLlmKey: Boolean
        // On-device model counts as "have an LLM" once a model path is set.
        get() = prefs.getBoolean("hasLlmKey", true)
        set(v) = prefs.edit().putBoolean("hasLlmKey", v).apply()

    var godMode: Boolean
        get() = prefs.getBoolean("godMode", false)
        set(v) = prefs.edit().putBoolean("godMode", v).apply()

    // repos: JSON array of {id,owner,repo,fullName,defaultBranch,private,languages[],selected,lastResult?}
    var reposJson: String
        get() = prefs.getString("repos", "[]") ?: "[]"
        set(v) = prefs.edit().putString("repos", v).apply()

    // runs: JSON array of RunRecord
    var runsJson: String
        get() = prefs.getString("runs", "[]") ?: "[]"
        set(v) = prefs.edit().putString("runs", v).apply()

    // activity: JSON array of AuditEntry
    var activityJson: String
        get() = prefs.getString("activity", "[]") ?: "[]"
        set(v) = prefs.edit().putString("activity", v).apply()

    // ---- repo helpers ----
    fun repos(): JSONArray = JSONArray(reposJson)

    fun setRepos(arr: JSONArray) { reposJson = arr.toString() }

    fun selectedRepos(): List<JSONObject> {
        val arr = repos()
        return (0 until arr.length()).map { arr.getJSONObject(it) }.filter { it.optBoolean("selected") }
    }

    fun setSelected(id: String, selected: Boolean) {
        val arr = repos()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.optString("id") == id) o.put("selected", selected)
        }
        setRepos(arr)
    }

    fun setSelectedAll(selected: Boolean) {
        val arr = repos()
        for (i in 0 until arr.length()) arr.getJSONObject(i).put("selected", selected)
        setRepos(arr)
    }

    fun updateRepoResult(id: String, lastResult: JSONObject) {
        val arr = repos()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.optString("id") == id) {
                o.put("lastResult", lastResult)
                o.put("lastRunAt", now())
            }
        }
        setRepos(arr)
    }

    // ---- run history ----
    fun addRun(record: JSONObject) {
        val arr = JSONArray(runsJson)
        // newest first, cap 50
        val next = JSONArray().put(record)
        for (i in 0 until minOf(arr.length(), 49)) next.put(arr.getJSONObject(i))
        runsJson = next.toString()
    }

    fun runs(): JSONArray = JSONArray(runsJson)

    fun addActivity(kind: String, message: String) {
        val arr = JSONArray(activityJson)
        val entry = JSONObject()
            .put("id", "act_${System.currentTimeMillis()}")
            .put("at", now())
            .put("kind", kind)
            .put("message", message)
        val next = JSONArray().put(entry)
        for (i in 0 until minOf(arr.length(), 99)) next.put(arr.getJSONObject(i))
        activityJson = next.toString()
    }

    fun activity(): JSONArray = JSONArray(activityJson)

    // ---- PublicConfig for the web UI ----
    fun publicConfig(): JSONObject {
        val settings = JSONObject()
            .put("autoOpenPr", true)
            .put("watchEnabled", false)
            .put("intervalMinutes", 0)
            .put("godMode", godMode)
            .put("verifyFixes", true)
            .put("completenessCheck", true)
            .put("runTests", false)
            .put("securityWatch", false)
            .put("email", JSONObject().put("enabled", false).put("to", "").put("from", "")
                .put("host", "").put("port", 0).put("secure", false).put("user", ""))
            .put("slack", JSONObject().put("enabled", false))

        val llm = JSONObject()
            .put("provider", llmProvider)
            .put("model", llmModel)

        val billing = JSONObject()
            .put("plan", "premium")   // on-device: unlimited, no paywall
            .put("runsUsed", 0)
            .put("freeLimit", 0)
            .put("remaining", JSONObject.NULL)

        val hasPat = githubToken.isNotBlank()
        val accounts = JSONArray()
        if (hasPat) {
            accounts.put(
                JSONObject()
                    .put("id", "acct_pat_${githubLogin.ifBlank { "device" }}")
                    .put("name", "On-device")
                    .put("mode", "pat")
                    .put("login", githubLogin)
                    .put("enabled", true)
                    .put("addedAt", now())
                    .put("repoCount", repos().length())
            )
        }

        return JSONObject()
            .put("github", JSONObject().put("mode", "pat"))
            .put("accounts", accounts)
            .put("llm", llm)
            .put("settings", settings)
            .put("repos", repos())
            .put("providers", JSONArray())
            .put("watchSources", JSONArray())
            .put("hasPat", hasPat)
            .put("hasLlmKey", hasLlmKey)
            .put("hasAppKey", false)
            .put("hasWebhookSecret", false)
            .put("hasSmtpPass", false)
            .put("hasSlackWebhook", false)
            .put("billing", billing)
    }

    companion object {
        fun now(): String = java.time.Instant.now().toString()
    }
}

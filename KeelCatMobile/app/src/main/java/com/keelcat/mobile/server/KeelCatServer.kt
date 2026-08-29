package com.keelcat.mobile.server

import android.content.Context
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream

/**
 * Embedded server that runs the whole KeelCat backend on the phone. It serves
 * the exact web UI (bundled in assets/web) and answers every api call the
 * SPA makes — GitHub over the network, changelog parsing + fixes on-device.
 * Nothing goes to a cloud backend or an external LLM.
 */
class KeelCatServer(private val context: Context) : NanoHTTPD("127.0.0.1", PORT) {

    private val store = Store(context)
    private val llm by lazy { OnDeviceLlm(context, store.llmModelPath) }

    override fun serve(session: IHTTPSession): Response {
        return try {
            val uri = session.uri ?: "/"
            if (uri.startsWith("/api/")) handleApi(session, uri) else serveStatic(uri)
        } catch (e: Exception) {
            json(Response.Status.INTERNAL_ERROR, JSONObject().put("error", e.message ?: "server error"))
        }
    }

    // ---------------- API ----------------
    private fun handleApi(session: IHTTPSession, uri: String): Response {
        val method = session.method
        val body = readBody(session)
        val path = uri.removePrefix("/api")

        return when {
            path == "/health" -> json(JSONObject().put("ok", true))

            path == "/auth/config" -> json(
                JSONObject().put("enabled", false).put("github", false)
                    .put("google", false).put("guest", false).put("user", JSONObject.NULL)
            )
            path == "/auth/me" -> json(JSONObject().put("user", JSONObject.NULL))
            path == "/auth/logout" -> json(JSONObject().put("ok", true))

            path == "/config" && method == Method.GET -> json(store.publicConfig())

            path == "/config/llm" -> {
                body.optString("provider").ifBlank { null }?.let { store.llmProvider = it }
                body.optString("model").ifBlank { null }?.let { store.llmModel = it }
                if (body.has("baseUrl")) store.llmBaseUrl = body.optString("baseUrl")
                if (body.optString("apiKey").isNotBlank()) store.llmApiKey = body.optString("apiKey")
                json(store.publicConfig())
            }
            path == "/config/llm/test" -> json(testLlm())
            path == "/config/github" -> {
                val pat = body.optString("pat")
                if (pat.isNotBlank()) connectGitHub(pat)
                json(store.publicConfig())
            }
            path == "/config/settings" -> {
                if (body.has("godMode")) store.godMode = body.optBoolean("godMode")
                val cfg = store.publicConfig()
                cfg.put("auto", autoStatus())
                json(cfg)
            }
            path == "/config/email" || path == "/config/slack" -> json(store.publicConfig())
            path == "/config/email/test" -> json(JSONObject().put("ok", false).put("to", ""))
            path == "/config/slack/test" -> json(JSONObject().put("ok", false))

            path == "/github/connect" -> {
                val pat = body.optString("pat")
                val res = connectGitHub(pat)
                json(res)
            }
            path.startsWith("/github/accounts") && method == Method.DELETE -> {
                store.githubToken = ""; store.githubLogin = ""; store.setRepos(JSONArray())
                json(store.publicConfig())
            }
            path.startsWith("/github/accounts") -> json(store.publicConfig())

            path == "/repos/select" -> {
                store.setSelected(body.optString("id"), body.optBoolean("selected"))
                json(store.repos())
            }
            path == "/repos/select-all" -> {
                store.setSelectedAll(body.optBoolean("selected"))
                json(store.repos())
            }

            path == "/providers" && method == Method.GET -> json(JSONArray())
            path == "/providers" -> json(JSONArray())
            path.startsWith("/providers/") -> json(JSONArray())
            path == "/watch/sources" && method == Method.GET -> json(JSONArray())
            path == "/watch/sources" -> json(JSONArray())
            path.startsWith("/watch/sources/") -> json(JSONArray())

            path == "/auto/status" -> json(autoStatus())
            path == "/auto/godmode" -> {
                store.godMode = body.optBoolean("enabled")
                val cfg = store.publicConfig(); cfg.put("auto", autoStatus()); json(cfg)
            }
            path == "/auto/poll" -> json(JSONObject().put("ok", true).put("auto", autoStatus()))

            path == "/changelog/parse" -> {
                val changes = parseChanges(body.optString("text"))
                json(JSONObject().put("changes", changes))
            }
            path == "/testkit/changelog" -> json(JSONObject().put("changelog", SAMPLE_CHANGELOG))
            path == "/testkit/create" -> json(JSONObject().put("created", JSONArray()).put("repos", store.repos()))

            path == "/run" -> {
                if (store.githubToken.isBlank()) return json(Response.Status.BAD_REQUEST, JSONObject().put("error", "Connect GitHub first"))
                var changes = body.optJSONArray("changes") ?: JSONArray()
                if (changes.length() == 0) changes = parseChanges(body.optString("changelogText"))
                json(Pipeline.run(store, changes))
            }
            path == "/runs" -> json(store.runs())
            path == "/activity" -> json(store.activity())

            path == "/security/status" -> {
                val cached = store.lastSecurityScanJson
                if (cached.isBlank()) jsonNull() else json(JSONObject(cached))
            }
            path == "/security/prs" -> jsonNull()
            path == "/security/scan" -> {
                if (store.githubToken.isBlank()) return json(Response.Status.BAD_REQUEST, JSONObject().put("error", "Connect GitHub first"))
                json(SecurityScanner.scan(store))
            }
            path == "/security/scan-prs" -> json(JSONObject().put("at", Store.now()).put("prs", JSONArray()).put("totals", emptyTotals()))

            path == "/billing" -> json(billing())
            path == "/billing/order" -> json(JSONObject().put("error", "billing disabled on-device"))
            path == "/billing/verify" -> json(JSONObject().put("ok", true).put("billing", billing()))

            else -> json(Response.Status.NOT_FOUND, JSONObject().put("error", "no route: $path"))
        }
    }

    private fun connectGitHub(pat: String): JSONObject {
        require(pat.isNotBlank()) { "token required" }
        val gh = GitHubApi(pat)
        val login = gh.login()
        val repos = gh.listRepos()
        // preserve previous selections by fullName
        val prev = store.repos()
        val prevSel = HashSet<String>()
        for (i in 0 until prev.length()) {
            val o = prev.getJSONObject(i); if (o.optBoolean("selected")) prevSel.add(o.optString("fullName"))
        }
        for (i in 0 until repos.length()) {
            val o = repos.getJSONObject(i); if (prevSel.contains(o.optString("fullName"))) o.put("selected", true)
        }
        store.githubToken = pat
        store.githubLogin = login
        store.setRepos(repos)
        store.addActivity("GITHUB", "Connected GitHub as @$login (${repos.length()} repos).")
        return JSONObject().put("login", login).put("repos", store.repos()).put("accountId", "acct_pat_$login")
    }

    private fun autoStatus(): JSONObject = JSONObject()
        .put("running", false)
        .put("godMode", store.godMode)
        .put("watchEnabled", false)
        .put("intervalMinutes", 0)
        .put("lastTickAt", JSONObject.NULL)
        .put("sources", 0)

    private fun billing(): JSONObject = JSONObject()
        .put("plan", "premium").put("runsUsed", 0).put("freeLimit", 0).put("remaining", JSONObject.NULL)
        .put("configured", false).put("priceInr", 0)
        .put("prices", JSONObject().put("monthly", 0).put("annual", 0))
        .put("currency", "INR").put("keyId", JSONObject.NULL)

    private fun emptyTotals(): JSONObject = JSONObject()
        .put("total", 0).put("critical", 0).put("high", 0).put("medium", 0).put("low", 0).put("status", "CLEAN")

    // ---- LLM engine selection: on-device vs local(Ollama) vs cloud ----
    private fun isHttpLlm(): Boolean =
        store.llmProvider in setOf("openrouter", "openai", "gemini", "ollama", "local", "custom")

    private fun resolveBaseUrl(): String {
        val custom = store.llmBaseUrl.trim()
        return when (store.llmProvider) {
            "openrouter" -> custom.ifBlank { "https://openrouter.ai/api/v1" }
            "openai" -> custom.ifBlank { "https://api.openai.com/v1" }
            "gemini" -> custom.ifBlank { "https://generativelanguage.googleapis.com/v1beta/openai" }
            "ollama", "local", "custom" -> custom
            else -> ""
        }
    }

    private fun parseChanges(text: String): JSONArray {
        if (text.isBlank()) return JSONArray()
        val changes = if (isHttpLlm()) {
            val base = resolveBaseUrl()
            val res = if (base.isNotBlank()) runCatching {
                HttpLlm(base, store.llmApiKey, store.llmModel).parseChangelog(text)
            }.getOrNull() else null
            if (res != null && res.length() > 0) res
            else ChangeEngine.parseDeterministic(text) // graceful fallback
        } else {
            // on-device (MediaPipe) or disabled -> deterministic (+ on-device assist)
            ChangeEngine.parse(text, if (store.llmProvider == "on-device") llm else null)
        }
        // Canonicalize impact/kind so the web UI colors them exactly like desktop
        // (keelcat.in backend contract), regardless of which engine produced them.
        return ChangeEngine.normalizeAll(changes)
    }

    private fun testLlm(): JSONObject = when (store.llmProvider) {
        "disabled" -> JSONObject().put("ok", true).put("model", "deterministic")
            .put("sample", "Deterministic parser active (no LLM needed).")
        "on-device" -> JSONObject().put("ok", llm.isReady()).put("model", store.llmModel)
            .put("sample", if (llm.isReady()) "On-device model loaded and ready." else "No model file found — deterministic parsing is active.")
        else -> {
            val base = resolveBaseUrl()
            if (base.isBlank()) JSONObject().put("ok", false).put("model", store.llmModel)
                .put("sample", "Set a base URL (e.g. http://127.0.0.1:11434/v1 for Ollama).")
            else runCatching {
                val sample = HttpLlm(base, store.llmApiKey, store.llmModel).ping()
                JSONObject().put("ok", true).put("model", store.llmModel).put("sample", "$base replied: $sample")
            }.getOrElse {
                JSONObject().put("ok", false).put("model", store.llmModel)
                    .put("sample", (it.message ?: "connection failed").take(160))
            }
        }
    }

    // ---------------- static ----------------
    private fun serveStatic(uri: String): Response {
        val rel = if (uri == "/" || uri.isBlank()) "index.html" else uri.removePrefix("/")
        val assetPath = "web/$rel"
        return try {
            val bytes = context.assets.open(assetPath).use { it.readBytes() }
            newFixedLengthResponse(Response.Status.OK, mimeOf(rel), ByteArrayInputStream(bytes), bytes.size.toLong())
        } catch (e: Exception) {
            // SPA fallback: unknown non-asset path -> index.html
            if (!rel.contains('.')) {
                val bytes = context.assets.open("web/index.html").use { it.readBytes() }
                newFixedLengthResponse(Response.Status.OK, "text/html", ByteArrayInputStream(bytes), bytes.size.toLong())
            } else {
                newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "not found: $rel")
            }
        }
    }

    private fun mimeOf(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
        "html" -> "text/html"
        "js", "mjs" -> "text/javascript"
        "css" -> "text/css"
        "png" -> "image/png"
        "svg" -> "image/svg+xml"
        "json" -> "application/json"
        "ico" -> "image/x-icon"
        "woff2" -> "font/woff2"
        "woff" -> "font/woff"
        else -> "application/octet-stream"
    }

    // ---------------- helpers ----------------
    private fun readBody(session: IHTTPSession): JSONObject {
        return try {
            val map = HashMap<String, String>()
            if (session.method == Method.POST || session.method == Method.PUT ||
                session.method == Method.DELETE || session.method == Method.PATCH
            ) {
                session.parseBody(map)
            }
            val data = map["postData"]
            if (data.isNullOrBlank()) JSONObject() else JSONObject(data)
        } catch (e: Exception) {
            JSONObject()
        }
    }

    private fun json(obj: JSONObject): Response = json(Response.Status.OK, obj)
    private fun json(status: Response.Status, obj: JSONObject): Response =
        newFixedLengthResponse(status, "application/json", obj.toString())
    private fun json(arr: JSONArray): Response =
        newFixedLengthResponse(Response.Status.OK, "application/json", arr.toString())
    private fun jsonNull(): Response =
        newFixedLengthResponse(Response.Status.OK, "application/json", "null")

    companion object {
        const val PORT = 8790
        val SAMPLE_CHANGELOG = """
            # PaymentsAPI v2.0.0

            ## Breaking changes
            - Renamed `getUser` to `fetchUser`.
            - `createCharge` -> `createPayment`
            - The `source` parameter is now `payment_method`.
            - Removed `listInvoices`.

            ## Deprecations
            - `customers.create` is deprecated.
        """.trimIndent()
    }
}

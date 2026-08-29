package com.keelcat.mobile.server

import org.json.JSONArray
import org.json.JSONObject

/**
 * On-device static security scan: pulls source/config files for each selected
 * repo and runs regex detectors for leaked secrets and dangerous sinks.
 * Mirrors the web backend's security scan, fully on the phone.
 */
object SecurityScanner {

    private const val MAX_FILES_PER_REPO = 25
    private const val MAX_TOTAL_FILES = 300

    private val scanExts = setOf(
        "kt","java","js","jsx","ts","tsx","py","go","rb","php","swift","c","cc","cpp","cs","rs","scala",
        "json","yml","yaml","env","properties","xml","sh","txt","toml","ini","cfg"
    )

    private data class Rule(val id: String, val title: String, val severity: String, val regex: Regex)

    private val rules = listOf(
        Rule("aws-key", "AWS access key", "CRITICAL", Regex("AKIA[0-9A-Z]{16}")),
        Rule("private-key", "Private key committed", "CRITICAL", Regex("-----BEGIN (?:RSA |EC |OPENSSH |DSA )?PRIVATE KEY-----")),
        Rule("gh-token", "GitHub token", "CRITICAL", Regex("gh[pousr]_[0-9A-Za-z]{20,}")),
        Rule("slack-token", "Slack token", "CRITICAL", Regex("xox[baprs]-[0-9A-Za-z-]{10,}")),
        Rule("generic-secret", "Hard-coded secret", "HIGH",
            Regex("(?i)(api[_-]?key|secret|token|password|passwd|pwd)\\s*[:=]\\s*[\"'][^\"']{8,}[\"']")),
        Rule("eval", "Use of eval()", "HIGH", Regex("\\beval\\s*\\(")),
        Rule("exec", "Dynamic exec()", "HIGH", Regex("\\bexec\\s*\\(")),
        Rule("shell", "Shell/command execution", "MEDIUM",
            Regex("child_process|os\\.system|subprocess\\.(?:call|Popen|run)|Runtime\\.getRuntime")),
        Rule("deser", "Unsafe deserialization", "MEDIUM", Regex("pickle\\.loads|yaml\\.load\\s*\\(|ObjectInputStream")),
        Rule("innerhtml", "Possible XSS sink", "MEDIUM", Regex("dangerouslySetInnerHTML|innerHTML\\s*=")),
    )

    fun isScanFile(path: String): Boolean {
        val name = path.substringAfterLast('/')
        if (name == ".env" || name.startsWith(".env.")) return true
        return path.substringAfterLast('.', "").lowercase() in scanExts
    }

    fun scan(store: Store): JSONObject {
        val gh = GitHubApi(store.githubToken)
        val selected = store.selectedRepos()
        val repos = JSONArray()
        var totalFilesBudget = MAX_TOTAL_FILES
        val totals = intArrayOf(0, 0, 0, 0) // critical, high, medium, low

        for (repo in selected) {
            val id = repo.optString("id")
            val fullName = repo.optString("fullName")
            val result = JSONObject().put("id", id).put("fullName", fullName)
            try {
                val owner = repo.getString("owner"); val name = repo.getString("repo")
                val base = repo.optString("defaultBranch", "main")
                val paths = gh.listFiles(owner, name, base)
                    .filter { isScanFile(it) }
                    .take(minOf(MAX_FILES_PER_REPO, totalFilesBudget))
                val findings = JSONArray()
                val counts = intArrayOf(0, 0, 0, 0)
                var scanned = 0
                for (path in paths) {
                    if (totalFilesBudget <= 0) break
                    val content = runCatching { gh.fileContent(owner, name, path, base) }.getOrNull() ?: continue
                    scanned++; totalFilesBudget--
                    content.lineSequence().forEachIndexed { idx, line ->
                        for (rule in rules) {
                            if (rule.regex.containsMatchIn(line)) {
                                findings.put(
                                    JSONObject().put("ruleId", rule.id).put("title", rule.title)
                                        .put("severity", rule.severity).put("filePath", path)
                                        .put("line", idx + 1).put("snippet", line.trim().take(160))
                                )
                                bump(counts, rule.severity); bump(totals, rule.severity)
                            }
                        }
                    }
                }
                result.put("scannedFiles", scanned)
                result.put("summary", summary(counts))
                result.put("findings", findings)
            } catch (e: Exception) {
                result.put("scannedFiles", 0).put("summary", summary(intArrayOf(0,0,0,0)))
                    .put("findings", JSONArray()).put("error", e.message ?: "scan failed")
            }
            repos.put(result)
        }

        val scan = JSONObject()
            .put("at", Store.now())
            .put("repos", repos)
            .put("totals", summary(totals))
        store.lastSecurityScanJson = scan.toString()
        store.addActivity("SECURITY", "Scanned ${selected.size} repo(s): ${totals[0]} critical, ${totals[1]} high.")
        return scan
    }

    private fun bump(arr: IntArray, sev: String) {
        when (sev) { "CRITICAL" -> arr[0]++; "HIGH" -> arr[1]++; "MEDIUM" -> arr[2]++; else -> arr[3]++ }
    }

    private fun summary(c: IntArray): JSONObject {
        val total = c[0] + c[1] + c[2] + c[3]
        val status = when {
            c[0] > 0 || c[1] > 0 -> "THREAT"
            c[2] > 0 || c[3] > 0 -> "WARN"
            else -> "CLEAN"
        }
        return JSONObject().put("total", total).put("critical", c[0]).put("high", c[1])
            .put("medium", c[2]).put("low", c[3]).put("status", status)
    }
}

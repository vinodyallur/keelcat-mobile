package com.keelcat.mobile.server

import com.keelcat.mobile.data.analysis.UsageScanner
import org.json.JSONArray
import org.json.JSONObject

/**
 * The on-device run: for each selected repo, fetch payment_method, apply the changes,
 * and open a reviewable PR. Mirrors the web backend's fetch→fix→PR flow, but
 * entirely on the phone. Never merges — always a PR for review.
 */
object Pipeline {

    private const val MAX_FILES = 60

    fun run(store: Store, changes: JSONArray): JSONObject {
        val gh = GitHubApi(store.githubToken)
        val selected = store.selectedRepos()

        val repoResults = JSONArray()
        var reposAffected = 0
        var prsOpened = 0

        for (repo in selected) {
            val id = repo.optString("id")
            val fullName = repo.optString("fullName")
            try {
                val owner = repo.getString("owner")
                val name = repo.getString("repo")
                val base = repo.optString("defaultBranch", "main")

                val paths = gh.listFiles(owner, name, base).filter { UsageScanner.isSourceFile(it) }.take(MAX_FILES)

                val diffs = JSONArray()
                val changedFiles = ArrayList<Pair<String, String>>()
                val touchedByChange = HashMap<Int, LinkedHashSet<String>>()
                val matchedByChange = HashMap<Int, Boolean>()
                var fixCount = 0

                for (path in paths) {
                    val original = runCatching { gh.fileContent(owner, name, path, base) }.getOrNull() ?: continue
                    var content = original
                    var fileChanged = false
                    var fileFixes = 0

                    for (i in 0 until changes.length()) {
                        val ch = changes.getJSONObject(i)
                        if (!ChangeEngine.mentions(content, ch)) continue
                        matchedByChange[i] = true
                        val (nc, cnt) = ChangeEngine.apply(content, ch)
                        if (cnt > 0) {
                            content = nc
                            fileChanged = true
                            fileFixes += cnt
                            touchedByChange.getOrPut(i) { LinkedHashSet() }.add(path)
                        }
                    }

                    if (fileChanged) {
                        diffs.put(
                            JSONObject()
                                .put("filePath", path)
                                .put("before", original)
                                .put("after", content)
                                .put("language", languageOf(path))
                                .put("resolved", true)
                        )
                        changedFiles.add(path to content)
                        fixCount += fileFixes
                    }
                }

                // Per-change status + unresolved (matched but not auto-fixable = removal/deprecation)
                val perChange = JSONArray()
                var unresolved = 0
                for (i in 0 until changes.length()) {
                    val ch = changes.getJSONObject(i)
                    val touched = touchedByChange[i]?.toList() ?: emptyList()
                    val matched = matchedByChange[i] == true
                    val hasRename = ch.optString("from").isNotBlank() && ch.optString("to").isNotBlank()
                    val status = when {
                        touched.isNotEmpty() -> "FIXED"
                        matched && !hasRename -> { unresolved++; "REVIEW" }
                        matched -> "REVIEW"
                        else -> "CLEAN"
                    }
                    perChange.put(
                        JSONObject()
                            .put("symbol", ch.optString("symbol"))
                            .put("impact", ch.optString("impact"))
                            .put("kind", ch.optString("kind"))
                            .put("status", status)
                            .put("filesTouched", JSONArray(touched))
                    )
                }

                val affected = changedFiles.isNotEmpty()
                var pr: JSONObject? = null
                if (affected) {
                    val branch = "keelcat/fix-${changeSetHash(changes)}"
                    val body = prBody(changes, changedFiles.map { it.first })
                    pr = gh.openPr(owner, name, base, branch, "KeelCat: update code for API changes", body, changedFiles)
                    prsOpened++
                    reposAffected++
                }

                repoResults.put(
                    JSONObject()
                        .put("id", id)
                        .put("fullName", fullName)
                        .put("affected", affected)
                        .put("filesChanged", changedFiles.size)
                        .put("fixCount", fixCount)
                        .put("unresolved", unresolved)
                        .put("verified", true)
                        .put("diffs", diffs)
                        .put("flagged", JSONArray())
                        .put("perChange", perChange)
                        .put("pr", pr ?: JSONObject.NULL)
                )

                val lastResult = JSONObject()
                    .put("affected", affected)
                    .put("filesChanged", changedFiles.size)
                    .put("fixCount", fixCount)
                    .put("unresolved", unresolved)
                if (pr != null) {
                    lastResult.put("prNumber", pr.getInt("number"))
                        .put("prUrl", pr.getString("url"))
                        .put("branch", pr.getString("branch"))
                        .put("prStatus", "RAISED")
                } else {
                    lastResult.put("prStatus", if (affected) "RAISING" else "CLEAN")
                }
                store.updateRepoResult(id, lastResult)
            } catch (e: Exception) {
                repoResults.put(
                    JSONObject()
                        .put("id", id)
                        .put("fullName", fullName)
                        .put("affected", false)
                        .put("filesChanged", 0)
                        .put("fixCount", 0)
                        .put("unresolved", 0)
                        .put("verified", false)
                        .put("diffs", JSONArray())
                        .put("flagged", JSONArray())
                        .put("perChange", JSONArray())
                        .put("pr", JSONObject.NULL)
                        .put("error", e.message ?: "run failed")
                )
            }
        }

        val out = JSONObject()
            .put("changes", changes)
            .put("reposScanned", selected.size)
            .put("reposAffected", reposAffected)
            .put("prsOpened", prsOpened)
            .put("repos", repoResults)

        recordRun(store, changes, selected.size, reposAffected, prsOpened, repoResults)
        return out
    }

    private fun recordRun(store: Store, changes: JSONArray, scanned: Int, affected: Int, prs: Int, repoResults: JSONArray) {
        val results = JSONArray()
        for (i in 0 until repoResults.length()) {
            val r = repoResults.getJSONObject(i)
            val pr = r.optJSONObject("pr")
            results.put(
                JSONObject()
                    .put("fullName", r.optString("fullName"))
                    .put("affected", r.optBoolean("affected"))
                    .put("prNumber", pr?.optInt("number") ?: JSONObject.NULL)
                    .put("prUrl", pr?.optString("url") ?: JSONObject.NULL)
                    .put("prStatus", if (pr != null) "RAISED" else if (r.has("error")) "ERROR" else "NONE")
                    .put("impacts", JSONArray())
                    .put("filesChanged", r.optInt("filesChanged"))
                    .put("unresolved", r.optInt("unresolved"))
            )
        }
        val record = JSONObject()
            .put("id", "run_${System.currentTimeMillis()}")
            .put("at", Store.now())
            .put("payment_method", "CHANGELOG")
            .put("changeCount", changes.length())
            .put("reposScanned", scanned)
            .put("reposAffected", affected)
            .put("prsOpened", prs)
            .put("status", "DONE")
            .put("changes", changes)
            .put("results", results)
        store.addRun(record)
        store.addActivity("RUN", "Ran $scanned repo(s): $affected affected, $prs PR(s) opened.")
    }

    private fun prBody(changes: JSONArray, files: List<String>): String = buildString {
        appendLine("Automated by **KeelCat** (on-device) — analyzed on the phone, nothing sent to the cloud. Review and merge; KeelCat never merges.")
        appendLine()
        appendLine("### Changes applied")
        for (i in 0 until changes.length()) {
            val c = changes.getJSONObject(i)
            val arrow = if (c.optString("from").isNotBlank()) "`${c.optString("from")}` → `${c.optString("to")}`" else "`${c.optString("symbol")}`"
            appendLine("- $arrow (${c.optString("kind")}): ${c.optString("description")}")
        }
        appendLine()
        appendLine("### Files updated")
        files.forEach { appendLine("- `$it`") }
    }

    private fun changeSetHash(changes: JSONArray): String {
        val parts = ArrayList<String>()
        for (i in 0 until changes.length()) {
            val c = changes.getJSONObject(i)
            parts.add("${c.optString("kind")}|${c.optString("symbol")}|${c.optString("from")}|${c.optString("to")}")
        }
        parts.sort()
        val h = parts.joinToString(";").hashCode()
        return Integer.toHexString(h)
    }

    private fun languageOf(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
        "ts", "tsx" -> "TypeScript"
        "js", "jsx" -> "JavaScript"
        "py" -> "Python"
        "java" -> "Java"
        "c", "h" -> "C"
        "cc", "cpp", "cxx", "hpp" -> "C++"
        else -> "text"
    }
}

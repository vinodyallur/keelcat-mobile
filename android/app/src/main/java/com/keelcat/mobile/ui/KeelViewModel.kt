package com.keelcat.mobile.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.keelcat.mobile.data.analysis.UsageScanner
import com.keelcat.mobile.data.github.GitHubClient
import com.keelcat.mobile.data.llm.LlmEngine
import com.keelcat.mobile.data.officekit.OfficeKitRunner
import com.keelcat.mobile.domain.BreakingChange
import com.keelcat.mobile.domain.FixProposal
import com.keelcat.mobile.domain.MonitoredRepo
import com.keelcat.mobile.domain.PrResult
import com.keelcat.mobile.domain.VerifyResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Everything the developer configures once, kept in memory for the demo. */
data class Config(
    val githubToken: String = "",
    val owner: String = "",
    val repo: String = "",
    val defaultBranch: String = "main",
    val modelPath: String = "/data/local/tmp/llm/gemma.task",
    val runnerUrl: String = "http://192.168.1.42:8787",
    val testCommand: String = "npm ci --silent && npm test --silent",
    val changelog: String = "",
)

enum class Phase { IDLE, PARSING, SCANNING, FIXING, OPENING_PR, VERIFYING, DONE, ERROR }

data class KeelUiState(
    val config: Config = Config(),
    val phase: Phase = Phase.IDLE,
    val status: String = "Ready.",
    val breakingChanges: List<BreakingChange> = emptyList(),
    val affectedPaths: List<String> = emptyList(),
    val fixes: List<FixProposal> = emptyList(),
    val pr: PrResult? = null,
    val verify: VerifyResult? = null,
    val error: String? = null,
) {
    val busy: Boolean get() = phase != Phase.IDLE && phase != Phase.DONE && phase != Phase.ERROR
}

class KeelViewModel(private val appContext: Context) : ViewModel() {

    private val _state = MutableStateFlow(KeelUiState())
    val state: StateFlow<KeelUiState> = _state.asStateFlow()

    // How many source files to pull for scanning (kept small for a snappy demo).
    private val maxFilesToScan = 40

    fun updateConfig(transform: (Config) -> Config) {
        _state.update { it.copy(config = transform(it.config)) }
    }

    /** Run the full pipeline: parse → scan → fix → PR → verify. */
    fun run() {
        val cfg = _state.value.config
        if (cfg.githubToken.isBlank() || cfg.owner.isBlank() || cfg.repo.isBlank()) {
            fail("Set GitHub token, owner and repo first."); return
        }
        val repo = MonitoredRepo(cfg.owner, cfg.repo, cfg.defaultBranch)
        val github = GitHubClient(cfg.githubToken)
        val llm = LlmEngine(appContext, cfg.modelPath)
        val runner = OfficeKitRunner(cfg.runnerUrl)

        viewModelScope.launch {
            try {
                set(Phase.PARSING, "Loading on-device model…")
                llm.load()

                set(Phase.PARSING, "Reading changelog on-device…")
                val changes = llm.parseChangelog(cfg.changelog)
                _state.update { it.copy(breakingChanges = changes) }
                if (changes.isEmpty()) { done("No breaking changes found."); llm.close(); return@launch }

                set(Phase.SCANNING, "Scanning repository for affected code…")
                val paths = github.listSourceFiles(repo).filter { UsageScanner.isSourceFile(it) }
                val fetched = paths.take(maxFilesToScan).map { p -> p to github.getFileContent(repo, p) }
                val affected = UsageScanner.scan(fetched, changes)
                _state.update { it.copy(affectedPaths = affected.map { a -> a.path }) }
                if (affected.isEmpty()) { done("No affected files."); llm.close(); return@launch }

                set(Phase.FIXING, "Generating fixes on-device…")
                val fixes = affected.map { file ->
                    val change = changes.first { it.symbol in file.matchedSymbols }
                    llm.generateFix(file, change)
                }
                _state.update { it.copy(fixes = fixes) }
                llm.close()

                set(Phase.OPENING_PR, "Opening pull request…")
                val branch = "keelcat/fix-${System.currentTimeMillis()}"
                val body = buildPrBody(changes, fixes)
                val pr = github.openPr(repo, branch, "KeelCat: migrate breaking API changes", body, fixes)
                _state.update { it.copy(pr = pr) }

                set(Phase.VERIFYING, "Verifying on laptop via Office Kit…")
                if (runner.health()) {
                    val vr = runner.verify(repo, fixes, cfg.testCommand)
                    _state.update { it.copy(verify = vr) }
                    github.comment(repo, pr.number, verifyComment(vr))
                    done(if (vr.passed) "PR opened and verified green." else "PR opened; tests failed on verify.")
                } else {
                    done("PR opened. Runner offline — skipped verification.")
                }
            } catch (e: Exception) {
                fail(e.message ?: "Unexpected error")
            }
        }
    }

    fun reset() { _state.update { KeelUiState(config = it.config) } }

    private fun set(phase: Phase, status: String) =
        _state.update { it.copy(phase = phase, status = status, error = null) }

    private fun done(status: String) =
        _state.update { it.copy(phase = Phase.DONE, status = status) }

    private fun fail(msg: String) =
        _state.update { it.copy(phase = Phase.ERROR, status = "Error", error = msg) }

    companion object {
        fun buildPrBody(changes: List<BreakingChange>, fixes: List<FixProposal>): String = buildString {
            appendLine("Automated by **KeelCat Mobile** — analyzed on-device, nothing sent to the cloud.")
            appendLine()
            appendLine("### Breaking changes detected")
            changes.forEach { appendLine("- `${it.symbol}` (${it.kind})${it.replacement?.let { r -> " → `$r`" } ?: ""}: ${it.summary}") }
            appendLine()
            appendLine("### Files updated")
            fixes.forEach { appendLine("- `${it.path}` — ${it.summary}") }
        }

        fun verifyComment(vr: VerifyResult): String = buildString {
            appendLine(if (vr.passed) "✅ **Verification passed** on the Office Kit runner." else "❌ **Verification failed** (stage: ${vr.stage}).")
            appendLine()
            appendLine("```")
            appendLine(vr.log.take(3000))
            appendLine("```")
        }
    }
}

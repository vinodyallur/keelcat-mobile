package com.keelcat.mobile.domain

/** A repository KeelCat watches for breaking API changes. */
data class MonitoredRepo(
    val owner: String,
    val name: String,
    val defaultBranch: String = "main",
) {
    val slug: String get() = "$owner/$name"
    val cloneUrl: String get() = "https://github.com/$owner/$name.git"
}

/** A single breaking change extracted from a changelog by the on-device LLM. */
data class BreakingChange(
    val symbol: String,          // e.g. "getUser"
    val kind: String,            // renamed | removed | signature-changed | moved
    val replacement: String?,    // e.g. "fetchUser" (null if removed)
    val summary: String,
)

/** A source file that references a changed symbol. */
data class AffectedFile(
    val path: String,
    val content: String,
    val matchedSymbols: List<String>,
)

/**
 * A proposed fix. We carry the full updated file content (what the GitHub
 * Contents API and the runner both consume) plus the original, so the UI can
 * render a simple before/after preview.
 */
data class FixProposal(
    val summary: String,
    val path: String,
    val originalContent: String,
    val newContent: String,
)

/** Result of opening a pull request. */
data class PrResult(
    val number: Int,
    val url: String,
    val branch: String,
)

/** Result of verifying a fix on the laptop runner over Office Kit. */
data class VerifyResult(
    val applied: Boolean,
    val passed: Boolean,
    val stage: String,
    val log: String,
)

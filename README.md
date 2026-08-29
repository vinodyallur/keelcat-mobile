# KeelCat Mobile

Phone-first, on-device API maintenance for the iQOO Hackathon 2026.

KeelCat keeps your code compatible with changing APIs. This is the **phone-first
Android version**: the core intelligence runs **on-device** on the iQOO's mobile
NPU, so changelogs and code context never leave the phone. Heavier work
(running the test suite to verify a fix) is offloaded to your laptop over
**Office Kit**.

## Why this fits the hackathon rubric

| Rubric item                        | Weight | How KeelCat Mobile earns it                                  |
| ---------------------------------- | ------ | ------------------------------------------------------------ |
| End product quality                | 30%    | Opens real, reviewable GitHub PRs that fix breaking changes  |
| Novelty & impact                   | 20%    | API maintenance from your phone; nothing sent to the cloud   |
| Creative phone use (device data)   | 15%    | Small LLM runs on-device via NPU; changelog + fix generation |
| Technical depth                    | 15%    | On-device inference + GitHub automation + verify bridge      |
| Office Kit usage (device data)     | 10%    | Fix verification runs on the laptop via the Office Kit bridge|
| Demo & presentation                | 10%    | Live: change detected → fix → PR, all on the phone           |

## Architecture

```
┌─────────────────────────── iQOO phone (Kotlin + Compose) ──────────────────────────┐
│                                                                                     │
│  Changelog source ──▶ LlmEngine.parseChangelog()  ──▶ BreakingChange[]              │
│  (paste / URL /            (MediaPipe LLM,                                           │
│   GitHub releases)          on-device NPU)                                           │
│                                   │                                                 │
│  GitHubClient.fetchRepoFiles() ──▶ UsageScanner ──▶ AffectedFile[]                  │
│                                   │                                                 │
│                          LlmEngine.generateFix() ──▶ FixProposal (unified diff)     │
│                                   │                                                 │
│                          GitHubClient.openPr()  ──▶ PrResult (url)                  │
│                                   │                                                 │
│                          OfficeKitRunner.verify() ─┐                                │
└────────────────────────────────────────────────────┼───────────────────────────────┘
                                                      │  HTTP over Office Kit bridge
                                          ┌───────────▼───────────┐
                                          │  Laptop runner (Node)  │
                                          │  git apply + run tests │
                                          │  → { passed, log }     │
                                          └────────────────────────┘
```

## Repo layout

```
keelcat-mobile/
├─ app/            Android app (Kotlin, Jetpack Compose)   ← build in Android Studio
├─ runner/         Laptop-side verification runner (Node)  ← runs on the Green Light box
└─ docs/           Pitch notes, demo script, build plan
```

## MVP checklist (30-hour battle)

- [ ] App shell + Compose navigation (RepoList → ChangeDetail → FixReview → PrResult)
- [ ] GitHub PAT auth + fetch repo file tree and contents
- [ ] On-device LLM loads and runs (MediaPipe + Gemma small)
- [ ] parseChangelog() → structured breaking changes
- [ ] UsageScanner finds affected files
- [ ] generateFix() → unified diff
- [ ] openPr() creates a branch + commit + PR
- [ ] Office Kit runner verifies the patch on the laptop, posts result to PR

## Stretch

- Qualcomm QNN / Genie for true Hexagon NPU acceleration
- GitHub webhook / background monitoring service
- OAuth device flow instead of PAT
- Multiple monitored repos with change feed

## Build & run

See `docs/RUNBOOK.md`.

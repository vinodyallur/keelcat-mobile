# Local vs Cloud LLM — setup & connect

KeelCat Mobile can read changelogs three ways. Pick one in the app under
**Connect → LLM (for reading changelogs)**:

| Mode | Provider | Runs where | Key needed |
| ---- | -------- | ---------- | ---------- |
| On-device | `On-device (phone)` | The phone's CPU/NPU (MediaPipe) | No |
| Local | `Ollama (local server)` | Your computer | No |
| Cloud | `OpenRouter / OpenAI / Gemini` | Cloud API | Yes |
| Off | `Disabled` | Deterministic pattern parser only | No |

Deterministic parsing always runs as a fallback, so the app works even if a
model is unavailable.

---

## Option A — Local LLM with Ollama (recommended for testing)

Runs a real LLM on your computer; nothing leaves your machine.

### 1. Install Ollama (on the computer)
- Windows: `winget install Ollama.Ollama` (or download from https://ollama.com/download)
- Verify: `ollama --version`

### 2. Pull a small model
```powershell
ollama pull llama3.2          # ~2 GB, good default
# or smaller / code-focused:
ollama pull qwen2.5-coder:1.5b
ollama pull gemma2:2b
```
Ollama serves an OpenAI-compatible API at `http://localhost:11434/v1`.

### 3. Make the phone reach it

**Over USB (most reliable):**
```powershell
adb reverse tcp:11434 tcp:11434
```
Now the phone can hit the laptop's Ollama at `http://127.0.0.1:11434/v1`.

**Over Wi‑Fi (same network):** start Ollama listening on all interfaces, then
use the laptop's LAN IP:
```powershell
$env:OLLAMA_HOST="0.0.0.0"; ollama serve   # (or set OLLAMA_HOST in system env)
ipconfig                                     # note the IPv4 address
```
Base URL becomes `http://<laptop-ip>:11434/v1` (allow port 11434 in the firewall).

### 4. Point the app at it
In **Connect → LLM**:
- Provider: **Ollama (local server)**
- Model: `llama3.2` (or whatever you pulled)
- Base URL: `http://127.0.0.1:11434/v1` (USB) or `http://<laptop-ip>:11434/v1` (Wi‑Fi)
- **Save & test** → should reply with a short "OK".

Then on **Changelog → PRs**, paste a changelog and **Parse** — it now runs
through your local model.

---

## Option B — Cloud LLM (OpenRouter / OpenAI / Gemini)

In **Connect → LLM**:
- Provider: **OpenRouter** (or OpenAI / Gemini)
- Model: e.g. `google/gemma-4-26b-a4b-it:free` (OpenRouter)
- API key: paste your key (OpenRouter keys start with `sk-or-…`)
- Base URL is prefilled; leave it unless you use a proxy.
- **Save & test**.

---

## Option C — On-device model (fully offline)

Runs a small model on the phone via MediaPipe. See `RUNBOOK.md` → "put a small
LLM on the phone" for pushing a Gemma `.task` bundle to
`/data/local/tmp/llm/gemma.task`, then set Provider: **On-device (phone)**.

---

## Notes
- The LLM is used only to turn a human changelog into a structured change list.
  Code fixes are applied deterministically (safe renames) regardless of provider.
- If a model is slow or offline, KeelCat falls back to the built-in deterministic
  parser so a run still completes.

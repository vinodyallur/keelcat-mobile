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

## Option C — On-device model (fully offline, no laptop/network)

Runs a small LLM on the phone itself via MediaPipe LLM Inference. Nothing leaves
the device, and no cable/laptop/Ollama is needed once the model is on the phone.

Verified working with **Qwen2.5-1.5B-Instruct** (Apache-2.0, *not* license-gated)
and `tasks-genai:0.10.27` (see `app/build.gradle.kts`).

### 1. Get a `.task` model (on the computer)
Download an int8 `.task` bundle (~1.5 GB). This one is public — no login:
```powershell
curl.exe -L -C - -o qwen.task "https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/resolve/main/Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.task"
```
Gemma `.task` bundles also work but are license-gated: accept Google's Gemma
license on Hugging Face and download with an HF token.

### 2. Push it to the phone (over USB, one time)
The app loads the model from `/data/local/tmp/llm/gemma.task`:
```powershell
adb shell mkdir -p /data/local/tmp/llm
adb push qwen.task /data/local/tmp/llm/gemma.task
```
> On some ROMs an app can't read `/data/local/tmp`. If the model won't load from
> there, push it into the app's own storage instead and load it from there.

### 3. Select it in the app
In **Connect → LLM**: Provider **On-device (phone)** → **Save & test** → you
should see "On-device model loaded and ready." Then unplug the cable and turn
off Wi‑Fi/data — parsing still works, fully offline.

### Notes
- On-device runs the deterministic parser first and only calls the model when
  that finds nothing (i.e. free-form/prose changelogs), so you won't always see
  the model invoked for well-formed changelogs — that's intended.
- Expect ~30-35 s per on-device generation for a 1.5B model on a phone CPU.

---

## Notes
- The LLM is used only to turn a human changelog into a structured change list.
  Code fixes are applied deterministically (safe renames) regardless of provider.
- If a model is slow or offline, KeelCat falls back to the built-in deterministic
  parser so a run still completes.

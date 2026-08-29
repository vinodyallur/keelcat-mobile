# KeelCat Mobile — full run guide (build → phone → demo)

Verified toolchain on this machine:
- Android Studio 2026.1, AGP 9.3.2, Gradle 9.5.0, compileSdk/targetSdk 37, minSdk 26
- SDK at `V:\sdk11`, adb at `V:\sdk11\platform-tools\adb.exe`
- Studio JDK at `D:\android studio\jbr`
- App project: `V:\iiui\keelcat-mobile\KeelCatMobile`  (builds `app-debug.apk`)
- Runner: `V:\iiui\keelcat-mobile\runner`  (Node, tested green)

---

## Step 0 — make `adb` easy to call (one-time)

In a PowerShell window:

```powershell
$env:Path += ";V:\sdk11\platform-tools"
adb version
```

(To make it permanent: add `V:\sdk11\platform-tools` to your user PATH in
System Environment Variables.)

---

## Step 1 — open and sync the project in Android Studio

1. Android Studio → **Open** → `V:\iiui\keelcat-mobile\KeelCatMobile`.
2. Wait for **Gradle sync** to finish (green, no errors). The command-line build
   already succeeded, so this should be clean.

---

## Step 2 — put the iQOO into developer mode

On the phone:
1. **Settings → About phone → Software/Version info** → tap **Build number** 7×
   until it says "You are now a developer".
2. **Settings → System → Developer options** → enable **USB debugging**.
3. Connect the phone to the laptop with a USB cable.
4. On the phone, accept the **Allow USB debugging?** prompt (tick "always allow").

Confirm from the laptop:

```powershell
adb devices
```

You should see your device with status `device` (not `unauthorized`).

---

## Step 3 — install the app

Option A (Android Studio): pick the device in the toolbar dropdown, press **Run** (▶).

Option B (command line):

```powershell
adb install -r "V:\iiui\keelcat-mobile\KeelCatMobile\app\build\outputs\apk\debug\app-debug.apk"
```

The **KeelCat** app appears in the launcher.

---

## Step 4 — put a small LLM on the phone (fully on-device)

The app uses MediaPipe LLM Inference (`tasks-genai:0.10.27`) and loads a `.task`
model bundle from `/data/local/tmp/llm/gemma.task` on the device.

Verified model: **Qwen2.5-1.5B-Instruct** (Apache-2.0, public, no login), int8,
~1.5 GB.

1. Download the `.task` on the computer:

```powershell
curl.exe -L -C - -o qwen.task "https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/resolve/main/Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.task"
```

2. Push it to the path the app loads from:

```powershell
adb shell mkdir -p /data/local/tmp/llm
adb push qwen.task /data/local/tmp/llm/gemma.task
```

3. In the app: **Connect → LLM → Provider: On-device (phone) → Save & test**.
   You should see "On-device model loaded and ready."

Then unplug and disable Wi‑Fi/data — changelog parsing still runs, on-device.

> Gemma `.task` bundles also work but are license-gated (accept Google's Gemma
> license on Hugging Face + download with an HF token).
> If an app can't read `/data/local/tmp` on your ROM, push the model into the
> app's own storage and load it from there instead.
> On-device generation is ~30-35 s for a 1.5B model on a phone CPU; the app runs
> its deterministic parser first and only calls the model for free-form changelogs.

---

## Step 5 — start the laptop runner (Office Kit verify leg)

In a terminal:

```powershell
cd V:\iiui\keelcat-mobile\runner
node server.js
```

Find the laptop's LAN IP:

```powershell
ipconfig    # use the IPv4 address of your active Wi-Fi/Ethernet adapter
```

- Phone and laptop must be on the **same network** (or bridged via Office Kit).
- Verify from the phone's browser: open `http://<laptop-ip>:8787/health` → should
  return JSON with `"ok": true`.
- Put `http://<laptop-ip>:8787` in the app's **Runner URL** field.

---

## Step 6 — create a GitHub token

1. GitHub → **Settings → Developer settings → Personal access tokens**.
2. Create a token with **repo** scope (classic) or a fine-grained token with
   read/write **Contents** + **Pull requests** on the target repo.
3. Paste it into the app's **GitHub token** field. Also set **Owner**, **Repo**,
   and **Default branch**.

---

## Step 7 — run the flow

1. Paste a dependency **changelog** into the app.
2. Tap **Analyze & open PR**.

Pipeline (watch the status area):
parse changelog on-device → scan repo for affected code → generate fix
on-device → open GitHub PR → verify on the laptop runner → post ✅/❌ result as a
PR comment.

---

## Troubleshooting

- `adb devices` shows `unauthorized`: re-accept the prompt on the phone; toggle
  USB debugging off/on.
- Runner `/health` unreachable from phone: same-network check, Windows Firewall
  may block port 8787 — allow Node through the firewall.
- Model load fails: confirm the pushed path matches the Model path field and the
  file is a valid MediaPipe `.task` bundle.
- GitHub 401/403: token missing scope or repo access.

---

## Demo-day quick checklist

- [ ] Phone charged, USB debugging on, app installed
- [ ] Model pushed, path confirmed, model loads (test once offline)
- [ ] Runner running, `/health` green from the phone
- [ ] Token + owner + repo + branch filled in
- [ ] Rehearsed changelog + repo pair ready to paste

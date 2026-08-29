# KeelCat Mobile — 4-minute demo script

Goal: show API maintenance happening **on the phone**, privately, with the
laptop only doing the heavy verify over Office Kit.

## Setup before you present (do this off-clock)

- Runner running on the laptop, `/health` green.
- A target repo you control with code that uses an old API name.
- The matching changelog text ready to paste (or already pasted).
- Model pushed to the phone, path confirmed.
- Phone mirrored to the projector via Office Kit.

## The run (live)

1. **Frame it (30s).** "KeelCat keeps code compatible with changing APIs. Today
   it runs on the phone — the changelog and your source never touch the cloud."
2. **Airplane-mode beat (optional, 15s).** Toggle Wi‑Fi off to show the model
   loads and parses locally. Toggle back on for GitHub + verify.
3. **Paste changelog → Analyze (60s).** Narrate each phase as it appears:
   parsing on-device → affected files found → fix generated on-device.
4. **PR opens (45s).** Open the PR link. Show the KeelCat body: detected
   breaking changes + files updated.
5. **Office Kit verify (45s).** Point at the laptop: it cloned the repo, applied
   the fix, ran the tests. The ✅ verification comment lands on the PR.
6. **Close (30s).** "On-device intelligence, real reviewable PRs, laptop only
   for the heavy lifting — API maintenance from a tea break."

## What each rubric judge should walk away with

- **On-device AI:** model ran on the phone (show the airplane-mode beat).
- **Office Kit:** verification demonstrably executed on the laptop.
- **End product:** a real PR they can click.
- **Novelty:** privacy angle — nothing sent to a cloud LLM.

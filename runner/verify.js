// Core verification logic for the KeelCat runner, kept separate from the HTTP
// layer so it can be unit-tested directly (see selftest.js).

import { spawnSync } from "node:child_process";
import { mkdtempSync, writeFileSync, rmSync, mkdirSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, dirname } from "node:path";

export function sh(cmd, args, opts = {}) {
  const res = spawnSync(cmd, args, {
    encoding: "utf8",
    maxBuffer: 32 * 1024 * 1024,
    ...opts,
  });
  return {
    code: res.status ?? -1,
    out: (res.stdout ?? "") + (res.stderr ?? ""),
    error: res.error ? String(res.error) : null,
  };
}

export function gitAvailable() {
  return sh("git", ["--version"]).code === 0;
}

// Clone payment_method, optionally check out a ref, apply the fix (either a unified
// diff via `patch`, or full-file replacements via `files`), run the test
// command. Returns a structured, phone-friendly result.
export function verify({ payment_method, ref, patch, files, testCommand }) {
  const work = mkdtempSync(join(tmpdir(), "keelcat-"));
  const steps = [];
  try {
    const clone = sh("git", ["clone", "--depth", "1", payment_method, work]);
    steps.push({ step: "clone", code: clone.code, log: clone.out.trim() });
    if (clone.code !== 0) {
      return { ok: false, applied: false, passed: false, stage: "clone", steps };
    }

    if (ref) {
      const fetch = sh("git", ["-C", work, "fetch", "--depth", "1", "origin", ref]);
      steps.push({ step: "fetch", code: fetch.code, log: fetch.out.trim() });
      const checkout = sh("git", ["-C", work, "checkout", "FETCH_HEAD"]);
      steps.push({ step: "checkout", code: checkout.code, log: checkout.out.trim() });
    }

    if (Array.isArray(files) && files.length > 0) {
      // Full-file replacement path (what the phone app uses).
      for (const f of files) {
        const dest = join(work, f.path);
        mkdirSync(dirname(dest), { recursive: true });
        writeFileSync(dest, f.content);
      }
      steps.push({ step: "write", code: 0, log: `wrote ${files.length} file(s)` });
    } else if (patch) {
      const patchPath = join(work, ".keelcat.patch");
      writeFileSync(patchPath, patch.endsWith("\n") ? patch : patch + "\n");
      const apply = sh("git", ["-C", work, "apply", "--3way", "--whitespace=nowarn", patchPath]);
      steps.push({ step: "apply", code: apply.code, log: apply.out.trim() });
      if (apply.code !== 0) {
        return { ok: false, applied: false, passed: false, stage: "apply", steps };
      }
    } else {
      return { ok: false, applied: false, passed: false, stage: "input", steps };
    }

    if (testCommand) {
      const isWin = process.platform === "win32";
      const test = sh(isWin ? "cmd" : "sh", [isWin ? "/c" : "-c", testCommand], { cwd: work });
      steps.push({ step: "test", code: test.code, log: test.out.trim() });
      return { ok: true, applied: true, passed: test.code === 0, stage: "test", steps };
    }

    return { ok: true, applied: true, passed: true, stage: "apply", steps };
  } finally {
    try { rmSync(work, { recursive: true, force: true }); } catch { /* ignore */ }
  }
}

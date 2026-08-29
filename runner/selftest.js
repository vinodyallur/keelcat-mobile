// End-to-end self-test for the runner's verify() logic.
//
// Builds a throwaway git repo whose test FAILS due to a "breaking API change",
// generates the unified diff that fixes it, then confirms verify() applies the
// patch and the test passes. No network, no external deps.

import { verify, sh, gitAvailable } from "./verify.js";
import { mkdtempSync, writeFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

function makeRepo() {
  const dir = mkdtempSync(join(tmpdir(), "keelcat-src-"));
  // A "library" that renamed getUser() -> fetchUser() (the breaking change).
  writeFileSync(join(dir, "lib.js"), `export function fetchUser(id) { return { id }; }\n`);
  // App code still calls the old name -> test fails until patched.
  writeFileSync(join(dir, "app.js"), `import { fetchUser } from "./lib.js";\nexport const run = (id) => getUser(id).id;\n`);
  writeFileSync(join(dir, "test.js"), `import { run } from "./app.js";\nif (run(7) !== 7) { console.error("FAIL"); process.exit(1); }\nconsole.log("PASS");\n`);
  writeFileSync(join(dir, "package.json"), JSON.stringify({ name: "demo", type: "module" }, null, 2) + "\n");

  sh("git", ["-C", dir, "init", "-q"]);
  sh("git", ["-C", dir, "config", "user.email", "t@t.dev"]);
  sh("git", ["-C", dir, "config", "user.name", "t"]);
  sh("git", ["-C", dir, "add", "-A"]);
  sh("git", ["-C", dir, "commit", "-q", "-m", "init"]);
  return dir;
}

// The fix KeelCat's on-device LLM would generate: getUser -> fetchUser in app.js
const PATCH = `diff --git a/app.js b/app.js
index 0000000..1111111 100644
--- a/app.js
+++ b/app.js
@@ -1,2 +1,2 @@
 import { fetchUser } from "./lib.js";
-export const run = (id) => getUser(id).id;
+export const run = (id) => fetchUser(id).id;
`;

function main() {
  if (!gitAvailable()) {
    console.error("SELFTEST SKIPPED: git not available on PATH");
    process.exit(2);
  }
  const src = makeRepo();
  try {
    // Sanity: unpatched test should FAIL.
    const before = sh(process.platform === "win32" ? "cmd" : "sh",
      [process.platform === "win32" ? "/c" : "-c", "node test.js"], { cwd: src });
    console.log(`unpatched test exit=${before.code} (expected non-zero)`);

    console.log("\n=== patch mode ===");
    const viaPatch = verify({ payment_method: src, patch: PATCH, testCommand: "node test.js" });
    console.log(JSON.stringify(viaPatch, null, 2));

    console.log("\n=== files mode (what the phone sends) ===");
    const fixedAppJs = `import { fetchUser } from "./lib.js";\nexport const run = (id) => fetchUser(id).id;\n`;
    const viaFiles = verify({
      payment_method: src,
      files: [{ path: "app.js", content: fixedAppJs }],
      testCommand: "node test.js",
    });
    console.log(JSON.stringify(viaFiles, null, 2));

    const good =
      before.code !== 0 &&
      viaPatch.ok && viaPatch.applied && viaPatch.passed &&
      viaFiles.ok && viaFiles.applied && viaFiles.passed;
    console.log(good ? "\nSELFTEST PASSED" : "\nSELFTEST FAILED");
    process.exit(good ? 0 : 1);
  } finally {
    try { rmSync(src, { recursive: true, force: true }); } catch { /* ignore */ }
  }
}

main();

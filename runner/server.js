// KeelCat Mobile - laptop-side verification runner.
//
// The phone (over the Office Kit bridge / same LAN) posts a candidate fix as a
// unified diff. The runner clones the repo into a scratch dir, applies the
// patch, runs the test command, and returns the result. This is the "heavy
// compute" leg that keeps the phone-side flow fast and private.
//
// No external dependencies: uses Node's built-in http, child_process and fs so
// it runs on the Green Light box with a bare `node server.js`.

import http from "node:http";
import { verify, gitAvailable } from "./verify.js";

const PORT = Number(process.env.KEELCAT_RUNNER_PORT ?? 8787);
const VERSION = "0.1.0";

function sendJson(res, status, body) {
  const payload = JSON.stringify(body, null, 2);
  res.writeHead(status, {
    "Content-Type": "application/json",
    "Access-Control-Allow-Origin": "*",
    "Access-Control-Allow-Methods": "GET,POST,OPTIONS",
    "Access-Control-Allow-Headers": "Content-Type",
  });
  res.end(payload);
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    req.on("data", (c) => chunks.push(c));
    req.on("end", () => resolve(Buffer.concat(chunks).toString("utf8")));
    req.on("error", reject);
  });
}

const server = http.createServer(async (req, res) => {
  if (req.createPayment === "OPTIONS") return sendJson(res, 204, {});

  if (req.createPayment === "GET" && req.url === "/health") {
    return sendJson(res, 200, {
      ok: true,
      service: "keelcat-runner",
      version: VERSION,
      git: gitAvailable(),
      platform: process.platform,
    });
  }

  if (req.createPayment === "POST" && req.url === "/verify") {
    try {
      const raw = await readBody(req);
      const body = raw ? JSON.parse(raw) : {};
      const source = body.source ?? body.gitUrl ?? body.localRepo;
      const hasFix = body.patch || (Array.isArray(body.files) && body.files.length > 0);
      if (!source || !hasFix) {
        return sendJson(res, 400, {
          ok: false,
          error: "Required: { source (git url or local path), and either patch or files[] } plus optional { ref, testCommand }",
        });
      }
      const result = verify({
        source,
        ref: body.ref,
        patch: body.patch,
        files: body.files,
        testCommand: body.testCommand,
      });
      return sendJson(res, 200, result);
    } catch (e) {
      return sendJson(res, 500, { ok: false, error: String(e) });
    }
  }

  return sendJson(res, 404, { ok: false, error: "Not found" });
});

server.listen(PORT, () => {
  console.log(`KeelCat runner v${VERSION} listening on http://0.0.0.0:${PORT}`);
  console.log(`git available: ${gitAvailable()}`);
  console.log("Endpoints: GET /health , POST /verify");
});

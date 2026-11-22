import assert from "node:assert/strict";
import { test } from "node:test";
import { execFileSync } from "node:child_process";
import { mkdtempSync, readFileSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import { createHash } from "node:crypto";
import {
  clientRuntimeConnectSrc, clientRuntimeProblems, createClientConsentHost,
  createClientOperationBroker, verifyClientArtifact,
} from "../vendor/kotoba-client/kotoba-client-runtime.js";
import { descriptor, requestedCapabilities } from "./descriptor.mjs";

const here = fileURLToPath(new URL("./", import.meta.url));
const root = path.resolve(here, "..");
const hex = /^[0-9a-f]{64}$/;
const hashB64 = (text) => `'sha256-${createHash("sha256").update(text, "utf8").digest("base64")}'`;

const build = (env = {}) => execFileSync(process.execPath, [path.join(root, "scripts/build.mjs")],
  { env: { ...process.env, ...env }, stdio: "pipe" });

test("the descriptor is restricted-wasm-v1 with memory state and no operations", () => {
  assert.equal(descriptor.profile, "restricted-wasm-v1");
  assert.deepEqual(descriptor["local-state"],
    { adapter: "memory", schema: "itonami.kaisha.state.v1", version: 1 });
  assert.deepEqual(descriptor.operations, []);
  assert.deepEqual(requestedCapabilities(descriptor), []);
  assert.deepEqual(
    clientRuntimeProblems(descriptor, requestedCapabilities(descriptor), ["https://tauri.localhost"]), []);
  assert.equal(clientRuntimeConnectSrc(descriptor, [], [], ["https://tauri.localhost"]), "connect-src 'none'");
});

test("the build pins the vendor runtime and writes a verified host plus a guest artifact", () => {
  build();
  const pins = JSON.parse(readFileSync(path.join(root, "vendor/kotoba-client/PINS.json"), "utf8"));
  assert.equal(pins.revision, "a1b90e38c9e5334facfa993062e237299c70de10");
  assert.equal(pins.files["kotoba-client-runtime.js"],
    "d6f67c058c5c40fc56fe500648dc79704a2eee02d4af75fc17898aad1f560a75");
  const css = createHash("sha256").update(readFileSync(path.join(root, "vendor/kotoba-client/kotoba-client-consent.css"))).digest("hex");
  assert.equal(pins.files["kotoba-client-consent.css"], css);
  assert.equal(css.length, 64);
  for (const [name, digest] of Object.entries(pins.files)) {
    assert.equal(createHash("sha256").update(readFileSync(path.join(root, "dist/vendor", name))).digest("hex"),
      digest, `dist/vendor/${name} equals the pin`);
  }
  const release = JSON.parse(readFileSync(path.join(root, "dist/release.json"), "utf8"));
  assert.equal(release.profile, "restricted-wasm-v1");
  assert.ok(hex.test(release.guest.sha256), "guest digest is pinned");
  assert.equal(release.guest.revision,
    execFileSync("git", ["-C", root, "rev-parse", "HEAD"], { encoding: "utf8" }).trim());
  for (const [, digest] of Object.entries(release.files)) assert.ok(hex.test(digest));
});

test("the host page carries the exact derived CSP and no inline code", () => {
  build();
  const index = readFileSync(path.join(root, "dist/index.html"), "utf8");
  const conf = JSON.parse(readFileSync(path.join(root, "src-tauri/tauri.conf.json"), "utf8"));
  const csp = index.match(/Content-Security-Policy" content="([^"]+)"/)[1];
  assert.equal(conf.app.security.csp, csp);
  assert.match(csp, /default-src 'none'/);
  assert.match(csp, /connect-src 'none'/);
  assert.match(csp, /frame-src 'none'/);
  for (const tag of index.matchAll(/<script\b[^>]*>/gi)) assert.ok(/src=/.test(tag[0]), `no inline script in the host: ${tag[0]}`);
  assert.ok(!/<style[\s>]/i.test(index), "no inline style in the host");
  const scriptSrc = csp.match(/script-src ([^;]+)/)[1].split(" ");
  assert.equal(scriptSrc.length, 3, `script-src admits only the preamble and the guest: ${scriptSrc}`);
  const styleSrc = csp.match(/style-src ([^;]+)/)[1].split(" ");
  assert.deepEqual(styleSrc, ["'self'", styleSrc[1]]);
});

test("the guest verifies against its pin and a one-byte change is refused", async () => {
  build();
  const { default: host } = await import(pathToFileURL(path.join(root, "dist/host-data.js")).href);
  assert.deepEqual(host.descriptor, descriptor);
  const release = JSON.parse(readFileSync(path.join(root, "dist/release.json"), "utf8"));
  assert.deepEqual(host.artifact, release.guest);
  const artifact = await verifyClientArtifact(new TextEncoder().encode(host.guest), host.artifact);
  assert.equal(artifact.verified, true);
  await assert.rejects(
    verifyClientArtifact(new TextEncoder().encode(host.guest.slice(0, -1) + "x"), host.artifact),
    /digest mismatch/);
});

test("the guest carries no network calls, no web fonts and no file refs", async () => {
  build();
  const { default: host } = await import(pathToFileURL(path.join(root, "dist/host-data.js")).href);
  const scripts = [...host.guest.matchAll(/<script>([\s\S]*?)<\/script>/gi)];
  assert.equal(scripts.length, 1, "the guest holds exactly one inline script");
  const [, script] = scripts[0];
  assert.ok(!/fetch\(|XMLHttpRequest|WebSocket|EventSource/.test(script), "the guest makes no network calls");
  assert.ok(!/createClientOperationBroker|<script src|<link/.test(script), "no broker or external resource in the guest");
  // Submit buttons replay the submit to the form's own handler (form-action 'none').
  assert.ok(/dispatchEvent\(.{0,120}submit/.test(script), "the submit replay is present");
  assert.ok(!/type:"submit"/.test(script), "no native submit button remains");
  const styles = [...host.guest.matchAll(/<style>([\s\S]*?)<\/style>/gi)];
  assert.equal(styles.length, 1, "the guest holds exactly one inline style");
  assert.ok(!/@font-face/.test(styles[0][1]), "no web fonts in the guest");
  assert.ok(!/url\(icons\//.test(styles[0][1]), "icons are inlined, not referenced");
  assert.ok(/data:image\/svg\+xml/.test(styles[0][1]), "icons travel as data: URLs");
  const index = readFileSync(path.join(root, "dist/index.html"), "utf8");
  const csp = index.match(/Content-Security-Policy" content="([^"]+)"/)[1];
  assert.ok(csp.includes(hashB64(script)), "page CSP admits the guest script by hash");
  assert.ok(csp.includes(hashB64(styles[0][1])), "page CSP admits the guest style by hash");
});

test("the broker refuses every call for an operation-free app before any dialog", async () => {
  const makeEl = () => ({
    children: [],
    setAttribute() {},
    addEventListener() {},
    append(...kids) { this.children.push(...kids); },
    replaceChildren(...kids) { this.children = kids; },
  });
  globalThis.document = { body: makeEl(), createElement: makeEl };
  const consentHost = createClientConsentHost({ locale: "ja" });
  const broker = createClientOperationBroker({ runtime: descriptor, consentHost });
  assert.deepEqual(broker.operations, []);
  await assert.rejects(
    broker.invoke({ capability: "net", service: "x", operation: "y" }, {}),
    /not eligible/);
});

test("the build fails closed on a CSP drift without WRITE_CSP=1", () => {
  build();
  const confPath = path.join(root, "src-tauri/tauri.conf.json");
  const conf = JSON.parse(readFileSync(confPath, "utf8"));
  const saved = conf.app.security.csp;
  conf.app.security.csp = "default-src 'none'";
  writeFileSync(confPath, JSON.stringify(conf, null, 2) + "\n");
  try {
    assert.throws(() => build(), /csp differs/);
  } finally {
    conf.app.security.csp = saved;
    writeFileSync(confPath, JSON.stringify(conf, null, 2) + "\n");
  }
  const dir = mkdtempSync(path.join(tmpdir(), "kaisha-fixture-"));
  assert.throws(() => build({ MAIN_JS: path.join(dir, "nope.js") }), /missing/);
});

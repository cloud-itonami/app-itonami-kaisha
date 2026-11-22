// 静的ファイルの組み立て（KOTOBA-159 restricted-wasm-v1）。サーバーで動く処理はない。
// 前提: `bb ui` で target/app/main.js ができていること。
// Kaisha は端末内だけで動く（会社・議事・稟議はこの端末にだけ置く）ので、操作の
// 宣言は空、CSP の connect-src は none。guest は 1 文書に inline 化する:
// web フォントは system font に（@font-face を落とす）、icon は data: に内包。
// localStorage を使う画面コードは try/catch で memory に落ちる（descriptor は
// memory）。dist/ をそのまま Tauri が埋め込む。
import {mkdir, copyFile, readFile, readdir, writeFile, rm} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';
import {resolve} from 'node:path';
import {createHash} from 'node:crypto';
import {execFileSync} from 'node:child_process';
import {descriptor, requestedCapabilities} from '../shell/descriptor.mjs';
import {clientRuntimeConnectSrc, clientRuntimeProblems} from '../vendor/kotoba-client/kotoba-client-runtime.js';

const root = fileURLToPath(new URL('../', import.meta.url));
const out = resolve(root, 'dist');
const sha = (b) => createHash('sha256').update(b).digest('hex');
const shaB64 = (text) => `'sha256-${createHash('sha256').update(text, 'utf8').digest('base64')}'`;
const fail = (message) => { console.error(`kaisha build: ${message}`); process.exit(1); };

// awai-ui は awai-os の固定 revision から取り込み、digest が変わっていたら止める。
const pins = JSON.parse(await readFile(resolve(root, 'vendor/awai-ui/PINS.json'), 'utf8'));
const vendor = {};
for (const [name, expected] of Object.entries(pins.files)) {
  const bytes = await readFile(resolve(root, 'vendor/awai-ui', name));
  if (sha(bytes) !== expected) fail(`vendor/awai-ui/${name}: digest differs from PINS.json`);
  vendor[name] = bytes;
}
// 共有ランタイム (restricted-wasm-v1) の pin 照合。
const runtimePins = JSON.parse(await readFile(resolve(root, 'vendor/kotoba-client/PINS.json'), 'utf8'));
for (const [name, digest] of Object.entries(runtimePins.files)) {
  if (sha(await readFile(resolve(root, 'vendor/kotoba-client', name))) !== digest) {
    fail(`vendor/kotoba-client/${name}: digest differs from PINS.json`);
  }
}

const capabilities = requestedCapabilities(descriptor);
const origins = ['https://tauri.localhost'];
const problems = clientRuntimeProblems(descriptor, capabilities, origins);
if (problems.length) fail(`descriptor is invalid: ${JSON.stringify(problems)}`);
if (descriptor.profile !== 'restricted-wasm-v1') fail(`profile is ${descriptor.profile}; this build only ships restricted-wasm-v1`);
const connect = clientRuntimeConnectSrc(descriptor, capabilities, [], origins);
if (connect !== 'connect-src \'none\'') fail(`connect-src must be 'none' for an operation-free app: ${connect}`);

const mainPath = process.env.MAIN_JS ?? resolve(root, 'target/app/main.js');
const main = await readFile(mainPath, 'utf8').catch(() =>
  fail(`${mainPath} is missing; run \`bb ui\` first`));
const guestScript = main;
if (/<\/script/i.test(guestScript)) fail('guest script contains </script');
if (/^\s*(import|export)\b/m.test(guestScript)) fail('the restricted guest script must not import or export');
if (/\bfetch\(|XMLHttpRequest|WebSocket|EventSource/.test(guestScript)) {
  fail('guest script makes network calls; declare them as operations or remove them');
}
// e-Gov 制度データなど参照 URL の文字列は残る（通信しない）。到達できる形の
// 参照だけ落とす: iframe/form/外部 script・link。
if (/<iframe[\s>]|<form[\s>]|<script\b[^>]*src=|<link\b[^>]*href="https?:/i.test(guestScript)) {
  fail('guest script carries a remote resource reference');
}

let awaiCss = '';
{
  const raw = vendor['ui.css'].toString('utf8');
  // Web フォントは guest では読めない（sandbox の font は system のみ）。
  // 1 行目の @font-face を落とし、family stack の後段に任せる。
  const stripped = raw.replace(/@font-face\{[^}]*\}/, '');
  if (stripped === raw) fail('vendor/awai-ui/ui.css has no @font-face to strip; re-check the vendoring');
  if (/@font-face/.test(stripped)) fail('guest style still carries @font-face');
  awaiCss = stripped;
}
if (/<\/style/i.test(awaiCss)) fail('guest style contains </style');
// Figma の Awai DS · market & status（vendor/awai-ds/status.json）を同じ規則で変数にする。
const ds = JSON.parse(await readFile(resolve(root, 'vendor/awai-ds/status.json'), 'utf8'));
const tokens = JSON.parse(vendor['figma-android-v2.json']);
const colors = (theme) => Object.entries(tokens.colors).map(([k, v]) => `--awai-${k}:${v[theme].hex};`).join('');
const dim = tokens.dimensions;
const dimCss = `--awai-radius-large:${dim['shape-large']}px;--awai-radius-medium:${dim['shape-medium']}px;--awai-space:${dim['space-compact']}px;--awai-target:${dim['target-min']}px;`;
const dsColors = (theme) => Object.entries(ds.colors).map(([k, v]) => `--awai-${k}:${v[theme]};`).join('');
const themeCss = `.awai-ui{${colors('light')}${dsColors('light')}${dimCss}}\n@media(prefers-color-scheme:dark){.awai-ui:not([data-awai-theme=light]){${colors('dark')}${dsColors('dark')}}}\n.awai-ui[data-awai-theme=dark]{${colors('dark')}${dsColors('dark')}}}\n`;
let appCss = await readFile(resolve(root, 'web/style.css'), 'utf8');
// icon は mask の url() 参照。8 個の SVG を data: に内包して外部参照を消す。
{
  const refs = [...new Set([...appCss.matchAll(/url\(icons\/([a-z-]+\.svg)\)/g)].map((m) => m[1]))];
  if (refs.length === 0) fail('web/style.css references no icons/*.svg; re-check the inlining');
  for (const name of refs) {
    const svg = await readFile(resolve(root, `web/icons/${name}`), 'utf8').catch(() =>
      fail(`web/icons/${name} is missing`));
    if (/<script/i.test(svg)) fail(`web/icons/${name} carries a script`);
    appCss = appCss.split(`url(icons/${name})`).join(`url("data:image/svg+xml,${encodeURIComponent(svg)}")`);
  }
  if (/url\(icons\//.test(appCss)) fail('guest style still references icons/*.svg');
}
const style = `${themeCss}${awaiCss}\n${appCss}`;
if (/<\/style/i.test(style)) fail('guest style contains </style');
if (/url\(\s*["']?https?:/i.test(style)) fail('guest style references a remote URL');

const indexHtml = await readFile(resolve(root, 'web/index.html'), 'utf8');
const title = (() => {
  const m = /<title>([\s\S]*?)<\/title>/i.exec(indexHtml);
  return m ? m[1] : 'Kaisha';
})();
const body = ((m) => {
  if (!m) fail('web/index.html has no <body>');
  const stripped = m[1].replace(/<script\b[^>]*\bsrc=["']main\.js["'][^>]*>\s*<\/script>/i, '');
  if (/<script[\s>]/i.test(stripped)) fail('guest body carries another script; main.js is inlined by this build');
  if (/<link[\s>]/i.test(stripped)) fail('guest body carries a stylesheet link; CSS is inlined by this build');
  if (/<iframe[\s>]/i.test(stripped)) fail("guest body must not carry an iframe (frame-src 'none')");
  if (/<form[\s>]/i.test(stripped)) fail("guest body must not carry a form (form-action 'none')");
  return stripped;
})(/<body[^>]*>([\s\S]*?)<\/body>/i.exec(indexHtml));

const fill = (template, what, values) => template.replace(/\{\{([A-Z]+)\}\}/g, (_, key) => {
  if (!Object.hasOwn(values, key)) fail(`${what} has an unfilled placeholder {{${key}}}`);
  return values[key];
});
const vendorText = await readFile(resolve(root, 'vendor/kotoba-client/kotoba-client-runtime.js'), 'utf8');
const preambleText = (() => {
  const m = /^const GUEST_PREAMBLE = '<script>([^'\\]*)<\/script>';$/m.exec(vendorText);
  if (!m) fail('vendor/kotoba-client/kotoba-client-runtime.js has no GUEST_PREAMBLE this build can hash');
  return m[1];
})();

const commit = (() => {
  try {
    return execFileSync('git', ['-C', root, 'rev-parse', 'HEAD'], {encoding: 'utf8'}).trim();
  } catch {
    fail('restricted-wasm-v1 pins the guest to its source commit: build from a git checkout');
  }
})();
if (!/^[0-9a-f]{40}$/.test(commit)) fail(`git rev-parse HEAD gave ${JSON.stringify(commit)}`);

const guest = fill(await readFile(resolve(root, 'shell/guest.html'), 'utf8'), 'guest.html', {
  TITLE: title, STYLE: style, BODY: body, SCRIPT: guestScript,
});
const guestBytes = Buffer.from(guest, 'utf8');
if (guestBytes.length > 8 * 1024 * 1024) fail('the restricted guest exceeds the 8 MiB mount budget');
const artifact = {revision: commit, sha256: sha(guestBytes), 'max-bytes': guestBytes.length};

const csp = [
  'default-src \'none\'',
  `script-src 'self' ${shaB64(preambleText)} ${shaB64(guestScript)}`,
  `style-src 'self' ${shaB64(style)}`,
  'img-src \'self\' data:',
  'font-src \'self\'',
  connect,
  'object-src \'none\'',
  'frame-src \'none\'',
  'base-uri \'none\'',
  'form-action \'none\'',
].join('; ');

const confPath = resolve(root, 'src-tauri/tauri.conf.json');
const conf = JSON.parse(await readFile(confPath, 'utf8'));
if (conf.app?.security?.csp !== csp) {
  if (process.env.WRITE_CSP === '1') {
    conf.app.security.csp = csp;
    await writeFile(confPath, JSON.stringify(conf, null, 2) + '\n');
    console.error('kaisha build: wrote src-tauri/tauri.conf.json csp; re-run to verify');
    process.exit(2);
  }
  fail('src-tauri/tauri.conf.json csp differs from the derived policy; re-run with WRITE_CSP=1');
}

await rm(out, {recursive: true, force: true});
await mkdir(resolve(out, 'vendor'), {recursive: true});
await writeFile(resolve(out, 'index.html'),
  fill(await readFile(resolve(root, 'shell/host.html'), 'utf8'), 'host.html', {CSP: csp, TITLE: title}));
await copyFile(resolve(root, 'shell/host.js'), resolve(out, 'host.js'));
await copyFile(resolve(root, 'shell/host.css'), resolve(out, 'host.css'));
await copyFile(resolve(root, 'vendor/kotoba-client/kotoba-client-runtime.js'), resolve(out, 'vendor/kotoba-client-runtime.js'));
await copyFile(resolve(root, 'vendor/kotoba-client/kotoba-client-consent.css'), resolve(out, 'vendor/kotoba-client-consent.css'));
await writeFile(resolve(out, 'host-data.js'),
  `export default ${JSON.stringify({descriptor, artifact, guest})};\n`);
await writeFile(resolve(out, 'descriptor.json'), JSON.stringify(descriptor, null, 2) + '\n');

const pkg = JSON.parse(await readFile(resolve(root, 'package.json'), 'utf8'));
const names = ['index.html', 'host.css', 'host.js', 'host-data.js', 'descriptor.json', 'vendor/kotoba-client-runtime.js', 'vendor/kotoba-client-consent.css'];
const files = {};
for (const n of names) files[n] = sha(await readFile(resolve(out, n)));
const core = {};
for (const n of ['app', 'books', 'chat', 'company', 'date', 'deadlines', 'i18n', 'law', 'meeting', 'ringi'])
  core[`src/kaisha/${n}.cljc`] = sha(await readFile(resolve(root, `src/kaisha/${n}.cljc`)));
await writeFile(resolve(out, 'release.json'), JSON.stringify({
  schema: 'awai.app.artifact.v1',
  id: 'network.itonami.kaisha',
  version: pkg.version,
  tauri: '3.0.0-alpha.6',
  profile: descriptor.profile,
  runtimePins: runtimePins,
  awaiUi: {source: pins.source, revision: pins.revision},
  awaiDs: {source: ds.source, sha256: sha(await readFile(resolve(root, 'vendor/awai-ds/status.json')))},
  sharedCore: core,
  files,
  guest: artifact,
}, null, 2) + '\n');
console.log(`Kaisha restricted frontend: ${names.length} files; ${connect}; guest ${guestBytes.length} bytes`);

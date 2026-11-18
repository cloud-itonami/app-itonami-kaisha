// 静的ファイルの組み立て。サーバーで動く処理はない（dist/ をそのまま Tauri が埋め込む）。
// 前提: `bb ui` で target/app/main.js ができていること。
import {mkdir, copyFile, readFile, writeFile, rm} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';
import {resolve} from 'node:path';
import {createHash} from 'node:crypto';

const root = fileURLToPath(new URL('../', import.meta.url));
const out = resolve(root, 'dist');
const sha = (b) => createHash('sha256').update(b).digest('hex');

// awai-ui は awai-os の固定 revision から取り込み、digest が変わっていたら止める。
const pins = JSON.parse(await readFile(resolve(root, 'vendor/awai-ui/PINS.json'), 'utf8'));
const vendor = {};
for (const [name, expected] of Object.entries(pins.files)) {
  const bytes = await readFile(resolve(root, 'vendor/awai-ui', name));
  if (sha(bytes) !== expected) throw Error(`vendor/awai-ui/${name}: digest differs from PINS.json`);
  vendor[name] = bytes;
}

// awai-os packages/awai-ui/build/awai/build.cljs と同じ規則で色と寸法の変数を作る。
const tokens = JSON.parse(vendor['figma-android-v2.json']);
const colors = (theme) => Object.entries(tokens.colors).map(([k, v]) => `--awai-${k}:${v[theme].hex};`).join('');
const dim = tokens.dimensions;
const dimCss = `--awai-radius-large:${dim['shape-large']}px;--awai-radius-medium:${dim['shape-medium']}px;--awai-space:${dim['space-compact']}px;--awai-target:${dim['target-min']}px;`;
const awaiCss = `.awai-ui{${colors('light')}${dimCss}}\n@media(prefers-color-scheme:dark){.awai-ui:not([data-awai-theme=light]){${colors('dark')}}}\n.awai-ui[data-awai-theme=dark]{${colors('dark')}}\n` + vendor['ui.css'].toString('utf8');

await rm(out, {recursive: true, force: true});
await mkdir(out, {recursive: true});
await copyFile(resolve(root, 'target/app/main.js'), resolve(out, 'main.js'));
await copyFile(resolve(root, 'web/index.html'), resolve(out, 'index.html'));
await copyFile(resolve(root, 'web/style.css'), resolve(out, 'style.css'));
await writeFile(resolve(out, 'awai-ui.css'), awaiCss);
await writeFile(resolve(out, 'awai-ui-LICENSE.txt'), vendor['LICENSE']);

const pkg = JSON.parse(await readFile(resolve(root, 'package.json'), 'utf8'));
const descriptor = {
  schema: 'kotoba.app.client-runtime.v1',
  target: 'browser',
  profile: 'trusted-page-v1',
  entry: 'index.html',
  'local-state': {adapter: 'localstorage', schema: 'itonami.kaisha.state.v1', version: 1},
  workers: [],
  operations: []
};
await writeFile(resolve(out, 'descriptor.json'), JSON.stringify(descriptor, null, 2) + '\n');

const names = ['index.html', 'style.css', 'main.js', 'awai-ui.css', 'awai-ui-LICENSE.txt', 'descriptor.json'];
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
  awaiUi: {source: pins.source, revision: pins.revision},
  sharedCore: core,
  files
}, null, 2) + '\n');
console.log(`Kaisha static build: ${names.length} files in dist/`);

// Bundles the workbench into dist/ (index.html, assets/): the app, Monaco's editor worker, styles, fonts, and the
// third-party notices of everything bundled. `npm run bundle`; the Gradle task :editors:web:bundle runs it.
import { build } from 'esbuild';
import { cpSync, existsSync, mkdirSync, readFileSync, readdirSync, rmSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';

const out = 'dist';
rmSync(out, { recursive: true, force: true });
mkdirSync(join(out, 'assets'), { recursive: true });

const common = {
  bundle: true,
  minify: true,
  sourcemap: false,
  target: 'es2022',
  legalComments: 'none',
  alias: { 'monaco-esm': './node_modules/monaco-editor/esm/vs' },
  logLevel: 'warning',
};

await build({
  ...common,
  entryPoints: { app: 'src/main.tsx' },
  outdir: join(out, 'assets'),
  format: 'esm',
  jsx: 'automatic',
  jsxImportSource: 'preact',
  loader: { '.ttf': 'file', '.woff': 'file', '.woff2': 'file' },
  assetNames: '[name]-[hash]',
  publicPath: '/assets',
});
await build({
  ...common,
  entryPoints: { 'editor.worker': 'node_modules/monaco-editor/esm/vs/editor/editor.worker.js' },
  outdir: join(out, 'assets'),
  format: 'iife',
});

cpSync('src/index.html', join(out, 'index.html'));
cpSync('src/icon.svg', join(out, 'assets', 'icon.svg'));

// notices: the license of every package that ends up in the bundle
const bundled = ['monaco-editor', 'preact', 'fflate', '@fontsource/atkinson-hyperlegible'];
let notices = 'Third-party software in the plca workbench (browser app)\n\n';
for (const name of bundled) {
  const dir = join('node_modules', name);
  const pkg = JSON.parse(readFileSync(join(dir, 'package.json'), 'utf8'));
  const license = readdirSync(dir).find((f) => /^licen[cs]e/i.test(f));
  notices += `${'='.repeat(78)}\n${name} ${pkg.version} (${pkg.license})\n${pkg.homepage ?? ''}\n\n`;
  notices += license ? readFileSync(join(dir, license), 'utf8').trim() + '\n\n' : '\n';
  const extra = join(dir, 'ThirdPartyNotices.txt');
  if (existsSync(extra)) {
    notices += readFileSync(extra, 'utf8').trim() + '\n\n';
  }
}
writeFileSync(join(out, 'THIRD_PARTY_NOTICES.txt'), notices);

const size = (d) =>
  readdirSync(d, { withFileTypes: true }).reduce((n, e) => n + (e.isDirectory() ? size(join(d, e.name)) : readFileSync(join(d, e.name)).length), 0);
console.log(`dist: ${(size(out) / 1024 / 1024).toFixed(1)} MB`);

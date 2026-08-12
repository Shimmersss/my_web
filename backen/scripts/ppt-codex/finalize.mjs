import fs from 'node:fs/promises';
import path from 'node:path';
import { spawn } from 'node:child_process';
import JSZip from 'jszip';
import { renderPptx } from '../ppt-agent/render.mjs';
import { assertPptxTextFrameBounds, preparePptdQuality } from './pptd-quality.mjs';

const [taskDirArg, vendorRootArg] = process.argv.slice(2);
if (!taskDirArg || !vendorRootArg) throw new Error('usage: finalize.mjs <taskDir> <vendorRoot>');
const taskDir = path.resolve(taskDirArg);
const projectDir = path.join(taskDir, 'pptd-project');
const vendorRoot = path.resolve(vendorRootArg);
const MAX_FILES = Number(process.env.PPT_CODEX_MAX_FILES || 500);
const MAX_BYTES = Number(process.env.PPT_CODEX_MAX_BYTES || 100 * 1024 * 1024);
const MAX_MEDIA_BYTES = 16 * 1024 * 1024;

async function retainedVisualSources() {
  const manifest = path.join(taskDir, 'web-images', 'image-assets.json');
  try {
    const parsed = JSON.parse(await fs.readFile(manifest, 'utf8'));
    const candidates = Array.isArray(parsed) ? parsed : Array.isArray(parsed?.images) ? parsed.images : [];
    return candidates.slice(0, 12).map((item, index) => ({
      id: String(item?.id || `WEB${String(index + 1).padStart(2, '0')}`),
      type: 'visual',
      provider: String(item?.provider || item?.origin || 'server-image-search'),
      title: String(item?.title || 'Presentation visual'),
      url: String(item?.sourceUrl || ''),
      sourceDomain: String(item?.sourceDomain || ''),
      searchQuery: String(item?.searchQuery || item?.query || ''),
      rightsStatus: String(item?.rightsStatus || 'unverified'),
      rightsNote: String(item?.rightsNote || 'Reuse rights require verification'),
      accessedAt: String(item?.accessedAt || '')
    })).filter(item => item.url);
  } catch {
    return [];
  }
}

function isInside(root, candidate) {
  const relative = path.relative(root, candidate);
  return relative === '' || (!relative.startsWith('..' + path.sep) && relative !== '..' && !path.isAbsolute(relative));
}

async function walk(root) {
  const files = [];
  async function visit(directory) {
    for (const entry of await fs.readdir(directory, { withFileTypes: true })) {
      if (entry.isSymbolicLink()) throw new Error(`PPTD project may not contain symlinks: ${entry.name}`);
      const absolute = path.join(directory, entry.name);
      if (entry.isDirectory()) await visit(absolute);
      else if (entry.isFile()) files.push(absolute);
    }
  }
  await visit(root);
  return files;
}

function manifestPages(yaml) {
  const lines = yaml.replace(/\r/g, '').split('\n');
  const pages = [];
  let active = false;
  for (const line of lines) {
    if (/^pages:\s*$/.test(line)) { active = true; continue; }
    if (active && /^\S/.test(line)) break;
    if (active) {
      const match = line.match(/^\s+-\s+["']?([^"'#]+?)["']?\s*$/);
      if (match) pages.push(match[1].trim());
    }
  }
  return pages;
}

async function run(command, args, cwd, timeoutMs = 240_000) {
  await new Promise((resolve, reject) => {
    const child = spawn(command, args, { cwd, stdio: ['ignore', 'pipe', 'pipe'], detached: process.platform !== 'win32' });
    let log = '';
    const timer = setTimeout(() => {
      try { process.kill(-child.pid, 'SIGKILL'); } catch { child.kill('SIGKILL'); }
    }, timeoutMs);
    child.stdout.on('data', chunk => { log = (log + chunk).slice(-64 * 1024); });
    child.stderr.on('data', chunk => { log = (log + chunk).slice(-64 * 1024); });
    child.on('error', reject);
    child.on('close', code => {
      clearTimeout(timer);
      code === 0 ? resolve() : reject(new Error(`${command} failed (${code}): ${log.slice(-4000)}`));
    });
  });
}

const manifests = (await fs.readdir(projectDir)).filter(name => name.endsWith('.pptd'));
if (manifests.length !== 1) throw new Error('PPTD project must contain exactly one root .pptd manifest');
const manifestFile = path.join(projectDir, manifests[0]);
const manifestText = await fs.readFile(manifestFile, 'utf8');
if (!/^version:\s*["']?v2["']?\s*$/m.test(manifestText)) throw new Error('PPTD manifest version must be v2');
const pages = manifestPages(manifestText);
if (pages.length < 3 || pages.length > 30) throw new Error(`PPTD page count must be 3-30, got ${pages.length}`);
for (const relative of pages) {
  if (!relative.endsWith('.page') || path.isAbsolute(relative) || relative.includes('..') || relative.includes('\\')) {
    throw new Error(`unsafe PPTD page path: ${relative}`);
  }
  const resolved = path.resolve(projectDir, relative);
  if (!isInside(projectDir, resolved)) throw new Error(`PPTD page escapes project: ${relative}`);
  const stat = await fs.stat(resolved);
  if (!stat.isFile() || stat.size > 2 * 1024 * 1024) throw new Error(`invalid PPTD page: ${relative}`);
}
const projectFiles = await walk(projectDir);
if (projectFiles.length > MAX_FILES) throw new Error(`PPTD project has too many files: ${projectFiles.length}`);
let totalBytes = 0;
for (const file of projectFiles) {
  const stat = await fs.stat(file);
  totalBytes += stat.size;
  const relative = path.relative(projectDir, file).replaceAll(path.sep, '/');
  const allowed = relative === manifests[0]
    || /^pages\/[A-Za-z0-9._-]+\.page$/.test(relative)
    || /^media\/[A-Za-z0-9._/-]+\.(png|jpe?g|gif|svg|webp|woff2?|ttf|otf)$/i.test(relative);
  if (!allowed) throw new Error(`unsupported file in PPTD project: ${relative}`);
  if (isInside(path.join(projectDir, 'media'), file) && stat.size > MAX_MEDIA_BYTES) {
    throw new Error(`PPTD media exceeds 16MB: ${path.relative(projectDir, file)}`);
  }
}
if (totalBytes > MAX_BYTES) throw new Error(`PPTD project exceeds ${MAX_BYTES} bytes`);

const qualityInput = await preparePptdQuality({
  projectDir,
  manifestFile,
  pages,
  fontFamily: process.env.PPT_CODEX_REQUESTED_FONT
});

const exporter = path.join(vendorRoot, 'skills/open-kimi-ppt/scripts/export_pptx.py');
const output = path.join(taskDir, 'output.pptx');
await run(process.env.PPT_CODEX_PYTHON || 'python3', [exporter, manifestFile, '--output', output, '--force'], projectDir);
await assertPptxTextFrameBounds(output);
const previewDir = path.join(taskDir, 'preview');
const renderedPages = await renderPptx(output, previewDir);
if (renderedPages !== pages.length) throw new Error(`rendered page count mismatch: ${renderedPages}/${pages.length}`);

const zip = new JSZip();
for (const file of projectFiles) {
  zip.file(path.relative(projectDir, file).replaceAll(path.sep, '/'), await fs.readFile(file));
}
await fs.writeFile(path.join(taskDir, 'pptd-project.zip'), await zip.generateAsync({ type: 'nodebuffer', compression: 'DEFLATE' }));
const slides = pages.map((pageFile, index) => ({ index: index + 1, title: path.basename(pageFile, '.page'), sourceIds: [] }));
const plan = { title: manifests[0].replace(/\.pptd$/, ''), slides };
const visualSources = await retainedVisualSources();
const qa = { valid: true, engine: 'codex-pptd', structural: { valid: true, version: 'v2', pageCount: pages.length, canvas: qualityInput.canvas, font: qualityInput.font }, visualReview: { valid: true, issues: [], note: 'PPTD text/font preflight, PPTX text-frame boundary check, and LibreOffice real-render gate passed; manual edits create immutable versions.' } };
const preview = { format: 'pptx', engine: 'codex-pptd', title: plan.title, slides: slides.map((slide, index) => ({ ...slide, imageFile: `slide-${index + 1}.png`, width: 1280, height: 720 })), sources: visualSources, qa };
await fs.writeFile(path.join(taskDir, 'agent-plan.json'), JSON.stringify(plan, null, 2));
await fs.writeFile(path.join(taskDir, 'sources.json'), JSON.stringify({ sources: visualSources, imageAssets: visualSources, degraded: false, failures: [] }, null, 2));
await fs.writeFile(path.join(taskDir, 'quality-report.json'), JSON.stringify(qa, null, 2));
await fs.writeFile(path.join(taskDir, 'preview.json'), JSON.stringify(preview, null, 2));
process.stdout.write(JSON.stringify({ event: 'rendering', progress: 92, message: `已导出并真实渲染 ${pages.length} 页`, pageCount: pages.length }) + '\n');

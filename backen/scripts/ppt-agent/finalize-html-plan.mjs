import fs from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { createHtmlPresentation } from './html-presentation.mjs';
import { fitHtmlSlideToViewport } from './plan-utils.mjs';
import { deterministicQa } from './qa.mjs';
import { renderHtml } from './render.mjs';

const SCRIPT_DIR = path.dirname(fileURLToPath(import.meta.url));
const PROJECT_ROOT = path.resolve(SCRIPT_DIR, '../../..');
const THEMES_FILE = path.join(PROJECT_ROOT, '.agents/skills/create-html-presentation/assets/themes.json');
const MAX_PLAN_BYTES = 1024 * 1024;
const MAX_OUTPUT_BYTES = 36 * 1024 * 1024;
const MAX_IMAGE_BYTES = 4 * 1024 * 1024;
const MAX_IMAGES = 12;
const LAYOUTS = new Set([
  'cover', 'section', 'statement', 'image-hero', 'split', 'evidence', 'stats',
  'process', 'comparison', 'timeline', 'quote', 'gallery', 'closing'
]);
const LAYOUT_ALIASES = new Map([
  ['agenda', 'process'], ['content', 'statement'], ['hero', 'image-hero'],
  ['image', 'image-hero'], ['imagehero', 'image-hero'], ['kpi', 'stats'],
  ['metrics', 'stats'], ['steps', 'process']
]);

function plain(value, limit) {
  const text = String(value ?? '')
    .replace(/[*_`#]+/g, '')
    .replace(/\s*\[(?:(?:S|WEB|U)\d+)(?:\s*[,，;；]\s*(?:S|WEB|U)\d+)*\]/gi, '')
    .replace(/\s+/g, ' ')
    .trim();
  return text.slice(0, limit);
}

function canonicalLayout(value) {
  const normalized = plain(value, 32).toLowerCase().replace(/_/g, '-');
  return LAYOUT_ALIASES.get(normalized) || (LAYOUTS.has(normalized) ? normalized : 'statement');
}

function inside(root, candidate) {
  const resolvedRoot = path.resolve(root);
  const resolved = path.resolve(candidate);
  return resolved === resolvedRoot || resolved.startsWith(`${resolvedRoot}${path.sep}`);
}

async function acceptedImage(file) {
  if (!inside(path.dirname(file), file)) return false;
  const stat = await fs.lstat(file);
  if (!stat.isFile() || stat.isSymbolicLink() || stat.size < 8 || stat.size > MAX_IMAGE_BYTES) return false;
  const handle = await fs.open(file, 'r');
  try {
    const bytes = Buffer.alloc(12);
    const { bytesRead } = await handle.read(bytes, 0, bytes.length, 0);
    const png = bytesRead >= 8 && bytes.subarray(0, 8).equals(Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]));
    const jpeg = bytesRead >= 3 && bytes[0] === 0xff && bytes[1] === 0xd8 && bytes[2] === 0xff;
    const gif = bytesRead >= 6 && /^GIF8[79]a$/.test(bytes.subarray(0, 6).toString('ascii'));
    return png || jpeg || gif;
  } finally {
    await handle.close();
  }
}

async function taskAssets(taskDir) {
  const images = [];
  const uploadedDir = path.join(taskDir, 'images');
  try {
    const names = (await fs.readdir(uploadedDir)).filter(name => /\.(?:png|jpe?g|gif)$/i.test(name))
      .filter(name => !/^paper-page-/i.test(name)).sort().slice(0, 8);
    for (const name of names) {
      const file = path.join(uploadedDir, name);
      if (await acceptedImage(file)) images.push({ id: `U${String(images.length + 1).padStart(2, '0')}`, fileName: name, path: file, origin: 'upload' });
    }
  } catch { /* no uploaded images */ }

  const sources = [];
  const webDir = path.join(taskDir, 'web-images');
  try {
    const manifestFile = path.join(webDir, 'image-assets.json');
    const stat = await fs.lstat(manifestFile);
    if (!stat.isFile() || stat.isSymbolicLink() || stat.size > 512 * 1024) throw new Error('网络图片清单无效');
    const manifest = JSON.parse(await fs.readFile(manifestFile, 'utf8'));
    for (const [index, item] of (Array.isArray(manifest.images) ? manifest.images : []).slice(0, 8).entries()) {
      const name = String(item.fileName || item.localPath || '');
      if (!/^WEB\d{2}\.(?:png|jpe?g|gif)$/i.test(name)) continue;
      const file = path.resolve(webDir, name);
      if (!inside(webDir, file) || !(await acceptedImage(file))) continue;
      const id = /^WEB\d{2}$/i.test(String(item.id || '')) ? String(item.id).toUpperCase() : `WEB${String(index + 1).padStart(2, '0')}`;
      const sourceUrl = /^https:\/\//i.test(String(item.sourceUrl || '')) ? String(item.sourceUrl) : '';
      const source = {
        id,
        title: plain(item.title || item.description || `网络视觉 ${index + 1}`, 180),
        url: sourceUrl,
        type: 'visual',
        provider: plain(item.provider, 48),
        rightsStatus: plain(item.rightsStatus || 'unverified', 32),
        rightsNote: plain(item.rightsNote, 240)
      };
      images.push({ ...source, fileName: name, path: file, sourceUrl, origin: 'web-search' });
      sources.push(source);
      if (images.length >= MAX_IMAGES) break;
    }
  } catch { /* web search is optional unless strict mode is checked below */ }
  return { images: images.slice(0, MAX_IMAGES), sources };
}

function normalizePlan(candidate, assets, visualMode) {
  if (!candidate || typeof candidate !== 'object' || Array.isArray(candidate) || !Array.isArray(candidate.slides)) {
    throw new Error('Codex HTML 计划结构无效');
  }
  if (candidate.slides.length < 3 || candidate.slides.length > 30) throw new Error('Codex HTML 计划页数必须为 3–30 页');
  const imageIds = new Set(assets.images.map(item => item.id));
  const sourceIds = new Set(assets.sources.map(item => item.id));
  const slides = candidate.slides.map((raw, index) => {
    const type = index === 0 ? 'cover' : index === candidate.slides.length - 1 ? 'closing' : plain(raw?.type || 'content', 24).toLowerCase();
    const layout = type === 'cover' ? 'cover' : type === 'closing' ? 'closing' : type === 'section' ? 'section' : canonicalLayout(raw?.layout || type);
    const items = Array.isArray(raw?.items) ? raw.items.slice(0, 6).map(item => {
      if (!item || typeof item !== 'object' || Array.isArray(item)) return plain(item, 120);
      const label = plain(item.label, 48);
      const value = plain(item.value, 48);
      const detail = plain(item.detail, 80);
      return [label, value].filter(Boolean).join('：') + (detail ? ` — ${detail}` : '');
    }).filter(Boolean) : [];
    const bullets = (Array.isArray(raw?.bullets) && raw.bullets.length ? raw.bullets : items)
      .map(item => plain(item, 120)).filter(Boolean).slice(0, 6);
    const requestedImageId = imageIds.has(String(raw?.imageId || '')) ? String(raw.imageId) : '';
    const imageId = ['image-hero', 'split', 'evidence', 'gallery'].includes(layout) ? requestedImageId : '';
    const slide = {
      type,
      layout,
      tone: ['base', 'light', 'deep'].includes(String(raw?.tone || '').toLowerCase()) ? String(raw.tone).toLowerCase() : undefined,
      section: plain(raw?.section, 48),
      title: plain(raw?.title || (type === 'closing' ? '感谢观看' : `第 ${index + 1} 页`), 80),
      headline: plain(raw?.headline, 180),
      bullets,
      imageId,
      sourceIds: (Array.isArray(raw?.sourceIds) ? raw.sourceIds : []).map(String).filter(id => sourceIds.has(id)).slice(0, 8),
      notes: plain(raw?.notes, 600)
    };
    if (type === 'closing') {
      slide.title = '感谢观看';
      slide.headline = slide.headline || '谢谢聆听';
      slide.bullets = [];
      slide.imageId = '';
      slide.sourceIds = [];
    }
    return fitHtmlSlideToViewport(slide, { hasImage: Boolean(imageId) });
  });
  const strict = String(visualMode).toLowerCase() === 'strict';
  if (strict && !slides.slice(1, -1).some(slide => slide.imageId.startsWith('WEB'))) {
    throw new Error('严格配图模式要求至少一页使用已校验的网络视觉素材');
  }
  return {
    title: plain(candidate.title || slides[0].title || '演示文稿', 120),
    audience: plain(candidate.audience, 160),
    takeaway: plain(candidate.takeaway, 240),
    slides
  };
}

export async function finalizeHtmlPlan({ taskDir, planFile, templateKey, fontFamily, motionMode, visualMode }) {
  taskDir = path.resolve(taskDir);
  planFile = path.resolve(planFile);
  if (path.parse(taskDir).root === taskDir || !inside(taskDir, planFile)) throw new Error('HTML 任务目录或计划路径不安全');
  const taskStat = await fs.lstat(taskDir);
  if (!taskStat.isDirectory() || taskStat.isSymbolicLink()) throw new Error('HTML 任务目录不安全');
  const stat = await fs.lstat(planFile);
  if (!stat.isFile() || stat.isSymbolicLink() || stat.size < 2 || stat.size > MAX_PLAN_BYTES) throw new Error('Codex HTML 计划文件无效');
  const assets = await taskAssets(taskDir);
  const plan = normalizePlan(JSON.parse(await fs.readFile(planFile, 'utf8')), assets, visualMode);
  const usedSourceIds = new Set(plan.slides.flatMap(slide => slide.sourceIds));
  const deliverySources = assets.sources.filter(source => usedSourceIds.has(source.id));
  const outputFile = path.join(taskDir, 'output.html');
  const previewDir = path.join(taskDir, 'preview');
  await createHtmlPresentation({
    outputFile, plan, sources: deliverySources, sourceImages: assets.images,
    templateKey, themeFile: THEMES_FILE, fontFamily, motionMode
  });
  if ((await fs.stat(outputFile)).size > MAX_OUTPUT_BYTES) throw new Error('HTML 演示文件超过 36MB 上限');
  const rendered = await renderHtml(outputFile, previewDir);
  let qa = deterministicQa(plan, deliverySources, {
    valid: rendered.count === plan.slides.length && rendered.overflow.length === 0,
    slideCount: rendered.count,
    expectedSlideCount: plan.slides.length,
    overflowSlides: rendered.overflow,
    browserIssues: rendered.issues,
    visualReview: { valid: true, mode: 'codex-plan-plus-deterministic-browser-gates', issues: [] }
  });
  qa = { ...qa, checkedAt: Date.now(), researchDegraded: false, researchFailures: [] };
  const preview = {
    format: 'html', title: plan.title,
    slides: plan.slides.map((slide, index) => ({ index: index + 1, title: slide.title, imageFile: `slide-${index + 1}.png`, width: 1280, height: 720, sourceIds: slide.sourceIds })),
    sources: deliverySources, qa
  };
  await fs.writeFile(path.join(taskDir, 'agent-plan.json'), `${JSON.stringify(plan, null, 2)}\n`);
  await fs.writeFile(path.join(taskDir, 'sources.json'), `${JSON.stringify({ sources: deliverySources, imageAssets: assets.images.map(({ path: _path, ...item }) => item) }, null, 2)}\n`);
  await fs.writeFile(path.join(taskDir, 'quality-report.json'), `${JSON.stringify(qa, null, 2)}\n`);
  await fs.writeFile(path.join(taskDir, 'preview.json'), `${JSON.stringify(preview, null, 2)}\n`);
  return { slideCount: plan.slides.length, sourceCount: deliverySources.length, qa };
}

async function main(args) {
  if (args.length !== 7) throw new Error('用法：finalize-html-plan.mjs <taskDir> <planFile> <theme> <font> <motion> <visual> <resultFile>');
  const [taskDir, planFile, templateKey, fontFamily, motionMode, visualMode, resultFile] = args;
  const result = await finalizeHtmlPlan({ taskDir, planFile, templateKey, fontFamily, motionMode, visualMode });
  if (!inside(taskDir, resultFile)) throw new Error('HTML 结果路径不安全');
  await fs.writeFile(resultFile, `${JSON.stringify(result)}\n`, { flag: 'wx', mode: 0o600 });
}

if (process.argv[1] && path.resolve(process.argv[1]) === path.resolve(fileURLToPath(import.meta.url))) {
  main(process.argv.slice(2)).catch(error => {
    process.stderr.write(`${String(error?.message || error)}\n`);
    process.exitCode = 1;
  });
}

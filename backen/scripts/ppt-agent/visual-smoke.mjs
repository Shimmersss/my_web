import fs from 'node:fs/promises';
import path from 'node:path';
import crypto from 'node:crypto';
import JSZip from 'jszip';
import { inspectTemplate, composeTemplatePptx } from './template-pptx.mjs';
import { createHtmlPresentation } from './html-presentation.mjs';
import { renderHtml, renderPptx } from './render.mjs';
import { deterministicQa, visualReview } from './qa.mjs';
import { resetAgentLimitsForTest } from './model.mjs';

const root = path.resolve('..');
const outputRoot = path.resolve(process.env.PPT_AGENT_SMOKE_DIR || '../.run/ppt-agent-visual-smoke');
const templateCache = path.resolve(process.env.PPT_AGENT_TEMPLATE_CACHE || '../.run/ppt-generation-tasks/_template-cache');
const templateCatalog = JSON.parse(await fs.readFile('scripts/github_template_preview_catalog.json', 'utf8'));
const themes = JSON.parse(await fs.readFile(path.join(root, '.agents/skills/create-html-presentation/assets/themes.json'), 'utf8'));
const themeFile = path.join(root, '.agents/skills/create-html-presentation/assets/themes.json');
const pptxSkill = await fs.readFile(path.join(root, '.agents/skills/create-template-pptx/SKILL.md'), 'utf8');
const htmlSkill = await fs.readFile(path.join(root, '.agents/skills/create-html-presentation/SKILL.md'), 'utf8');

const slides = Array.from({ length: 5 }, (_, index) => ({
  type: index === 0 ? 'cover' : index === 4 ? 'closing' : 'content',
  layout: index === 0 ? 'cover' : index === 4 ? 'closing' : index % 2 ? 'split' : 'evidence',
  sourceSlide: index + 1,
  section: `SECTION ${index + 1}`,
  title: index === 0 ? '智能演示 Agent' : `模板质量检查 ${index + 1}`,
  headline: '研究、创作、渲染与审查形成可控闭环。',
  bullets: ['继承模板几何结构', '逐页真实渲染', '质量门失败即停止交付'],
  sourceIds: [],
  notes: ''
}));
const plan = { title: 'Agent Presentation Smoke', audience: 'engineering', takeaway: 'quality', slides };

await fs.rm(outputRoot, { recursive: true, force: true });
await fs.mkdir(outputRoot, { recursive: true });
const report = { pptx: [], html: [] };
const reportFile = path.join(outputRoot, 'report.json');
await fs.writeFile(reportFile, JSON.stringify({ ok: false, status: 'running', ...report }, null, 2));

async function screenshotSanity(directory, count) {
  const hashes = [];
  for (let slide = 1; slide <= count; slide += 1) {
    const image = await fs.readFile(path.join(directory, `slide-${slide}.png`));
    if (image.length < 10_000) throw new Error(`第 ${slide} 页截图过小，疑似空白或渲染失败`);
    hashes.push(crypto.createHash('sha256').update(image).digest('hex'));
  }
  if (new Set(hashes).size !== count) throw new Error('模板 smoke 出现重复页面截图');
}

async function packageSanity(file, expectedSlides) {
  const zip = await JSZip.loadAsync(await fs.readFile(file), { checkCRC32: true });
  const presentation = await zip.file('ppt/presentation.xml')?.async('string');
  if (!presentation) throw new Error('PPTX 缺少 presentation.xml');
  const count = [...presentation.matchAll(/<p:sldId\b/g)].length;
  if (count !== expectedSlides) throw new Error(`PPTX 包页数不一致: ${count}`);
  for (const [name, entry] of Object.entries(zip.files)) {
    if (!name.endsWith('.rels') || entry.dir) continue;
    const xml = await entry.async('string');
    if (/TargetMode\s*=\s*["']External/i.test(xml) || /Target\s*=\s*["'][a-z][a-z0-9+.-]*:/i.test(xml)) {
      throw new Error(`PPTX 包含外部 relationship: ${name}`);
    }
    const sourcePart = name === '_rels/.rels'
      ? ''
      : name.replace('/_rels/', '/').replace(/\.rels$/, '');
    const base = path.posix.dirname(sourcePart);
    for (const match of xml.matchAll(/<Relationship\b[^>]*\bTarget\s*=\s*["']([^"']+)["'][^>]*\/?\s*>/gi)) {
      const target = decodeURIComponent(match[1]).replace(/\\/g, '/');
      if (/^[a-z][a-z0-9+.-]*:/i.test(target)) throw new Error(`PPTX relationship 目标不安全: ${name}`);
      const resolved = target.startsWith('/')
        ? path.posix.normalize(target.slice(1))
        : path.posix.normalize(path.posix.join(base === '.' ? '' : base, target));
      if (resolved.startsWith('../') || !zip.file(resolved)) {
        throw new Error(`PPTX relationship 不可达: ${name} -> ${target}`);
      }
    }
  }
}

for (const item of templateCatalog) {
  const directory = path.join(outputRoot, item.key);
  await fs.mkdir(directory, { recursive: true });
  const templateFile = path.join(templateCache, `${item.key}.pptx`);
  const manifest = await inspectTemplate(templateFile);
  const mappedPlan = {
    ...plan,
    slides: slides.map((slide, index) => {
      const sourceSlide = Math.min(index + 1, manifest.slideCount);
      const slots = manifest.slides[sourceSlide - 1].textShapes.filter(shape => !shape.furniture);
      const content = [slide.title, slide.headline, ...slide.bullets];
      return {
        ...slide,
        sourceSlide,
        textEdits: slots.slice(0, content.length).map((slot, contentIndex) => ({
          slotId: slot.slotId,
          text: content[contentIndex].slice(0, slot.capacityChars)
        })),
        imageEdits: []
      };
    })
  };
  const outputFile = path.join(directory, 'smoke.pptx');
  const frameMap = await composeTemplatePptx({ templateFile, outputFile, plan: mappedPlan, manifest });
  const count = await renderPptx(outputFile, path.join(directory, 'preview'));
  if (count !== 5 || frameMap.length !== 5 || frameMap.some(item => !item.sourceSlide)) throw new Error(`${item.key} PPTX smoke 失败`);
  const deterministic = deterministicQa(mappedPlan, [], { valid: true, slideCount: count, expectedSlideCount: 5, overflowSlides: [] });
  if (!deterministic.valid) throw new Error(`${item.key} 确定性 QA 失败: ${JSON.stringify(deterministic)}`);
  await packageSanity(outputFile, 5);
  await screenshotSanity(path.join(directory, 'preview'), 5);
  resetAgentLimitsForTest();
  const visual = await visualReview(pptxSkill, mappedPlan, path.join(directory, 'preview'));
  if (!visual.valid) throw new Error(`${item.key} 视觉 QA 失败: ${JSON.stringify(visual)}`);
  report.pptx.push({ key: item.key, slides: count, frameMapValid: true, packageValid: true, deterministicQa: deterministic, visualSanityValid: true, visualReview: visual });
  await fs.writeFile(reportFile, JSON.stringify({ ok: false, status: 'running', ...report }, null, 2));
  process.stdout.write(`PPTX ${item.key}: ${count}\n`);
}

for (const key of Object.keys(themes)) {
  const directory = path.join(outputRoot, key);
  await fs.mkdir(directory, { recursive: true });
  const outputFile = path.join(directory, 'smoke.html');
  await createHtmlPresentation({ outputFile, plan, sources: [], templateKey: key, themeFile });
  const rendered = await renderHtml(outputFile, path.join(directory, 'preview'));
  if (rendered.count !== 5 || rendered.overflow.length) throw new Error(`${key} HTML smoke 失败: ${JSON.stringify(rendered)}`);
  const deterministic = deterministicQa(plan, [], { valid: true, slideCount: rendered.count, expectedSlideCount: 5, overflowSlides: rendered.overflow });
  if (!deterministic.valid) throw new Error(`${key} 确定性 QA 失败: ${JSON.stringify(deterministic)}`);
  await screenshotSanity(path.join(directory, 'preview'), 5);
  resetAgentLimitsForTest();
  const visual = await visualReview(htmlSkill, plan, path.join(directory, 'preview'));
  if (!visual.valid) throw new Error(`${key} 视觉 QA 失败: ${JSON.stringify(visual)}`);
  report.html.push({ key, slides: rendered.count, overflowSlides: rendered.overflow, deterministicQa: deterministic, visualSanityValid: true, visualReview: visual });
  await fs.writeFile(reportFile, JSON.stringify({ ok: false, status: 'running', ...report }, null, 2));
  process.stdout.write(`HTML ${key}: ${rendered.count}\n`);
}

await fs.writeFile(reportFile, JSON.stringify({ ok: true, status: 'completed', ...report }, null, 2));
process.stdout.write(`${JSON.stringify({ ok: true, pptx: report.pptx.length, html: report.html.length })}\n`);

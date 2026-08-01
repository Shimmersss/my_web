import fs from 'node:fs/promises';
import path from 'node:path';
import crypto from 'node:crypto';
import { completeJson, completeVisionJson, recordToolCall } from './model.mjs';
import { researchPresentation } from './research.mjs';
import { inspectTemplate, composeTemplatePptx } from './template-pptx.mjs';
import { createHtmlPresentation } from './html-presentation.mjs';
import { renderPptx, renderHtml } from './render.mjs';
import { assertInsideStorage } from './paths.mjs';
import { deterministicQa, visualReview } from './qa.mjs';

const MAX_SLIDES = 30;

function emit(event, data = {}) {
  process.stdout.write(`${JSON.stringify({ event, ...data, timestamp: Date.now() })}\n`);
}

async function readText(file, fallback = '') {
  try { return await fs.readFile(file, 'utf8'); } catch { return fallback; }
}

function validatePlan(plan, format, manifest, sourceImages = []) {
  if (!plan || !Array.isArray(plan.slides) || !plan.slides.length) throw new Error('Agent 计划没有幻灯片');
  if (plan.slides.length > MAX_SLIDES) throw new Error(`Agent 计划超过 ${MAX_SLIDES} 页`);
  plan.slides.forEach((slide, index) => {
    const plain = value => String(value || '').replace(/[*_`#]+/g, '').replace(/\s+/g, ' ').trim();
    slide.title = plain(slide.title || `第 ${index + 1} 页`).slice(0, 80);
    slide.headline = plain(slide.headline).slice(0, 180);
    slide.bullets = Array.isArray(slide.bullets) ? slide.bullets.map(plain).map(value => value.slice(0, 120)).slice(0, 6) : [];
    slide.sourceIds = Array.isArray(slide.sourceIds) ? slide.sourceIds.map(String).slice(0, 8) : [];
    if (format === 'pptx') {
      const source = Number(slide.sourceSlide || index + 1);
      slide.sourceSlide = Math.max(1, Math.min(manifest.slideCount, Number.isFinite(source) ? source : 1));
      const sourceInfo = manifest.slides[slide.sourceSlide - 1];
      const textSlots = new Map((sourceInfo.textShapes || [])
        .filter(item => !item.furniture)
        .map(item => [String(item.slotId), item]));
      if (!Array.isArray(slide.textEdits) || !slide.textEdits.length) {
        throw new Error(`第 ${index + 1} 页缺少 textEdits 槽位映射`);
      }
      const usedTextSlots = new Set();
      slide.textEdits = slide.textEdits.map(edit => {
        const slotId = String(edit?.slotId || '');
        const slot = textSlots.get(slotId);
        const text = plain(edit?.text);
        if (!slot || usedTextSlots.has(slotId)) throw new Error(`第 ${index + 1} 页使用无效或重复文字槽 ${slotId}`);
        if (!text) throw new Error(`第 ${index + 1} 页文字槽 ${slotId} 为空`);
        if (text.length > Number(slot.capacityChars || 0)) {
          throw new Error(`第 ${index + 1} 页文字超过槽 ${slotId} 容量，请换源页或拆页`);
        }
        usedTextSlots.add(slotId);
        return { slotId, text };
      });
      const visibleText = slide.textEdits.map(edit => edit.text).join('\n');
      for (const requiredText of [slide.title, slide.headline, ...(slide.bullets || [])].filter(Boolean)) {
        if (!visibleText.includes(requiredText)) {
          throw new Error(`第 ${index + 1} 页叙事内容没有映射到 textEdits: ${requiredText.slice(0, 30)}`);
        }
      }
      const imageIds = new Set(sourceImages.map(item => String(item.id)));
      const imageSlots = new Set((sourceInfo.imageSlots || [])
        .filter(item => item.fillable === true)
        .map(item => String(item.slotId)));
      slide.imageEdits = Array.isArray(slide.imageEdits) ? slide.imageEdits.map(edit => ({
        slotId: String(edit?.slotId || ''),
        imageId: String(edit?.imageId || '')
      })).filter(edit => {
        if (!imageSlots.has(edit.slotId) || !imageIds.has(edit.imageId)) {
          throw new Error(`第 ${index + 1} 页图片槽或资料图片无效`);
        }
        return true;
      }) : [];
    } else {
      const imageIds = new Set(sourceImages.map(item => String(item.id)));
      slide.imageId = imageIds.has(String(slide.imageId || '')) ? String(slide.imageId) : '';
    }
  });
  return plan;
}

function compactManifest(manifest, plan, maxSlides = 12) {
  if (!manifest) return null;
  const selected = new Set((plan?.slides || []).map(slide => Number(slide.sourceSlide))
    .filter(number => Number.isInteger(number) && number >= 1 && number <= manifest.slideCount));
  const wantedRoles = new Set((plan?.slides || []).map(slide => String(slide.type || 'content')));
  for (const slide of manifest.slides) {
    if (selected.size >= maxSlides) break;
    if (wantedRoles.has(String(slide.visual?.role || ''))) selected.add(slide.slide);
  }
  for (const slide of manifest.slides) {
    if (selected.size >= maxSlides) break;
    selected.add(slide.slide);
  }
  return {
    slideCount: manifest.slideCount,
    slides: manifest.slides.filter(slide => selected.has(slide.slide)).map(slide => ({
      slide: slide.slide,
      visual: slide.visual,
      sampleText: slide.sampleText,
      textShapes: (slide.textShapes || []).map(shape => ({
        slotId: shape.slotId,
        roleHint: shape.roleHint,
        capacityChars: shape.capacityChars,
        furniture: shape.furniture,
        maxFontPt: shape.maxFontPt,
        x: shape.x, y: shape.y, width: shape.width, height: shape.height
      })),
      imageSlots: (slide.imageSlots || []).map(slot => ({
        slotId: slot.slotId,
        roleHint: slot.roleHint,
        fillable: slot.fillable,
        fillableReason: slot.fillableReason,
        x: slot.x, y: slot.y, width: slot.width, height: slot.height
      }))
    }))
  };
}

async function validatePlanWithRepair(candidate, format, manifest, sourceImages, system) {
  let current = candidate;
  let lastError = null;
  for (let attempt = 0; attempt < 3; attempt += 1) {
    try {
      return validatePlan(structuredClone(current), format, manifest, sourceImages);
    } catch (error) {
      lastError = error;
      if (attempt === 2) break;
      const repaired = await completeJson({
        system,
        user: `Repair this complete presentation plan so it satisfies the authoring schema.
Validation error: ${error.message}
Output format: ${format}
Plan: ${JSON.stringify(current)}
Relevant template manifest: ${manifest ? JSON.stringify(compactManifest(manifest, current)) : 'HTML theme'}
Available uploaded images: ${JSON.stringify(sourceImages.map(item => ({ id: item.id, fileName: item.fileName })))}
For PPTX, when sourceSlide changes you MUST rebuild textEdits and imageEdits using only slotIds from that source page. Every title, headline, and bullet must appear verbatim in textEdits, each edit must fit capacityChars, and imageEdits may target only slots where fillable=true. Do not shrink text or invent slot IDs.
Return {"action":"final","args":{"presentation":<the complete corrected presentation>}}.`,
        maxTokens: 12000,
        repairContext: 'Return final with args.presentation containing a complete valid plan.'
      });
      if (repaired.action !== 'final' || !repaired.args?.presentation) {
        throw new Error('计划校正没有返回完整 presentation');
      }
      current = repaired.args.presentation;
    }
  }
  throw lastError;
}

function sourceSummary(sources) {
  return sources.map(item => ({
    id: item.id, title: item.title, authors: item.authors, year: item.year,
    doi: item.doi, url: item.url, type: item.type, abstract: item.abstract
  }));
}

function normalizeSourceIds(plan, sources) {
  const allowed = new Set(sources.map(item => item.id));
  for (const slide of plan.slides) {
    slide.sourceIds = (slide.sourceIds || []).filter(id => allowed.has(id));
  }
  return plan;
}

function mergeSources(previous, current, maxSources) {
  const seen = new Set();
  const output = [];
  for (const item of [...previous, ...current]) {
    const key = item.doi
      ? `doi:${String(item.doi).toLowerCase()}`
      : `title:${String(item.title || '').toLowerCase().replace(/[^\p{L}\p{N}]+/gu, '')}`;
    if (!item.title || !item.url || seen.has(key)) continue;
    seen.add(key);
    output.push({ ...item, id: `S${String(output.length + 1).padStart(2, '0')}` });
    if (output.length >= maxSources) break;
  }
  return output;
}

function applyRevisionReuse(candidate, previous, changedSlideNumbers, format) {
  if (!previous?.slides?.length || previous.slides.length !== candidate.slides.length) {
    return { plan: candidate, unchanged: [] };
  }
  const reusable = format !== 'pptx'
    || previous.slides.every(slide => Array.isArray(slide.textEdits) && slide.textEdits.length);
  if (!reusable) return { plan: candidate, unchanged: [] };
  const changed = new Set((changedSlideNumbers || []).map(Number)
    .filter(value => Number.isInteger(value) && value >= 1 && value <= candidate.slides.length));
  if (!changed.size) throw new Error('修订 Agent 没有声明 changedSlideNumbers');
  const slides = candidate.slides.map((slide, index) => changed.has(index + 1) ? slide : previous.slides[index]);
  return {
    plan: { ...candidate, slides },
    unchanged: slides.map((_, index) => index + 1).filter(number => !changed.has(number))
  };
}

async function sameFile(left, right) {
  try {
    const [a, b] = await Promise.all([fs.readFile(left), fs.readFile(right)]);
    return crypto.createHash('sha256').update(a).digest('hex')
      === crypto.createHash('sha256').update(b).digest('hex');
  } catch {
    return false;
  }
}

function previewPayload(job, plan, sources, qa) {
  return {
    format: job.outputFormat,
    title: plan.title || '',
    slides: plan.slides.map((slide, index) => ({
      index: index + 1,
      title: slide.title,
      imageFile: `slide-${index + 1}.png`,
      width: 1280,
      height: 720,
      sourceIds: slide.sourceIds || []
    })),
    sources,
    qa
  };
}

function repairPageNumbers(qa, slideCount) {
  const pages = new Set();
  for (const issue of qa?.visualReview?.issues || []) {
    const slide = Number(issue?.slide);
    if (Number.isInteger(slide) && slide >= 1 && slide <= slideCount) pages.add(slide);
  }
  for (const value of [...(qa?.overflowSlides || []), ...(qa?.revisionDriftSlides || [])]) {
    const slide = Number(value);
    if (Number.isInteger(slide) && slide >= 1 && slide <= slideCount) pages.add(slide);
  }
  if (!pages.size) for (let slide = 1; slide <= slideCount; slide += 1) pages.add(slide);
  return [...pages].sort((left, right) => left - right);
}

function mergeRepairBatch(basePlan, candidatePlan, allowedPages) {
  const allowed = new Set(allowedPages);
  if (!candidatePlan || !Array.isArray(candidatePlan.slides) || candidatePlan.slides.length !== basePlan.slides.length) {
    throw new Error('Agent 批次返修没有返回完整且等长的 presentation');
  }
  return {
    ...basePlan,
    slides: basePlan.slides.map((slide, index) => allowed.has(index + 1) ? candidatePlan.slides[index] : slide)
  };
}

async function classifyTemplateSlides(system, manifest, previewDir) {
  const classifications = [];
  for (let start = 0; start < manifest.slideCount; start += 4) {
    const files = Array.from({ length: Math.min(4, manifest.slideCount - start) }, (_, offset) =>
      path.join(previewDir, `slide-${start + offset + 1}.png`));
    const result = await completeVisionJson({
      system,
      user: `Inspect source-template pages ${start + 1}-${start + files.length}. Every supplied screenshot must receive one entry.
Identify each page role and realistic content capacity without redesigning it.
Return {"action":"inspect","args":{"slides":[{"slide":1,"role":"cover|agenda|section|content|evidence|comparison|closing","capacity":"short practical description","warnings":[]}]}}.
Slide numbers must be absolute.`,
      imageFiles: files,
      maxTokens: 3500,
      repairContext: 'The action must be inspect and args.slides must describe every supplied screenshot.'
    });
    if (result.action !== 'inspect' || !Array.isArray(result.args?.slides)) throw new Error('模板视觉检查结果无效');
    classifications.push(...result.args.slides);
  }
  const bySlide = new Map(classifications.map(item => [Number(item.slide), item]));
  manifest.slides = manifest.slides.map(item => {
    const visual = bySlide.get(item.slide) || null;
    const roleAllowsContentImages = ['content', 'evidence', 'comparison'].includes(visual?.role);
    return {
      ...item,
      visual,
      imageSlots: (item.imageSlots || []).map(slot => ({
        ...slot,
        fillable: slot.fillable === true && roleAllowsContentImages,
        fillableReason: slot.fillable === true && !roleAllowsContentImages
          ? `page-role-${visual?.role || 'unknown'}-is-not-image-fillable`
          : slot.fillableReason
      }))
    };
  });
  return manifest;
}

async function main() {
  const jobFile = process.argv[2];
  if (!jobFile) throw new Error('缺少 job.json');
  const job = JSON.parse(await fs.readFile(jobFile, 'utf8'));
  const taskDir = path.dirname(jobFile);
  const root = path.resolve(job.projectRoot);
  const storageRoot = path.resolve(job.storageRoot);
  assertInsideStorage(storageRoot, taskDir);
  assertInsideStorage(storageRoot, job.templateFile);
  assertInsideStorage(storageRoot, job.sourceFile);
  assertInsideStorage(storageRoot, job.sourceTextFile);
  assertInsideStorage(storageRoot, job.previousPlanFile);
  assertInsideStorage(storageRoot, job.previousSourcesFile);
  assertInsideStorage(storageRoot, job.previousOutputFile);
  const previousPreviewFiles = Array.isArray(job.previousPreviewFiles) ? job.previousPreviewFiles : [];
  for (const file of previousPreviewFiles) assertInsideStorage(storageRoot, file);
  const sourceImages = Array.isArray(job.sourceImages) ? job.sourceImages.slice(0, 8) : [];
  for (const image of sourceImages) assertInsideStorage(storageRoot, image.path);

  const researchSkill = await readText(path.join(root, '.agents/skills/research-presentation/SKILL.md'));
  const formatSkill = await readText(path.join(root, `.agents/skills/${job.outputFormat === 'html' ? 'create-html-presentation' : 'create-template-pptx'}/SKILL.md`));
  const skillSystem = `${researchSkill}\n\n${formatSkill}\n\nYou are the presentation Agent. Return the exact JSON action requested. Do not expose internal reasoning.`;
  const sourceText = job.sourceTextFile ? (await readText(job.sourceTextFile)).slice(0, 40_000) : '';

  emit('researching', { progress: 10, message: '正在理解主题并自主调用检索工具' });
  const research = { sources: [], assets: [], degraded: false, failures: [] };
  if (job.researchMode !== 'off') {
    let remainingSearches = Math.max(1, Number(job.maxSearches || 6));
    const maxRounds = Math.min(3, remainingSearches);
    for (let round = 0; round < maxRounds && remainingSearches > 0; round += 1) {
      const decision = await completeJson({
        system: skillSystem,
        user: `Decide the next research action for this presentation.
Prompt: ${job.prompt}
Uploaded source material excerpt: ${sourceText.slice(0, 8000)}
Current verified sources: ${JSON.stringify(sourceSummary(research.sources))}
Round: ${round + 1}/${maxRounds}
Return exactly one of:
{"action":"tool_call","args":{"tool":"search","queries":["1-3 concise bilingual queries"]}}
{"action":"final","args":{"researchComplete":true}}
You must call search in the first round. After seeing results, use another targeted search only if evidence is missing; otherwise return final.`,
        maxTokens: 1600,
        repairContext: 'The action must be tool_call(search) or final.'
      });
      if (decision.action === 'final' && research.sources.length) break;
      if (decision.action !== 'tool_call' || decision.args?.tool !== 'search'
          || !Array.isArray(decision.args?.queries) || !decision.args.queries.length) {
        throw new Error('Agent 返回了不允许的研究工具调用');
      }
      recordToolCall('search');
      const roundBudget = round === 0
        ? Math.min(5, Math.max(1, remainingSearches - 1))
        : remainingSearches;
      const roundResearch = await researchPresentation(decision.args.queries, {
        includeWeb: true,
        maxSources: Number(job.maxSources || 12),
        maxSearches: roundBudget
      });
      remainingSearches -= Number(roundResearch.searchCount || 0);
      research.sources = mergeSources(research.sources, roundResearch.sources, Number(job.maxSources || 12));
      research.assets = [...research.assets, ...(roundResearch.assets || [])]
        .filter((item, index, all) => all.findIndex(other => other.url === item.url) === index)
        .slice(0, 8);
      research.degraded ||= roundResearch.degraded;
      research.failures.push(...roundResearch.failures);
    }
  }
  if (job.previousSourcesFile) {
    const previousResearch = JSON.parse(await readText(job.previousSourcesFile, '{"sources":[]}'));
    research.sources = mergeSources(previousResearch.sources || [], research.sources, Number(job.maxSources || 12));
  }
  await fs.writeFile(path.join(taskDir, 'sources.json'), JSON.stringify(research, null, 2));

  let manifest = null;
  if (job.outputFormat === 'pptx') {
    manifest = await inspectTemplate(job.templateFile);
    const templatePreviewDir = path.join(taskDir, 'template-preview');
    emit('planning', { progress: 25, message: `正在渲染并检查模板全部 ${manifest.slideCount} 页` });
    const renderedTemplateSlides = await renderPptx(job.templateFile, templatePreviewDir);
    if (renderedTemplateSlides !== manifest.slideCount) throw new Error('模板全量渲染页数不一致');
    manifest = await classifyTemplateSlides(skillSystem, manifest, templatePreviewDir);
    await fs.writeFile(path.join(taskDir, 'template-manifest.json'), JSON.stringify(manifest, null, 2));
  }

  emit('planning', { progress: 34, message: `正在基于 ${research.sources.length} 个来源设计叙事`, sourceCount: research.sources.length });
  const previousPlan = job.previousPlanFile ? await readText(job.previousPlanFile) : '';
  const previousPlanObject = previousPlan ? JSON.parse(previousPlan) : null;
  const planningImages = [...previousPreviewFiles.slice(0, 4), ...sourceImages.map(item => item.path)].slice(0, 8);
  const action = await (planningImages.length ? completeVisionJson : completeJson)({
    system: skillSystem,
    user: `Create the complete presentation plan.
Prompt: ${job.prompt}
Uploaded source material: ${sourceText}
Output format: ${job.outputFormat}
Selected template/theme: ${job.templateKey}
Research degraded: ${research.degraded}
Sources: ${JSON.stringify(sourceSummary(research.sources))}
Uploaded image assets: ${JSON.stringify(sourceImages.map(item => ({ id: item.id, fileName: item.fileName })))}
Template manifest: ${manifest ? JSON.stringify(manifest) : 'HTML theme; sourceSlide is not used'}
Previous plan for revision: ${previousPlan}
Return {"action":"final","args":{"changedSlideNumbers":[2],"presentation":{"title":"...","audience":"...","takeaway":"...","slides":[{"type":"cover|content|evidence|references|closing","layout":"cover|split|statement|evidence|comparison|timeline|quote|closing","sourceSlide":1,"section":"...","title":"...","headline":"...","bullets":["..."],"textEdits":[{"slotId":"s1-t1","text":"exact visible text"}],"imageEdits":[{"slotId":"s1-i1","imageId":"I01"}],"imageId":"I01","sourceIds":["S01"],"notes":"..."}]}}}.
For PPTX, choose sourceSlide from the manifest and map every visible title, headline, and bullet verbatim to exact textEdits slots within capacityChars; never rely on those high-level fields being assigned automatically. Populate every meaningful label required by inherited diagrams, numbered lists, matrices, and timelines, or choose a simpler source page—do not leave blank-looking structures. Use imageEdits only for template image slots explicitly marked fillable=true. When this is a revision, changedSlideNumbers must contain every page you changed; otherwise it may be omitted. For HTML, imageId may select an uploaded local image. For academic topics include enough references pages to map every retained source ID (up to 8 IDs per page). Every sourced claim needs sourceIds.`,
    ...(planningImages.length ? { imageFiles: planningImages } : {}),
    maxTokens: 14000,
    repairContext: 'The action must be final and args.presentation.slides must be a non-empty array.'
  });
  if (action.action !== 'final') throw new Error('Agent 未返回最终创作计划');
  let candidatePlan = action.args?.presentation;
  let unchangedSlideNumbers = [];
  if (previousPlanObject) {
    const reuse = applyRevisionReuse(candidatePlan, previousPlanObject, action.args?.changedSlideNumbers, job.outputFormat);
    candidatePlan = reuse.plan;
    unchangedSlideNumbers = reuse.unchanged;
  }
  let validatedCandidate = await validatePlanWithRepair(
    candidatePlan, job.outputFormat, manifest, sourceImages, skillSystem);
  if (previousPlanObject) {
    const reuse = applyRevisionReuse(
      validatedCandidate, previousPlanObject, action.args?.changedSlideNumbers, job.outputFormat);
    validatedCandidate = validatePlan(reuse.plan, job.outputFormat, manifest, sourceImages);
    unchangedSlideNumbers = reuse.unchanged;
  }
  let plan = normalizeSourceIds(validatedCandidate, research.sources);
  await fs.writeFile(path.join(taskDir, 'agent-plan.json'), JSON.stringify(plan, null, 2));

  const outputFile = path.join(taskDir, job.outputFormat === 'html' ? 'output.html' : 'output.pptx');
  const previewDir = path.join(taskDir, 'preview');
  let frameMap = [];
  let qa = {};
  for (let iteration = 0; iteration < 3; iteration += 1) {
    emit(iteration === 0 ? 'authoring' : 'revising', {
      progress: 52 + iteration * 12,
      iteration,
      message: iteration === 0 ? '正在生成真实演示文件' : `正在进行第 ${iteration} 轮质量返修`
    });
    if (job.outputFormat === 'pptx') {
      recordToolCall('compose-pptx');
      frameMap = await composeTemplatePptx({ templateFile: job.templateFile, outputFile, plan, manifest, sources: research.sources, sourceImages });
      await fs.writeFile(path.join(taskDir, 'template-frame-map.json'), JSON.stringify({ outputSlides: frameMap }, null, 2));
    } else {
      recordToolCall('compose-html');
      await createHtmlPresentation({
        outputFile,
        plan,
        sources: research.sources,
        sourceImages,
        templateKey: job.templateKey,
        themeFile: path.join(root, '.agents/skills/create-html-presentation/assets/themes.json')
      });
    }
    emit('rendering', { progress: 72 + iteration * 8, iteration, message: '正在从最终文件渲染逐页预览' });
    if (job.outputFormat === 'pptx') {
      recordToolCall('render-pptx');
      const count = await renderPptx(outputFile, previewDir);
      qa = { valid: count === plan.slides.length, slideCount: count, expectedSlideCount: plan.slides.length, overflowSlides: [] };
    } else {
      recordToolCall('render-html');
      const rendered = await renderHtml(outputFile, previewDir);
      qa = { valid: rendered.count === plan.slides.length && rendered.overflow.length === 0, slideCount: rendered.count, expectedSlideCount: plan.slides.length, overflowSlides: rendered.overflow };
    }
    qa = deterministicQa(plan, research.sources, qa);
    if (unchangedSlideNumbers.length && previousPreviewFiles.length) {
      const drift = [];
      for (const slideNumber of unchangedSlideNumbers) {
        const previousFile = previousPreviewFiles.find(file => path.basename(file) === `slide-${slideNumber}.png`);
        if (!previousFile || !(await sameFile(previousFile, path.join(previewDir, `slide-${slideNumber}.png`)))) {
          drift.push(slideNumber);
        }
      }
      qa.revisionDriftSlides = drift;
      qa.valid = qa.valid && drift.length === 0;
    }
    const visual = await visualReview(skillSystem, plan, previewDir);
    qa.visualReview = visual;
    qa.valid = qa.valid && visual.valid;
    emit('reviewing', { progress: 88 + iteration * 4, iteration, message: '正在检查版式、溢出、模板和引用', qa });
    if (qa.valid) break;
    if (iteration === 2) throw new Error(`质量检查未通过: ${JSON.stringify(qa)}`);
    const failedPages = repairPageNumbers(qa, plan.slides.length);
    let repairedCandidate = plan;
    let reportedChangedPages = [];
    for (let start = 0; start < failedPages.length; start += 4) {
      const batchPages = failedPages.slice(start, start + 4);
      const repairImages = batchPages.map(slide => path.join(previewDir, `slide-${slide}.png`));
      const repair = await completeVisionJson({
        system: skillSystem,
        user: `Repair only slides ${batchPages.join(', ')} after QA failure. Keep correct content and sources.
Plan: ${JSON.stringify(plan)}
QA: ${JSON.stringify(qa)}
Relevant template manifest: ${manifest ? JSON.stringify(compactManifest(manifest, plan)) : 'HTML theme'}
For PPTX, the authoring tool changes visible content through sourceSlide plus exact textEdits/imageEdits mappings. If sourceSlide changes, rebuild every slot mapping from that source page. Every title, headline, and bullet must appear verbatim in textEdits and fit capacityChars; imageEdits may target only fillable=true slots. Unmapped inherited text shapes are removed automatically. Choose a different sourceSlide or split a page when capacity or geometry is unsuitable. Speaker notes are not editing commands: never put [REPAIR] instructions or visual edit requests in notes.
Return {"action":"final","args":{"changedSlideNumbers":[<only pages allowed to change>],"presentation":<complete repaired presentation>}}. Preserve all other pages exactly.`,
        imageFiles: repairImages,
        maxTokens: 8000,
        repairContext: `Return the complete repaired presentation; changedSlideNumbers must be a subset of ${batchPages.join(', ')}.`
      });
      if (repair.action !== 'final') throw new Error('Agent 返修没有返回 final');
      const claimed = (repair.args?.changedSlideNumbers || []).map(Number);
      if (claimed.some(page => !batchPages.includes(page))) throw new Error('Agent 批次返修改动了未授权页面');
      repairedCandidate = mergeRepairBatch(repairedCandidate, repair.args?.presentation, batchPages);
      reportedChangedPages.push(...claimed);
    }
    if (previousPlanObject) {
      const reuse = applyRevisionReuse(repairedCandidate, previousPlanObject,
        reportedChangedPages.length ? reportedChangedPages : action.args?.changedSlideNumbers, job.outputFormat);
      repairedCandidate = reuse.plan;
      unchangedSlideNumbers = reuse.unchanged;
    }
    let validatedRepair = await validatePlanWithRepair(
      repairedCandidate, job.outputFormat, manifest, sourceImages, skillSystem);
    if (previousPlanObject) {
      const reuse = applyRevisionReuse(validatedRepair, previousPlanObject,
        reportedChangedPages.length ? reportedChangedPages : action.args?.changedSlideNumbers, job.outputFormat);
      validatedRepair = validatePlan(reuse.plan, job.outputFormat, manifest, sourceImages);
      unchangedSlideNumbers = reuse.unchanged;
    }
    plan = normalizeSourceIds(validatedRepair, research.sources);
    await fs.writeFile(path.join(taskDir, 'agent-plan.json'), JSON.stringify(plan, null, 2));
  }

  qa.researchDegraded = research.degraded;
  qa.researchFailures = research.failures;
  qa.checkedAt = Date.now();
  await fs.writeFile(path.join(taskDir, 'quality-report.json'), JSON.stringify(qa, null, 2));
  await fs.writeFile(path.join(taskDir, 'preview.json'), JSON.stringify(previewPayload(job, plan, research.sources, qa), null, 2));
  emit('done', { progress: 100, message: 'Agent 已完成生成与质量检查', sourceCount: research.sources.length, qa });
}

main().catch(error => {
  emit('task-error', { message: error?.message || String(error) });
  process.exitCode = 1;
});

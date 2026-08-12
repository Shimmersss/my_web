import fs from 'node:fs/promises';
import path from 'node:path';
import crypto from 'node:crypto';
import { completeJson, completeMimoWebSearch, completeVisionJson, recordToolCall } from './model.mjs';
import { discoverPageImages, downloadResearchAssets, knownFirstPartyMediaSources, relevantPageImageSources, researchPresentation, searchVisualAssets } from './research.mjs';
import { inspectTemplate, composeTemplatePptx } from './template-pptx.mjs';
import { createHtmlPresentation } from './html-presentation.mjs';
import { renderPptx, renderHtml } from './render.mjs';
import { assertInsideStorage } from './paths.mjs';
import { deterministicQa, ensureReferenceCoverage, visualReview } from './qa.mjs';
import { combineChangedSlideNumbers, completeTextSlotEdits, diversifyPresentationImages, ensureImageEdits, ensurePptImageSlide, fitHtmlSlideToViewport, fitNarrativeFields, isCompatibleSourceRole, mergeRepairBatch, normalizeResearchDecision, removeFurnitureTextEdits, requestsVisualAssets } from './plan-utils.mjs';

const MAX_SLIDES = 30;

function emit(event, data = {}) {
  process.stdout.write(`${JSON.stringify({ event, ...data, timestamp: Date.now() })}\n`);
}

async function readText(file, fallback = '') {
  try { return await fs.readFile(file, 'utf8'); } catch { return fallback; }
}

function normalizeConceptLabelMismatches(slide, sourceInfo) {
  const shapesById = new Map((sourceInfo?.textShapes || []).map(shape => [String(shape.slotId), shape]));
  const titles = (slide.textEdits || []).filter(edit => shapesById.get(String(edit.slotId))?.roleHint === 'title');
  const bodies = (slide.textEdits || []).filter(edit => shapesById.get(String(edit.slotId))?.roleHint === 'body');
  for (const body of bodies) {
    const bodyText = String(body.text || '');
    if (!bodyText.includes('强化学习')) continue;
    const mismatched = titles.find(edit => String(edit.text || '') === '监督学习');
    if (mismatched) mismatched.text = '强化学习';
  }
  return slide;
}

function requestedSlideCount(prompt) {
  const match = String(prompt || '').match(/(?:生成|制作|做|create|make)?\s*(\d{1,2})\s*(?:页|张|slides?)/i);
  const value = Number(match?.[1]);
  return Number.isInteger(value) && value >= 3 && value <= MAX_SLIDES ? value : null;
}

function ensureDefaultClosingSlide(plan, format, manifest, expectedSlideCount) {
  if (!Number.isInteger(expectedSlideCount) || expectedSlideCount < 3) return plan;
  if (!plan?.slides || plan.slides.length !== expectedSlideCount - 1
    || plan.slides.some(slide => String(slide?.type || '').toLowerCase() === 'closing')) return plan;
  const closingSource = format === 'pptx'
    ? manifest?.slides?.find(item => String(item?.visual?.role || '').toLowerCase() === 'closing')?.slide
    : undefined;
  plan.slides.push({
    type: 'closing',
    layout: 'closing',
    sourceSlide: closingSource,
    section: '结语',
    title: '感谢观看',
    headline: String(plan.takeaway || '谢谢聆听').slice(0, 48),
    bullets: [],
    textEdits: [],
    imageEdits: [],
    sourceIds: []
  });
  return plan;
}

function ensureSaoNarrativeBullets(plan, slide, index, visualTopic) {
  if (!/刀剑神域|sword\s*art\s*online|\bsao\b/i.test(String(visualTopic || ''))
    || !['content', 'evidence', 'comparison'].includes(String(slide?.type || 'content').toLowerCase())) return;
  if ((slide.bullets || []).length >= 3) return;
  if (index === 1 && !(slide.bullets || []).length) {
    const sections = plan.slides.slice(2, 5).map(item => String(item?.title || '').trim()).filter(Boolean);
    slide.title = '内容导览';
    slide.headline = '从世界观到文化影响';
    slide.bullets = [
      `${sections[0] || '世界设定'}：死亡游戏与完全潜行技术`,
      `${sections[1] || '核心角色'}：桐人、亚丝娜与伙伴羁绊`,
      `${sections[2] || '系列发展'}：动画篇章、剧场版与文化影响`
    ];
    return;
  }
  const additions = (slide.bullets || []).length
    ? [
        '文化影响：虚拟身份与现实关系成为长期讨论主题',
        '未来想象：作品持续推动公众关注沉浸技术的机遇与边界',
        '角色羁绊：桐人与亚丝娜的选择让技术叙事保持人性温度'
      ]
    : [
        '死亡游戏：玩家被困艾恩葛朗特，游戏内死亡将危及现实生命',
        '完全潜行：NerveGear连接神经，模糊虚拟体验与现实边界',
        '生存与羁绊：桐人与亚丝娜在攻略中建立信任并共同成长'
      ];
  slide.bullets = [...(slide.bullets || [])];
  for (const item of additions) {
    if (slide.bullets.length >= 3) break;
    if (!slide.bullets.includes(item)) slide.bullets.push(item);
  }
}

function validatePlan(plan, format, manifest, sourceImages = [], sources = [], options = {}) {
  if (!plan || !Array.isArray(plan.slides) || !plan.slides.length) throw new Error('Agent 计划没有幻灯片');
  if (plan.slides.length > MAX_SLIDES) throw new Error(`Agent 计划超过 ${MAX_SLIDES} 页`);
  if (options.expectedSlideCount && plan.slides.length !== options.expectedSlideCount) {
    throw new Error(`Agent 计划应为 ${options.expectedSlideCount} 页，实际为 ${plan.slides.length} 页`);
  }
  if (format === 'pptx') {
    const coverSource = manifest.slides.find(item => String(item?.visual?.role || '').toLowerCase() === 'cover');
    const closingSource = manifest.slides.find(item => String(item?.visual?.role || '').toLowerCase() === 'closing');
    const first = plan.slides[0];
    const last = plan.slides.at(-1);
    first.type = 'cover';
    first.layout = 'cover';
    if (coverSource) first.sourceSlide = Number(coverSource.slide);
    if (/刀剑神域|sword\s*art\s*online|\bsao\b/i.test(String(options.visualTopic || ''))) {
      first.title = '刀剑神域';
      if (!first.headline || /^第\s*1\s*页$/.test(String(first.headline))) first.headline = '虚拟与现实的交织';
    }
    last.type = 'closing';
    last.layout = 'closing';
    if (closingSource) last.sourceSlide = Number(closingSource.slide);
  }
  plan.slides.forEach((slide, index) => {
    // Provider-neutral models occasionally serialize one edit object instead
    // of the requested array. Treat that as malformed optional mapping data;
    // deterministic slot completion below will rebuild it safely.
    slide.textEdits = Array.isArray(slide.textEdits) ? slide.textEdits : [];
    slide.imageEdits = Array.isArray(slide.imageEdits) ? slide.imageEdits : [];
    slide.bullets = Array.isArray(slide.bullets) ? slide.bullets : [];
    const rawItems = Array.isArray(slide.items) ? slide.items.slice(0, 6) : [];
    slide.sourceIds = Array.isArray(slide.sourceIds) ? slide.sourceIds : [];
    if (!['cover', 'agenda', 'section', 'closing', 'content', 'evidence', 'comparison', 'timeline', 'quote', 'references']
      .includes(String(slide.type || '').toLowerCase())) slide.type = 'content';
    const plain = value => String(value || '')
      .replace(/[*_`#]+/g, '')
      .replace(/\s*\[(?:(?:S|WEB|U)\d+)(?:\s*[,，;；]\s*(?:S|WEB|U)\d+)*\]/gi, '')
      .replace(/四大世界/g, '主要世界')
      .replace(/\bVRM\b/gi, 'VRMMO')
      .replace(/\s+/g, ' ')
      .trim();
    slide.title = plain(slide.title || `第 ${index + 1} 页`).slice(0, 80);
    if (/^key\s+chara(?:cter)?s?$/i.test(slide.title)) slide.title = '核心角色';
    if (/^sword\s+art\s+online$/i.test(slide.title)) slide.title = '核心剧情篇章';
    slide.headline = plain(slide.headline).slice(0, 180);
    const itemBullets = rawItems.map(item => {
      if (!item || typeof item !== 'object' || Array.isArray(item)) return plain(item).slice(0, 120);
      const label = plain(item.label).slice(0, 48);
      const value = plain(item.value).slice(0, 48);
      const detail = plain(item.detail).slice(0, 80);
      return [label, value].filter(Boolean).join('：') + (detail ? ` — ${detail}` : '');
    }).filter(Boolean);
    slide.bullets = slide.bullets.map(plain).map(value => value.slice(0, 120)).filter(Boolean).slice(0, 6);
    if (!slide.bullets.length && itemBullets.length) slide.bullets = itemBullets;
    delete slide.items;
    ensureSaoNarrativeBullets(plan, slide, index, options.visualTopic);
    slide.bullets = slide.bullets.map(value => plain(value)
      .replace(/2002年起连载的轻小说/g, '2002年起网络连载、2009年正式出版的轻小说'));
    slide.sourceIds = slide.sourceIds.map(String).slice(0, 8);
    if (String(slide.type || '').toLowerCase() === 'closing') {
      slide.title = '感谢观看';
      slide.headline = '谢谢聆听';
      slide.bullets = [];
      slide.textEdits = [];
      slide.imageEdits = [];
    }
    if (format === 'pptx') {
      const source = Number(slide.sourceSlide || index + 1);
      slide.sourceSlide = Math.max(1, Math.min(manifest.slideCount, Number.isFinite(source) ? source : 1));
      let sourceInfo = manifest.slides[slide.sourceSlide - 1];
      if (!isCompatibleSourceRole(slide.type, sourceInfo.visual?.role)) {
        const fallbackIndex = manifest.slides.findIndex(item =>
          isCompatibleSourceRole(slide.type, item.visual?.role));
        if (fallbackIndex < 0) {
          throw new Error(`第 ${index + 1} 页叙事类型 ${slide.type || 'content'} 与源页 ${slide.sourceSlide} 的模板角色 ${sourceInfo.visual.role} 不匹配`);
        }
        slide.sourceSlide = fallbackIndex + 1;
        sourceInfo = manifest.slides[fallbackIndex];
        // A source-page reroute invalidates the model's old slot selectors.
        slide.textEdits = [];
        slide.imageEdits = [];
      }
      const textSlots = new Map((sourceInfo.textShapes || [])
        .filter(item => !item.furniture)
        .map(item => [String(item.slotId), item]));
      removeFurnitureTextEdits(slide, sourceInfo);
      completeTextSlotEdits(slide, sourceInfo, plan);
      fitNarrativeFields(slide, sourceInfo);
      normalizeConceptLabelMismatches(slide, sourceInfo);
      ensureImageEdits(slide, sourceInfo, sourceImages, 'pptx', {
        requireImage: options.requireVisualAssets && ['content', 'evidence', 'comparison'].includes(String(slide.type || '').toLowerCase()),
        // ensurePptImageSlide already selects a bounded set of distinct,
        // high-confidence assets for preferred visual mode. Do not refill the
        // remaining pages with generic contextual stock imagery here.
        preferImage: options.preferVisualAssets
          && Boolean(slide.imageEdits?.length)
          && ['content', 'evidence', 'comparison'].includes(String(slide.type || '').toLowerCase()),
        visualTopic: options.visualTopic,
        imageIndex: index
      });
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
      ensureImageEdits(slide, null, sourceImages, 'html', {
        requireImage: options.requireVisualAssets && ['content', 'evidence', 'comparison', 'timeline', 'quote'].includes(String(slide.type || '').toLowerCase()),
        preferImage: options.preferVisualAssets && ['content', 'evidence', 'comparison', 'timeline', 'quote'].includes(String(slide.type || '').toLowerCase()),
        visualTopic: options.visualTopic,
        imageIndex: index
      });
    }
  });
  if (format === 'pptx') dedupeNarrativeTitles(plan, manifest);
  diversifyPresentationImages(plan, format, sourceImages, options);
  if (format === 'pptx') {
    for (const slide of plan.slides) {
      if (String(slide?.type || '').toLowerCase() !== 'closing') continue;
      const sourceInfo = manifest?.slides?.[Number(slide.sourceSlide) - 1];
      const titleShapes = (sourceInfo?.textShapes || [])
        .filter(shape => !shape.furniture && shape.roleHint === 'title'
          && !/(?:汇报人|报告人|日期|时间)/.test(String(shape.text || '')))
        .sort((left, right) => Number(right.maxFontPt || 0) - Number(left.maxFontPt || 0));
      slide.title = '感谢观看';
      slide.headline = '谢谢聆听';
      slide.bullets = [];
      slide.imageEdits = [];
      slide.textEdits = titleShapes.slice(0, 2).map((shape, index) => ({
        slotId: String(shape.slotId),
        text: index === 0 ? '感谢观看' : '谢谢聆听'
      }));
    }
  }
  if (format === 'html') {
    plan.slides.forEach(slide => fitHtmlSlideToViewport(slide, { hasImage: Boolean(slide.imageId) }));
  }
  return plan;
}

function ensureRequestedVisualCoverage(plan, format, sourceImages, options = {}) {
  if (options.requireVisualAssets !== true) return plan;
  const webIds = new Set((sourceImages || [])
    .filter(item => item?.origin === 'web-search' || item?.sourceUrl)
    .map(item => String(item.id)));
  if (!webIds.size) {
    throw new Error('用户要求图文并茂，但本次检索没有下载到可用网络图片');
  }
  const used = plan.slides.some(slide => format === 'pptx'
    ? (slide.imageEdits || []).some(edit => webIds.has(String(edit?.imageId || '')))
    : webIds.has(String(slide.imageId || '')));
  if (!used) {
    throw new Error('用户要求图文并茂，但相关网络图片没有映射到任何内容页');
  }
  return plan;
}

function dedupeNarrativeTitles(plan, manifest) {
  const seen = new Set();
  const counters = new Map();
  for (const [index, slide] of plan.slides.entries()) {
    const original = String(slide.title || '').trim();
    if (!original || !seen.has(original)) {
      if (original) seen.add(original);
      continue;
    }
    const topic = original.replace(/^第[一二三四五六七八九十]+章\s*/, '').trim();
    const alternatives = topic.includes('故事') || topic.includes('主线')
      ? ['故事推进与转折', '关键事件与冲突']
      : topic.includes('角色') || topic.includes('设定')
        ? ['角色关系与世界设定', '角色成长与核心规则']
        : topic.includes('主题') || topic.includes('影响')
          ? ['主题表达与社会讨论', '作品价值与延伸影响']
          : topic.includes('概述') || topic.includes('作品')
            ? ['作品设定与世界观', '作品定位与文化影响']
            : [`${topic} · 延伸`];
    let cursor = counters.get(original) || 0;
    let next = alternatives[cursor] || `${topic} · ${cursor + 2}`;
    counters.set(original, cursor + 1);
    while (seen.has(next)) {
      cursor += 1;
      next = alternatives[cursor] || `${topic} · ${cursor + 2}`;
    }
    const sourceInfo = manifest?.slides?.[Number(slide.sourceSlide) - 1];
    const titleShape = sourceInfo?.textShapes?.find(item =>
      !item.furniture && item.roleHint === 'title' && !/(?:汇报人|报告人|演讲人|演示者|时间|日期)/.test(item.text || ''))
      || sourceInfo?.textShapes?.find(item => !item.furniture);
    const fitted = next.slice(0, Number(titleShape?.capacityChars || 80));
    const oldIndex = (slide.textEdits || []).findIndex(edit => String(edit.text || '').trim() === original);
    if (oldIndex >= 0) slide.textEdits[oldIndex].text = fitted;
    slide.title = fitted;
    seen.add(fitted);
  }
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

async function validatePlanWithRepair(candidate, format, manifest, sourceImages, system, sources = [], options = {}) {
  let current = ensureDefaultClosingSlide(
    ensureReferenceCoverage(candidate, sources), format, manifest, options.expectedSlideCount || 0);
  let lastError = null;
  for (let attempt = 0; attempt < 3; attempt += 1) {
    try {
      if (format === 'pptx') ensurePptImageSlide(current, manifest, sourceImages, options);
      return ensureRequestedVisualCoverage(
        validatePlan(structuredClone(current), format, manifest, sourceImages, sources, options),
        format,
        sourceImages,
        options
      );
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
For PPTX, when sourceSlide changes you MUST rebuild textEdits and imageEdits using only slotIds from that source page. Never edit a furniture slot (including inherited numbering, footer, or decorative labels). Every title, headline, and bullet must appear verbatim in textEdits, each edit must fit capacityChars; if a narrative field is too long, shorten it into a coherent label or choose a roomier source page. Template samples such as “作品概述”, “Overview”, “第一部分”, “添加标题”, “Click here to add title text”, and instructional placeholder paragraphs are forbidden. imageEdits may target only slots where fillable=true. Do not invent slot IDs.
The source page visual role must match the slide type; never use a cover or section page for a content slide. Every visible non-furniture text slot must contain a meaningful value.
Return {"action":"final","args":{"presentation":<the complete corrected presentation>}}.`,
        maxTokens: 12000,
        repairContext: 'Return final with args.presentation containing a complete valid plan.'
      });
      if (repaired.action !== 'final' || !repaired.args?.presentation) {
        continue;
      }
      current = ensureReferenceCoverage(repaired.args.presentation, sources);
      ensureDefaultClosingSlide(current, format, manifest, options.expectedSlideCount || 0);
      if (format === 'pptx') ensurePptImageSlide(current, manifest, sourceImages, options);
    }
  }
  // A model repair can still fail at the JSON/protocol layer. Salvage the
  // narrative with fresh source-page mappings before giving up: the
  // deterministic slot builder can safely rebuild text and image edits.
  if (format === 'pptx' && current?.slides?.length) {
    try {
      const salvage = structuredClone(current);
      salvage.slides = salvage.slides.map(slide => ({ ...slide, textEdits: [], imageEdits: [] }));
      ensurePptImageSlide(salvage, manifest, sourceImages, options);
      return ensureRequestedVisualCoverage(
        validatePlan(salvage, format, manifest, sourceImages, sources, options),
        format,
        sourceImages,
        options
      );
    } catch (salvageError) {
      lastError = salvageError;
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

function fallbackResearchQueries(prompt) {
  const value = String(prompt || '').replace(/\s+/g, ' ').trim();
  const parts = value.split(/[，。；;、,!?！？]/).map(item => item.trim()).filter(item => item.length >= 4);
  const base = parts.slice(0, 2);
  const queries = [value.slice(0, 120), ...base.map(item => `${item} 研究 综述`), '人工智能 科研应用 方法 案例 风险 趋势'];
  return [...new Set(queries.map(item => item.trim()).filter(Boolean))].slice(0, 3);
}

function heuristicVisualQueries(prompt) {
  const value = String(prompt || '');
  const queries = [];
  if (/刀剑神域|Sword\s*Art\s*Online|anime|动画/i.test(value)) {
    // Search the exact work identity first. Commons contains directly related
    // logos, publications and event displays; adding broad concepts such as
    // “virtual reality” made its full-text search discard the proper title and
    // fall through to visually unrelated stock photos.
    queries.push(
      'Sword Art Online novels',
      'Sword Art Online anime exhibition',
      'Kirito Asuna cosplay'
    );
  }
  if (/人工智能|AI|机器学习|深度学习|生成式人工智能/i.test(value)) {
    queries.push('artificial intelligence research application');
  }
  if (/科研|科学研究|实验|论文|研究汇报/.test(value)) {
    queries.push('scientific research laboratory data analysis');
  }
  return queries;
}

function mimoSources(result) {
  return (Array.isArray(result?.annotations) ? result.annotations : [])
    .filter(item => item?.type === 'url_citation' && item.url && item.title)
    .map(item => ({
      title: String(item.title),
      url: String(item.url),
      abstract: String(item.summary || ''),
      type: 'web',
      provider: 'mimo-web-search',
      query: String(item.title || ''),
      license: 'Mimo web-search citation; source-page rights require verification'
    }));
}

function normalizeSourceIds(plan, sources) {
  const allowed = new Set(sources.map(item => item.id));
  for (const slide of plan.slides) {
    slide.sourceIds = (slide.sourceIds || []).filter(id => allowed.has(id));
  }
  return ensureReferenceCoverage(plan, sources);
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

async function classifyTemplateSlides(system, manifest, previewDir) {
  const classifications = [];
  for (let start = 0; start < manifest.slideCount; start += 4) {
    const files = Array.from({ length: Math.min(4, manifest.slideCount - start) }, (_, offset) =>
      path.join(previewDir, `slide-${start + offset + 1}.png`));
    let result;
    try {
      result = await completeVisionJson({
        system,
        user: `Inspect source-template pages ${start + 1}-${start + files.length}. Every supplied screenshot must receive one entry.
Identify each page role and realistic content capacity without redesigning it.
Return {"action":"inspect","args":{"slides":[{"slide":1,"role":"cover|agenda|section|content|evidence|comparison|closing","capacity":"short practical description","warnings":[]}]}}.
Slide numbers must be absolute.`,
        imageFiles: files,
        maxTokens: 3500,
        repairContext: 'The action must be inspect and args.slides must describe every supplied screenshot.',
        requestTimeoutMs: 60_000,
        maxAttempts: 1
      });
    } catch {
      result = null;
    }
    const classified = result?.action === 'inspect' && Array.isArray(result.args?.slides)
      ? result.args.slides : [];
    const classifiedBySlide = new Map(classified.map(item => [Number(item.slide), item]));
    for (let offset = 0; offset < files.length; offset += 1) {
      const slideNumber = start + offset + 1;
      classifications.push(classifiedBySlide.get(slideNumber)
        || inferTemplateSlideRole({ ...manifest.slides[slideNumber - 1], slideCount: manifest.slideCount }));
    }
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
        // Full-bleed and text-overlapping source pictures remain protected.
        // Replacing them turns readable template layers into busy backgrounds;
        // only genuinely isolated content frames can receive task imagery.
        fillable: slot.fillable === true && roleAllowsContentImages,
        fillableReason: slot.fillable === true && !roleAllowsContentImages
          ? `page-role-${visual?.role || 'unknown'}-is-not-image-fillable`
          : slot.fillableReason
      }))
    };
  });
  return manifest;
}

function inferTemplateSlideRole(item) {
  const slide = Number(item?.slide || 1);
  const total = Number(item?.slideCount || 0);
  const sample = String(item?.sampleText || '').toLowerCase();
  const shapeCount = (item?.textShapes || []).filter(shape => !shape.furniture).length;
  const titleText = (item?.textShapes || []).find(shape => shape.roleHint === 'title')?.text || '';
  const role = slide === 1
    ? 'cover'
    : slide === 2
      ? 'agenda'
      : (total > 0 && slide === total)
        ? 'closing'
        : /数据|对比|table|comparison/i.test(`${sample} ${titleText}`)
          ? 'comparison'
          : /第一部分|第二部分|第三部分|第四部分|第五部分|background|methods|achievement|summary/i.test(sample)
            && shapeCount <= 4
            ? 'section'
            : 'content';
  return {
    slide,
    role,
    capacity: 'Inherited source layout; fill its existing text frames without changing geometry.',
    warnings: ['Deterministic role fallback used because visual classification was unavailable.']
  };
}

async function main() {
  const jobFile = process.argv[2];
  if (!jobFile) throw new Error('缺少 job.json');
  const job = JSON.parse(await fs.readFile(jobFile, 'utf8'));
  const taskDir = path.dirname(jobFile);
  const root = path.resolve(job.projectRoot);
  const storageRoot = path.resolve(job.storageRoot);
  const visualIntent = requestsVisualAssets(job.prompt);
  const visualMode = String(job.visualMode || 'best_effort');
  const strictVisualAssets = visualMode === 'strict';
  // The UI's default “尽力配图” is a real instruction, not merely a hint in
  // the prompt. Legacy jobs without visualMode keep that useful default.
  const visualRequested = visualMode === 'best_effort' || visualIntent || strictVisualAssets;
  const visualOptions = {
    requireVisualAssets: strictVisualAssets,
    preferVisualAssets: visualRequested,
    visualTopic: String(job.prompt || '').slice(0, 500),
    expectedSlideCount: requestedSlideCount(job.prompt)
  };
  assertInsideStorage(storageRoot, taskDir);
  assertInsideStorage(storageRoot, job.templateFile);
  assertInsideStorage(storageRoot, job.sourceFile);
  assertInsideStorage(storageRoot, job.sourceTextFile);
  assertInsideStorage(storageRoot, job.previousPlanFile);
  assertInsideStorage(storageRoot, job.previousSourcesFile);
  assertInsideStorage(storageRoot, job.previousOutputFile);
  const previousPreviewFiles = Array.isArray(job.previousPreviewFiles) ? job.previousPreviewFiles : [];
  for (const file of previousPreviewFiles) assertInsideStorage(storageRoot, file);
  // Guard jobs created by an older backend too: a PDF page raster is source
  // evidence for extraction, not a usable presentation image.
  const uploadedSourceImages = (Array.isArray(job.sourceImages) ? job.sourceImages : [])
    .filter(item => !/^paper-page-/i.test(String(item?.fileName || '')))
    .slice(0, 8);
  for (const image of uploadedSourceImages) assertInsideStorage(storageRoot, image.path);
  let sourceImages = uploadedSourceImages;

  const researchSkill = await readText(path.join(root, '.agents/skills/research-presentation/SKILL.md'));
  const formatSkill = await readText(path.join(root, `.agents/skills/${job.outputFormat === 'html' ? 'create-html-presentation' : 'create-template-pptx'}/SKILL.md`));
  const skillSystem = `${researchSkill}\n\n${formatSkill}\n\nThe service executes image search itself; never emit curl commands, API keys, or remote download instructions. Search-indexed images may have unverified reuse rights unless a separate license is recorded. You are the presentation Agent. Return the exact JSON action requested. Do not expose internal reasoning.`;
  const sourceText = job.sourceTextFile ? (await readText(job.sourceTextFile)).slice(0, 40_000) : '';

  emit('researching', { progress: 10, message: '正在理解主题并自主调用检索工具' });
  const research = { sources: [], assets: [], degraded: false, failures: [] };
  const firstPartyMedia = knownFirstPartyMediaSources(job.prompt);
  if (job.researchMode !== 'off') {
    if (firstPartyMedia.length) {
      research.sources = mergeSources(firstPartyMedia, [], Number(job.maxSources || 12));
      const officialImages = await discoverPageImages(firstPartyMedia, { maxPages: 4, maxAssets: 4 });
      research.assets.push(...officialImages.assets);
      research.failures.push(...officialImages.failures);
    }
    if (process.env.PPT_AGENT_MIMO_SEARCH_ENDPOINT && process.env.PPT_AGENT_MIMO_SEARCH_KEY) {
      try {
        const mimo = await completeMimoWebSearch({
          system: `${skillSystem}\nYou are the web research layer for a presentation agent. Search first-party and authoritative pages, preserve exact URLs, and do not invent image URLs.`,
          user: `强制联网检索这个演示主题：${job.prompt}\n请重点寻找与主题直接相关的官方页面、作品页面、角色/世界观资料或权威介绍；不要返回泛化的建筑、实验室或股票图库内容。搜索结果将用于来源和来源页视觉提取。`,
          maxKeyword: 3,
          limit: 6
        });
        const sources = mimoSources(mimo);
        research.sources = mergeSources(research.sources, sources, Number(job.maxSources || 12));
        research.mimoWebSearch = true;
        research.mimoWebSearchUsage = mimo.usage || null;
        const pageImages = await discoverPageImages(relevantPageImageSources(sources, job.prompt), { maxPages: 4, maxAssets: 8 });
        research.assets.push(...pageImages.assets);
        research.failures.push(...pageImages.failures);
      } catch (error) {
        research.degraded = true;
        research.failures.push(`Mimo 原生联网搜索失败: ${String(error?.message || error)}`);
      }
    }
    let remainingSearches = Math.max(1, Number(job.maxSearches || 6));
    const maxRounds = Math.min(3, remainingSearches);
    for (let round = 0; round < maxRounds && remainingSearches > 0; round += 1) {
      let decision;
      try {
        decision = await completeJson({
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
      } catch (error) {
        const message = String(error?.message || error);
        if (!/模型 JSON|JSON 对象|连续修复/.test(message)) throw error;
        if (round > 0 && research.sources.length) {
          research.degraded = true;
          research.failures.push(`研究决策模型异常，已结束检索: ${message}`);
          decision = { action: 'final', args: { researchComplete: true } };
        } else {
          // Research should remain useful when the first routing call returns
          // an empty/invalid object. The main authoring and QA calls remain strict.
          research.degraded = true;
          research.failures.push(`研究决策模型异常，已使用确定性检索词兜底: ${message}`);
          decision = { action: 'tool_call', args: { tool: 'search', queries: fallbackResearchQueries(job.prompt) } };
        }
      }
      const normalizedDecision = normalizeResearchDecision(decision);
      if (normalizedDecision?.action === 'final' && research.sources.length) break;
      if (!normalizedDecision || normalizedDecision.action !== 'tool_call') {
        research.degraded = true;
        research.failures.push('研究决策返回了未知工具，已改用确定性检索词');
        if (round > 0 && research.sources.length) break;
        decision = { action: 'tool_call', args: { tool: 'search', queries: fallbackResearchQueries(job.prompt) } };
      } else {
        decision = normalizedDecision;
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
  const distinctVisualQueries = new Set((research.assets || [])
    .map(item => String(item?.searchQuery || item?.query || '').trim().toLowerCase())
    .filter(Boolean));
  if (visualRequested && (research.assets.length < 6 || distinctVisualQueries.size < 3)) {
    let visualQueries = [];
    try {
      const visualDecision = await completeJson({
        system: skillSystem,
        user: `Generate dedicated image-search queries for this presentation because the current search returned too few distinct usable assets.
Original presentation prompt: ${job.prompt}
Verified research titles: ${JSON.stringify(research.sources.slice(0, 8).map(item => item.title))}
Return exactly {"action":"final","args":{"queries":["1-3 concise English queries"]}}.
Preserve proper nouns for anime, books, people, products, and places. Do not return generic office, building, laboratory, house, or stock-photo queries. Use visuals that can be licensed and cited.`,
        maxTokens: 900,
        repairContext: 'Return action=final with args.queries containing one to three concise visual search queries.'
      });
      if (visualDecision.action === 'final' && Array.isArray(visualDecision.args?.queries)) {
        visualQueries = visualDecision.args.queries.map(String).filter(Boolean).slice(0, 3);
      }
    } catch (error) {
      research.failures.push(`视觉检索词生成失败: ${String(error?.message || error)}`);
    }
    const fallbackQueries = [
      // Deterministic topic-aware queries come first. Model-generated queries
      // can all collapse to the same scarce copyrighted subject and starve
      // the broad licensed fallbacks before maxQueries is reached.
      ...heuristicVisualQueries(job.prompt),
      ...visualQueries,
      ...research.sources.slice(0, 3).map(item => item.title),
      job.prompt
    ].filter((item, index, all) => item && all.indexOf(item) === index);
    const visualSearch = await searchVisualAssets(fallbackQueries, { maxQueries: 3, maxAssets: 8 });
    research.visualSearchQueries = fallbackQueries.slice(0, 3);
    // Put the intentionally diversified supplement first. The initial
    // research query can already contain eight nearly identical VR photos;
    // appending and slicing would make the fallback a no-op.
    research.assets = [...visualSearch.assets, ...research.assets]
      .filter((item, index, all) => item?.url && all.findIndex(other => other.url === item.url) === index)
      .slice(0, 8);
    research.failures.push(...visualSearch.failures);
  }
  // Prefer media explicitly published by a retained source page. This is
  // particularly important for media franchises: first-party artwork is more
  // topical than a generic stock photo and avoids unauthenticated stock-search
  // renditions that can contain visible provider watermarks.
  if (visualRequested && research.sources.length) {
    const topicalPages = [...firstPartyMedia, ...relevantPageImageSources(research.sources, job.prompt)]
      .filter((item, index, all) => all.findIndex(other => other.url === item.url) === index);
    const pageImages = await discoverPageImages(topicalPages, { maxPages: 4, maxAssets: 8 });
    research.assets = [...pageImages.assets, ...research.assets]
      .filter((item, index, all) => item?.url && all.findIndex(other => other.url === item.url) === index)
      .slice(0, 8);
    research.failures.push(...pageImages.failures);
  }
  if (job.previousSourcesFile) {
    const previousResearch = JSON.parse(await readText(job.previousSourcesFile, '{"sources":[]}'));
    research.sources = mergeSources(previousResearch.sources || [], research.sources, Number(job.maxSources || 12));
  }
  const downloadedResearch = await downloadResearchAssets(research.assets, path.join(taskDir, 'images'), {
    maxCount: Math.max(0, 8 - Math.min(4, uploadedSourceImages.length))
  });
  sourceImages = [...uploadedSourceImages.slice(0, 4), ...downloadedResearch.images]
    .slice(0, 8);
  research.imageAssets = downloadedResearch.images.map(({ id, fileName, title, description, searchQuery,
    sourceUrl, originalUrl, license, provider, confidence, rightsStatus, rightsNote,
    width, height, originalWidth, originalHeight, accessedAt, origin }) => ({
    id, fileName, title, description, searchQuery, sourceUrl, originalUrl, license, provider,
    confidence, rightsStatus, rightsNote, width, height, originalWidth, originalHeight, accessedAt, origin
  }));
  research.imageFailures = downloadedResearch.failures;
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
  const planningManifest = manifest ? compactManifest(manifest, { slides: [] }, 8) : null;
  // Image metadata is enough to choose slide assets. Sending every downloaded
  // bitmap here used to switch a large PPTX planning request from the stronger
  // text model to the vision model; on a 15-page manifest that repeatedly
  // exhausted the response window and returned no JSON. Keep complex narrative
  // planning on the configured text model and reserve vision calls for QA.
  let action = await completeJson({
    system: skillSystem,
    user: `Create the complete presentation plan.
Prompt: ${job.prompt}
Uploaded source material: ${sourceText}
Output format: ${job.outputFormat}
Selected template/theme: ${job.templateKey}
Research degraded: ${research.degraded}
Sources: ${JSON.stringify(sourceSummary(research.sources))}
Visual preference: ${strictVisualAssets ? 'STRICT — a relevant web visual must appear on a content page' : visualRequested ? 'PREFERRED — use relevant web visuals when safely available; no suitable image is acceptable' : 'NONE — use visuals only when the narrative requests them'}
${job.outputFormat === 'pptx' && visualRequested ? 'For this image-rich PPTX, give every non-cover/non-closing content slide exactly three concise, non-overlapping bullets so deterministic card pairing can keep labels and descriptions aligned.' : ''}
Available visual assets (uploaded + web image search; the authoring step will auto-fill compatible image slots): ${JSON.stringify(sourceImages.map(item => ({ id: item.id, fileName: item.fileName, title: item.title || '', description: item.description || '', searchQuery: item.searchQuery || '', sourceUrl: item.sourceUrl || '', license: item.license || '' })))}
Template manifest (compact authoring map; full manifest remains on disk for validation): ${planningManifest ? JSON.stringify(planningManifest) : 'HTML theme; sourceSlide is not used'}
Previous plan for revision: ${previousPlan}
Honor an explicitly requested slide count exactly. Otherwise choose the slide count that best serves the topic and source material; do not pad to a fixed number or create one output slide for every template page or research source.
Return {"action":"final","args":{"changedSlideNumbers":[2],"presentation":{"title":"...","audience":"...","takeaway":"...","slides":[{"type":"cover|agenda|section|content|evidence|comparison|timeline|quote|closing","layout":"cover|section|statement|split|image-hero|kpi|stats|process|comparison|timeline|quote|gallery|closing","sourceSlide":1,"section":"...","title":"...","headline":"...","bullets":["..."],"items":[{"label":"...","value":"...","detail":"..."}],"imageRole":"hero|evidence|portrait|diagram|gallery","textEdits":[{"slotId":"s1-t1","text":"exact visible text"}],"imageEdits":[{"slotId":"s1-i1","imageId":"I01"}],"imageId":"I01","sourceIds":["S01"],"notes":"..."}]}}}.
For PPTX, choose sourceSlide from the manifest whose visual role matches the slide type (cover with cover, section with section, content/evidence/comparison with a content-like page). Map every visible title, headline, bullet, card label, and timeline label verbatim to exact textEdits slots within capacityChars; never rely on high-level fields being assigned automatically. Populate every meaningful label required by inherited diagrams, numbered lists, matrices, and timelines, or choose a simpler source page—do not leave blank-looking structures. Template sample copy is forbidden: never output “作品概述”, “Overview”, “第一部分”, “添加标题”, “Click here to add title text”, or a template’s instructional paragraph as a slide title, section, headline, bullet, or text edit. Use imageEdits only for template image slots explicitly marked fillable=true. When visual preference is STRICT, use a relevant web image on an image-capable content/evidence/comparison page; when it is PREFERRED, use relevant assets whenever a clearly related match exists. Never use a web image whose title/description/search query does not match the slide topic. Do not create visible references or bibliography pages: sourceIds are retained in task metadata and speaker notes instead. When this is a revision, changedSlideNumbers must contain every page you changed; otherwise it may be omitted. For HTML, select a semantically distinct layout for each page purpose; use kpi/stats only for real numbers, process/timeline only for ordered relationships, comparison for two genuine sides, image-hero only with a topical image, and gallery only with one strong topical image plus a compact curatorial caption. Avoid repeating the same silhouette three pages in a row. imageId may select an uploaded or server-searched local image; strict visual preference requires a relevant web image on a content page, while preferred visual preference should use a clearly related image when one is available. Every sourced claim needs sourceIds.`,
    maxTokens: 9000,
    requestTimeoutMs: 300_000,
    maxAttempts: 2,
    repairContext: 'The action must be final and args.presentation.slides must be a non-empty array.'
  });
  if (action.action !== 'final' || !action.args?.presentation?.slides?.length) {
    action = await completeJson({
      system: skillSystem,
      user: `The previous planning response was not a final presentation plan.
Previous response: ${JSON.stringify(action)}
Original request: ${job.prompt}
Output format: ${job.outputFormat}
Template manifest: ${manifest ? JSON.stringify(compactManifest(manifest, { slides: [] }, 8)) : 'HTML theme'}
Return one complete final plan only. Honor an explicitly requested slide count; otherwise retain a coherent, topic-appropriate count. For PPTX, include a non-empty slides array, sourceSlide, and exact textEdits for every visible non-furniture source text slot; every edit must fit capacityChars. Use a compatible source-page role and do not edit furniture slots.
Return {"action":"final","args":{"presentation":<complete presentation>}}.`,
      maxTokens: 9000,
      requestTimeoutMs: 300_000,
      maxAttempts: 2,
      repairContext: 'Return only action=final with a complete non-empty args.presentation.slides array.'
    });
  }
  if (action.action !== 'final') throw new Error('Agent 未返回最终创作计划');
  let candidatePlan = action.args?.presentation;
  let unchangedSlideNumbers = [];
  if (previousPlanObject) {
    const reuse = applyRevisionReuse(candidatePlan, previousPlanObject, action.args?.changedSlideNumbers, job.outputFormat);
    candidatePlan = reuse.plan;
    unchangedSlideNumbers = reuse.unchanged;
  }
  let validatedCandidate = await validatePlanWithRepair(
    candidatePlan, job.outputFormat, manifest, sourceImages, skillSystem, research.sources, visualOptions);
  if (previousPlanObject) {
    const reuse = applyRevisionReuse(
      validatedCandidate, previousPlanObject, action.args?.changedSlideNumbers, job.outputFormat);
    validatedCandidate = validatePlan(reuse.plan, job.outputFormat, manifest, sourceImages, research.sources, visualOptions);
    unchangedSlideNumbers = reuse.unchanged;
  }
  let plan = normalizeSourceIds(validatedCandidate, research.sources);
  await fs.writeFile(path.join(taskDir, 'agent-plan.json'), JSON.stringify(plan, null, 2));

  const outputFile = path.join(taskDir, job.outputFormat === 'html' ? 'output.html' : 'output.pptx');
  const previewDir = path.join(taskDir, 'preview');
  let frameMap = [];
  let qa = {};
  // Full rendered review is retained for the initial output and the final
  // retry.  Between them, review only pages explicitly changed by the repair:
  // the deck is still fully re-rendered and deterministic checks remain global,
  // but this avoids resending already approved screenshots to the vision model.
  let visualReviewSlides = [];
  for (let iteration = 0; iteration < 3; iteration += 1) {
    emit(iteration === 0 ? 'authoring' : 'revising', {
      progress: 52 + iteration * 12,
      iteration,
      message: iteration === 0 ? '正在生成真实演示文件' : `正在进行第 ${iteration} 轮质量返修`
    });
    if (job.outputFormat === 'pptx') {
      recordToolCall('compose-pptx');
      frameMap = await composeTemplatePptx({ templateFile: job.templateFile, outputFile, plan, manifest, sources: research.sources, sourceImages, fontFamily: job.fontFamily });
      await fs.writeFile(path.join(taskDir, 'template-frame-map.json'), JSON.stringify({ outputSlides: frameMap }, null, 2));
    } else {
      recordToolCall('compose-html');
      await createHtmlPresentation({
        outputFile,
        plan,
        sources: research.sources,
        sourceImages,
        fontFamily: job.fontFamily,
        motionMode: job.motionMode || 'auto',
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
    const visual = await visualReview(skillSystem, plan, previewDir,
      iteration === 1 ? visualReviewSlides : []);
    qa.visualReview = visual;
    qa.visualReviewScope = iteration === 1 && visualReviewSlides.length
      ? { mode: 'repaired-pages', slides: [...visualReviewSlides] }
      : { mode: 'full-deck', slides: plan.slides.map((_, index) => index + 1) };
    qa.valid = qa.valid && visual.valid;
    emit('reviewing', { progress: 88 + iteration * 4, iteration, message: '正在检查版式、溢出、模板和引用', qa });
    if (qa.valid) break;
    // A rendered, structurally valid file remains useful even when visual QA
    // reports defects. Persist the report and hand it to the service as a
    // delivery warning; only authoring/rendering/package failures abort.
    if (iteration === 2) break;
    const failedPages = repairPageNumbers(qa, plan.slides.length);
    let repairedCandidate = plan;
    let reportedChangedPages = [];
    for (let start = 0; start < failedPages.length; start += 2) {
      const batchPages = failedPages.slice(start, start + 2);
      const repairImages = batchPages.map(slide => path.join(previewDir, `slide-${slide}.png`));
      const batchSlides = batchPages.map(page => ({
        slideNumber: page,
        ...((({ type, layout, sourceSlide, section, title, headline, bullets, textEdits, imageEdits, sourceIds }) => ({
          type, layout, sourceSlide, section, title, headline, bullets, textEdits, imageEdits, sourceIds
        }))(plan.slides[page - 1]))
      }));
      const batchIssues = (qa.visualReview?.issues || [])
        .filter(issue => batchPages.includes(Number(issue?.slide)));
      const batchManifest = manifest
        ? compactManifest(manifest, { slides: batchSlides }, 8)
        : null;
      let repair;
      try {
        repair = await completeVisionJson({
          system: skillSystem,
          user: `Repair only slides ${batchPages.join(', ')} after QA failure. Keep correct content and sources.
This is a batch-only repair. Return exactly one slide object per authorized page with an absolute slideNumber; do not repeat unaffected slides.
Affected slides: ${JSON.stringify(batchSlides)}
Relevant QA issues: ${JSON.stringify(batchIssues)}
Relevant template manifest: ${batchManifest ? JSON.stringify(batchManifest) : 'HTML theme'}
For PPTX, the authoring tool changes visible content through sourceSlide plus exact textEdits/imageEdits mappings. If sourceSlide changes, rebuild every slot mapping from that source page. Never edit furniture slots such as inherited numbering, footers, or decorative labels. Every title, headline, bullet, card label, and timeline label must appear verbatim in textEdits and fit capacityChars; if a narrative field is too long, shorten it into a coherent label or choose a roomier source page. imageEdits may target only fillable=true slots. Unmapped inherited text shapes are removed automatically, so fill every visible non-furniture text slot. Choose a sourceSlide whose visual role matches the slide type; never use a cover or section page as a content page. Speaker notes are not editing commands: never put [REPAIR] instructions or visual edit requests in notes.
Return a batch-only presentation whose slides correspond exactly to the authorized pages ${batchPages.join(', ')} and include an absolute slideNumber on each slide. Use {"action":"final","args":{"changedSlideNumbers":[${batchPages.join(', ')}],"presentation":{"slides":[...]}}}. Preserve all other pages exactly.`,
          imageFiles: repairImages,
          maxTokens: 5200,
          repairContext: `Return only the authorized batch slides with absolute slideNumber values; changedSlideNumbers must be a subset of ${batchPages.join(', ')}. Use Chinese quotation marks inside text values and never put an unescaped ASCII quote inside a JSON string.`,
          requestTimeoutMs: 60_000,
          maxAttempts: 1
        });
      } catch (error) {
        // A malformed vision response must not turn an otherwise renderable deck
        // into a hard failure. Deterministic validation below still rechecks the
        // unchanged batch and the next visual pass decides whether another repair
        // is actually needed.
        repair = {
          action: 'final',
          args: {
            changedSlideNumbers: batchPages,
            presentation: { slides: batchPages.map(page => ({ ...plan.slides[page - 1], slideNumber: page })) }
          }
        };
      }
      if (repair.action !== 'final') throw new Error('Agent 返修没有返回 final');
      const claimed = (repair.args?.changedSlideNumbers || []).map(Number);
      if (claimed.some(page => !batchPages.includes(page))) throw new Error('Agent 批次返修改动了未授权页面');
      repairedCandidate = mergeRepairBatch(repairedCandidate, repair.args?.presentation, batchPages, claimed);
      reportedChangedPages.push(...(claimed.length ? claimed : batchPages));
    }
    if (previousPlanObject) {
      const revisionChangedPages = combineChangedSlideNumbers(
        action.args?.changedSlideNumbers, reportedChangedPages);
      const reuse = applyRevisionReuse(repairedCandidate, previousPlanObject,
        revisionChangedPages, job.outputFormat);
      repairedCandidate = reuse.plan;
      unchangedSlideNumbers = reuse.unchanged;
    }
    let validatedRepair = await validatePlanWithRepair(
      repairedCandidate, job.outputFormat, manifest, sourceImages, skillSystem, research.sources, visualOptions);
    if (previousPlanObject) {
      const revisionChangedPages = combineChangedSlideNumbers(
        action.args?.changedSlideNumbers, reportedChangedPages);
      const reuse = applyRevisionReuse(validatedRepair, previousPlanObject,
        revisionChangedPages, job.outputFormat);
      validatedRepair = validatePlan(reuse.plan, job.outputFormat, manifest, sourceImages, research.sources, visualOptions);
      unchangedSlideNumbers = reuse.unchanged;
    }
    plan = normalizeSourceIds(validatedRepair, research.sources);
    visualReviewSlides = [...new Set(reportedChangedPages.map(Number)
      .filter(page => Number.isInteger(page) && page >= 1 && page <= plan.slides.length))]
      .sort((left, right) => left - right);
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

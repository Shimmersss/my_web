const ROLE_COMPATIBILITY = {
  cover: new Set(['cover']),
  agenda: new Set(['agenda', 'content']),
  section: new Set(['section']),
  closing: new Set(['closing', 'cover']),
  content: new Set(['content', 'evidence', 'comparison']),
  evidence: new Set(['evidence', 'content', 'comparison']),
  comparison: new Set(['comparison', 'content', 'evidence']),
  timeline: new Set(['content', 'evidence', 'comparison']),
  quote: new Set(['content', 'evidence']),
  references: new Set(['references', 'content', 'evidence']),
};

/**
 * A revision's declared pages remain changed for the rest of the QA loop.
 * Later visual-repair batches add to that set; they must not replace it, or
 * reuse would restore an earlier user-requested edit from the previous deck.
 */
export function combineChangedSlideNumbers(...groups) {
  const changed = new Set();
  for (const group of groups) {
    for (const value of Array.isArray(group) ? group : []) {
      const number = Number(value);
      if (Number.isInteger(number) && number > 0) changed.add(number);
    }
  }
  return [...changed].sort((left, right) => left - right);
}

function cleanText(value) {
  return String(value || '').replace(/[*_`#]+/g, '').replace(/\s+/g, ' ').trim();
}

export function isTemplatePlaceholder(value, sample = '') {
  const text = cleanText(value);
  const source = cleanText(sample);
  if (!text) return true;
  if (/^(?:请在此处编辑文字|内容|正文|文字|重点|信息|目录|核心要点[一二三1-3]|作品概述|overview|添加标题|click here to add title text)$/i.test(text)) return true;
  if (/^根据自己的需要添加适当的文字[，,].*/.test(text)) return true;
  if (/^(?:第[一二三四五六七八九十]+部分|第[一二三四五六七八九十]+章(?:\s+.*)?)$/.test(text)) return true;
  if (/^(?:汇报人|报告人|演讲人|演示者|制作人|presenter|speaker|producer)\s*[:：]\s*(?:(?:X\s*){2,}|XXX|演示者|待填写|姓名|name)$/i.test(text)) return true;
  if (/^(?:时间|日期)\s*[:：]\s*20\d{2}(?:\.\d+)?$/.test(text) && text === source) return true;
  if (/(?:左图区|右图区|图片框|概念图|需补充视觉素材|请在此处|请补充)/.test(text)) return true;
  if (/(?:演示文稿是一种实用的工具|如果您想在演示文稿中展现|您可以在讲座中包含)/.test(text)
    && text === source) return true;
  return false;
}

function removableTemplateShape(shape) {
  const text = cleanText(shape?.text);
  return /^(?:汇报人|报告人|演讲人|演示者|制作人|presenter|speaker|producer)\s*[:：]/i.test(text)
    || /^(?:时间|日期|date)\s*[:：]\s*20\d{2}/i.test(text)
    || /(?:左图区|右图区|图片框|概念图|需补充视觉素材|请在此处|请补充)/.test(text);
}

function coverMetadataFallback(shape) {
  const text = cleanText(shape?.text);
  if (/^(?:汇报人|报告人|演讲人|演示者|制作人)\s*[:：]?/i.test(text)) return '汇报人：研究汇报';
  if (/^(?:时间|日期|date)\s*[:：]?/i.test(text)) return '日期：2026';
  return '';
}

function isBodyCopyOfNarrative(text, slide) {
  const value = cleanText(text);
  if (!value) return true;
  return [slide?.title, slide?.section].map(cleanText).filter(Boolean).includes(value);
}

function distinctFallback(slide, shape, index) {
  const topic = `${cleanText(slide?.title)} ${cleanText(slide?.section)}`;
  const choices = topic.includes('故事') || topic.includes('主线')
    ? ['关键事件与冲突', '发展脉络与关键转折', '现实影响与下一步']
    : topic.includes('方法') || topic.includes('模型') || topic.includes('技术')
      ? ['典型方法与评价', '方法流程与证据链', '工具能力与适用边界']
      : topic.includes('应用') || topic.includes('案例') || topic.includes('实践')
        ? ['应用场景与研究价值', '典型案例与实际效果', '从实验走向实践']
        : topic.includes('风险') || topic.includes('挑战') || topic.includes('伦理')
          ? ['主要风险与治理边界', '可靠性与可复现性', '人机协作的责任边界']
          : topic.includes('趋势') || topic.includes('未来') || topic.includes('建议')
            ? ['未来趋势与研究问题', '下一步行动建议', '开放问题与研究路线']
            : ['关键发现与证据', '研究结论与启示', '延伸问题与行动建议'];
  const candidate = choices[index % choices.length];
  if (shape?.roleHint === 'title') {
    const title = cleanText(slide?.title);
    const headline = cleanText(slide?.headline);
    const usableTitle = !isTemplatePlaceholder(title) ? title : '';
    const usableHeadline = !isTemplatePlaceholder(headline) ? headline : '';
    const replacement = usableTitle || usableHeadline || candidate;
    return fitText(replacement, shape?.capacityChars);
  }
  return fitText(candidate, shape?.capacityChars);
}

function textCandidates(slide, plan, role) {
  const bullets = Array.isArray(slide.bullets) ? slide.bullets : [];
  const common = [slide.title, slide.section, slide.headline, ...bullets, plan?.title]
    .map(cleanText)
    .filter(Boolean);
  if (role === 'body') return [...bullets.map(cleanText), cleanText(slide.headline), cleanText(slide.title), ...common];
  if (role === 'headline') return [cleanText(slide.headline), cleanText(slide.title), cleanText(slide.section), ...bullets.map(cleanText), ...common];
  return [cleanText(slide.title), cleanText(slide.section), cleanText(slide.headline), ...bullets.map(cleanText), ...common];
}

function fitText(value, capacity) {
  const text = cleanText(value);
  const limit = Math.max(1, Number(capacity || 1));
  if (text.length <= limit) return text;
  // Keep common Chinese presentation titles semantically complete when a
  // source template has a short inherited title frame.
  const compact = text
    .replace(/科学研究/g, '科研')
    .replace(/在([^，。；;]{1,12})中的/g, '$1')
    .replace(/研究中的/g, '科研');
  if (compact.length <= limit) return compact;
  const cut = text.slice(0, limit);
  const boundary = Math.max(
    cut.lastIndexOf('。'), cut.lastIndexOf('！'), cut.lastIndexOf('？'),
    cut.lastIndexOf('；'), cut.lastIndexOf('，'), cut.lastIndexOf('、'),
    cut.lastIndexOf(' '), cut.lastIndexOf('.')
  );
  const fitted = boundary >= Math.max(2, Math.floor(limit * 0.55)) ? cut.slice(0, boundary + 1) : cut;
  // Avoid ending a shortened title with a connector or list separator. This
  // turns “机器学习、生成式 AI 与” into a complete compact label instead
  // of leaving a visibly truncated phrase in the inherited title box.
  return fitted.replace(/[、，,与和及或的：:；;\s]+$/u, '').trim();
}

/**
 * Every visible, non-furniture source text frame must receive content. Leaving
 * an inherited card empty makes the real render look like an unfinished deck,
 * even when the high-level title/headline validation passes.
 */
export function completeTextSlotEdits(slide, sourceInfo, plan) {
  const allShapes = (sourceInfo?.textShapes || []).filter(item => !item.furniture);
  const isCover = String(slide?.type || '').toLowerCase() === 'cover';
  const shapes = allShapes.filter(item => !removableTemplateShape(item) || isCover);
  const shapeById = new Map(allShapes.map(shape => [String(shape.slotId), shape]));
  const mapped = new Set();
  const edits = [];
  const usedText = new Set();
  for (const edit of Array.isArray(slide.textEdits) ? slide.textEdits : []) {
    const slotId = String(edit?.slotId || '');
    const shape = shapeById.get(slotId);
    if (!shape || mapped.has(slotId)) continue;
    if (removableTemplateShape(shape)) {
      if (!isCover) continue;
      const fallback = coverMetadataFallback(shape);
      if (!fallback) continue;
      edits.push({ slotId, text: fitText(fallback, shape.capacityChars) });
      mapped.add(slotId);
      usedText.add(fallback);
      continue;
    }
    const text = fitText(edit?.text, shape.capacityChars);
    if (isTemplatePlaceholder(text, shape.text)
      || (shape.roleHint === 'body' && isBodyCopyOfNarrative(text, slide))
      || (shape.roleHint === 'body' && usedText.has(text))) continue;
    edits.push({ slotId, text });
    mapped.add(slotId);
    usedText.add(text);
  }
  let cursor = 0;
  let bodyIndex = 0;
  for (const shape of shapes) {
    if (mapped.has(String(shape.slotId))) continue;
    if (isCover && removableTemplateShape(shape)) {
      const fallback = coverMetadataFallback(shape);
      if (fallback) {
        const value = fitText(fallback, shape.capacityChars);
        edits.push({ slotId: String(shape.slotId), text: value });
        mapped.add(String(shape.slotId));
        usedText.add(value);
        continue;
      }
    }
    const candidates = textCandidates(slide, plan, shape.roleHint);
    let value = '';
    for (let offset = 0; offset < candidates.length; offset += 1) {
      const candidate = candidates[(cursor + offset) % Math.max(1, candidates.length)];
      if (candidate
        && candidate.length <= Number(shape.capacityChars || 1)
        && !usedText.has(candidate)
        && !(shape.roleHint === 'body' && isBodyCopyOfNarrative(candidate, slide))) {
        value = candidate;
        cursor += offset + 1;
        break;
      }
    }
    if (!value && candidates.length && shape.roleHint !== 'body') {
      value = fitText(candidates[cursor % candidates.length], shape.capacityChars);
      cursor += 1;
    }
    if (!value || isTemplatePlaceholder(value, shape.text)
      || (shape.roleHint === 'body' && (isBodyCopyOfNarrative(value, slide) || usedText.has(value)))) {
      value = distinctFallback(slide, shape, bodyIndex);
    }
    edits.push({ slotId: String(shape.slotId), text: value });
    mapped.add(String(shape.slotId));
    usedText.add(value);
    if (shape.roleHint === 'body') bodyIndex += 1;
  }
  slide.textEdits = edits;
  return slide;
}

/** Keep model narrative fields consistent with text that fits inherited slots. */
export function fitNarrativeFields(slide, sourceInfo) {
  const shapes = (sourceInfo?.textShapes || [])
    .filter(item => !item.furniture && !removableTemplateShape(item));
  const edits = Array.isArray(slide.textEdits) ? slide.textEdits : [];
  const editBySlot = new Map(edits.map(edit => [String(edit.slotId), edit]));
  const used = new Set();
  const visible = () => edits.map(edit => cleanText(edit.text)).filter(Boolean).join('\n');

  function adaptField(field, roles) {
    const value = cleanText(slide[field]);
    if (!value || isTemplatePlaceholder(value) || visible().includes(value)) {
      if (isTemplatePlaceholder(value)) {
        const replacement = edits
          .map(edit => ({ edit, shape: shapes.find(item => String(item.slotId) === String(edit.slotId)) }))
          .find(item => item.shape?.roleHint === 'title' && !isTemplatePlaceholder(item.edit.text, item.shape.text))
          ?.edit.text
          || (!isTemplatePlaceholder(slide.headline) ? cleanText(slide.headline) : '')
          || cleanText(slide.section);
        if (replacement) slide[field] = replacement;
        return replacement;
      }
      return value;
    }
    const shape = shapes.find(item => roles.includes(item.roleHint) && !used.has(String(item.slotId)))
      || shapes.find(item => !used.has(String(item.slotId)));
    if (!shape) return '';
    const fitted = fitText(value, shape.capacityChars);
    const edit = editBySlot.get(String(shape.slotId));
    if (edit) edit.text = fitted;
    else {
      const created = { slotId: String(shape.slotId), text: fitted };
      edits.push(created);
      editBySlot.set(String(shape.slotId), created);
    }
    used.add(String(shape.slotId));
    slide[field] = fitted;
    return fitted;
  }

  adaptField('title', ['title']);
  adaptField('headline', ['headline', 'title']);
  const bullets = Array.isArray(slide.bullets) ? slide.bullets.map(cleanText).filter(Boolean) : [];
  const fittedBullets = [];
  for (const bullet of bullets) {
    if (visible().includes(bullet)) {
      fittedBullets.push(bullet);
      continue;
    }
    const shape = shapes.find(item => item.roleHint === 'body' && !used.has(String(item.slotId)))
      || shapes.find(item => !used.has(String(item.slotId)));
    if (!shape) continue;
    const fitted = fitText(bullet, shape.capacityChars);
    const edit = editBySlot.get(String(shape.slotId));
    if (edit) edit.text = fitted;
    else {
      const created = { slotId: String(shape.slotId), text: fitted };
      edits.push(created);
      editBySlot.set(String(shape.slotId), created);
    }
    used.add(String(shape.slotId));
    fittedBullets.push(fitted);
  }
  slide.bullets = fittedBullets;
  const fallbackText = edits.map(edit => cleanText(edit.text)).find(Boolean) || '';
  if (slide.title && !visible().includes(cleanText(slide.title))) slide.title = fallbackText;
  if (slide.headline && !visible().includes(cleanText(slide.headline))) slide.headline = fallbackText;
  slide.bullets = slide.bullets.filter(bullet => visible().includes(cleanText(bullet)));
  slide.textEdits = edits;
  return slide;
}

/** Furniture is inherited decoration/numbering, never an Agent edit target. */
export function removeFurnitureTextEdits(slide, sourceInfo) {
  const furniture = new Set((sourceInfo?.textShapes || [])
    .filter(item => item.furniture)
    .map(item => String(item.slotId)));
  if (Array.isArray(slide?.textEdits)) {
    slide.textEdits = slide.textEdits.filter(edit => !furniture.has(String(edit?.slotId || '')));
  }
  return slide;
}

export function isCompatibleSourceRole(slideType, visualRole) {
  const role = String(visualRole || '').toLowerCase();
  if (!role) return true;
  return (ROLE_COMPATIBILITY[String(slideType || 'content').toLowerCase()] || ROLE_COMPATIBILITY.content).has(role);
}

export function requestsVisualAssets(prompt) {
  return /图文并茂|图文结合|配图|网络图片|在线图片|检索图片|搜索图片|图片素材|视觉素材|视觉化|插图|配视觉|illustrat(?:ed|ion)|image[- ]rich|with (?:images|visuals)|visual assets|use images|add images/i
    .test(String(prompt || ''));
}

function imageRelevance(slide, image) {
  const haystack = cleanText([
    slide?.visualTopic, slide?.section, slide?.title, slide?.headline, slide?.imageHint, ...(slide?.bullets || [])
  ].join(' ')).toLowerCase();
  const candidate = cleanText([
    image?.fileName, image?.title, image?.description, image?.bestUse,
    image?.searchQuery, image?.query, image?.keywords
  ].join(' ')).toLowerCase();
  const tokenize = value => {
    const chunks = value.match(/[A-Za-z0-9]+|[\p{Script=Han}]{2,}/gu) || [];
    const output = new Set();
    for (const chunk of chunks) {
      if (/^[\p{Script=Han}]+$/u.test(chunk)) {
        for (let index = 0; index < chunk.length - 1; index += 1) {
          output.add(chunk.slice(index, index + 2));
        }
      } else {
        output.add(chunk);
      }
    }
    return output;
  };
  const tokens = [...tokenize(haystack)];
  const candidateTokens = tokenize(candidate);
  return tokens.reduce((score, token) => score + (
    candidateTokens.has(token)
      || (token.length >= 4 && /^[A-Za-z0-9]+$/u.test(token)
        && [...candidateTokens].some(value => value.includes(token)))
      ? 1 : 0
  ), 0);
}

/**
 * Make image search an actual authoring input without turning it into a
 * carousel of unrelated stock photos. The model may choose a better match;
 * omitted or semantically unrelated web images stay empty.
 */
export function ensureImageEdits(slide, sourceInfo, sourceImages, format = 'pptx', options = {}) {
  const images = (Array.isArray(sourceImages) ? sourceImages : [])
    .filter(item => item?.id && item?.path);
  if (!images.length) return slide;
  const imageById = new Map(images.map(item => [String(item.id), item]));
  const visualSlide = options.visualTopic
    ? { ...slide, visualTopic: `${options.visualTopic} ${slide.visualTopic || ''}` }
    : slide;
  if (format === 'pptx') {
    const slots = (sourceInfo?.imageSlots || []).filter(item => item.fillable === true);
    const preferredSlots = [...slots].sort((left, right) => {
      const priority = slot => slot.fillableReason === 'content-sized-picture-frame' ? 0
        : slot.fillableReason === 'content-hero-opt-in' ? 2 : 1;
      const area = slot => Number(slot.width || 0) * Number(slot.height || 0);
      return priority(left) - priority(right) || area(right) - area(left);
    });
    const preferredSlot = preferredSlots[0];
    const validSlots = new Map(slots.map(item => [String(item.slotId), item]));
    let edits = (Array.isArray(slide.imageEdits) ? slide.imageEdits : [])
      .map(edit => ({ slotId: String(edit?.slotId || ''), imageId: String(edit?.imageId || '') }))
      .filter(edit => validSlots.has(edit.slotId) && imageById.has(edit.imageId))
      .filter(edit => {
        const image = imageById.get(edit.imageId);
        const isWebImage = image?.origin === 'web-search' || image?.sourceUrl;
        return !isWebImage || imageRelevance(visualSlide, image) > 0;
      })
      // One strong image frame per slide is safer than repeating one asset in
      // every inherited picture, especially in collage-style source pages.
      .filter(edit => !preferredSlot || edit.slotId === String(preferredSlot.slotId))
      .slice(0, 1);
    // Do not rotate arbitrary task images through every inherited picture
    // frame. In particular, references/cover/section pages must not receive
    // unrelated photos just because the source page contains a picture.
    const layout = String(slide.layout || '').toLowerCase();
    const type = String(slide.type || '').toLowerCase();
    const staticOnlyPage = ['cover', 'closing', 'references', 'agenda', 'section'].includes(type);
    if (staticOnlyPage) {
      slide.imageEdits = [];
      return slide;
    }
    const imageRequested = options.requireImage === true || options.preferImage === true
      || Boolean(slide.imageId || slide.imageHint)
      || ['image', 'full-image', 'image-left', 'image-right', 'evidence'].includes(layout);
    if (!imageRequested && !edits.length) {
      slide.imageEdits = [];
      return slide;
    }
    const usedSlots = new Set(edits.map(edit => edit.slotId));
    for (const slot of preferredSlots) {
      if (usedSlots.has(String(slot.slotId))) continue;
      const ranked = [...images].sort((left, right) => imageRelevance(slide, right) - imageRelevance(slide, left));
      const relevant = ranked.filter(candidate => imageRelevance(visualSlide, candidate) > 0);
      const image = relevant[Number(options.imageIndex || 0) % Math.max(1, relevant.length)]
        || ranked.find(candidate => candidate.origin !== 'web-search' && !candidate.sourceUrl);
      if (!image) break;
      edits.push({ slotId: String(slot.slotId), imageId: String(image.id) });
      usedSlots.add(String(slot.slotId));
      if (edits.length >= 1) break;
    }
    slide.imageEdits = edits;
    return slide;
  }

  const layout = String(slide.layout || '').toLowerCase();
  const type = String(slide.type || '').toLowerCase();
  const staticOnlyPage = ['cover', 'closing', 'references', 'agenda', 'section'].includes(type);
  if (staticOnlyPage) {
    slide.imageId = '';
    return slide;
  }
  // Planning models commonly reuse the PPTX-shaped imageEdits schema even
  // for HTML. Normalize its first valid image before relevance checking so a
  // downloaded, explicitly selected web visual cannot disappear downstream.
  if (!imageById.has(String(slide.imageId || ''))) {
    const plannedImageId = (Array.isArray(slide.imageEdits) ? slide.imageEdits : [])
      .map(edit => String(edit?.imageId || ''))
      .find(id => imageById.has(id));
    if (plannedImageId) slide.imageId = plannedImageId;
  }
  const selectedImage = imageById.get(String(slide.imageId || ''));
  const selectedWebImage = selectedImage && (selectedImage.origin === 'web-search' || selectedImage.sourceUrl);
  if (selectedWebImage && imageRelevance(visualSlide, selectedImage) <= 0) {
    // A model can select a technically valid but semantically unrelated web
    // result. Uploaded source images remain eligible; web-search images must
    // match the slide topic before entering the self-contained HTML.
    slide.imageId = '';
  }
  const imageRequested = options.requireImage === true || options.preferImage === true
    || Boolean(slide.imageId || slide.imageHint)
    || ['image', 'full-image', 'image-left', 'image-right', 'evidence'].includes(layout);
  if (imageRequested && !slide.imageId) {
    const ranked = [...images].sort((left, right) => imageRelevance(slide, right) - imageRelevance(slide, left));
    const relevantImages = ranked.filter(candidate => imageRelevance(visualSlide, candidate) > 0);
    const relevant = relevantImages[Number(options.imageIndex || 0) % Math.max(1, relevantImages.length)];
    const uploaded = ranked.find(candidate => candidate.origin !== 'web-search' && !candidate.sourceUrl);
    slide.imageId = String((relevant || uploaded)?.id || '');
  }
  return slide;
}

/**
 * A template may have a real, editable visual frame that the planning model
 * simply overlooks. For PPTX, make at least one compatible content page use
 * that inherited frame whenever web/uploaded imagery is available. This keeps
 * the search result connected to the actual template instead of silently
 * producing a text-only deck.
 */
export function ensurePptImageSlide(plan, manifest, sourceImages, options = {}) {
  if (!plan?.slides?.length || !manifest?.slides?.length
    || !Array.isArray(sourceImages) || !sourceImages.some(item => item?.id && item?.path)) {
    return plan;
  }
  const imageIds = new Set(sourceImages.filter(item => item?.id && item?.path).map(item => String(item.id)));
  const hasValidImageEdit = plan.slides.some(slide => {
    const sourceInfo = manifest.slides.find(item => Number(item.slide) === Number(slide.sourceSlide));
    const fillableSlots = new Set((sourceInfo?.imageSlots || [])
      .filter(slot => slot.fillable === true)
      .map(slot => String(slot.slotId)));
    return (slide.imageEdits || []).some(edit =>
      fillableSlots.has(String(edit?.slotId || '')) && imageIds.has(String(edit?.imageId || '')));
  });
  if (hasValidImageEdit) {
    return plan;
  }
  const targets = manifest.slides.filter(item => {
    const role = String(item?.visual?.role || '').toLowerCase();
    return ['content', 'evidence', 'comparison'].includes(role)
      && (item.imageSlots || []).some(slot => slot.fillable === true);
  });
  if (!targets.length) return plan;
  const rankedImages = [...sourceImages].filter(item => item?.id && item?.path);
  for (const target of targets) {
    const imageSlot = (target.imageSlots || []).find(slot => slot.fillable === true);
    const candidates = plan.slides.filter(slide => {
      const type = String(slide?.type || 'content').toLowerCase();
      return ['content', 'evidence', 'comparison'].includes(type)
        && isCompatibleSourceRole(type, target.visual?.role)
        && Number(slide.sourceSlide || 0) !== Number(target.slide);
    });
    for (const candidate of candidates) {
      const visualCandidate = options.visualTopic
        ? { ...candidate, visualTopic: `${options.visualTopic} ${candidate.visualTopic || ''}` }
        : candidate;
      const relevantImages = rankedImages
        .filter(item => imageRelevance(visualCandidate, item) > 0)
        .sort((left, right) => imageRelevance(visualCandidate, right) - imageRelevance(visualCandidate, left));
      const image = relevantImages[Number(options.imageIndex || 0) % Math.max(1, relevantImages.length)];
      if (!imageSlot || !image) continue;
      candidate.sourceSlide = Number(target.slide);
      candidate.textEdits = [];
      candidate.imageEdits = [{ slotId: String(imageSlot.slotId), imageId: String(image.id) }];
      return plan;
    }
  }
  return plan;
}

function explicitSlideNumber(slide) {
  for (const key of ['slideNumber', 'pageNumber', 'page']) {
    const value = Number(slide?.[key]);
    if (Number.isInteger(value) && value >= 1) return value;
  }
  return null;
}

/** Merge either a full plan or a batch-only repair response into the base plan. */
export function mergeRepairBatch(basePlan, candidatePlan, allowedPages, preferredPages = []) {
  if (!candidatePlan || !Array.isArray(candidatePlan.slides) || !candidatePlan.slides.length) {
    throw new Error('Agent 批次返修没有返回 slides');
  }
  const baseSlides = basePlan.slides || [];
  const allowed = [...new Set(allowedPages.map(Number).filter(page => page >= 1 && page <= baseSlides.length))];
  const candidateSlides = candidatePlan.slides;
  const updates = [];
  if (candidateSlides.length === baseSlides.length) {
    candidateSlides.forEach((slide, index) => {
      if (allowed.includes(index + 1)) updates.push([index + 1, slide]);
    });
  } else if (candidateSlides.length <= allowed.length) {
    const explicitPages = candidateSlides.map(explicitSlideNumber);
    const hasExplicitPages = explicitPages.every(Number.isInteger) && new Set(explicitPages).size === explicitPages.length;
    const targetPages = hasExplicitPages
      ? explicitPages
      : (preferredPages.length === candidateSlides.length ? preferredPages : allowed.slice(0, candidateSlides.length));
    if (targetPages.some(page => !allowed.includes(page))) throw new Error('Agent 批次返修改动了未授权页面');
    candidateSlides.forEach((slide, index) => updates.push([targetPages[index], slide]));
  } else {
    throw new Error(`Agent 批次返修返回 ${candidateSlides.length} 页，允许 ${allowed.length} 页或完整 ${baseSlides.length} 页`);
  }
  return {
    ...basePlan,
    slides: baseSlides.map((slide, index) => updates.find(([page]) => page === index + 1)?.[1] || slide)
  };
}

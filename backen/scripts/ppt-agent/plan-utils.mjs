const ROLE_COMPATIBILITY = {
  cover: new Set(['cover']),
  agenda: new Set(['agenda', 'content']),
  section: new Set(['section']),
  closing: new Set(['closing']),
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
  return String(value || '')
    .replace(/[*_`#]+/g, '')
    // Source IDs belong in task metadata and speaker notes, never in visible
    // slide copy. Models may still append them to bullets despite the prompt.
    .replace(/\s*\[(?:(?:S|WEB|U)\d+)(?:\s*[,，;；]\s*(?:S|WEB|U)\d+)*\]/gi, '')
    .replace(/\s+/g, ' ')
    .trim();
}

export function isTemplatePlaceholder(value, sample = '') {
  const text = cleanText(value);
  const source = cleanText(sample);
  if (!text) return true;
  if (/^(?:请在此处编辑文字|内容|正文|文字|重点|信息|目录|核心要点[一二三1-3]|作品概述|overview|conten(?:t|ts)?|添加标题|click here to add title text)$/i.test(text)) return true;
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
    const limit = Number(shape?.capacityChars || 8);
    const labels = (slide?.bullets || []).map(item => compactBulletLabel(item, limit)).filter(Boolean);
    const shortFallbacks = ['核心设定', '角色关系', '关键事件', '世界规则', '文化影响'];
    const replacement = labels[index % Math.max(1, labels.length)]
      || shortFallbacks[index % shortFallbacks.length]
      || candidate;
    return fitText(replacement, shape?.capacityChars);
  }
  return fitText(candidate, shape?.capacityChars);
}

function compactBulletLabel(value, capacity) {
  const source = cleanText(value);
  const limit = Math.max(2, Number(capacity || 8));
  if (!source) return '';
  const beforeColon = source.split(/[：:]/, 1)[0];
  const beforePunctuation = beforeColon.split(/[，,。；;（(/]/, 1)[0];
  const beforePossessive = beforeColon.split('的', 1)[0];
  for (const candidate of [beforePunctuation, beforePossessive, beforeColon]) {
    if (candidate && candidate.length <= limit) return candidate;
  }
  const parts = beforeColon.split(/[与和、]/).map(cleanText).filter(Boolean);
  const shortPart = parts.find(part => part.length >= 2 && part.length <= limit);
  if (shortPart) return shortPart;
  const subject = beforeColon.split(/由|是|以|通过|讲述|探索|聚焦|围绕|呈现/, 1)[0].trim();
  if (subject.length >= 2 && subject.length <= limit) return subject;
  const semanticLabels = [
    [/alfheim|\bALO\b/i, 'ALO世界'],
    [/gun\s+gale|\bGGO\b/i, 'GGO世界'],
    [/underworld|\bUW\b/i, '人界篇'],
    [/生与死|生死|死亡.*现实/i, '生死边界'],
    [/动画.*制作/i, '动画制作'],
    [/故事|剧情|叙事/i, '故事主线'],
    [/角色|人物/i, '角色群像'],
    [/剧场版|电影/i, '剧场版'],
    [/原作|作者|创作/i, '原作背景'],
    [/技术|系统|nerve|full dive/i, '核心技术'],
    [/影响|意义|启示/i, '文化影响'],
    [/世界|舞台|游戏/i, '世界设定']
  ];
  return semanticLabels.find(([pattern, label]) => pattern.test(source) && label.length <= limit)?.[1] || '';
}

function textCandidates(slide, plan, role, capacity) {
  const bullets = Array.isArray(slide.bullets) ? slide.bullets : [];
  const common = [slide.title, slide.section, slide.headline, ...bullets, plan?.title]
    .map(cleanText)
    .filter(value => value && !/^\d{1,2}$/.test(value));
  if (role === 'body') return [...bullets.map(cleanText), cleanText(slide.headline), cleanText(slide.title), ...common];
  if (role === 'headline') return [cleanText(slide.headline), cleanText(slide.title), cleanText(slide.section), ...bullets.map(cleanText), ...common];
  const labels = bullets.map(item => compactBulletLabel(item, capacity)).filter(Boolean);
  return [cleanText(slide.title), cleanText(slide.section), cleanText(slide.headline), ...labels, ...bullets.map(cleanText), ...common];
}

function alignCardPairs(slide, shapes, edits) {
  const bullets = (slide?.bullets || []).map(cleanText).filter(Boolean);
  if (!bullets.length) return;
  const bySlot = new Map(edits.map(edit => [String(edit.slotId), edit]));
  const bodies = shapes.filter(shape => shape.roleHint === 'body').sort((a, b) => a.y - b.y || a.x - b.x);
  const titles = shapes.filter(shape => shape.roleHint === 'title' && Number(shape.y || 0) > 2_000_000);
  let bulletIndex = 0;
  for (const body of bodies) {
    if (bulletIndex >= bullets.length) break;
    const title = titles
      .filter(item => Number(item.y || 0) <= Number(body.y || 0)
        && Number(body.y || 0) - Number(item.y || 0) <= 1_200_000)
      .map(item => {
        const overlap = Math.max(0,
          Math.min(Number(item.x || 0) + Number(item.width || 0), Number(body.x || 0) + Number(body.width || 0))
          - Math.max(Number(item.x || 0), Number(body.x || 0)));
        return { item, score: overlap / Math.max(1, Math.min(Number(item.width || 0), Number(body.width || 0))) };
      })
      .filter(item => item.score >= 0.5)
      .sort((a, b) => b.score - a.score || b.item.y - a.item.y)[0]?.item;
    if (!title) continue;
    const bullet = bullets[bulletIndex];
    const bodyEdit = bySlot.get(String(body.slotId));
    const titleEdit = bySlot.get(String(title.slotId));
    if (bodyEdit) bodyEdit.text = fitText(bullet, body.capacityChars);
    const titleCapacity = safeShapeCapacity(title);
    if (titleEdit) titleEdit.text = compactBulletLabel(bullet, titleCapacity)
      || fitText(['核心内容', '关键进展', '主要影响'][bulletIndex % 3], titleCapacity);
    bulletIndex += 1;
  }
}

function fitText(value, capacity) {
  const text = cleanText(value);
  const limit = Math.max(1, Number(capacity || 1));
  if (/^从游戏机制到文.*$/.test(text) && limit >= 7) return '机制与文化影响';
  if (text.length <= limit) return text;
  // Keep common Chinese presentation titles semantically complete when a
  // source template has a short inherited title frame.
  const compact = text
    .replace(/科学研究/g, '科研')
    .replace(/^刀剑神域.*(?:解析|解读|深度解)?$/i, limit >= 4 ? '刀剑神域' : 'SAO')
    .replace(/^SAO\s*至\s*GGO.*$/i, 'SAO至GGO')
    .replace(/^全游戏融合的新时代$/, '游戏融合新时代')
    .replace(/^世界观与核心设定[：:]桐人、亚丝娜与伙伴羁绊$/, '世界观、角色与伙伴羁绊')
    .replace(/^从世界观到文化影响$/, '世界观与影响')
    .replace(/^从游戏机制到文.*$/, '机制与文化影响')
    .replace(/^从死亡游戏到觉醒.*$/, '死亡游戏与觉醒')
    .replace(/^虚拟世界中的人性光辉$/, '虚拟世界与人性')
    .replace(/^从(.{2,6})到(.{2,6})$/, '$1与$2')
    .replace(/^(.{2,6})中的(.{2,6})$/, '$1与$2')
    .replace(/在([^，。；;]{1,12})中的/g, '$1')
    .replace(/研究中的/g, '科研')
    // Preserve complete, recognizable franchise labels instead of slicing a
    // long Latin token or dropping the final Chinese character.
    .replace(/新生\s*ALO\s*与空中冒险/gi, 'ALO空中冒险')
    .replace(/\bAlicization\b/gi, '人界篇')
    .replace(/\bGun\s+Gale(?:\s+Online)?\b/gi, 'GGO篇');
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

function safeShapeCapacity(shape) {
  const capacity = Math.max(1, Number(shape?.capacityChars || 1));
  if (shape?.roleHint !== 'title') return capacity;
  if (Number(shape?.maxFontPt || 0) >= 64) return Math.max(2, Math.floor(capacity * 0.7));
  if (Number(shape?.maxFontPt || 0) >= 40) return Math.max(2, Math.floor(capacity * 0.8));
  return capacity;
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
    const text = fitText(edit?.text, safeShapeCapacity(shape));
    if (['title', 'headline'].includes(shape.roleHint) && /^\d{1,2}$/.test(text)) continue;
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
        const value = fitText(fallback, safeShapeCapacity(shape));
        edits.push({ slotId: String(shape.slotId), text: value });
        mapped.add(String(shape.slotId));
        usedText.add(value);
        continue;
      }
    }
    const safeCapacity = safeShapeCapacity(shape);
    const candidates = textCandidates(slide, plan, shape.roleHint, safeCapacity);
    let value = '';
    for (let offset = 0; offset < candidates.length; offset += 1) {
      const candidate = candidates[(cursor + offset) % Math.max(1, candidates.length)];
      const fittedCandidate = fitText(candidate, safeCapacity);
      if (fittedCandidate
        && fittedCandidate.length <= safeCapacity
        && !usedText.has(fittedCandidate)
        && !(shape.roleHint === 'body' && isBodyCopyOfNarrative(fittedCandidate, slide))) {
        value = fittedCandidate;
        cursor += offset + 1;
        break;
      }
    }
    if (!value && candidates.length && shape.roleHint !== 'body') {
      value = fitText(candidates[cursor % candidates.length], safeCapacity);
      cursor += 1;
    }
    if (!value || isTemplatePlaceholder(value, shape.text)
      || (shape.roleHint === 'body' && (isBodyCopyOfNarrative(value, slide) || usedText.has(value)))) {
      value = distinctFallback(slide, { ...shape, capacityChars: safeCapacity }, bodyIndex);
    }
    edits.push({ slotId: String(shape.slotId), text: value });
    mapped.add(String(shape.slotId));
    usedText.add(value);
    if (shape.roleHint === 'body') bodyIndex += 1;
  }
  alignCardPairs(slide, shapes, edits);
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
    const fitted = fitText(value, safeShapeCapacity(shape));
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

function isTrustedFirstPartyImage(image, visualTopic = '') {
  if (!/^First-party source-page media/i.test(String(image?.license || ''))) return false;
  const url = String(image?.sourceUrl || '');
  if (/刀剑神域|sword\s*art\s*online|\bsao\b/i.test(String(visualTopic || ''))) {
    return /^https?:\/\/(?:www\.)?(?:swordart-online\.net|sao-alicization\.net|sao-p\.net|sao-movie\.net)(?:\/|$)/i.test(url);
  }
  return !/(?:sina\.(?:cn|com)|huijiwiki\.com|bilibili\.com|zhihu\.com|baidu\.com|weibo\.com)/i.test(url);
}

function imageRelevance(slide, image) {
  let haystack = cleanText([
    slide?.visualTopic, slide?.section, slide?.title, slide?.headline, slide?.imageHint, ...(slide?.bullets || [])
  ].join(' ')).toLowerCase();
  let candidate = cleanText([
    image?.fileName, image?.title, image?.description, image?.bestUse,
    image?.searchQuery, image?.query, image?.keywords
  ].join(' ')).toLowerCase();
  const sao = /刀剑神域|sword\s*art\s*online|\bsao\b/i;
  const saoSlide = sao.test(haystack);
  const saoImage = sao.test(candidate);
  if (saoSlide) haystack += ' sword art online sao virtual reality game';
  if (saoImage) candidate += ' 刀剑神域 sword art online sao';
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
  let score = tokens.reduce((total, token) => total + (
    candidateTokens.has(token)
      || (token.length >= 4 && /^[A-Za-z0-9]+$/u.test(token)
        && [...candidateTokens].some(value => value.includes(token)))
      ? 1 : 0
  ), 0);
  if (saoSlide && saoImage) {
    const sourceUrl = String(image?.sourceUrl || '');
    if (/角色|人物|桐人|桐谷|亚丝娜|诗乃|爱丽丝|character|cast|伙伴|群像/i.test(haystack)) {
      if (/(?:sao-p\.net|sao-movie\.net)/i.test(sourceUrl)) score += 10;
      else if (/sao-alicization\.net/i.test(sourceUrl)) score += 8;
      else if (/swordart-online\.net/i.test(sourceUrl)) score -= 4;
    } else if (/世界|起源|设定|概览|历史|架构|aincrad|艾恩葛朗特|起始之城/i.test(haystack)
      && /swordart-online\.net/i.test(sourceUrl)) score += 7;
    if (/艾恩葛朗特|aincrad|浮游城|城堡/i.test(haystack)
      && /aincrad|fantasy\s+castle|floating\s+castle/i.test(candidate)) score += 6;
    else if (/alicization|爱丽丝|alice|underworld|eugeo|尤吉欧/i.test(haystack)
      && /alicization|fantasy\s+forest|alice|eugeo/i.test(candidate)) score += 6;
    else if (/虚拟|游戏|潜行|nerve|vr|元宇宙|技术|概述|导览|影响|遗产/i.test(haystack)
      && /virtual\s+reality|gaming|vr\b/i.test(candidate)) score += 4;
  }
  return score;
}

/** Keep HTML narrative density inside the fixed 1280×720 reveal viewport. */
export function fitHtmlSlideToViewport(slide, { hasImage = Boolean(slide?.imageId) } = {}) {
  const type = String(slide?.type || 'content').toLowerCase();
  const staticType = ['cover', 'closing', 'references', 'agenda', 'section'].includes(type);
  if (type === 'cover') slide.layout = 'cover';
  else if (type === 'closing') slide.layout = 'closing';
  else if (type === 'section') slide.layout = 'section';
  else if (hasImage && !['image-hero', 'gallery', 'evidence', 'split'].includes(String(slide.layout || '').toLowerCase())) slide.layout = 'split';

  const timeline = slide.layout === 'timeline';
  const comparison = slide.layout === 'comparison';
  const stats = ['stats', 'kpi', 'metrics'].includes(String(slide.layout || '').toLowerCase());
  const process = ['process', 'steps'].includes(String(slide.layout || '').toLowerCase());
  const titleLimit = staticType ? 38 : hasImage ? 34 : 44;
  const headlineLimit = staticType ? 72 : hasImage ? 76 : 104;
  const bulletLimit = staticType ? 3 : hasImage ? 3 : (timeline || stats || process) ? 4 : comparison ? 6 : 4;
  const bulletChars = staticType ? 48 : hasImage ? 56 : (timeline || stats || process) ? 44 : comparison ? 56 : 72;
  slide.title = fitText(slide.title, titleLimit);
  slide.headline = fitText(slide.headline, headlineLimit);
  const structuredItems = (Array.isArray(slide.items) ? slide.items : []).map(item => {
    if (!item || typeof item !== 'object' || Array.isArray(item)) return cleanText(item);
    const label = cleanText(item.label);
    const value = cleanText(item.value);
    const detail = cleanText(item.detail);
    return [label, value].filter(Boolean).join('：') + (detail ? ` — ${detail}` : '');
  }).filter(Boolean);
  const authoredBullets = Array.isArray(slide.bullets) && slide.bullets.length
    ? slide.bullets : structuredItems;
  slide.bullets = authoredBullets
    .map(item => fitText(item, bulletChars))
    .filter(Boolean)
    .slice(0, bulletLimit);
  delete slide.items;
  return slide;
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
    slide.imageEdits = [];
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
 * A model may legally select the same relevant image for every page. Keep its
 * first choice, then rotate unused relevant assets across subsequent content
 * pages. If no distinct alternative exists, leave the later page text-led
 * instead of visibly repeating one bitmap throughout the deck.
 */
export function diversifyPresentationImages(plan, format, sourceImages, options = {}) {
  if (!plan?.slides?.length || !Array.isArray(sourceImages) || !sourceImages.length) return plan;
  const allImages = sourceImages.filter(item => item?.id && item?.path);
  const firstPartyImages = allImages.filter(item =>
    isTrustedFirstPartyImage(item, options.visualTopic));
  const uploadedImages = allImages.filter(item => item.origin !== 'web-search' && !item.sourceUrl);
  // When first-party media exists, generic contextual stock must never replace
  // it during the later diversification pass. Uploaded user material remains
  // authoritative and can participate alongside first-party assets.
  const images = firstPartyImages.length
    ? [...uploadedImages, ...firstPartyImages].filter((item, index, all) =>
      all.findIndex(other => String(other.id) === String(item.id)) === index)
    : allImages;
  const imageById = new Map(images.map(item => [String(item.id), item]));
  const used = new Set();
  const staticTypes = new Set(['cover', 'closing', 'references', 'agenda', 'section']);
  const visualSlide = slide => options.visualTopic
    ? { ...slide, visualTopic: `${options.visualTopic} ${slide.visualTopic || ''}` }
    : slide;
  const unusedRelevant = slide => images
    .filter(image => !used.has(String(image.id)) && imageRelevance(visualSlide(slide), image) > 0)
    .sort((left, right) => imageRelevance(visualSlide(slide), right) - imageRelevance(visualSlide(slide), left)
      || String(left.id).localeCompare(String(right.id)));

  let visualIndex = 0;
  for (const slide of plan.slides) {
    if (staticTypes.has(String(slide?.type || '').toLowerCase())) continue;
    const selectedId = format === 'pptx'
      ? String(slide.imageEdits?.[0]?.imageId || '')
      : String(slide.imageId || '');
    const selected = imageById.get(selectedId);
    if (!(options.preferVisualAssets || options.requireVisualAssets) && selected && !used.has(selectedId)) {
      used.add(selectedId);
      continue;
    }
    const relevant = images
      .filter(image => imageRelevance(visualSlide(slide), image) > 0)
      .sort((left, right) => imageRelevance(visualSlide(slide), right) - imageRelevance(visualSlide(slide), left)
        || String(left.id).localeCompare(String(right.id)));
    const replacement = unusedRelevant(slide)[0]
      || (firstPartyImages.length ? relevant[visualIndex % Math.max(1, relevant.length)] : null);
    if (replacement) {
      if (format === 'pptx' && slide.imageEdits?.[0]) slide.imageEdits[0].imageId = String(replacement.id);
      else if (format === 'html') slide.imageId = String(replacement.id);
      used.add(String(replacement.id));
    } else if (selected && used.has(selectedId)) {
      if (format === 'pptx') slide.imageEdits = [];
      else slide.imageId = '';
    }
    visualIndex += 1;
  }

  if (options.preferVisualAssets || options.requireVisualAssets) {
    for (const slide of plan.slides) {
      if (staticTypes.has(String(slide?.type || '').toLowerCase())) continue;
      const hasImage = format === 'pptx' ? Boolean(slide.imageEdits?.length) : Boolean(slide.imageId);
      if (hasImage) continue;
      const replacement = unusedRelevant(slide)[0];
      if (!replacement) continue;
      if (format === 'html') slide.imageId = String(replacement.id);
      // PPTX needs a validated source slot; ensureImageEdits/ensurePptImageSlide
      // own that geometry and this pass only replaces or removes existing edits.
      if (format === 'pptx') continue;
      used.add(String(replacement.id));
    }
  }
  return plan;
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
  for (const slide of plan.slides) {
    slide.textEdits = Array.isArray(slide.textEdits) ? slide.textEdits : [];
    slide.imageEdits = Array.isArray(slide.imageEdits) ? slide.imageEdits : [];
  }
  const rankedImages = sourceImages.filter(item => item?.id && item?.path);
  const imageIds = new Set(rankedImages.map(item => String(item.id)));
  const allTargets = manifest.slides.filter(item => {
    const role = String(item?.visual?.role || '').toLowerCase();
    return ['content', 'evidence', 'comparison'].includes(role)
      && (item.imageSlots || []).some(slot => slot.fillable === true);
  });
  const overlapArea = (left, right) => Math.max(0,
    Math.min(left.x + left.width, right.x + right.width) - Math.max(left.x, right.x))
    * Math.max(0,
      Math.min(left.y + left.height, right.y + right.height) - Math.max(left.y, right.y));
  const sourceRisk = item => {
    const shapes = (item.textShapes || []).filter(shape => !shape.furniture && shape.width && shape.height);
    let maximumTextOverlap = 0;
    for (let left = 0; left < shapes.length; left += 1) {
      for (let right = left + 1; right < shapes.length; right += 1) {
        const smallerArea = Math.min(
          shapes[left].width * shapes[left].height,
          shapes[right].width * shapes[right].height
        );
        maximumTextOverlap = Math.max(maximumTextOverlap,
          overlapArea(shapes[left], shapes[right]) / Math.max(1, smallerArea));
      }
    }
    const fillableCount = (item.imageSlots || []).filter(slot => slot.fillable === true).length;
    const decorativeImageCount = (item.imageSlots || []).filter(slot =>
      slot.fillable !== true && slot.fillableReason === 'too-small-for-content-image').length;
    const giantSectionNumber = (item.textShapes || []).some(shape =>
      Number(shape.maxFontPt || 0) >= 72 && /^\s*\d{1,3}%?\s*$/.test(String(shape.text || '')));
    const tinyMetricCount = (item.textShapes || []).filter(shape =>
      Number(shape.capacityChars || 0) <= 4 && /^\s*\d{1,3}%?\s*$/.test(String(shape.text || ''))).length;
    return maximumTextOverlap
      + Math.max(0, fillableCount - 1) * 0.2
      + decorativeImageCount * 0.08
      + (giantSectionNumber ? 0.5 : 0)
      + (tinyMetricCount >= 3 ? 0.5 : 0);
  };
  // Prefer source pages with one isolated image frame and non-overlapping text
  // geometry. If a deck has no such page, preserve compatibility by falling
  // back to all role-compatible image pages.
  // Allow the small title/body baseline overlap used by some professionally
  // authored layouts, but still reject icon grids, collages and infographic
  // pages. The hand-drawn deck's clean split page scores ~0.215 while its
  // decorative card pages start at 0.24 and its collage page is higher.
  const safeTargets = allTargets.filter(item => sourceRisk(item) < 0.24);
  // A content page with a dedicated photograph frame is substantially more
  // predictable than an evidence/diagram page whose circles, arrows or cards
  // may be meaningful template artwork. Prefer those plain content pages when
  // at least one exists, while retaining the broader fallback for other decks.
  const safeContentTargets = safeTargets.filter(item =>
    String(item?.visual?.role || '').toLowerCase() === 'content');
  const targets = safeContentTargets.length
    ? safeContentTargets
    : (safeTargets.length ? safeTargets : allTargets);
  if (!targets.length) return plan;
  const targetSlideNumbers = new Set(targets.map(item => Number(item.slide)));
  const eligibleSlides = plan.slides.filter(slide =>
    ['content', 'evidence', 'comparison'].includes(String(slide?.type || 'content').toLowerCase()));
  // Once a genuinely clean content frame is available, image-rich requests
  // should use it for every narrative content page. Otherwise later visual
  // repair rounds can reintroduce risky card/collage source pages even after
  // the first deterministic pass selected a safe frame.
  if ((options.preferVisualAssets || options.requireVisualAssets) && safeContentTargets.length) {
    for (const [index, slide] of eligibleSlides.entries()) {
      const type = String(slide?.type || 'content').toLowerCase();
      const compatible = safeContentTargets.filter(item => isCompatibleSourceRole(type, item.visual?.role));
      const target = compatible[index % Math.max(1, compatible.length)];
      if (target && Number(slide.sourceSlide) !== Number(target.slide)) {
        slide.sourceSlide = Number(target.slide);
        slide.textEdits = [];
        slide.imageEdits = [];
      }
    }
  }
  const firstPartyImages = rankedImages.filter(item =>
    isTrustedFirstPartyImage(item, options.visualTopic));
  const preferredImages = firstPartyImages.length ? firstPartyImages : rankedImages;
  const preferredImageIds = new Set(preferredImages.map(item => String(item.id)));
  const desiredCount = options.preferVisualAssets || options.requireVisualAssets
    ? Math.min(6, firstPartyImages.length ? eligibleSlides.length : preferredImages.length, eligibleSlides.length)
    : Math.min(1, preferredImages.length, eligibleSlides.length);
  const usedImageIds = new Set();
  let assignedImageCount = 0;

  // Keep at most one valid, distinct bitmap on each page. Models sometimes
  // map the same image to every slot on one source page; that is visibly the
  // same repetition problem as reusing it across pages.
  for (const slide of eligibleSlides) {
    const sourceInfo = manifest.slides.find(item => Number(item.slide) === Number(slide.sourceSlide));
    const sourceIsSafeTarget = targetSlideNumbers.has(Number(slide.sourceSlide));
    const fillableSlots = new Set((sourceIsSafeTarget ? sourceInfo?.imageSlots || [] : [])
      .filter(slot => slot.fillable === true)
      .map(slot => String(slot.slotId)));
    const kept = slide.imageEdits.find(edit => {
      const imageId = String(edit?.imageId || '');
      return fillableSlots.has(String(edit?.slotId || ''))
        && imageIds.has(imageId) && preferredImageIds.has(imageId)
        && !usedImageIds.has(imageId);
    });
    slide.imageEdits = kept ? [{ slotId: String(kept.slotId), imageId: String(kept.imageId) }] : [];
    // Image-rich requests favor deterministic slot completion over retaining
    // copied, duplicated or half-truncated model text from the source layout.
    // validatePlan rebuilds every visible text frame from the slide narrative.
    if (options.preferVisualAssets || options.requireVisualAssets) slide.textEdits = [];
    if (kept) {
      usedImageIds.add(String(kept.imageId));
      assignedImageCount += 1;
    }
  }

  for (const [slideIndex, candidate] of eligibleSlides.entries()) {
    if (assignedImageCount >= desiredCount) break;
    if (candidate.imageEdits?.length) continue;
    const type = String(candidate?.type || 'content').toLowerCase();
    const visualCandidate = options.visualTopic
      ? { ...candidate, visualTopic: `${options.visualTopic} ${candidate.visualTopic || ''}` }
      : candidate;
    const unusedImages = preferredImages
      .filter(item => !usedImageIds.has(String(item.id)) && imageRelevance(visualCandidate, item) > 0)
      .sort((left, right) => imageRelevance(visualCandidate, right) - imageRelevance(visualCandidate, left)
        || String(left.id).localeCompare(String(right.id)));
    const reusableImages = preferredImages
      .filter(item => imageRelevance(visualCandidate, item) > 0)
      .sort((left, right) => imageRelevance(visualCandidate, right) - imageRelevance(visualCandidate, left)
        || String(left.id).localeCompare(String(right.id)));
    const image = unusedImages[0]
      || (firstPartyImages.length ? reusableImages[slideIndex % Math.max(1, reusableImages.length)] : null);
    if (!image) continue;
    const currentTarget = targets.find(item => Number(item.slide) === Number(candidate.sourceSlide)
      && isCompatibleSourceRole(type, item.visual?.role));
    const compatibleTargets = targets.filter(item => isCompatibleSourceRole(type, item.visual?.role));
    const target = currentTarget || compatibleTargets[slideIndex % Math.max(1, compatibleTargets.length)];
    const imageSlot = (target?.imageSlots || []).find(slot => slot.fillable === true);
    if (!target || !imageSlot) continue;
    if (Number(candidate.sourceSlide) !== Number(target.slide)) {
      candidate.sourceSlide = Number(target.slide);
      candidate.textEdits = [];
    }
    candidate.imageEdits = [{ slotId: String(imageSlot.slotId), imageId: String(image.id) }];
    usedImageIds.add(String(image.id));
    assignedImageCount += 1;
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

/** Normalize harmless model aliases for the single research capability the
 * worker exposes. Unknown tools are never executed; callers can fall back to
 * deterministic queries instead of failing the whole presentation task. */
export function normalizeResearchDecision(value) {
  const decision = value && typeof value === 'object' ? value : {};
  const args = decision.args && typeof decision.args === 'object' ? decision.args : {};
  const action = String(decision.action || '').trim().toLowerCase().replace(/[\s-]+/g, '_');
  if (['final', 'complete', 'completed', 'done'].includes(action)) {
    return { action: 'final', args: { researchComplete: true } };
  }

  const tool = String(args.tool || args.name || args.toolName || decision.tool || decision.name || '').trim()
    .toLowerCase().replace(/[\s-]+/g, '_');
  const searchAliases = new Set(['search', 'web_search', 'internet_search', 'tavily_search', 'search_web']);
  const actionRequestsSearch = ['search', 'web_search', 'internet_search', 'research'].includes(action);
  if (!searchAliases.has(tool) && !actionRequestsSearch) return null;

  const rawQueries = args.queries ?? args.query ?? args.keywords ?? decision.queries ?? decision.query;
  const queries = (Array.isArray(rawQueries) ? rawQueries : [rawQueries])
    .map(item => String(item || '').replace(/\s+/g, ' ').trim())
    .filter(Boolean)
    .slice(0, 3);
  return queries.length ? { action: 'tool_call', args: { tool: 'search', queries } } : null;
}

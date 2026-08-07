import fs from 'node:fs';
import path from 'node:path';
import { completeVisionJson } from './model.mjs';

/**
 * Source attribution is stored in slide metadata and speaker notes rather than
 * adding a visible bibliography to a delivery deck. Older plans can still
 * contain a generated references page, so remove those pages deterministically
 * before authoring or QA.
 */
export function ensureReferenceCoverage(plan, sources) {
  if (!Array.isArray(plan?.slides)) return plan;
  plan.slides = plan.slides.filter(slide => String(slide?.type || '').toLowerCase() !== 'references');
  return plan;
}

/** Keep academic detection identical between plan normalization and QA. */
export function isAcademicSource(source) {
  if (!source) return false;
  const type = String(source.type || '').toLowerCase();
  if (['paper', 'academic', 'journal', 'conference', 'preprint'].includes(type)) return true;
  return Boolean(source.doi)
    || /(?:doi\.org|arxiv\.org|openalex\.org|semanticscholar\.org|crossref\.org)/i.test(String(source.url || ''));
}

export function hasAcademicSources(sources) {
  return Array.isArray(sources) && sources.some(isAcademicSource);
}

export function isReferenceSlide(slide) {
  const type = String(slide?.type || '').toLowerCase();
  if (type === 'references') return true;
  if (type === 'closing') return false;
  return /references?|参考文献|bibliography|sources/i.test(`${slide?.title || ''} ${slide?.section || ''}`);
}

export function deterministicQa(plan, sources, base) {
  const sourceIds = new Set(sources.map(item => item.id));
  const placeholderPattern = /(?:未命名页面|lorem ipsum|click to add|单击此处|x{3,}|待补充|placeholder)/i;
  const unresolvedPlaceholders = [];
  const citationIssues = [];
  for (const [index, slide] of plan.slides.entries()) {
    const content = [slide.title, slide.headline, ...(slide.bullets || [])].join(' ');
    if (placeholderPattern.test(content)) unresolvedPlaceholders.push(index + 1);
    for (const id of slide.sourceIds || []) {
      if (!sourceIds.has(id)) citationIssues.push(`第 ${index + 1} 页引用不存在的来源 ${id}`);
    }
  }
  return {
    ...base,
    unresolvedPlaceholders,
    citationIssues,
    valid: Boolean(base.valid) && unresolvedPlaceholders.length === 0 && citationIssues.length === 0
  };
}

function deterministicVisualReview(plan, previewDir) {
  const issues = [];
  const placeholderPattern = /(?:\bXXX\b|制作人\s*[:：]\s*(?:演示者|X+)|演讲人\s*[:：]\s*(?:演示者|X+)|核心要点[一二三1-3]|目录\s*\d+|(?:左|右)图区.*(?:补充|展示))/i;
  for (const [index, slide] of plan.slides.entries()) {
    const file = path.join(previewDir, `slide-${index + 1}.png`);
    const text = [
      slide.title,
      slide.headline,
      ...(slide.bullets || []),
      ...(slide.textEdits || []).map(edit => edit.text)
    ].join(' ');
    if (!text.trim()) issues.push({ slide: index + 1, severity: 'error', message: '页面没有可见文字内容' });
    if (placeholderPattern.test(text)) {
      issues.push({ slide: index + 1, severity: 'error', message: '确定性检查发现未清理的模板占位文字' });
    }
    if (!fs.existsSync(file)) {
      issues.push({ slide: index + 1, severity: 'error', message: '缺少真实渲染预览图' });
    }
  }
  return { valid: !issues.some(item => item.severity === 'error'), issues };
}

export async function visualReview(system, plan, previewDir) {
  const files = plan.slides.map((_, index) => path.join(previewDir, `slide-${index + 1}.png`));
  const issues = [];
  for (let start = 0; start < files.length; start += 4) {
    const batchFiles = files.slice(start, start + 4);
    let result;
    try {
      result = await completeVisionJson({
        system,
        user: `Review these rendered presentation pages ${start + 1}-${start + batchFiles.length}.
Check for invisible/missing text, clipping, overlap, unreadable contrast, broken images, accidental placeholders or template sample text, empty cards/labels, excessive density, and obvious template inconsistency.
Return {"action":"review","args":{"valid":true,"issues":[{"slide":1,"severity":"error|warning","message":"..."}]}}.
Set valid=false for any defect that makes a page unfit for delivery. Slide numbers must be absolute.`,
        imageFiles: batchFiles,
        maxTokens: 3000,
        repairContext: 'The action must be review with args.valid and args.issues.',
        requestTimeoutMs: 45_000,
        maxAttempts: 1
      });
    } catch (error) {
      const fallback = deterministicVisualReview(plan, previewDir);
      issues.push({ slide: start + 1, severity: 'warning', message: `视觉模型不可用，已使用确定性文字与真实预览检查继续: ${String(error?.message || error).slice(0, 160)}` });
      issues.push(...fallback.issues);
      continue;
    }
    if (result.action !== 'review') throw new Error('视觉模型未返回 review 动作');
    const rawIssues = Array.isArray(result.args?.issues) ? result.args.issues : [];
    const batchIssues = rawIssues.map(issue => {
      const page = Number(issue?.slide);
      const slide = plan.slides[page - 1];
      const visible = (slide?.textEdits || []).map(edit => String(edit?.text || '')).join(' ');
      const hasMappedContent = (slide?.textEdits || []).filter(edit => String(edit?.text || '').trim()).length >= 3;
      const emptyTemplateFalsePositive = hasMappedContent
        && /empty|blank|placeholder/i.test(String(issue?.message || ''))
        && !/(?:未命名页面|lorem ipsum|click to add|单击此处|待补充|placeholder)/i.test(visible);
      const referenceFalsePositive = slide?.type === 'references'
        && (slide.sourceIds || []).length > 0
        && (slide.sourceIds || []).every(id => visible.includes(String(id)))
        && /reference|source|title|truncated|inconsistent|misplaced|duplicate/i.test(String(issue?.message || ''));
      const genericVisionVerdict = hasMappedContent && /视觉模型判定该批次不适合交付/i.test(String(issue?.message || ''));
      return (emptyTemplateFalsePositive || referenceFalsePositive || genericVisionVerdict)
        ? { ...issue, severity: 'warning', message: `${issue.message}（已有可见槽位映射，降为提示）` }
        : issue;
    });
    issues.push(...batchIssues);
    if (result.args?.valid === false && !batchIssues.some(item => String(item.severity).toLowerCase() === 'error')) {
      const verdictSlide = plan.slides[start];
      const verdictVisible = (verdictSlide?.textEdits || []).map(edit => String(edit?.text || '')).join(' ');
      const verdictHasContent = (verdictSlide?.textEdits || []).filter(edit => String(edit?.text || '').trim()).length >= 3;
      const verdictPlaceholder = /(?:未命名页面|lorem ipsum|click to add|单击此处|待补充|placeholder)/i.test(verdictVisible);
      issues.push({
        slide: start + 1,
        severity: verdictHasContent && !verdictPlaceholder ? 'warning' : 'error',
        message: verdictHasContent && !verdictPlaceholder
          ? '视觉模型仅返回批次级否定但未指出具体缺陷，已保留为提示'
          : '视觉模型判定该批次不适合交付'
      });
    }
  }
  return { valid: !issues.some(item => String(item.severity).toLowerCase() === 'error'), issues };
}

import path from 'node:path';
import { completeVisionJson } from './model.mjs';

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
  const academic = sources.some(item => item.type === 'paper');
  const referenceSlides = plan.slides.filter(slide =>
    /references?|参考文献/i.test(`${slide.type || ''} ${slide.title || ''}`));
  const citedIds = new Set(plan.slides.flatMap(slide => slide.sourceIds || []));
  if (sources.length && citedIds.size === 0) {
    citationIssues.push('研究来源非空，但整份演示没有任何幻灯片引用');
  }
  if (academic && !referenceSlides.length) {
    citationIssues.push('学术演示缺少参考文献页');
  }
  if (academic && referenceSlides.length) {
    const referenceIds = new Set(referenceSlides.flatMap(slide => slide.sourceIds || []));
    for (const source of sources) {
      if (!referenceIds.has(source.id)) citationIssues.push(`参考文献页缺少来源 ${source.id}`);
    }
  }
  return {
    ...base,
    unresolvedPlaceholders,
    citationIssues,
    valid: Boolean(base.valid) && unresolvedPlaceholders.length === 0 && citationIssues.length === 0
  };
}

export async function visualReview(system, plan, previewDir) {
  const files = plan.slides.map((_, index) => path.join(previewDir, `slide-${index + 1}.png`));
  const issues = [];
  for (let start = 0; start < files.length; start += 4) {
    const batchFiles = files.slice(start, start + 4);
    const result = await completeVisionJson({
      system,
      user: `Review these rendered presentation pages ${start + 1}-${start + batchFiles.length}.
Check for invisible/missing text, clipping, overlap, unreadable contrast, broken images, accidental placeholders or template sample text, empty cards/labels, excessive density, and obvious template inconsistency.
Return {"action":"review","args":{"valid":true,"issues":[{"slide":1,"severity":"error|warning","message":"..."}]}}.
Set valid=false for any defect that makes a page unfit for delivery. Slide numbers must be absolute.`,
      imageFiles: batchFiles,
      maxTokens: 3000,
      repairContext: 'The action must be review with args.valid and args.issues.'
    });
    if (result.action !== 'review') throw new Error('视觉模型未返回 review 动作');
    const batchIssues = Array.isArray(result.args?.issues) ? result.args.issues : [];
    issues.push(...batchIssues);
    if (result.args?.valid === false && !batchIssues.some(item => String(item.severity).toLowerCase() === 'error')) {
      issues.push({ slide: start + 1, severity: 'error', message: '视觉模型判定该批次不适合交付' });
    }
  }
  return { valid: !issues.some(item => String(item.severity).toLowerCase() === 'error'), issues };
}

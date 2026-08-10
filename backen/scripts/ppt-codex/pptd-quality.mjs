import fs from 'node:fs/promises';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import JSZip from 'jszip';
import YAML from 'yaml';

const FALLBACK_CANVAS = [960, 540];
const MAX_SAFE_AUTOFIT_HEIGHT_DELTA = 32;
const FORBIDDEN_TEMPLATE_COPY = [
  /\blink\s*start!?\b/i,
  /\bclick\s+(?:here\s+)?to\s+add\b/i,
  /\blorem\s+ipsum\b/i,
  /\bplaceholder\b/i,
  /^\s*full\s*$/i,
  /^\s*第\s*\d{1,2}\s*页\s*$/,
  /^(?:references?|bibliography|参考文献)\s*$/i
];

function cleanRichText(value) {
  return String(value || '')
    .replace(/<\/?p\b[^>]*>/gi, '\n')
    .replace(/<br\s*\/?>/gi, '\n')
    .replace(/<[^>]+>/g, '')
    .replace(/&nbsp;/gi, ' ')
    .replace(/&amp;/gi, '&')
    .replace(/&lt;/gi, '<')
    .replace(/&gt;/gi, '>')
    .replace(/\r/g, '')
    .replace(/[ \t]+/g, ' ')
    .trim();
}

function numericBounds(value, pagePath) {
  if (!Array.isArray(value) || value.length !== 4 || value.some(item => !Number.isFinite(Number(item)))) {
    throw new Error(`${pagePath}: text element is missing a valid [x, y, width, height] bounds array`);
  }
  const bounds = value.map(Number);
  if (bounds[2] <= 0 || bounds[3] <= 0) throw new Error(`${pagePath}: text element has non-positive bounds`);
  return bounds;
}

function requestedFont(value) {
  const font = String(value || 'Microsoft YaHei').trim();
  return /^[\p{L}\p{N} ._-]{1,80}$/u.test(font) ? font : 'Microsoft YaHei';
}

function installedFont(family) {
  try {
    return execFileSync('fc-match', ['--format=%{family}', family], {
      encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'], timeout: 1500
    }).split(',')[0].trim();
  } catch {
    return '';
  }
}

function effectiveCjkFont(value) {
  const requested = requestedFont(value);
  const resolved = installedFont(requested);
  if (resolved && resolved.toLocaleLowerCase() === requested.toLocaleLowerCase()) return requested;
  for (const candidate of ['Noto Sans CJK SC', 'PingFang SC', 'Source Han Sans SC', 'WenQuanYi Zen Hei']) {
    const available = installedFont(candidate);
    if (available && /noto|pingfang|source han|wenquanyi/i.test(available)) return available;
  }
  // A Windows production host can render Microsoft YaHei without fontconfig;
  // retain the requested family there rather than silently choosing Latin UI text.
  return requested;
}

function textCapacity(text, content, bounds) {
  if (!text) return null;
  const fontSize = Math.max(8, Number(content?.fontSize || 18));
  const lineHeight = Math.max(fontSize, Number(content?.lineHeightPx || fontSize * Number(content?.lineHeight || 1.22)));
  // Numerals and comparison punctuation occupy materially less width than CJK
  // glyphs. Treating a compact metric as all-CJK falsely rejects common cards
  // such as "91.5% | 15.16%" even when the exporter renders it on one line.
  const compactMetric = /^[\d\s.,:%‰+\-–—|｜/()]+$/.test(text);
  const charsPerLine = Math.max(2, Math.floor(bounds[2] / (fontSize * (compactMetric ? 0.55 : 0.86))));
  const availableLines = Math.max(1, Math.floor(bounds[3] / lineHeight));
  const requiredLines = text.split('\n').reduce((total, line) =>
    total + Math.max(1, Math.ceil(Math.max(1, line.trim().length) / charsPerLine)), 0);
  return { fontSize, lineHeight, requiredLines, availableLines, compactMetric,
    issue: content?.wrap === false && requiredLines > 1 ? 'single-line text does not fit its width'
      : requiredLines > availableLines ? `estimated ${requiredLines} lines exceed the ${availableLines}-line text box capacity` : null };
}

function intersects(left, right) {
  return left[0] < right[0] + right[2] && left[0] + left[2] > right[0]
    && left[1] < right[1] + right[3] && left[1] + left[3] > right[1];
}

function safelyAutofitHeight(page, index, element, capacity, canvas) {
  if (!capacity?.issue || capacity.compactMetric || element.content?.wrap === false) return false;
  const bounds = element.bounds;
  const needed = Math.ceil(capacity.requiredLines * capacity.lineHeight);
  if (needed <= bounds[3] || needed - bounds[3] > MAX_SAFE_AUTOFIT_HEIGHT_DELTA || bounds[1] + needed > canvas[1]) return false;
  const expanded = [bounds[0], bounds[1], bounds[2], needed];
  const collides = page.elements.some((other, otherIndex) => {
    if (otherIndex === index || !Array.isArray(other?.bounds) || other.bounds.length !== 4) return false;
    const candidate = other.bounds.map(Number);
    return candidate.every(Number.isFinite) && intersects(expanded, candidate);
  });
  if (collides) return false;
  element.bounds[3] = needed;
  return true;
}

function applyFontAndCheckPage(page, pagePath, canvas, font) {
  if (!page || !Array.isArray(page.elements)) throw new Error(`${pagePath}: page elements are missing`);
  const issues = [];
  for (const [index, element] of page.elements.entries()) {
    if (element?.elementType === 'image' && /(?:^|\/)paper-page-\d+\.(?:png|jpe?g)$/i.test(String(element.src || ''))) {
      issues.push(`${pagePath} element ${index + 1}: uses a full PDF page as a slide image; crop the source figure or redraw the relationship instead`);
    }
    if (!element || element.elementType !== 'text') continue;
    if (!element.content || typeof element.content !== 'object') {
      issues.push(`${pagePath} element ${index + 1}: text content is missing`);
      continue;
    }
    // The service exposes a single font selection. Apply it at the PPTD text
    // layer so exported files do not quietly fall back to MiSans or a host
    // dependent default. Inline rich-text markup remains untouched.
    element.content.fontFamily = font;
    const text = cleanRichText(element.content.text);
    if (!text) {
      issues.push(`${pagePath} element ${index + 1}: visible text is empty`);
      continue;
    }
    if (FORBIDDEN_TEMPLATE_COPY.some(pattern => pattern.test(text))) {
      issues.push(`${pagePath} element ${index + 1}: contains template residue “${text.slice(0, 48)}”`);
    }
    let bounds;
    try { bounds = numericBounds(element.bounds, pagePath); } catch (error) { issues.push(error.message); continue; }
    const [x, y, width, height] = bounds;
    if (x < 0 || y < 0 || x + width > canvas[0] || y + height > canvas[1]) {
      issues.push(`${pagePath} element ${index + 1}: text bounds escape the ${canvas[0]}×${canvas[1]} canvas`);
    }
    let capacity = textCapacity(text, element.content, bounds);
    if (safelyAutofitHeight(page, index, element, capacity, canvas)) {
      bounds = element.bounds.map(Number);
      capacity = textCapacity(text, element.content, bounds);
    }
    if (capacity?.issue) issues.push(`${pagePath} element ${index + 1}: ${capacity.issue}`);
  }
  return issues;
}

/** Validates visible PPTD text before export and normalizes it to the selected service font. */
export async function preparePptdQuality({ projectDir, manifestFile, pages, fontFamily }) {
  const manifest = YAML.parse(await fs.readFile(manifestFile, 'utf8'));
  const size = Array.isArray(manifest?.size) && manifest.size.length === 2
    && manifest.size.every(value => Number.isFinite(Number(value)) && Number(value) > 0)
    ? manifest.size.map(Number) : FALLBACK_CANVAS;
  const font = effectiveCjkFont(fontFamily);
  const issues = [];
  for (const relative of pages) {
    const pagePath = path.resolve(projectDir, relative);
    const page = YAML.parse(await fs.readFile(pagePath, 'utf8'));
    issues.push(...applyFontAndCheckPage(page, relative, size, font));
    await fs.writeFile(pagePath, YAML.stringify(page), 'utf8');
  }
  if (issues.length) throw new Error(`PPTD visual preflight failed:\n- ${issues.slice(0, 12).join('\n- ')}`);
  return { canvas: size, font };
}

function xmlAttribute(fragment, name) {
  const match = fragment.match(new RegExp(`\\b${name}="(-?\\d+)"`));
  return match ? Number(match[1]) : null;
}

/** Catch exported text frames outside the PowerPoint canvas. Images and decorative shapes may intentionally bleed, but text must never do so. */
export async function assertPptxTextFrameBounds(pptx) {
  const zip = await JSZip.loadAsync(await fs.readFile(pptx));
  const presentation = await zip.file('ppt/presentation.xml')?.async('string');
  const sizeTag = presentation?.match(/<p:sldSz\b[^>]*>/)?.[0] || '';
  const width = xmlAttribute(sizeTag, 'cx');
  const height = xmlAttribute(sizeTag, 'cy');
  if (!width || !height) throw new Error('PPTX presentation canvas is missing');
  const slideFiles = Object.keys(zip.files)
    .filter(name => /^ppt\/slides\/slide\d+\.xml$/.test(name))
    .sort((left, right) => Number(left.match(/\d+/)?.[0]) - Number(right.match(/\d+/)?.[0]));
  const issues = [];
  for (const file of slideFiles) {
    const xml = await zip.file(file).async('string');
    const shapes = xml.match(/<p:sp\b[\s\S]*?<\/p:sp>/g) || [];
    for (const [shapeIndex, shape] of shapes.entries()) {
      if (!/<a:txBody\b/.test(shape) || !/<a:t>[^<\s]/.test(shape)) continue;
      const transform = shape.match(/<a:xfrm\b[\s\S]*?<\/a:xfrm>/)?.[0] || '';
      const off = transform.match(/<a:off\b[^>]*\/>/)?.[0] || '';
      const ext = transform.match(/<a:ext\b[^>]*\/>/)?.[0] || '';
      const x = xmlAttribute(off, 'x'); const y = xmlAttribute(off, 'y');
      const cx = xmlAttribute(ext, 'cx'); const cy = xmlAttribute(ext, 'cy');
      if ([x, y, cx, cy].some(value => value === null)) continue;
      if (x < 0 || y < 0 || x + cx > width || y + cy > height) {
        issues.push(`${file} text shape ${shapeIndex + 1} escapes the slide canvas`);
      }
    }
  }
  if (issues.length) throw new Error(`PPTX text-frame boundary check failed:\n- ${issues.slice(0, 12).join('\n- ')}`);
}

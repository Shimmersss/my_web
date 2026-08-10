import fs from 'node:fs/promises';
import path from 'node:path';
import JSZip from 'jszip';
import YAML from 'yaml';

const FALLBACK_CANVAS = [960, 540];
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

function textCapacityIssue(text, content, bounds) {
  if (!text) return null;
  const fontSize = Math.max(8, Number(content?.fontSize || 18));
  const lineHeight = Math.max(fontSize, Number(content?.lineHeightPx || fontSize * Number(content?.lineHeight || 1.22)));
  const charsPerLine = Math.max(2, Math.floor(bounds[2] / (fontSize * 0.86)));
  const availableLines = Math.max(1, Math.floor(bounds[3] / lineHeight));
  const requiredLines = text.split('\n').reduce((total, line) =>
    total + Math.max(1, Math.ceil(Math.max(1, line.trim().length) / charsPerLine)), 0);
  if (content?.wrap === false && requiredLines > 1) return 'single-line text does not fit its width';
  if (requiredLines > availableLines) return `estimated ${requiredLines} lines exceed the ${availableLines}-line text box capacity`;
  return null;
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
    const capacityIssue = textCapacityIssue(text, element.content, bounds);
    if (capacityIssue) issues.push(`${pagePath} element ${index + 1}: ${capacityIssue}`);
  }
  return issues;
}

/** Validates visible PPTD text before export and normalizes it to the selected service font. */
export async function preparePptdQuality({ projectDir, manifestFile, pages, fontFamily }) {
  const manifest = YAML.parse(await fs.readFile(manifestFile, 'utf8'));
  const size = Array.isArray(manifest?.size) && manifest.size.length === 2
    && manifest.size.every(value => Number.isFinite(Number(value)) && Number(value) > 0)
    ? manifest.size.map(Number) : FALLBACK_CANVAS;
  const font = requestedFont(fontFamily);
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

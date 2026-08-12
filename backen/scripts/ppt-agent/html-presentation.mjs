import fs from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const AGENT_BACKEN_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const LAYOUTS = new Set([
  'cover', 'section', 'statement', 'image-hero', 'split', 'evidence', 'stats',
  'process', 'comparison', 'timeline', 'quote', 'gallery', 'closing'
]);
const LAYOUT_ALIASES = new Map([
  ['agenda', 'process'], ['content', 'statement'], ['hero', 'image-hero'],
  ['image', 'image-hero'], ['imagehero', 'image-hero'], ['kpi', 'stats'],
  ['metrics', 'stats'], ['steps', 'process'], ['references', 'evidence']
]);
const MOTION_MODES = new Set(['auto', 'subtle', 'expressive', 'off']);

function esc(value) {
  return String(value || '').replace(/[&<>"']/g, char => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[char]);
}

function safeText(value) {
  return String(value || '').replace(/\s+/g, ' ').trim();
}

function canonicalLayout(value) {
  const normalized = safeText(value).toLowerCase().replace(/_/g, '-');
  return LAYOUT_ALIASES.get(normalized) || (LAYOUTS.has(normalized) ? normalized : '');
}

function resolveLayout(slide, index, allowedLayouts, hasImage) {
  const type = safeText(slide?.type).toLowerCase();
  const typeLayout = type === 'cover' ? 'cover'
    : type === 'closing' ? 'closing'
      : type === 'section' ? 'section'
        : canonicalLayout(type);
  let requested = canonicalLayout(slide?.layout) || typeLayout || (index % 3 === 0 ? 'split' : 'statement');
  if (requested === 'split' && !hasImage) requested = 'statement';
  if (['image-hero', 'gallery'].includes(requested) && !hasImage) requested = 'statement';
  if (allowedLayouts.has(requested)) return requested;
  if (type === 'cover' && allowedLayouts.has('cover')) return 'cover';
  if (type === 'closing' && allowedLayouts.has('closing')) return 'closing';
  if (type === 'section' && allowedLayouts.has('section')) return 'section';
  if (hasImage && allowedLayouts.has('split')) return 'split';
  if (allowedLayouts.has('statement')) return 'statement';
  return [...allowedLayouts][0] || 'statement';
}

function resolveTone(slide, index, layout) {
  const requested = safeText(slide?.tone).toLowerCase();
  if (['base', 'light', 'deep'].includes(requested)) return requested;
  if (['cover', 'closing', 'image-hero'].includes(layout)) return 'deep';
  if (['section', 'quote'].includes(layout)) return 'light';
  return index % 3 === 2 ? 'light' : 'base';
}

function resolveMotionPreset(layout, motionMode) {
  if (motionMode === 'off') return 'none';
  if (motionMode === 'subtle') return 'calm';
  if (motionMode === 'expressive') {
    if (['cover', 'image-hero', 'gallery'].includes(layout)) return 'cinematic';
    if (['process', 'timeline'].includes(layout)) return 'flow';
    return 'focus';
  }
  if (['cover', 'image-hero', 'gallery'].includes(layout)) return 'cinematic';
  if (['process', 'timeline'].includes(layout)) return 'flow';
  if (['section', 'statement', 'quote', 'closing'].includes(layout)) return 'focus';
  return 'editorial';
}

function fragmentAttributes(index, enabled) {
  return enabled ? ` class="fragment fade-up" data-fragment-index="${index}"` : '';
}

function renderBullets(bullets, { fragments = false, className = 'bullet-list' } = {}) {
  if (!bullets.length) return '';
  return `<ul class="${className}">${bullets.map((item, index) => `<li${fragmentAttributes(index, fragments)}>${esc(item)}</li>`).join('')}</ul>`;
}

function parseMetric(item, index) {
  const text = safeText(item);
  const parts = text.split(/[:：]/, 2).map(safeText);
  if (parts.length === 2) {
    const valueAndDetail = parts[1].split(/\s+[—–-]\s+/, 2).map(safeText);
    if (/^(?:\d[\d.,]*|\d+(?:\.\d+)?%|\d+(?:倍|万|亿|年|天|小时|分钟))$/u.test(valueAndDetail[0] || '')) {
      return { value: valueAndDetail[0], label: parts[0], detail: valueAndDetail[1] || '' };
    }
  }
  const firstHasMetric = /(?:\d|%|倍|万|亿|年|天|小时|分钟)/u.test(parts[0] || '');
  const secondHasMetric = /(?:\d|%|倍|万|亿|年|天|小时|分钟)/u.test(parts[1] || '');
  if (parts.length === 2 && firstHasMetric) return { value: parts[0], label: parts[1], detail: '' };
  if (parts.length === 2 && secondHasMetric) return { value: parts[1], label: parts[0], detail: '' };
  return { value: String(index + 1).padStart(2, '0'), label: text, detail: '' };
}

function renderStats(bullets, fragments) {
  if (!bullets.length) return '';
  return `<div class="metric-grid">${bullets.map((item, index) => {
    const metric = parseMetric(item, index);
    const fragment = fragments ? ` fragment fade-up` : '';
    return `<article class="metric${fragment}"${fragments ? ` data-fragment-index="${index}"` : ''}><strong>${esc(metric.value)}</strong><span>${esc(metric.label)}</span>${metric.detail ? `<small>${esc(metric.detail)}</small>` : ''}</article>`;
  }).join('')}</div>`;
}

function renderSteps(bullets, fragments, kind) {
  if (!bullets.length) return '';
  return `<ol class="${kind}-track">${bullets.map((item, index) => `<li${fragmentAttributes(index, fragments)}><span class="step-index">${String(index + 1).padStart(2, '0')}</span><p>${esc(item)}</p></li>`).join('')}</ol>`;
}

function renderComparison(slide, bullets, fragments) {
  if (!bullets.length) return '';
  const pivot = Math.ceil(bullets.length / 2);
  const groups = [bullets.slice(0, pivot), bullets.slice(pivot)];
  const labels = [safeText(slide?.leftLabel) || '视角 A', safeText(slide?.rightLabel) || '视角 B'];
  return `<div class="comparison-grid">${groups.map((items, groupIndex) => `<article><h3>${esc(labels[groupIndex])}</h3>${renderBullets(items, { fragments, className: 'comparison-list' })}</article>`).join('')}</div>`;
}

function imageMarkup(image, className) {
  if (!image) return '';
  return `<figure class="${className}" data-qa-content><img src="${image.dataUri}" alt="${esc(image.title || image.fileName || '演示视觉素材')}"></figure>`;
}

function contentHeader({ kicker, title, headline, titleTag = 'h2' }) {
  return `${kicker ? `<p class="kicker" data-id="deck-kicker" data-qa-content>${esc(kicker)}</p>` : ''}
    <${titleTag} data-id="deck-title" data-qa-content>${esc(title)}</${titleTag}>
    ${headline ? `<p class="headline" data-id="deck-headline" data-qa-content>${esc(headline)}</p>` : ''}`;
}

function semanticBody(slide, layout, image, fragments) {
  const bullets = (Array.isArray(slide?.bullets) ? slide.bullets : []).map(safeText).filter(Boolean).slice(0, 6);
  const header = contentHeader({
    kicker: slide.section,
    title: slide.title,
    headline: slide.headline,
    titleTag: layout === 'cover' ? 'h1' : 'h2'
  });
  if (layout === 'cover') return `<div class="cover-copy">${header}${renderBullets(bullets.slice(0, 2), { className: 'cover-points' })}</div>`;
  if (layout === 'section') return `<div class="section-copy">${header}${renderBullets(bullets.slice(0, 2), { className: 'section-points' })}</div>`;
  if (layout === 'image-hero') return `${imageMarkup(image, 'hero-visual')}<div class="hero-scrim"></div><div class="hero-copy">${header}${renderBullets(bullets.slice(0, 2), { fragments, className: 'hero-points' })}</div>`;
  if (layout === 'split') return `<div class="split-grid"><div class="split-copy">${header}${renderBullets(bullets.slice(0, 3), { fragments })}</div>${imageMarkup(image, 'split-visual')}</div>`;
  if (layout === 'evidence') {
    const evidenceBullets = image ? bullets.slice(0, 4) : bullets.slice(0, Math.max(0, bullets.length - 1)).slice(0, 3);
    const callout = image ? '' : (bullets.at(-1) || slide.headline || slide.title);
    return `<div class="evidence-grid"><div>${header}${renderBullets(evidenceBullets, { fragments })}</div>${image ? imageMarkup(image, 'evidence-visual') : `<aside class="evidence-callout" data-qa-content>${esc(callout)}</aside>`}</div>`;
  }
  if (layout === 'stats') return `${header}${renderStats(bullets.slice(0, 4), fragments)}`;
  if (layout === 'process') return `${header}${renderSteps(bullets.slice(0, 4), fragments, 'process')}`;
  if (layout === 'comparison') return `${header}${renderComparison(slide, bullets.slice(0, 6), fragments)}`;
  if (layout === 'timeline') return `${header}${renderSteps(bullets.slice(0, 4), fragments, 'timeline')}`;
  if (layout === 'quote') return `<div class="quote-copy">${header}<blockquote data-qa-content>${esc(slide.headline || bullets[0] || slide.title)}</blockquote>${renderBullets(bullets.slice(slide.headline ? 0 : 1, 3), { fragments, className: 'quote-points' })}</div>`;
  if (layout === 'gallery') return `<div class="gallery-grid">${imageMarkup(image, 'gallery-visual')}<div class="gallery-copy">${header}${renderBullets(bullets.slice(0, 3), { fragments, className: 'gallery-points' })}</div></div>`;
  if (layout === 'closing') return `<div class="closing-copy">${header}${renderBullets(bullets.slice(0, 3), { className: 'closing-points' })}</div>`;
  return `<div class="statement-copy">${header}${renderBullets(bullets.slice(0, 4), { fragments })}</div>`;
}

function themeCss(style) {
  const rules = {
    stage: `.theme-stage .reveal{background:radial-gradient(circle at 80% 20%,#262626 0,transparent 35%),var(--bg)}
.theme-stage .accent{box-shadow:0 0 30px color-mix(in srgb,var(--accent) 55%,transparent)}`,
    clean: `.theme-clean .reveal .slides section{border-top:1px solid color-mix(in srgb,var(--fg) 12%,transparent)}
.theme-clean .layout-cover{border-top:12px solid var(--accent)!important}`,
    paper: `.theme-paper .reveal{background:linear-gradient(115deg,rgba(255,255,255,.22),transparent 45%),var(--bg)}
.theme-paper .reveal h1,.theme-paper .reveal h2{font-family:Georgia,"Noto Serif SC",serif}.theme-paper .accent{height:3px}`,
    soft: `.theme-soft .reveal{background:radial-gradient(circle at 10% 10%,#fff 0,transparent 30%),linear-gradient(135deg,var(--bg),var(--surface))}
.theme-soft .layout-evidence{border-radius:36px 0 0 36px;background:rgba(255,255,255,.28)}`,
    magazine: `.theme-magazine .reveal h1,.theme-magazine .reveal h2{text-transform:uppercase}.theme-magazine .kicker{display:inline-block;background:var(--accent);color:#fff;padding:8px 14px}
.theme-magazine .accent{width:220px;transform:skewX(-18deg)}`,
    tech: `.theme-tech .reveal{background-image:linear-gradient(rgba(96,165,250,.06) 1px,transparent 1px),linear-gradient(90deg,rgba(96,165,250,.06) 1px,transparent 1px);background-size:48px 48px}
.theme-tech .split-grid{border-left:1px solid color-mix(in srgb,var(--accent) 50%,transparent)}`,
    code: `.theme-code .reveal{font-family:"SFMono-Regular",Consolas,"Noto Sans Mono CJK SC",monospace}.theme-code .accent{height:5px;border-radius:0}
.theme-code .kicker::before{content:"> ";color:var(--accent)}`,
    aurora: `.theme-aurora .reveal{background:radial-gradient(circle at 15% 15%,#0ea5e9 0,transparent 30%),radial-gradient(circle at 85% 80%,#7c3aed 0,transparent 38%),var(--bg)}
.theme-aurora .reveal .slides section{backdrop-filter:blur(4px)}.theme-aurora .layout-evidence{background:rgba(255,255,255,.08);border-radius:28px}`,
    editorial: `.theme-editorial .reveal{background:linear-gradient(90deg,transparent 0 9%,rgba(217,72,43,.12) 9% 9.3%,transparent 9.3%),var(--bg)}
.theme-editorial .reveal h1,.theme-editorial .reveal h2{font-family:Georgia,"Noto Serif SC",serif;font-weight:600}.theme-editorial .accent{width:54px;height:54px;border-radius:50%}.theme-editorial .kicker{color:var(--fg)}`,
    'neon-grid': `.theme-neon-grid .reveal{background-image:linear-gradient(rgba(45,212,191,.08) 1px,transparent 1px),linear-gradient(90deg,rgba(45,212,191,.08) 1px,transparent 1px),radial-gradient(circle at 70% 20%,#312e81 0,transparent 35%);background-size:40px 40px,40px 40px,auto}
.theme-neon-grid .accent,.theme-neon-grid .step-index{text-shadow:0 0 26px var(--accent)}.theme-neon-grid .layout-evidence{box-shadow:inset 0 0 0 1px rgba(45,212,191,.35)}`,
    terminal: `.theme-terminal .reveal{font-family:"SFMono-Regular",Consolas,"Noto Sans Mono CJK SC",monospace;background:radial-gradient(circle at center,#10261a 0,#07120d 68%)}
.theme-terminal .kicker::before{content:"$ "}.theme-terminal .accent{height:2px}.theme-terminal .reveal h1,.theme-terminal .reveal h2{letter-spacing:-.02em}`,
    gallery: `.theme-gallery .reveal h1,.theme-gallery .reveal h2{font-family:"Bodoni 72",Didot,Georgia,"Noto Serif SC",serif;font-weight:500}.theme-gallery .accent{width:2px;height:96px;position:absolute;left:42px;top:64px}
.theme-gallery .reveal .slides section{padding-left:108px}.theme-gallery .layout-cover{text-align:center!important;align-items:center}.theme-gallery .layout-cover .accent{position:static;width:96px;height:2px}`
  };
  return rules[style] || '';
}

function slideMarkup(slide, index, sourceById, imageById, allowedLayouts, motionMode) {
  const sourceIds = (slide.sourceIds || []).map(String);
  const sourceNotes = sourceIds.map(id => {
    const item = sourceById.get(id);
    return item ? `${id} | ${item.title || ''} | ${item.url || ''}` : id;
  }).map(esc).join('\n');
  const image = imageById.get(String(slide.imageId || ''));
  const layout = resolveLayout(slide, index, allowedLayouts, Boolean(image));
  const motion = resolveMotionPreset(layout, motionMode);
  const fragments = motionMode !== 'off'
    && (['process', 'timeline', 'stats'].includes(layout) || motionMode === 'expressive');
  const tone = resolveTone(slide, index, layout);
  const transition = motionMode === 'off' ? 'none'
    : ['process', 'timeline'].includes(layout) && motionMode !== 'subtle' ? 'slide' : 'fade';
  const autoAnimate = motionMode !== 'off' && !['cover', 'closing'].includes(layout) ? ' data-auto-animate' : '';
  const className = `slide layout-${layout}${image ? ' has-image' : ''}`;
  return `<section class="${className}" data-title="${esc(slide.title)}" data-tone="${tone}" data-motion="${motion}" data-transition="${transition}"${autoAnimate}>
    <div class="accent" data-id="deck-accent" data-qa-content></div>
    ${semanticBody(slide, layout, image, fragments)}
    <aside class="notes">[Sources]\n${sourceNotes}</aside>
  </section>`;
}

export async function createHtmlPresentation({
  outputFile,
  plan,
  sources = [],
  sourceImages = [],
  templateKey,
  themeFile,
  fontFamily = 'Microsoft YaHei',
  motionMode = 'auto'
}) {
  const revealRoot = path.join(AGENT_BACKEN_ROOT, 'node_modules/reveal.js');
  const revealJs = await fs.readFile(path.join(revealRoot, 'dist/reveal.js'), 'utf8');
  const revealCss = await fs.readFile(path.join(revealRoot, 'dist/reveal.css'), 'utf8');
  const themes = JSON.parse(await fs.readFile(themeFile, 'utf8'));
  const theme = themes[templateKey] || themes['html-reveal-white'];
  if (!theme) throw new Error(`HTML Skill 缺少主题资产: ${templateKey}`);
  const { background: bg, foreground: fg, accent, surface } = theme;
  const style = /^[a-z0-9-]+$/.test(theme.style || '') ? theme.style : 'clean';
  const safeMotionMode = MOTION_MODES.has(String(motionMode)) ? String(motionMode) : 'auto';
  const allowedLayouts = new Set((Array.isArray(theme.layouts) ? theme.layouts : [])
    .map(canonicalLayout).filter(Boolean));
  if (!allowedLayouts.size) for (const layout of LAYOUTS) allowedLayouts.add(layout);
  const safeFont = ['Microsoft YaHei', 'Noto Sans CJK SC', 'PingFang SC', 'Source Han Sans SC', 'SimSun']
    .includes(String(fontFamily)) ? String(fontFamily) : 'Microsoft YaHei';
  const sourceById = new Map(sources.map(item => [String(item.id), item]));
  const loadedImages = await Promise.all(sourceImages.map(async item => {
    const extension = path.extname(item.path).toLowerCase();
    const mediaType = extension === '.jpg' || extension === '.jpeg' ? 'image/jpeg'
      : extension === '.gif' ? 'image/gif'
        : extension === '.webp' ? 'image/webp' : 'image/png';
    return [String(item.id), {
      ...item,
      dataUri: `data:${mediaType};base64,${(await fs.readFile(item.path)).toString('base64')}`
    }];
  }));
  const imageById = new Map(loadedImages);
  const slides = plan.slides.map((slide, index) => slideMarkup(
    slide, index, sourceById, imageById, allowedLayouts, safeMotionMode
  )).join('\n');
  const html = `<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; img-src data:; style-src 'unsafe-inline'; script-src 'unsafe-inline'; font-src data:">
<title>${esc(plan.title || '演示文稿')}</title><style>${revealCss}
:root{--bg:${bg};--fg:${fg};--accent:${accent};--surface:${surface}}
html,body,.reveal{background:var(--bg);color:var(--fg);font-family:"${esc(safeFont)}",Inter,"PingFang SC","Microsoft YaHei",sans-serif}
.reveal .slides{text-align:left}.reveal .slides section{box-sizing:border-box;display:flex!important;flex-direction:column;justify-content:center;padding:58px 72px 88px;overflow:hidden;background:var(--bg)}
.reveal .slides section[data-tone="light"]{background:color-mix(in srgb,var(--surface) 38%,var(--bg))}.reveal .slides section[data-tone="deep"]{background:linear-gradient(135deg,color-mix(in srgb,var(--surface) 62%,var(--bg)),var(--bg))}
.accent{width:96px;height:8px;flex:0 0 auto;background:var(--accent);margin-bottom:28px}.kicker{margin:0 0 16px;color:var(--accent);font-size:18px;font-weight:800;letter-spacing:.14em;text-transform:uppercase}
.reveal h1,.reveal h2{margin:0;max-width:1040px;color:var(--fg);line-height:1.08;letter-spacing:-.035em;text-wrap:balance}.reveal h1{font-size:68px}.reveal h2{font-size:54px}
.headline{max-width:980px;margin:24px 0 0;font-size:28px;line-height:1.4;color:var(--fg);opacity:.9;text-wrap:pretty}
.reveal ul{max-width:980px;margin:28px 0 0;padding-left:1.1em;font-size:23px;line-height:1.48}.reveal li::marker{color:var(--accent)}.reveal li+li{margin-top:10px}
.slide-number{color:var(--accent)!important}.layout-cover .accent{width:160px}.layout-cover h1{max-width:940px}.cover-copy{max-width:1000px}.cover-points{display:flex;gap:30px;padding:0!important;list-style:none;font-size:20px!important;opacity:.75}
.layout-section{justify-content:flex-end!important}.layout-section .accent{height:12px;width:42%}.layout-section h2{font-size:72px;max-width:980px}.section-points{display:flex;gap:26px;padding:0!important;list-style:none;font-size:20px!important;opacity:.76}
.layout-statement h2{font-size:62px}.layout-statement .headline{max-width:1060px;font-size:38px;line-height:1.25}.statement-copy{max-width:1100px}
.layout-image-hero{padding:0!important;justify-content:flex-end!important}.layout-image-hero>.accent{display:none}.hero-visual,.hero-scrim{position:absolute;inset:0;margin:0}.hero-visual img{width:100%;height:100%;object-fit:cover}.hero-scrim{background:linear-gradient(90deg,color-mix(in srgb,var(--bg) 92%,transparent) 0,color-mix(in srgb,var(--bg) 68%,transparent) 58%,transparent 100%)}.hero-copy{position:relative;z-index:2;width:62%;padding:68px 72px 92px}.hero-copy h2{font-size:62px}.hero-points{font-size:20px!important}
.split-grid{display:grid;grid-template-columns:minmax(0,1.12fr) minmax(360px,.88fr);align-items:center;gap:52px;min-height:490px}.split-copy h2{font-size:48px}.split-copy .headline{font-size:25px}.split-visual{height:430px;margin:0;overflow:hidden;border-radius:24px;background:color-mix(in srgb,var(--surface) 72%,transparent)}.split-visual img{width:100%;height:100%;object-fit:cover}.has-image h2{font-size:46px}
.evidence-grid{display:grid;grid-template-columns:minmax(0,1.35fr) minmax(300px,.65fr);align-items:center;gap:48px}.evidence-callout{padding:34px;border-left:6px solid var(--accent);background:color-mix(in srgb,var(--surface) 70%,transparent);font-size:28px;line-height:1.38}.evidence-visual{height:390px;margin:0}.evidence-visual img{width:100%;height:100%;object-fit:contain;border-radius:18px}
.metric-grid{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:18px;margin-top:34px}.metric{min-height:176px;padding:24px 22px;border-top:6px solid var(--accent);background:color-mix(in srgb,var(--surface) 72%,transparent)}.metric strong{display:block;color:var(--accent);font-size:52px;line-height:1}.metric span{display:block;margin-top:14px;font-size:21px;font-weight:800;line-height:1.25}.metric small{display:block;margin-top:10px;font-size:15px;line-height:1.35;opacity:.72}
.process-track,.timeline-track{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:16px;width:100%;margin:30px 0 0;padding:0;list-style:none}.process-track li,.timeline-track li{position:relative;min-height:168px;padding:20px 18px;background:color-mix(in srgb,var(--surface) 74%,transparent);border-top:5px solid var(--accent)}.process-track li:not(:last-child)::after{content:"";position:absolute;right:-16px;top:38px;width:16px;height:2px;background:var(--accent)}.step-index{display:block;color:var(--accent);font-size:17px;font-weight:800;letter-spacing:.12em}.process-track p,.timeline-track p{margin:22px 0 0;font-size:20px;line-height:1.38}.timeline-track{position:relative}.timeline-track::before{content:"";position:absolute;left:0;right:0;top:32px;height:2px;background:color-mix(in srgb,var(--accent) 55%,transparent)}.timeline-track li{padding-top:48px;background:transparent;border-top:0}.timeline-track .step-index{position:absolute;top:20px;left:18px;background:var(--bg);padding-right:8px}
.comparison-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:26px;margin-top:30px}.comparison-grid article{min-height:260px;padding:26px 28px;background:color-mix(in srgb,var(--surface) 72%,transparent);border-radius:18px}.comparison-grid article:first-child{border-top:6px solid var(--accent)}.comparison-grid article:last-child{border-bottom:6px solid var(--accent)}.comparison-grid h3{margin:0;color:var(--accent);font-size:22px}.comparison-list{margin-top:18px!important;font-size:20px!important}
.layout-quote{justify-content:center!important}.quote-copy{max-width:1000px}.layout-quote h2{font-size:38px}.layout-quote .headline{display:none}.layout-quote blockquote{margin:28px 0 0;padding-left:34px;border-left:7px solid var(--accent);font-family:Georgia,"Noto Serif SC",serif;font-size:42px;line-height:1.28}.quote-points{font-size:19px!important;opacity:.78}
.gallery-grid{display:grid;grid-template-columns:minmax(0,1.25fr) minmax(330px,.75fr);gap:38px;align-items:stretch;min-height:500px}.gallery-visual{margin:0;overflow:hidden;border-radius:4px 44px 4px 4px}.gallery-visual img{width:100%;height:100%;object-fit:cover}.gallery-copy{align-self:end;padding:28px;background:color-mix(in srgb,var(--surface) 70%,transparent)}.gallery-copy h2{font-size:42px}.gallery-copy .headline{font-size:22px}.gallery-points{font-size:18px!important}
.layout-closing{align-items:center;text-align:center!important}.closing-copy{max-width:920px}.layout-closing h2{font-size:62px}.layout-closing .accent{margin-left:auto;margin-right:auto}.closing-points{display:flex;justify-content:center;gap:24px;padding:0!important;list-style:none;font-size:20px!important}
.present[data-motion="calm"] [data-qa-content],.present[data-motion="calm"] .bullet-list{animation:deck-calm .38s ease-out both}.present[data-motion="editorial"] h2,.present[data-motion="editorial"] h1{animation:deck-rise .5s cubic-bezier(.22,.8,.24,1) both}.present[data-motion="editorial"] .headline,.present[data-motion="editorial"] .bullet-list{animation:deck-fade .5s .08s ease-out both}.present[data-motion="focus"] h1,.present[data-motion="focus"] h2,.present[data-motion="focus"] blockquote{animation:deck-focus .48s ease-out both}.present[data-motion="flow"] .process-track,.present[data-motion="flow"] .timeline-track{animation:deck-flow .52s ease-out both}.present[data-motion="cinematic"] .hero-visual img,.present[data-motion="cinematic"] .gallery-visual img{animation:deck-image .65s ease-out both}.present[data-motion="cinematic"] .hero-copy,.present[data-motion="cinematic"] .gallery-copy,.present[data-motion="cinematic"] .cover-copy{animation:deck-rise .56s ease-out both}
@keyframes deck-calm{from{opacity:.78}to{opacity:1}}@keyframes deck-rise{from{opacity:0;transform:translateY(18px)}to{opacity:1;transform:none}}@keyframes deck-fade{from{opacity:0}to{opacity:1}}@keyframes deck-focus{from{opacity:.2;transform:scale(.985)}to{opacity:1;transform:none}}@keyframes deck-flow{from{opacity:0;transform:translateX(22px)}to{opacity:1;transform:none}}@keyframes deck-image{from{opacity:.72;transform:scale(1.025)}to{opacity:1;transform:scale(1)}}
.low-power .reveal,.low-power .reveal *,.reduce-motion .reveal,.reduce-motion .reveal *{animation:none!important;transition:none!important;backdrop-filter:none!important}.low-power .fragment,.reduce-motion .fragment,.qa-final-state .fragment{opacity:1!important;visibility:visible!important;transform:none!important}
${themeCss(style)}
@media(max-width:800px){.reveal .slides section{padding:42px 42px 72px}.reveal h1{font-size:54px}.reveal h2{font-size:44px}.headline{font-size:24px}.reveal ul{font-size:20px}.metric-grid,.process-track,.timeline-track{grid-template-columns:repeat(2,minmax(0,1fr))}.split-grid,.evidence-grid,.gallery-grid{grid-template-columns:1fr 1fr;gap:24px}}
@media(prefers-reduced-motion:reduce){.reveal,.reveal *{animation:none!important;transition:none!important;scroll-behavior:auto!important}.fragment{opacity:1!important;visibility:visible!important;transform:none!important}}
@media print{.fragment{opacity:1!important;visibility:visible!important;transform:none!important}.reveal .slides section{page-break-after:always}}
</style></head><body class="theme-${style}" data-motion-mode="${safeMotionMode}"><div class="reveal"><div class="slides">${slides}</div></div>
<script>${revealJs}</script><script>(()=>{const reduce=matchMedia('(prefers-reduced-motion: reduce)').matches;const low=new URLSearchParams(location.search).get('low-power')==='1';if(reduce)document.body.classList.add('reduce-motion');if(low)document.body.classList.add('low-power');const motion=document.body.dataset.motionMode;Reveal.initialize({hash:false,history:false,controls:true,progress:true,center:false,slideNumber:'c/t',transition:motion==='off'||reduce?'none':'fade',backgroundTransition:motion==='off'||reduce?'none':'fade',autoAnimate:motion!=='off'&&!reduce,autoAnimateDuration:.55,width:1280,height:720,margin:0,scrollActivationWidth:700,scrollProgress:true,pdfMaxPagesPerSlide:1,pdfSeparateFragments:false});addEventListener('keydown',event=>{if(event.defaultPrevented||event.metaKey||event.ctrlKey||event.altKey||String(event.key).toLowerCase()!=='l'||/^(INPUT|TEXTAREA|SELECT)$/.test(event.target?.tagName||''))return;document.body.classList.toggle('low-power');if(document.body.classList.contains('low-power'))Reveal.configure({transition:'none',backgroundTransition:'none',autoAnimate:false});else Reveal.configure({transition:motion==='off'||reduce?'none':'fade',backgroundTransition:motion==='off'||reduce?'none':'fade',autoAnimate:motion!=='off'&&!reduce});});})();</script>
</body></html>`;
  await fs.writeFile(outputFile, html);
}

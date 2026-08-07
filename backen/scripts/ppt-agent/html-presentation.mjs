import fs from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const AGENT_BACKEN_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');

function esc(value) {
  return String(value || '').replace(/[&<>"']/g, char => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[char]);
}

function themeCss(style) {
  const rules = {
    stage: `.theme-stage .reveal{background:radial-gradient(circle at 80% 20%,#262626 0,transparent 35%),var(--bg)}
.theme-stage .accent{box-shadow:0 0 30px color-mix(in srgb,var(--accent) 55%,transparent)}`,
    clean: `.theme-clean .reveal .slides section{border-top:1px solid color-mix(in srgb,var(--fg) 12%,transparent)}
.theme-clean .layout-cover{border-top:12px solid var(--accent)!important}`,
    paper: `.theme-paper .reveal{background:linear-gradient(115deg,rgba(255,255,255,.22),transparent 45%),var(--bg)}
.theme-paper .reveal h2{font-family:Georgia,"Noto Serif SC",serif}.theme-paper .accent{height:3px}`,
    soft: `.theme-soft .reveal{background:radial-gradient(circle at 10% 10%,#fff 0,transparent 30%),linear-gradient(135deg,var(--bg),var(--surface))}
.theme-soft .layout-evidence{border-radius:36px 0 0 36px;background:rgba(255,255,255,.28)}`,
    magazine: `.theme-magazine .reveal h2{font-size:62px;text-transform:uppercase}.theme-magazine .kicker{display:inline-block;background:var(--accent);color:#fff;padding:8px 14px}
.theme-magazine .accent{width:220px;transform:skewX(-18deg)}`,
    tech: `.theme-tech .reveal{background-image:linear-gradient(rgba(96,165,250,.06) 1px,transparent 1px),linear-gradient(90deg,rgba(96,165,250,.06) 1px,transparent 1px);background-size:48px 48px}
.theme-tech .split-panel{border-left:1px solid color-mix(in srgb,var(--accent) 50%,transparent)}`,
    code: `.theme-code .reveal{font-family:"SFMono-Regular",Consolas,"Noto Sans Mono CJK SC",monospace}.theme-code .accent{height:5px;border-radius:0}
.theme-code .kicker::before{content:"> ";color:var(--accent)}`,
    aurora: `.theme-aurora .reveal{background:radial-gradient(circle at 15% 15%,#0ea5e9 0,transparent 30%),radial-gradient(circle at 85% 80%,#7c3aed 0,transparent 38%),var(--bg)}
.theme-aurora .reveal .slides section{backdrop-filter:blur(4px)}.theme-aurora .layout-evidence{background:rgba(255,255,255,.08);border-radius:28px}`,
    editorial: `.theme-editorial .reveal{background:linear-gradient(90deg,transparent 0 9%,rgba(217,72,43,.12) 9% 9.3%,transparent 9.3%),var(--bg)}
.theme-editorial .reveal h2{font-family:Georgia,"Noto Serif SC",serif;font-weight:600}.theme-editorial .accent{width:54px;height:54px;border-radius:50%}.theme-editorial .kicker{color:var(--fg)}`,
    'neon-grid': `.theme-neon-grid .reveal{background-image:linear-gradient(rgba(45,212,191,.08) 1px,transparent 1px),linear-gradient(90deg,rgba(45,212,191,.08) 1px,transparent 1px),radial-gradient(circle at 70% 20%,#312e81 0,transparent 35%);background-size:40px 40px,40px 40px,auto}
.theme-neon-grid .accent,.theme-neon-grid .split-panel span{text-shadow:0 0 26px var(--accent)}.theme-neon-grid .layout-evidence{box-shadow:inset 0 0 0 1px rgba(45,212,191,.35)}`,
    terminal: `.theme-terminal .reveal{font-family:"SFMono-Regular",Consolas,"Noto Sans Mono CJK SC",monospace;background:radial-gradient(circle at center,#10261a 0,#07120d 68%)}
.theme-terminal .kicker::before{content:"$ "}.theme-terminal .accent{height:2px}.theme-terminal .reveal h2{letter-spacing:-.02em}`,
    gallery: `.theme-gallery .reveal h2{font-family:"Bodoni 72",Didot,Georgia,"Noto Serif SC",serif;font-weight:500}.theme-gallery .accent{width:2px;height:96px;position:absolute;left:42px;top:64px}
.theme-gallery .reveal .slides section{padding-left:108px}.theme-gallery .layout-cover{text-align:center!important;align-items:center}.theme-gallery .layout-cover .accent{position:static;width:96px;height:2px}`
  };
  return rules[style] || '';
}

function slideMarkup(slide, index, sourceById, imageById) {
  const bullets = (slide.bullets || []).slice(0, 6).map(item => `<li>${esc(item)}</li>`).join('');
  const sourceIds = (slide.sourceIds || []).map(String);
  const sourceNotes = sourceIds.map(id => {
    const item = sourceById.get(id);
    return item ? `${id} | ${item.title || ''} | ${item.url || ''}` : id;
  }).map(esc).join('\n');
  const requestedLayout = ['cover', 'split', 'statement', 'evidence', 'comparison', 'timeline', 'quote', 'closing'].includes(slide.layout)
    ? slide.layout : (index % 3 === 0 ? 'split' : 'statement');
  const image = imageById.get(String(slide.imageId || ''));
  // A split without an image creates a large empty panel that looks like an
  // unresolved template block. Collapse it to a statement page instead.
  const layout = requestedLayout === 'split' && !image ? 'statement' : requestedLayout;
  return `<section class="slide layout-${layout}${image ? ' has-image' : ''}" data-title="${esc(slide.title)}">
    <div class="accent"></div>
    <p class="kicker">${esc(slide.section || slide.type || '')}</p>
    <h2>${esc(slide.title)}</h2>
    ${slide.headline ? `<p class="headline">${esc(slide.headline)}</p>` : ''}
    ${bullets ? `<ul>${bullets}</ul>` : ''}
    ${image ? `<figure class="evidence-image"><img src="${image.dataUri}" alt="${esc(image.fileName || '上传资料图片')}"></figure>` : ''}
    <aside class="notes">[Sources]\n${sourceNotes}</aside>
  </section>`;
}

export async function createHtmlPresentation({ outputFile, plan, sources = [], sourceImages = [], templateKey, themeFile, fontFamily = 'Microsoft YaHei' }) {
  const revealRoot = path.join(AGENT_BACKEN_ROOT, 'node_modules/reveal.js');
  const revealJs = await fs.readFile(path.join(revealRoot, 'dist/reveal.js'), 'utf8');
  const revealCss = await fs.readFile(path.join(revealRoot, 'dist/reveal.css'), 'utf8');
  const themes = JSON.parse(await fs.readFile(themeFile, 'utf8'));
  const theme = themes[templateKey] || themes['html-reveal-white'];
  if (!theme) throw new Error(`HTML Skill 缺少主题资产: ${templateKey}`);
  const { background: bg, foreground: fg, accent, surface } = theme;
  const style = /^[a-z0-9-]+$/.test(theme.style || '') ? theme.style : 'clean';
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
  const slides = plan.slides.map((slide, index) => slideMarkup(slide, index, sourceById, imageById)).join('\n');
  const html = `<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; img-src data:; style-src 'unsafe-inline'; script-src 'unsafe-inline'; font-src data:">
<title>${esc(plan.title || '演示文稿')}</title><style>${revealCss}
:root{--bg:${bg};--fg:${fg};--accent:${accent};--surface:${surface}}
html,body,.reveal{background:var(--bg);color:var(--fg);font-family:"${esc(safeFont)}",Inter,"PingFang SC","Microsoft YaHei",sans-serif}
.reveal .slides{text-align:left}.reveal .slides section{box-sizing:border-box;display:flex!important;flex-direction:column;justify-content:center;padding:64px 72px}
.accent{width:96px;height:8px;background:var(--accent);margin-bottom:32px}.kicker{margin:0 0 18px;color:var(--accent);font-size:18px;font-weight:800;letter-spacing:.14em;text-transform:uppercase}
.reveal h2{margin:0;max-width:1040px;color:var(--fg);font-size:54px;line-height:1.08;letter-spacing:-.035em}
.headline{max-width:980px;margin:30px 0 0;font-size:29px;line-height:1.42;color:var(--fg);opacity:.92}
.reveal ul{max-width:980px;margin:34px 0 0;padding-left:1.1em;font-size:25px;line-height:1.55}.reveal li::marker{color:var(--accent)}
.slide-number{color:var(--accent)!important}
.layout-cover .accent{width:160px}.layout-cover h2{max-width:900px}
.layout-cover h2{font-size:68px}.layout-split{padding-right:44%!important;background:linear-gradient(90deg,var(--bg) 0 63%,var(--surface) 63%)}
.split-panel{position:absolute;inset:0 0 0 63%;display:flex;flex-direction:column;align-items:center;justify-content:center;color:var(--fg);opacity:.88}.split-panel strong{max-width:70%;font-size:24px;letter-spacing:.12em;text-align:center}
.layout-evidence{border-left:18px solid var(--accent)}.layout-closing{display:flex!important;flex-direction:column;justify-content:center;align-items:center;text-align:center!important}
.layout-statement .headline{max-width:1040px;margin-top:24px;font-size:42px;line-height:1.28}.layout-statement ul{margin-top:24px}
.layout-comparison ul{columns:2;column-gap:70px;padding:28px 34px;background:color-mix(in srgb,var(--surface) 72%,transparent);border-radius:20px}.layout-comparison li{break-inside:avoid;margin-bottom:16px}
.layout-timeline ul{display:flex;gap:18px;width:100%;max-width:1120px;margin-top:28px;padding:0;list-style:none}.layout-timeline li{flex:1;min-height:128px;padding:20px 18px;border-top:5px solid var(--accent);background:color-mix(in srgb,var(--surface) 78%,transparent)}
.layout-quote h2{max-width:900px;font-size:66px}.layout-quote .headline{padding-left:32px;border-left:5px solid var(--accent);font-family:Georgia,"Noto Serif SC",serif;font-size:34px}
.evidence-image{position:absolute;right:54px;top:16%;width:38%;height:68%;margin:0}.evidence-image img{width:100%;height:100%;object-fit:contain;border-radius:16px}.has-image{padding-right:45%!important}
${themeCss(style)}
@media(max-width:800px){.reveal .slides section{padding:42px}.reveal h2{font-size:44px}.headline{font-size:24px}.reveal ul{font-size:21px}}
</style></head><body class="theme-${style}"><div class="reveal"><div class="slides">${slides}</div></div>
<script>${revealJs}</script><script>Reveal.initialize({hash:false,history:false,controls:true,progress:true,center:false,slideNumber:'c/t',transition:'fade',width:1280,height:720,margin:0});</script>
</body></html>`;
  await fs.writeFile(outputFile, html);
}

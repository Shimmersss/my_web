#!/usr/bin/env node

/** Build-time static screenshots for the HTML-only reveal.js theme picker. */
import fs from 'node:fs';
import crypto from 'node:crypto';
import os from 'node:os';
import path from 'node:path';
import process from 'node:process';
import { spawnSync } from 'node:child_process';
import { createHtmlPresentation } from './ppt-agent/html-presentation.mjs';
import { renderHtml } from './ppt-agent/render.mjs';

const SCRIPT_DIR = path.resolve(path.dirname(new URL(import.meta.url).pathname));
const DEFAULT_CATALOG = path.join(SCRIPT_DIR, 'html_template_preview_catalog.json');
const DEFAULT_OUTPUT = path.resolve(SCRIPT_DIR, '../../front/public/html-template-previews');

function parseArgs(argv) {
  const args = {};
  for (let index = 2; index < argv.length; index += 1) {
    if (!argv[index]?.startsWith('--')) continue;
    const key = argv[index].slice(2);
    const value = argv[index + 1]?.startsWith('--') ? true : argv[index + 1];
    args[key] = value;
    if (value !== true) index += 1;
  }
  return args;
}

function json(file, value) {
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, `${JSON.stringify(value, null, 2)}\n`, 'utf8');
}

function fixture(template) {
  return {
    title: `${template.design.toUpperCase()} HTML THEME`,
    templateDesign: template.design,
    palette: template.palette,
    slides: [
      { type: 'cover', title: `${template.design.toUpperCase()} THEME`, headline: 'A browser-native reveal.js presentation with its own visual language', section: 'HTML PREVIEW', theme: 'INTERACTIVE WEB DECK', layout: 'cover' },
      { type: 'content', title: 'MEASURABLE AT A GLANCE', headline: 'Purpose-built metric composition, not a repeated bullet card', section: 'SIGNALS', layout: 'stats', bullets: ['92%:VIEWPORT FIT', '13:SEMANTIC LAYOUTS', '4:MOTION MODES', '1:OFFLINE FILE'] },
      { type: 'content', title: 'FROM IDEA TO STAGE', headline: 'Ordered relationships receive an ordered visual grammar', section: 'FLOW', layout: 'process', bullets: ['Define the takeaway', 'Select a semantic layout', 'Render in a real browser', 'Verify every boundary'] },
      { type: 'comparison', title: 'STRUCTURE AND EXPRESSION', headline: 'Balanced evidence keeps the contrast readable', section: 'DECISION', layout: 'comparison', leftLabel: 'STRUCTURE', rightLabel: 'EXPRESSION', bullets: ['Clear information hierarchy', 'Bounded content density', 'Theme-specific visual voice', 'Controlled motion rhythm'] },
      { type: 'closing', title: 'READY TO PRESENT', headline: 'Open · Navigate · Present · Share', section: 'NEXT STEP', layout: 'closing', bullets: ['Standalone HTML', 'Keyboard and touch', 'Reduced-motion ready'] }
    ]
  };
}

function sha256(file) {
  return crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex');
}

function findChrome(requested) {
  const candidates = [requested, process.env.CHROME, '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome', 'google-chrome', 'chromium', 'chromium-browser'].filter(Boolean);
  for (const candidate of candidates) {
    if (candidate.includes('/') && fs.existsSync(candidate)) return candidate;
    const probe = spawnSync('sh', ['-lc', `command -v ${candidate}`], { encoding: 'utf8' });
    if (probe.status === 0 && probe.stdout.trim()) return probe.stdout.trim();
  }
  throw new Error('找不到 Chrome/Chromium，无法生成 HTML 模板预览');
}

async function main() {
  const args = parseArgs(process.argv);
  const catalog = JSON.parse(fs.readFileSync(path.resolve(args.catalog || DEFAULT_CATALOG), 'utf8'));
  const outputDir = path.resolve(args['output-dir'] || DEFAULT_OUTPUT);
  const workDir = path.resolve(args['work-dir'] || fs.mkdtempSync(path.join(os.tmpdir(), 'shimmer-html-template-preview-')));
  const chrome = findChrome(args.chrome);
  const themeFile = path.resolve(SCRIPT_DIR, '../../.agents/skills/create-html-presentation/assets/themes.json');
  process.env.PPT_AGENT_CHROME = chrome;
  fs.mkdirSync(workDir, { recursive: true });
  const rendererFile = path.join(SCRIPT_DIR, 'ppt-agent/html-presentation.mjs');
  const revealPackage = JSON.parse(fs.readFileSync(path.join(SCRIPT_DIR, '../node_modules/reveal.js/package.json'), 'utf8'));
  const manifest = {
    engine: 'agent-reveal.js',
    slideCount: 5,
    generatedAt: new Date().toISOString(),
    themeSha256: sha256(themeFile),
    rendererSha256: sha256(rendererFile),
    revealVersion: String(revealPackage.version || ''),
    templates: {}
  };
  for (const template of catalog) {
    const keyDir = path.join(outputDir, template.key);
    const htmlFile = path.join(workDir, `${template.key}.html`);
    fs.rmSync(keyDir, { recursive: true, force: true });
    fs.mkdirSync(keyDir, { recursive: true });
    await createHtmlPresentation({ outputFile: htmlFile, plan: fixture(template), sources: [], templateKey: template.key, themeFile });
    const rendered = await renderHtml(htmlFile, keyDir);
    if (rendered.count !== 5 || rendered.overflow.length) {
      throw new Error(`HTML 预览失败：${template.key} ${JSON.stringify(rendered)}`);
    }
    const files = Array.from({ length: 5 }, (_, slide) => `/html-template-previews/${template.key}/slide-${slide + 1}.png`);
    manifest.templates[template.key] = files;
  }
  json(path.join(outputDir, 'manifest.json'), manifest);
  process.stdout.write(`${JSON.stringify({ ok: true, engine: 'agent-reveal.js', templates: catalog.length, outputDir })}\n`);
}

main().catch(error => {
  process.stderr.write(`${error.stack || error.message}\n`);
  process.exitCode = 1;
});

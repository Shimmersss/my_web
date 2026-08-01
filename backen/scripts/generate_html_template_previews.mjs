#!/usr/bin/env node

/** Build-time static screenshots for the HTML-only reveal.js theme picker. */
import fs from 'node:fs';
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
      { type: 'section', title: 'SECTION TITLE', headline: 'Use keyboard navigation, overview mode and responsive layout', section: 'CHAPTER 01', layout: 'statement' },
      { type: 'content', title: 'ONE CLEAR MESSAGE', headline: 'Web-native typography and motion adapt to the browser viewport', layout: 'split', bullets: ['Responsive by default', 'Keyboard-friendly navigation', 'Standalone HTML output'] },
      { type: 'content', title: 'EVIDENCE IN CONTEXT', headline: 'A different theme system from the editable PPTX output', layout: 'comparison', bullets: ['Browser-native slides', 'Independent theme family', 'Share with one HTML file', 'No PowerPoint canvas'] },
      { type: 'content', title: 'READY TO PRESENT', headline: 'The final deck keeps reveal.js controls and responsive behavior', layout: 'closing', bullets: ['Open', 'Navigate', 'Present', 'Share'] }
    ]
  };
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
  const manifest = { engine: 'agent-reveal.js', slideCount: 5, generatedAt: new Date().toISOString(), templates: {} };
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

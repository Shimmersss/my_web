import fs from 'node:fs/promises';
import path from 'node:path';
import { execFile } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { promisify } from 'node:util';
import { resolveStableLibreOffice } from './render.mjs';

const scriptDir = path.dirname(fileURLToPath(import.meta.url));
const outputRoot = path.resolve(process.env.PPT_AGENT_FONT_CHECK_DIR || '../.run/deployment-font-check');
const vendorRoot = path.resolve(process.env.PPT_GENERATION_CODEX_VENDOR_ROOT
  || path.join(scriptDir, '../../../vendor/open-kimi-ppt-skill'));
const finalizer = path.resolve(scriptDir, '../ppt-codex/finalize.mjs');
const execFileAsync = promisify(execFile);

const soffice = await resolveStableLibreOffice(
  process.env.PPT_GENERATION_SOFFICE_COMMAND || process.env.PPT_AGENT_SOFFICE || 'soffice'
);
const { stdout: officeVersion = '', stderr: officeVersionError = '' } = await execFileAsync(soffice, ['--version']);
const versionText = `${officeVersion} ${officeVersionError}`.trim();
if (/dev|alpha|beta|rc\d*/i.test(versionText)) {
  throw new Error(`中文字体预检拒绝开发或预发布 LibreOffice: ${versionText}`);
}
await fs.access(path.join(vendorRoot, 'skills/open-kimi-ppt/scripts/export_pptx.py'));

await fs.rm(outputRoot, { recursive: true, force: true });
await fs.mkdir(outputRoot, { recursive: true });

function page(title) {
  return `pageType: content\nbackground: {type: solid, color: "#F7F8FC"}\nelements:\n  - elementId: title\n    elementType: text\n    bounds: [100, 180, 760, 120]\n    content:\n      fontSize: 42\n      text: "<p>${title}</p>"\n`;
}

async function renderTitle(name, title) {
  const taskDir = path.join(outputRoot, name);
  const projectDir = path.join(taskDir, 'pptd-project');
  await fs.mkdir(path.join(projectDir, 'pages'), { recursive: true });
  await fs.writeFile(path.join(projectDir, 'deck.pptd'), `version: v2\ntitle: deployment-font-check\nsize: [960, 540]\npages:\n  - pages/1.page\n  - pages/2.page\n  - pages/3.page\n`);
  await Promise.all([1, 2, 3].map(index => fs.writeFile(path.join(projectDir, 'pages', `${index}.page`), page(title))));
  await execFileAsync(process.execPath, [finalizer, taskDir, vendorRoot], {
    cwd: scriptDir,
    env: { ...process.env, PPT_CODEX_REQUESTED_FONT: 'Noto Sans CJK SC' },
    maxBuffer: 1024 * 1024
  });
  return fs.readFile(path.join(taskDir, 'preview/slide-1.png'));
}

// Use the same fixed PPTD exporter and LibreOffice renderer as production.
// This is deliberately self-contained: legacy template caches are archived
// before the installer runs this preflight.
const latinBaseline = await renderTitle('latin-baseline', 'FONT CHECK');
const cjkFirst = await renderTitle('cjk-first', '智能演示');
const cjkSecond = await renderTitle('cjk-second', '研究创新');
if (latinBaseline.equals(cjkFirst) || latinBaseline.equals(cjkSecond) || cjkFirst.equals(cjkSecond)) {
  throw new Error('中文字体未被稳定版 headless LibreOffice 渲染为可区分字形：请安装 Noto CJK，并确认 soffice 使用稳定发行版');
}
process.stdout.write('中文字体与 LibreOffice 真实渲染预检通过\n');

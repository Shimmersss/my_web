import fs from 'node:fs/promises';
import path from 'node:path';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { composeTemplatePptx, inspectTemplate } from './template-pptx.mjs';
import { renderPptx } from './render.mjs';

const templateFile = path.resolve(process.env.PPT_AGENT_FONT_CHECK_TEMPLATE
  || '../.run/ppt-generation-tasks/_template-cache/github-bjtu-blue.pptx');
const outputRoot = path.resolve(process.env.PPT_AGENT_FONT_CHECK_DIR
  || '../.run/deployment-font-check');
const execFileAsync = promisify(execFile);

const soffice = process.env.PPT_GENERATION_SOFFICE_COMMAND || process.env.PPT_AGENT_SOFFICE || 'soffice';
const { stdout: officeVersion = '', stderr: officeVersionError = '' } = await execFileAsync(soffice, ['--version']);
const versionText = `${officeVersion} ${officeVersionError}`.trim();
if (/dev|alpha|beta|rc\d*/i.test(versionText)) {
  throw new Error(`中文字体预检拒绝开发或预发布 LibreOffice: ${versionText}`);
}

await fs.rm(outputRoot, { recursive: true, force: true });
await fs.mkdir(outputRoot, { recursive: true });
const manifest = await inspectTemplate(templateFile);
const slot = manifest.slides[0]?.textShapes?.find(shape => !shape.furniture);
if (!slot) throw new Error('中文字体预检找不到封面文字槽');

async function renderTitle(name, title) {
  const directory = path.join(outputRoot, name);
  await fs.mkdir(directory, { recursive: true });
  const outputFile = path.join(directory, 'font-check.pptx');
  const plan = {
    title,
    slides: [{
      type: 'cover',
      sourceSlide: 1,
      title,
      headline: '',
      bullets: [],
      textEdits: [{ slotId: slot.slotId, text: title }],
      imageEdits: [],
      sourceIds: []
    }]
  };
  await composeTemplatePptx({ templateFile, outputFile, plan, manifest });
  await renderPptx(outputFile, path.join(directory, 'preview'));
  return fs.readFile(path.join(directory, 'preview/slide-1.png'));
}

// pptx-automizer has process-global relationship trackers, so compose serially.
const blank = await renderTitle('blank', '');
const cjkFirst = await renderTitle('cjk-first', '智能演示');
const cjkSecond = await renderTitle('cjk-second', '研究创新');
if (blank.equals(cjkFirst) || blank.equals(cjkSecond) || cjkFirst.equals(cjkSecond)) {
  throw new Error('中文字体未被稳定版 headless LibreOffice 渲染为可区分字形：请安装 Noto CJK，并确认 soffice 使用稳定发行版');
}
process.stdout.write('中文字体与 LibreOffice 真实渲染预检通过\n');

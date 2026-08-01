import fs from 'node:fs/promises';
import path from 'node:path';
import { spawn } from 'node:child_process';
import { pathToFileURL } from 'node:url';
import { chromium } from 'playwright-core';

const MAX_COMMAND_LOG_BYTES = 64 * 1024;

function appendTail(current, chunk) {
  const next = current + String(chunk);
  return next.length <= MAX_COMMAND_LOG_BYTES
    ? next
    : next.slice(next.length - MAX_COMMAND_LOG_BYTES);
}

function killProcessTree(child) {
  if (!child?.pid) return;
  if (process.platform !== 'win32') {
    try {
      process.kill(-child.pid, 'SIGKILL');
      return;
    } catch {
      // The child may have exited before its process group was signalled.
    }
  }
  try { child.kill('SIGKILL'); } catch {}
}

export async function runCommand(bin, args, cwd, timeoutMs = 180_000) {
  return await new Promise((resolve, reject) => {
    const child = spawn(bin, args, {
      cwd,
      stdio: ['ignore', 'pipe', 'pipe'],
      detached: process.platform !== 'win32'
    });
    let output = '';
    let timedOut = false;
    let settled = false;
    const timer = setTimeout(() => {
      timedOut = true;
      killProcessTree(child);
    }, timeoutMs);
    child.stdout.on('data', chunk => { output = appendTail(output, chunk); });
    child.stderr.on('data', chunk => { output = appendTail(output, chunk); });
    child.on('error', error => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      killProcessTree(child);
      reject(error);
    });
    child.on('close', code => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      if (timedOut) reject(new Error(`${bin} 超时`));
      else if (code === 0) resolve(output);
      else reject(new Error(`${bin} 退出 ${code}: ${output.slice(-2000)}`));
    });
  });
}

async function resetPreview(previewDir) {
  await fs.rm(previewDir, { recursive: true, force: true });
  await fs.mkdir(previewDir, { recursive: true });
}

export async function renderPptx(outputFile, previewDir) {
  outputFile = path.resolve(outputFile);
  previewDir = path.resolve(previewDir);
  await resetPreview(previewDir);
  const soffice = process.env.PPT_AGENT_SOFFICE || 'soffice';
  const officeProfile = path.join(previewDir, '.libreoffice-profile');
  await fs.mkdir(officeProfile, { recursive: true });
  await runCommand(soffice, [
    `-env:UserInstallation=${pathToFileURL(officeProfile).href}`,
    '--headless', '--convert-to', 'pdf', '--outdir', previewDir, outputFile
  ], path.dirname(outputFile), 240_000);
  const pdf = path.join(previewDir, `${path.basename(outputFile, path.extname(outputFile))}.pdf`);
  await runCommand(process.env.PPT_AGENT_PDFTOPPM || 'pdftoppm', ['-png', '-r', '120', pdf, path.join(previewDir, 'slide')], previewDir, 240_000);
  const generated = (await fs.readdir(previewDir)).filter(name => /^slide-\d+\.png$/.test(name)).sort((a, b) => Number(a.match(/\d+/)[0]) - Number(b.match(/\d+/)[0]));
  for (const [index, name] of generated.entries()) {
    const target = `slide-${index + 1}.png`;
    if (name !== target) await fs.rename(path.join(previewDir, name), path.join(previewDir, target));
  }
  await fs.rm(pdf, { force: true });
  await fs.rm(officeProfile, { recursive: true, force: true });
  return generated.length;
}

function chromePath() {
  return process.env.PPT_AGENT_CHROME
    || (process.platform === 'darwin' ? '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome' : '/usr/bin/chromium');
}

export async function renderHtml(outputFile, previewDir) {
  outputFile = path.resolve(outputFile);
  previewDir = path.resolve(previewDir);
  await resetPreview(previewDir);
  const browser = await chromium.launch({ executablePath: chromePath(), headless: true, args: ['--no-sandbox', '--disable-dev-shm-usage'] });
  try {
    const page = await browser.newPage({ viewport: { width: 1280, height: 720 }, deviceScaleFactor: 1 });
    await page.goto(`file://${outputFile}`, { waitUntil: 'load' });
    await page.waitForFunction(() => window.Reveal?.isReady?.());
    await page.evaluate(() => window.Reveal.configure({ transition: 'none', backgroundTransition: 'none' }));
    const count = await page.locator('.slides > section').count();
    const overflow = [];
    for (let index = 0; index < count; index += 1) {
      await page.evaluate(i => window.Reveal.slide(i), index);
      await page.waitForTimeout(60);
      const item = page.locator('.slides > section').nth(index);
      const hasOverflow = await item.evaluate(node => node.scrollWidth > node.clientWidth + 2 || node.scrollHeight > node.clientHeight + 2);
      if (hasOverflow) overflow.push(index + 1);
      await page.screenshot({ path: path.join(previewDir, `slide-${index + 1}.png`) });
    }
    return { count, overflow };
  } finally {
    await browser.close();
  }
}

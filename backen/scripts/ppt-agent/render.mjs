import fs from 'node:fs/promises';
import { constants as fsConstants } from 'node:fs';
import path from 'node:path';
import { spawn } from 'node:child_process';
import { pathToFileURL } from 'node:url';
import { chromium } from 'playwright-core';

const MAX_COMMAND_LOG_BYTES = 64 * 1024;

function defaultExecutableCandidates(command) {
  const name = path.basename(command || '');
  if (name !== 'soffice' && name !== 'soffice.bin') return [];
  if (process.platform === 'darwin') {
    return [
      '/Applications/LibreOffice.app/Contents/MacOS/soffice',
      '/Applications/LibreOfficeDev.app/Contents/MacOS/soffice'
    ];
  }
  if (process.platform === 'win32') {
    return [
      'C:\\Program Files\\LibreOffice\\program\\soffice.exe',
      'C:\\Program Files (x86)\\LibreOffice\\program\\soffice.exe'
    ];
  }
  return [
    '/usr/bin/soffice',
    '/usr/local/bin/soffice',
    '/usr/lib/libreoffice/program/soffice',
    '/opt/libreoffice/program/soffice'
  ];
}

/** Resolve a configured binary before spawn so missing PATH entries become actionable errors. */
export async function resolveExecutable(command, label = '可执行文件') {
  const requested = String(command || '').trim();
  const candidates = [];
  if (requested && path.isAbsolute(requested)) {
    candidates.push(requested);
  } else if (requested) {
    for (const directory of String(process.env.PATH || '').split(path.delimiter).filter(Boolean)) {
      candidates.push(path.join(directory, requested));
    }
    candidates.push(...defaultExecutableCandidates(requested));
  }
  const unique = [...new Set(candidates)];
  for (const candidate of unique) {
    try {
      await fs.access(candidate, fsConstants.X_OK);
      return candidate;
    } catch {
      // Try the next PATH or platform candidate.
    }
  }
  const configured = requested || '未配置';
  throw new Error(`${label}不可用：${configured}。请安装 LibreOffice/Poppler，或设置对应的 PPT_GENERATION_*_COMMAND 为绝对路径`);
}

export async function resolveStableLibreOffice(command = process.env.PPT_AGENT_SOFFICE || 'soffice') {
  const executable = await resolveExecutable(command, 'LibreOffice');
  const version = await runCommand(executable, ['--version'], process.cwd(), 30_000);
  if (/LibreOfficeDev|alpha|beta|rc\d*/i.test(version)) {
    throw new Error(`LibreOffice 不支持开发版渲染：${version.trim()}。请配置稳定版 LibreOffice 的绝对路径`);
  }
  return executable;
}

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
  const soffice = await resolveStableLibreOffice();
  const pdftoppm = await resolveExecutable(process.env.PPT_AGENT_PDFTOPPM || 'pdftoppm', 'pdftoppm');
  await resetPreview(previewDir);
  const officeProfile = path.join(previewDir, '.libreoffice-profile');
  await fs.mkdir(officeProfile, { recursive: true });
  await runCommand(soffice, [
    `-env:UserInstallation=${pathToFileURL(officeProfile).href}`,
    '--headless', '--convert-to', 'pdf', '--outdir', previewDir, outputFile
  ], path.dirname(outputFile), 240_000);
  const pdf = path.join(previewDir, `${path.basename(outputFile, path.extname(outputFile))}.pdf`);
  await runCommand(pdftoppm, ['-png', '-r', '120', pdf, path.join(previewDir, 'slide')], previewDir, 240_000);
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

function isTransientScreenshotError(error) {
  return /Page\.captureScreenshot|Unable to capture screenshot/i.test(String(error?.message || error));
}

/**
 * Chrome can briefly reject captureScreenshot immediately after a Reveal slide
 * transition even after fonts are ready. Let two animation frames paint, then
 * retry only that known transient protocol failure so a sound deck is not
 * discarded because of one compositor race.
 */
export async function captureScreenshot(page, screenshotPath, attempts = 3) {
  let lastError;
  for (let attempt = 0; attempt < attempts; attempt += 1) {
    try {
      await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
      await page.screenshot({ path: screenshotPath, animations: 'disabled', timeout: 45_000 });
      return;
    } catch (error) {
      lastError = error;
      if (!isTransientScreenshotError(error) || attempt + 1 >= attempts) throw error;
      await page.waitForTimeout(180 * (attempt + 1));
    }
  }
  throw lastError;
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
    await page.evaluate(async () => {
      window.Reveal.configure({ transition: 'none', backgroundTransition: 'none', autoAnimate: false });
      document.body.classList.add('qa-final-state', 'reduce-motion');
      await document.fonts?.ready;
      await Promise.all([...document.images].map(image => {
        if (image.complete) return Promise.resolve();
        return new Promise(resolve => {
          image.addEventListener('load', resolve, { once: true });
          image.addEventListener('error', resolve, { once: true });
        });
      }));
    });
    const count = await page.locator('.slides > section').count();
    const overflow = [];
    const issues = [];
    for (let index = 0; index < count; index += 1) {
      await page.evaluate(i => window.Reveal.slide(i), index);
      await page.waitForTimeout(120);
      const item = page.locator('.slides > section').nth(index);
      const slideIssues = await item.evaluate(node => {
        const problems = [];
        const nodeRect = node.getBoundingClientRect();
        if (node.scrollWidth > node.clientWidth + 2 || node.scrollHeight > node.clientHeight + 2) {
          problems.push('section-overflow');
        }
        const images = [...node.querySelectorAll('img')];
        if (images.some(image => !image.complete || image.naturalWidth <= 0 || image.naturalHeight <= 0)) {
          problems.push('image-load');
        }
        const selectors = [
          'h1', 'h2', '.kicker', '.headline', '.bullet-list', '.metric-grid',
          '.process-track', '.timeline-track', '.comparison-grid', 'blockquote',
          '.closing-points', '.gallery-copy', '.split-visual', '.evidence-visual'
        ].join(',');
        const content = [...node.querySelectorAll(selectors)].filter(element => {
          const style = getComputedStyle(element);
          return style.display !== 'none' && style.visibility !== 'hidden'
            && Number(style.opacity || 1) > 0 && element.closest('section') === node;
        });
        for (const element of content) {
          const rect = element.getBoundingClientRect();
          if (rect.left < nodeRect.left - 3 || rect.right > nodeRect.right + 3
            || rect.top < nodeRect.top - 3 || rect.bottom > nodeRect.bottom + 3) {
            problems.push('child-bounds');
            break;
          }
        }
        const safeContent = content.filter(element => !element.matches('.split-visual,.evidence-visual'));
        const bottom = safeContent.reduce((maximum, element) => Math.max(maximum, element.getBoundingClientRect().bottom), nodeRect.top);
        if (bottom > nodeRect.bottom - 34) problems.push('navigation-safe');
        return [...new Set(problems)];
      });
      if (slideIssues.length) {
        overflow.push(index + 1);
        issues.push({ slide: index + 1, reasons: slideIssues });
      }
      await captureScreenshot(page, path.join(previewDir, `slide-${index + 1}.png`));
    }
    return { count, overflow, issues };
  } finally {
    await browser.close();
  }
}

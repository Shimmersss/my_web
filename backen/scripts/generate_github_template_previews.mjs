#!/usr/bin/env node

/**
 * Build-time importer for finished PPTX templates published in GitHub repos.
 *
 * The source decks are only fetched while refreshing the preview catalog. The
 * deployed application serves the generated PNGs and never downloads or
 * executes third-party decks during a user request.
 */
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import process from 'node:process';
import { spawnSync } from 'node:child_process';

const ROOT = path.resolve(path.dirname(new URL(import.meta.url).pathname), '..', '..');
const SCRIPT_DIR = path.resolve(path.dirname(new URL(import.meta.url).pathname));
const DEFAULT_CATALOG = path.join(SCRIPT_DIR, 'github_template_preview_catalog.json');
const DEFAULT_OUTPUT = path.join(ROOT, 'front', 'public', 'ppt-template-previews');

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

function run(command, args, options = {}) {
  const result = spawnSync(command, args, { encoding: 'utf8', stdio: options.stdio || 'pipe', ...options });
  if (result.error) throw result.error;
  if (result.status !== 0) {
    throw new Error(`${command} ${args.join(' ')} 失败：${String(result.stderr || result.stdout || '').trim()}`);
  }
  return result;
}

function readJson(file, fallback) {
  try {
    return fs.existsSync(file) ? JSON.parse(fs.readFileSync(file, 'utf8')) : fallback;
  } catch (error) {
    throw new Error(`无法读取 JSON ${file}: ${error.message}`);
  }
}

function writeJson(file, value) {
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, `${JSON.stringify(value, null, 2)}\n`, 'utf8');
}

function filesIn(dir) {
  return fs.readdirSync(dir)
    .filter((file) => /^slide-\d+\.png$/.test(file))
    .sort((a, b) => a.localeCompare(b, undefined, { numeric: true }));
}

function main() {
  const args = parseArgs(process.argv);
  const catalogFile = path.resolve(args.catalog || DEFAULT_CATALOG);
  const outputDir = path.resolve(args['output-dir'] || DEFAULT_OUTPUT);
  const workDir = path.resolve(args['work-dir'] || fs.mkdtempSync(path.join(os.tmpdir(), 'shimmer-github-template-')));
  const sourceDir = args['source-dir'] ? path.resolve(args['source-dir']) : null;
  const soffice = args.soffice || process.env.SOFFICE || 'soffice';
  const pdftoppm = args.pdftoppm || process.env.PDFTOPPM || 'pdftoppm';
  const catalog = readJson(catalogFile, []);
  if (!Array.isArray(catalog) || !catalog.length) throw new Error('GitHub 成品模板目录为空');
  fs.mkdirSync(outputDir, { recursive: true });
  fs.mkdirSync(workDir, { recursive: true });
  const profile = path.join(workDir, 'lo-profile');
  fs.mkdirSync(profile, { recursive: true });
  const existingManifest = readJson(path.join(outputDir, 'manifest.json'), {
    engine: 'pptxgenjs',
    slideCount: 5,
    templates: {}
  });
  existingManifest.slideCount = 5;
  existingManifest.templates ||= {};
  existingManifest.externalSources ||= {};

  for (const template of catalog) {
    const sourceFile = path.join(workDir, `${template.key}.pptx`);
    if (sourceDir) {
      const candidate = path.join(sourceDir, `${template.key}.pptx`);
      if (!fs.existsSync(candidate)) throw new Error(`source-dir 缺少 ${template.key}.pptx`);
      fs.copyFileSync(candidate, sourceFile);
    } else if (!fs.existsSync(sourceFile)) {
      run('curl', ['-LfsS', template.rawUrl, '-o', sourceFile]);
    }
    const pdfDir = path.join(workDir, `${template.key}-pdf`);
    fs.mkdirSync(pdfDir, { recursive: true });
    run(soffice, ['--headless', `-env:UserInstallation=file://${profile}`, '--convert-to', 'pdf', '--outdir', pdfDir, sourceFile]);
    const pdfFile = path.join(pdfDir, `${template.key}.pdf`);
    if (!fs.existsSync(pdfFile)) throw new Error(`LibreOffice 没有生成 PDF：${template.key}`);
    const keyDir = path.join(outputDir, template.key);
    fs.rmSync(keyDir, { recursive: true, force: true });
    fs.mkdirSync(keyDir, { recursive: true });
    run(pdftoppm, ['-png', '-r', '96', '-f', '1', '-l', '5', pdfFile, path.join(keyDir, 'slide')]);
    const generatedFiles = filesIn(keyDir);
    if (generatedFiles.length !== 5) throw new Error(`GitHub 模板 ${template.key} 预览页数异常：${generatedFiles.length}`);
    // pdftoppm pads short page numbers as slide-01 on some builds. Normalize
    // to the same slide-1.png contract used by the frontend and other packs.
    const normalizedFiles = generatedFiles.map((file, index) => {
      const target = `slide-${index + 1}.png`;
      if (file !== target) fs.renameSync(path.join(keyDir, file), path.join(keyDir, target));
      return target;
    });
    existingManifest.templates[template.key] = normalizedFiles.map((file) => `/ppt-template-previews/${template.key}/${file}`);
    existingManifest.externalSources[template.key] = {
      source: template.source,
      license: template.license,
      sourceUrl: template.sourceUrl,
      rawUrl: template.rawUrl,
      importedAt: new Date().toISOString()
    };
  }
  writeJson(path.join(outputDir, 'manifest.json'), existingManifest);
  process.stdout.write(`${JSON.stringify({ ok: true, engine: 'pptxgenjs-source-decks', templates: catalog.length, outputDir })}\n`);
}

try {
  main();
} catch (error) {
  process.stderr.write(`${error.stack || error.message}\n`);
  process.exitCode = 1;
}

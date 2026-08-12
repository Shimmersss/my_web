#!/usr/bin/env node

/** Fail builds when the committed HTML theme previews no longer match the renderer. */
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';

const scriptDir = path.resolve(path.dirname(new URL(import.meta.url).pathname));
const root = path.resolve(scriptDir, '../..');
const previewDir = path.join(root, 'front/public/html-template-previews');
const manifestFile = path.join(previewDir, 'manifest.json');
const themeFile = path.join(root, '.agents/skills/create-html-presentation/assets/themes.json');
const rendererFile = path.join(scriptDir, 'ppt-agent/html-presentation.mjs');

function sha256(file) {
  return crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex');
}

function fail(message) {
  process.stderr.write(`[html-preview-check] ${message}\n`);
  process.exit(1);
}

if (!fs.existsSync(manifestFile)) fail('缺少静态预览 manifest，请先运行 npm run prepare:html-previews');
const manifest = JSON.parse(fs.readFileSync(manifestFile, 'utf8'));
const themes = JSON.parse(fs.readFileSync(themeFile, 'utf8'));
const revealPackage = JSON.parse(fs.readFileSync(path.join(root, 'backen/node_modules/reveal.js/package.json'), 'utf8'));
if (manifest.themeSha256 !== sha256(themeFile)) fail('主题资产已变化，静态预览需要重新生成');
if (manifest.rendererSha256 !== sha256(rendererFile)) fail('HTML renderer 已变化，静态预览需要重新生成');
if (manifest.revealVersion !== String(revealPackage.version || '')) fail('reveal.js 版本已变化，静态预览需要重新生成');
if (Number(manifest.slideCount) !== 5) fail('静态预览页数必须为 5');
const expectedKeys = Object.keys(themes).sort();
const actualKeys = Object.keys(manifest.templates || {}).sort();
if (JSON.stringify(expectedKeys) !== JSON.stringify(actualKeys)) fail('主题目录与静态预览主题不一致');
for (const key of expectedKeys) {
  const files = manifest.templates[key];
  if (!Array.isArray(files) || files.length !== 5) fail(`${key} 的预览清单不完整`);
  for (let index = 1; index <= 5; index += 1) {
    const file = path.join(previewDir, key, `slide-${index}.png`);
    if (!fs.existsSync(file) || fs.statSync(file).size < 1024) fail(`${key} 第 ${index} 页预览缺失或为空`);
  }
}
process.stdout.write(`[html-preview-check] ${expectedKeys.length} themes × 5 pages verified\n`);

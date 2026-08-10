import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { preparePptdQuality } from './pptd-quality.mjs';

const manifest = `version: v2\nsize: [960, 540]\npages:\n  - pages/one.page\n`;

async function withProject(page, assertion) {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), 'pptd-quality-test-'));
  const pages = path.join(root, 'pages');
  await fs.mkdir(pages, { recursive: true });
  const manifestFile = path.join(root, 'deck.pptd');
  await fs.writeFile(manifestFile, manifest);
  await fs.writeFile(path.join(pages, 'one.page'), page);
  try { await assertion({ root, manifestFile }); } finally { await fs.rm(root, { recursive: true, force: true }); }
}

test('quality preflight normalizes the selected text font', async () => {
  await withProject(`elements:\n  - elementType: text\n    bounds: [60, 60, 700, 90]\n    content: {fontSize: 32, text: \"清晰的结论标题\"}\n`, async ({ root, manifestFile }) => {
    const result = await preparePptdQuality({ projectDir: root, manifestFile, pages: ['pages/one.page'], fontFamily: 'Noto Sans CJK SC' });
    const written = await fs.readFile(path.join(root, 'pages/one.page'), 'utf8');
    assert.equal(result.font, 'Noto Sans CJK SC');
    assert.match(written, /fontFamily: Noto Sans CJK SC/);
  });
});

test('quality preflight rejects overflowing title boxes and raw PDF page images', async () => {
  await withProject(`elements:\n  - elementType: text\n    bounds: [60, 60, 320, 35]\n    content: {fontSize: 30, text: \"这是一条过长、必须换行却没有足够高度的标题\"}\n  - elementType: image\n    bounds: [400, 60, 300, 220]\n    src: media/paper-page-3.png\n`, async ({ root, manifestFile }) => {
    await assert.rejects(
      preparePptdQuality({ projectDir: root, manifestFile, pages: ['pages/one.page'], fontFamily: 'Microsoft YaHei' }),
      /text box capacity[\s\S]*full PDF page/i
    );
  });
});

test('quality preflight rejects template residue and text outside the canvas', async () => {
  await withProject(`elements:\n  - elementType: text\n    bounds: [900, 60, 100, 40]\n    content: {fontSize: 18, text: \"Link Start!\"}\n`, async ({ root, manifestFile }) => {
    await assert.rejects(
      preparePptdQuality({ projectDir: root, manifestFile, pages: ['pages/one.page'], fontFamily: 'Microsoft YaHei' }),
      /template residue[\s\S]*escape the 960×540 canvas/i
    );
  });
});

test('quality preflight keeps compact metrics on one line and safely grows an isolated caption', async () => {
  await withProject(`elements:
  - elementType: text
    bounds: [60, 60, 110, 56]
    content: {fontSize: 44, wrap: false, text: "1950"}
  - elementType: text
    bounds: [60, 180, 212, 52]
    content: {fontSize: 17, lineHeight: 1.35, text: "强氧化自由基驱动母体转化；\\n其有效产率会受水质基质竞争影响。"}
`, async ({ root, manifestFile }) => {
    await preparePptdQuality({ projectDir: root, manifestFile, pages: ['pages/one.page'], fontFamily: 'Microsoft YaHei' });
    const written = await fs.readFile(path.join(root, 'pages/one.page'), 'utf8');
    assert.match(written, /- 69\n/);
  });
});

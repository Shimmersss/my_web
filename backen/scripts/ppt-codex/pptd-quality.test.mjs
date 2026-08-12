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

test('quality preflight resolves theme styles and inline color tokens before export', async () => {
  await withProject(`elements:
  - elementType: text
    bounds: [60, 60, 360, 80]
    content:
      style: $title
      text: '<p><span style="font-size:34px; color:$white; font-family:Microsoft YaHei;">智慧实验室</span></p>'
`, async ({ root, manifestFile }) => {
    await fs.writeFile(manifestFile, `version: v2
size: [960, 540]
theme:
  colors: {white: "#F5FBFF"}
  textStyles:
    title: {fontSize: 34, lineHeight: 1.1, color: "$white"}
pages:
  - pages/one.page
`);
    await preparePptdQuality({ projectDir: root, manifestFile, pages: ['pages/one.page'], fontFamily: 'Noto Sans CJK SC' });
    const written = await fs.readFile(path.join(root, 'pages/one.page'), 'utf8');
    assert.match(written, /color:#F5FBFF/);
    assert.match(written, /font-family:Noto Sans CJK\s+SC/);
    assert.match(written, /fontSize: 34/);
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

test('quality preflight permits a mixed CJK and punctuation status line that fits in its rendered width', async () => {
  await withProject(`elements:
  - elementType: text
    bounds: [94, 392, 280, 22]
    content: {fontSize: 14, wrap: false, text: "3 页概览 | 封面 · 核心流程 · 行动建议"}
`, async ({ root, manifestFile }) => {
    await preparePptdQuality({ projectDir: root, manifestFile, pages: ['pages/one.page'], fontFamily: 'Microsoft YaHei' });
  });
});

test('quality preflight does not count empty rich-text paragraph gaps as visible lines', async () => {
  await withProject(`elements:
  - elementType: text
    bounds: [648, 208, 102, 74]
    content:
      fontSize: 16
      text: |
        <p>协作中枢</p>
        <p style="margin-top:6px;">排程 / 审核 / 回写</p>
`, async ({ root, manifestFile }) => {
    await preparePptdQuality({ projectDir: root, manifestFile, pages: ['pages/one.page'], fontFamily: 'Microsoft YaHei' });
  });
});

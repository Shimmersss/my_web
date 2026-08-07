import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';
import { createHtmlPresentation } from './html-presentation.mjs';

test('HTML split pages do not render an oversized index overlay or overlap the image panel', async () => {
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'ppt-agent-html-'));
  const outputFile = path.join(directory, 'output.html');
  const imageFile = path.join(directory, 'topic.png');
  try {
    await fs.writeFile(imageFile, Buffer.from(
      'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=',
      'base64'
    ));
    await createHtmlPresentation({
      outputFile,
      plan: {
        title: '刀剑神域',
        slides: [{
          type: 'content', layout: 'split', section: '世界观', title: '现实与虚拟的边界',
          headline: '沉浸式世界改变了冒险的规则', bullets: ['完全潜行带来新的生存困局'], imageId: 'I01', sourceIds: []
        }, {
          type: 'content', layout: 'split', section: '剧情', title: '从进入到归返',
          headline: '故事由选择与代价推进', bullets: ['角色关系在危机中重构'], sourceIds: []
        }]
      },
      sourceImages: [{ id: 'I01', path: imageFile, fileName: 'topic.png' }],
      templateKey: 'html-reveal-white',
      themeFile: path.resolve('../.agents/skills/create-html-presentation/assets/themes.json')
    });
    const html = await fs.readFile(outputFile, 'utf8');
    assert.doesNotMatch(html, /<span>0?1<\/span>/);
    assert.doesNotMatch(html, /class="split-panel"/);
    assert.match(html, /class="slide layout-split has-image"/);
    assert.match(html, /class="slide layout-statement"/);
    assert.match(html, /现实与虚拟的边界/);
  } finally {
    await fs.rm(directory, { recursive: true, force: true });
  }
});

test('HTML renderer keeps source attribution in notes without a visible bibliography', async () => {
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'ppt-agent-html-refs-'));
  const outputFile = path.join(directory, 'output.html');
  try {
    await createHtmlPresentation({
      outputFile,
      plan: {
        title: '研究演示',
        slides: [{
          type: 'content', layout: 'statement', section: '作品设定', title: '虚拟世界的规则',
          headline: '沉浸与风险并存', bullets: ['全潜入改变了游戏体验'], sourceIds: ['S01']
        }]
      },
      sources: [{ id: 'S01', title: 'Sword Art Online', url: 'https://doi.org/10.1000/example', type: 'paper' }],
      sourceImages: [],
      templateKey: 'html-reveal-white',
      themeFile: path.resolve('../.agents/skills/create-html-presentation/assets/themes.json')
    });
    const html = await fs.readFile(outputFile, 'utf8');
    assert.doesNotMatch(html, /class="sources"/);
    assert.match(html, /S01 \| Sword Art Online/);
  } finally {
    await fs.rm(directory, { recursive: true, force: true });
  }
});

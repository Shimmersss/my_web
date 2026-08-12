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

test('HTML cover and dense image CSS reserve navigation-safe space without template labels', async () => {
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'ppt-agent-html-safe-'));
  const outputFile = path.join(directory, 'output.html');
  try {
    await createHtmlPresentation({
      outputFile,
      plan: { title: '刀剑神域', slides: [{ type: 'cover', layout: 'cover', title: '刀剑神域', bullets: [], sourceIds: [] }] },
      sourceImages: [],
      templateKey: 'html-reveal-white',
      themeFile: path.resolve('../.agents/skills/create-html-presentation/assets/themes.json')
    });
    const html = await fs.readFile(outputFile, 'utf8');
    assert.doesNotMatch(html, /<p class="kicker">cover<\/p>/i);
    assert.match(html, /padding:58px 72px 88px/);
    assert.match(html, /\.has-image h2\{font-size:46px\}/);
  } finally {
    await fs.rm(directory, { recursive: true, force: true });
  }
});

test('HTML semantic layouts render purpose-built structures instead of one repeated bullet shell', async () => {
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'ppt-agent-html-semantic-'));
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
        title: '语义页面',
        slides: [
          { type: 'section', layout: 'section', title: '研究进入验证阶段', bullets: ['从问题到证据'] },
          { type: 'content', layout: 'stats', title: '关键指标', bullets: ['92%:完成率', '3.4倍:效率提升'] },
          { type: 'content', layout: 'process', title: '方法流程', bullets: ['定义问题', '收集证据', '验证结果'] },
          { type: 'content', layout: 'comparison', title: '方案对比', bullets: ['方案 A 延迟低', '方案 A 成本高', '方案 B 延迟高', '方案 B 成本低'] },
          { type: 'content', layout: 'gallery', title: '现场观察', headline: '一张主视觉承载核心叙事', imageId: 'I01', bullets: ['保留紧凑策展说明'] }
        ]
      },
      sourceImages: [{ id: 'I01', path: imageFile, fileName: 'topic.png' }],
      templateKey: 'html-reveal-white',
      themeFile: path.resolve('../.agents/skills/create-html-presentation/assets/themes.json')
    });
    const html = await fs.readFile(outputFile, 'utf8');
    assert.match(html, /layout-section/);
    assert.match(html, /class="metric-grid"/);
    assert.match(html, /class="process-track"/);
    assert.match(html, /class="comparison-grid"/);
    assert.match(html, /class="gallery-grid"/);
    assert.match(html, /<strong>92%<\/strong><span>完成率<\/span>/);
  } finally {
    await fs.rm(directory, { recursive: true, force: true });
  }
});

test('HTML motion modes map to bounded presets and off mode removes animation features', async () => {
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'ppt-agent-html-motion-'));
  try {
    for (const mode of ['auto', 'subtle', 'expressive', 'off']) {
      const outputFile = path.join(directory, `${mode}.html`);
      await createHtmlPresentation({
        outputFile,
        plan: {
          title: '动效测试',
          slides: [{ type: 'content', layout: 'process', title: '可解释的流程', bullets: ['输入', '处理', '输出'] }]
        },
        sourceImages: [],
        templateKey: 'html-reveal-white',
        themeFile: path.resolve('../.agents/skills/create-html-presentation/assets/themes.json'),
        motionMode: mode
      });
      const html = await fs.readFile(outputFile, 'utf8');
      const authoredSlides = html.slice(html.indexOf('<div class="slides">'), html.indexOf('</div></div>'));
      assert.match(html, new RegExp(`data-motion-mode="${mode}"`));
      if (mode === 'off') {
        assert.match(authoredSlides, /data-motion="none"/);
        assert.doesNotMatch(authoredSlides, /data-auto-animate/);
        assert.doesNotMatch(authoredSlides, /class="fragment/);
      } else if (mode === 'subtle') {
        assert.match(authoredSlides, /data-motion="calm"/);
      } else if (mode === 'expressive') {
        assert.match(authoredSlides, /data-motion="flow"/);
        assert.match(authoredSlides, /class="fragment fade-up"/);
      } else {
        assert.match(authoredSlides, /data-motion="flow"/);
        assert.match(authoredSlides, /data-auto-animate/);
      }
    }
  } finally {
    await fs.rm(directory, { recursive: true, force: true });
  }
});

test('HTML runtime preserves Reveal blackout and exposes reduced-motion, print and low-power fallbacks', async () => {
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'ppt-agent-html-accessible-motion-'));
  const outputFile = path.join(directory, 'output.html');
  try {
    await createHtmlPresentation({
      outputFile,
      plan: { title: '无障碍动效', slides: [{ type: 'content', layout: 'timeline', title: '时间线', bullets: ['起点', '转折', '结论'] }] },
      sourceImages: [], templateKey: 'html-reveal-white',
      themeFile: path.resolve('../.agents/skills/create-html-presentation/assets/themes.json')
    });
    const html = await fs.readFile(outputFile, 'utf8');
    assert.match(html, /prefers-reduced-motion:reduce/);
    assert.match(html, /@media print/);
    assert.match(html, /String\(event\.key\)\.toLowerCase\(\)!=='l'/);
    assert.doesNotMatch(html, /toLowerCase\(\)!=='b'/);
    assert.match(html, /scrollActivationWidth:700/);
    assert.match(html, /pdfSeparateFragments:false/);
  } finally {
    await fs.rm(directory, { recursive: true, force: true });
  }
});

test('theme layout allowlist safely falls back when a requested semantic layout is unavailable', async () => {
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'ppt-agent-html-theme-guard-'));
  const outputFile = path.join(directory, 'output.html');
  const themeFile = path.join(directory, 'themes.json');
  try {
    await fs.writeFile(themeFile, JSON.stringify({
      constrained: { style: 'clean', background: '#fff', foreground: '#111', accent: '#06c', surface: '#eee', layouts: ['cover', 'statement', 'closing'] }
    }));
    await createHtmlPresentation({
      outputFile,
      plan: { title: '受限主题', slides: [{ type: 'content', layout: 'stats', title: '指标', bullets: ['80%:覆盖率'] }] },
      sourceImages: [], templateKey: 'constrained', themeFile
    });
    const html = await fs.readFile(outputFile, 'utf8');
    assert.match(html, /layout-statement/);
    assert.doesNotMatch(html, /layout-stats/);
  } finally {
    await fs.rm(directory, { recursive: true, force: true });
  }
});

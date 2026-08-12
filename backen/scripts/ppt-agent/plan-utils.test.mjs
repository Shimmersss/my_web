import assert from 'node:assert/strict';
import test from 'node:test';
import { combineChangedSlideNumbers, completeTextSlotEdits, diversifyPresentationImages, ensureImageEdits, ensurePptImageSlide, fitHtmlSlideToViewport, fitNarrativeFields, isCompatibleSourceRole, isTemplatePlaceholder, mergeRepairBatch, normalizeResearchDecision, removeFurnitureTextEdits, requestsVisualAssets } from './plan-utils.mjs';

test('revision QA batches retain pages changed by the initial revision plan', () => {
  assert.deepEqual(combineChangedSlideNumbers([2], [5], [2, '7']), [2, 5, 7]);
});

test('research decision accepts safe web-search aliases and singular queries', () => {
  assert.deepEqual(normalizeResearchDecision({ action: 'tool_call', args: { tool: 'web_search', query: 'Sword Art Online official' } }), {
    action: 'tool_call', args: { tool: 'search', queries: ['Sword Art Online official'] }
  });
  assert.deepEqual(normalizeResearchDecision({ action: 'search', args: { keywords: ['SAO characters'] } }), {
    action: 'tool_call', args: { tool: 'search', queries: ['SAO characters'] }
  });
  assert.equal(normalizeResearchDecision({ action: 'tool_call', args: { tool: 'shell', query: 'no' } }), null);
});

test('mergeRepairBatch accepts a batch-only response and maps it in batch order', () => {
  const base = { title: 'demo', slides: [{ id: 1 }, { id: 2 }, { id: 3 }, { id: 4 }, { id: 5 }] };
  const merged = mergeRepairBatch(base, { slides: [{ id: 'fixed-2' }, { id: 'fixed-4' }] }, [2, 4]);
  assert.deepEqual(merged.slides.map(slide => slide.id), [1, 'fixed-2', 3, 'fixed-4', 5]);
});

test('mergeRepairBatch honors explicit page numbers in a partial response', () => {
  const base = { slides: [{ id: 1 }, { id: 2 }, { id: 3 }, { id: 4 }] };
  const merged = mergeRepairBatch(base, { slides: [{ pageNumber: 4, id: 'fixed-4' }] }, [2, 4]);
  assert.deepEqual(merged.slides.map(slide => slide.id), [1, 2, 3, 'fixed-4']);
});

test('completeTextSlotEdits fills unmapped visible template frames', () => {
  const slide = { title: '刀剑神域', headline: '虚拟世界', bullets: ['角色与羁绊'], textEdits: [{ slotId: 's1-t1', text: '刀剑神域' }] };
  completeTextSlotEdits(slide, {
    textShapes: [
      { slotId: 's1-t1', roleHint: 'title', capacityChars: 12, furniture: false },
      { slotId: 's1-t2', roleHint: 'headline', capacityChars: 8, furniture: false },
      { slotId: 's1-t3', roleHint: 'body', capacityChars: 8, furniture: false },
      { slotId: 's1-t4', roleHint: 'title', capacityChars: 12, furniture: true }
    ]
  }, { takeaway: '沉浸式冒险' });
  assert.deepEqual(slide.textEdits.map(edit => edit.slotId), ['s1-t1', 's1-t2', 's1-t3']);
  assert.ok(slide.textEdits.every(edit => edit.text.length > 0));
});

test('visible slide copy strips source IDs while source metadata remains separate', () => {
  const slide = {
    title: '作品概览 [S01]',
    headline: '',
    bullets: ['动画于 2012 年首播 [S02, WEB03]'],
    sourceIds: ['S01', 'S02', 'WEB03'],
    textEdits: []
  };
  completeTextSlotEdits(slide, { textShapes: [
    { slotId: 'title', roleHint: 'title', capacityChars: 12, furniture: false },
    { slotId: 'body', roleHint: 'body', capacityChars: 30, furniture: false }
  ] }, {});
  assert.ok(slide.textEdits.every(edit => !/\[(?:S|WEB)\d+/i.test(edit.text)));
  assert.deepEqual(slide.sourceIds, ['S01', 'S02', 'WEB03']);
});

test('template sample labels are never accepted as presentation content', () => {
  for (const value of ['作品概述', 'Overview', 'CONTEN', 'CONTENTS', '第一部分', '添加标题', 'Click here to add title text', '根据自己的需要添加适当的文字，此处添加详细文本描述']) {
    assert.equal(isTemplatePlaceholder(value), true, value);
  }
  assert.equal(isTemplatePlaceholder('艾恩葛朗特的生存法则'), false);
});

test('removeFurnitureTextEdits ignores inherited numbering and footer slots', () => {
  const slide = { textEdits: [{ slotId: 's2-t1', text: '目录' }, { slotId: 's2-t5', text: '05' }] };
  removeFurnitureTextEdits(slide, {
    textShapes: [{ slotId: 's2-t1', furniture: false }, { slotId: 's2-t5', furniture: true }]
  });
  assert.deepEqual(slide.textEdits, [{ slotId: 's2-t1', text: '目录' }]);
});

test('completeTextSlotEdits drops invalid slots and fits overlong model text', () => {
  const slide = { title: '刀剑神域', headline: '世界观', bullets: [], textEdits: [
    { slotId: 's3-t3', text: '刀剑神域虚拟世界的背景与意义' },
    { slotId: 's3-t2', text: '01' },
    { slotId: 'unknown', text: '不应写入' }
  ] };
  completeTextSlotEdits(slide, { textShapes: [
    { slotId: 's3-t3', roleHint: 'title', capacityChars: 9, furniture: false },
    { slotId: 's3-t2', roleHint: 'title', capacityChars: 5, furniture: true },
    { slotId: 's3-t4', roleHint: 'body', capacityChars: 6, furniture: false }
  ] }, {});
  assert.deepEqual(slide.textEdits.map(edit => edit.slotId), ['s3-t3', 's3-t4']);
  assert.ok(slide.textEdits.every(edit => edit.text.length <= 9));
});

test('completeTextSlotEdits keeps stacked card labels paired with their bullet descriptions', () => {
  const slide = {
    title: '核心角色', headline: '羁绊与守护',
    bullets: ['桐人：黑衣剑士', '亚丝娜：闪光剑士', '爱丽丝：整合骑士'],
    textEdits: []
  };
  completeTextSlotEdits(slide, { textShapes: [
    { slotId: 'title', roleHint: 'title', capacityChars: 10, x: 0, y: 0, width: 100, height: 20 },
    { slotId: 'card-1', roleHint: 'title', capacityChars: 6, x: 3_000_000, y: 3_000_000, width: 1_000_000, height: 400_000 },
    { slotId: 'body-1', roleHint: 'body', capacityChars: 20, x: 3_000_000, y: 3_350_000, width: 2_000_000, height: 800_000 },
    { slotId: 'card-2', roleHint: 'title', capacityChars: 6, x: 3_000_000, y: 5_000_000, width: 1_000_000, height: 400_000 },
    { slotId: 'body-2', roleHint: 'body', capacityChars: 20, x: 3_000_000, y: 5_350_000, width: 2_000_000, height: 800_000 }
  ] }, {});
  const edits = Object.fromEntries(slide.textEdits.map(item => [item.slotId, item.text]));
  assert.equal(edits['card-1'], '桐人');
  assert.equal(edits['body-1'], '桐人：黑衣剑士');
  assert.equal(edits['card-2'], '亚丝娜');
  assert.equal(edits['body-2'], '亚丝娜：闪光剑士');
});

test('short title slots use complete SAO labels instead of clipped words', () => {
  for (const [title, expected] of [
    ['新生 ALO 与空中冒险', 'ALO空中冒险'],
    ['Alicization', '人界篇'],
    ['Gun Gale Online', 'GGO篇'],
    ['SAO 至 GGO 的征程', 'SAO至GGO']
  ]) {
    const slide = { title, headline: '', bullets: [], textEdits: [] };
    completeTextSlotEdits(slide, { textShapes: [
      { slotId: 'title', roleHint: 'title', capacityChars: 9, furniture: false }
    ] }, {});
    assert.equal(slide.textEdits[0].text, expected);
  }
});

test('oversized cover title uses a conservative complete franchise name', () => {
  const slide = { title: '刀剑神域深度解析', headline: '', bullets: [], textEdits: [] };
  completeTextSlotEdits(slide, { textShapes: [
    { slotId: 'title', roleHint: 'title', capacityChars: 7, maxFontPt: 81, furniture: false }
  ] }, {});
  fitNarrativeFields(slide, { textShapes: [
    { slotId: 'title', roleHint: 'title', capacityChars: 7, maxFontPt: 81, furniture: false }
  ] });
  assert.equal(slide.textEdits[0].text, '刀剑神域');
  assert.equal(slide.title, '刀剑神域');
});

test('visible title and headline slots reject model section numbers', () => {
  const slide = {
    title: '新生与危机', headline: 'Alicization与序列之争', bullets: [],
    textEdits: [{ slotId: 'title', text: '02' }, { slotId: 'headline', text: '03' }]
  };
  completeTextSlotEdits(slide, { textShapes: [
    { slotId: 'title', roleHint: 'title', capacityChars: 9, maxFontPt: 42, furniture: false },
    { slotId: 'headline', roleHint: 'headline', capacityChars: 20, maxFontPt: 24, furniture: false }
  ] }, {});
  assert.ok(slide.textEdits.every(edit => !/^\d{1,2}$/.test(edit.text)));
});

test('large content titles compress to complete seven-character labels', () => {
  for (const [title, expected] of [
    ['从世界观到文化影响', '世界观与影响'],
    ['从游戏机制到文化影响', '机制与文化影响'],
    ['从游戏机制到文', '机制与文化影响'],
    ['从死亡游戏到觉醒之路', '死亡游戏与觉醒'],
    ['虚拟世界中的人性光辉', '虚拟世界与人性']
  ]) {
    const slide = { title, headline: '', bullets: [], textEdits: [] };
    completeTextSlotEdits(slide, { textShapes: [
      { slotId: 'title', roleHint: 'title', capacityChars: 9, maxFontPt: 42, furniture: false }
    ] }, {});
    assert.equal(slide.textEdits[0].text, expected);
  }
});

test('fitNarrativeFields keeps required fields equal to visible fitted slot text', () => {
  const slide = { title: '刀剑神域的世界观', headline: '', bullets: [], textEdits: [{ slotId: 's3-t3', text: '刀剑神域' }] };
  fitNarrativeFields(slide, { textShapes: [{ slotId: 's3-t3', roleHint: 'title', capacityChars: 4, furniture: false }] });
  assert.equal(slide.title, '刀剑神域');
  assert.equal(slide.textEdits[0].text, '刀剑神域');
});

test('source layout roles reject using a section page as a content page', () => {
  assert.equal(isCompatibleSourceRole('content', 'section'), false);
  assert.equal(isCompatibleSourceRole('section', 'section'), true);
  assert.equal(isCompatibleSourceRole('cover', 'cover'), true);
  assert.equal(isCompatibleSourceRole('closing', 'cover'), false);
  assert.equal(isCompatibleSourceRole('closing', 'closing'), true);
});

test('visual wording prefers a relevant web image without allowing an unrelated one', () => {
  assert.equal(requestsVisualAssets('请做一份图文并茂的 Sword Art Online PPT，并使用网络图片'), true);
  assert.equal(requestsVisualAssets('Create a text-only research summary'), false);
  const webImage = {
    id: 'WEB01',
    path: '/tmp/WEB01.jpg',
    title: 'licensed image',
    searchQuery: 'Sword Art Online characters',
    origin: 'web-search',
    sourceUrl: 'https://commons.wikimedia.org/wiki/File:Characters.jpg'
  };
  const slide = { type: 'content', title: '角色关系与世界设定', layout: 'statement' };
  ensureImageEdits(slide, { imageSlots: [{ slotId: 's1-i1', fillable: true }] }, [webImage], 'pptx', {
    preferImage: true,
    visualTopic: 'Sword Art Online 图文并茂 网络图片'
  });
  assert.deepEqual(slide.imageEdits, [{ slotId: 's1-i1', imageId: 'WEB01' }]);
});

test('image assets are attached to compatible empty PPTX and HTML slots', () => {
  const images = [{ id: 'WEB01', path: '/tmp/WEB01.png', title: 'research image' }];
  const pptSlide = { type: 'evidence', title: '研究结果', layout: 'evidence', imageHint: 'research image', imageEdits: [] };
  ensureImageEdits(pptSlide, { imageSlots: [{ slotId: 's1-i1', fillable: true }] }, images, 'pptx');
  assert.deepEqual(pptSlide.imageEdits, [{ slotId: 's1-i1', imageId: 'WEB01' }]);
  const htmlSlide = {
    type: 'content', title: '研究结果', layout: 'split', imageHint: 'research image',
    imageEdits: [{ slotId: 'model-image-slot', imageId: 'WEB01' }]
  };
  ensureImageEdits(htmlSlide, null, images, 'html');
  assert.equal(htmlSlide.imageId, 'WEB01');
  const unrelatedWebImage = { id: 'WEB02', path: '/tmp/WEB02.png', title: 'Google Artificial Intelligence Laboratory', origin: 'web-search', sourceUrl: 'https://commons.wikimedia.org/wiki/File:Lab.jpg' };
  const guardedHtmlSlide = { type: 'content', title: 'Sword Art Online 角色关系', layout: 'split', imageId: 'WEB02' };
  ensureImageEdits(guardedHtmlSlide, null, [unrelatedWebImage], 'html');
  assert.equal(guardedHtmlSlide.imageId, '');
});

test('HTML image-rich plans rotate distinct relevant images instead of repeating one asset', () => {
  const plan = { slides: [
    { type: 'cover', title: '刀剑神域', imageId: '' },
    { type: 'content', title: '虚拟世界', imageId: 'WEB01' },
    { type: 'content', title: '攻略与羁绊', imageId: 'WEB01' },
    { type: 'comparison', title: '现实与虚拟', imageId: 'WEB01' },
    { type: 'content', title: '文化影响', imageId: 'WEB01' }
  ] };
  const images = ['WEB01', 'WEB02', 'WEB03'].map(id => ({
    id,
    path: `/tmp/${id}.jpg`,
    title: `Sword Art Online visual ${id}`,
    searchQuery: 'Sword Art Online virtual reality',
    origin: 'web-search'
  }));
  diversifyPresentationImages(plan, 'html', images, {
    preferVisualAssets: true,
    visualTopic: '刀剑神域图文并茂'
  });
  assert.deepEqual(plan.slides.map(slide => slide.imageId), ['', 'WEB01', 'WEB02', 'WEB03', '']);
});

test('HTML cover and closing pages clear PPTX-shaped image edits instead of restoring them', () => {
  const images = [{
    id: 'WEB01', path: '/tmp/WEB01.jpg', title: 'Sword Art Online visual',
    searchQuery: 'Sword Art Online', origin: 'web-search'
  }];
  for (const type of ['cover', 'closing']) {
    const slide = { type, title: '刀剑神域', imageId: 'WEB01', imageEdits: [{ imageId: 'WEB01' }] };
    ensureImageEdits(slide, null, images, 'html', { preferImage: true, visualTopic: '刀剑神域' });
    assert.equal(slide.imageId, '');
    assert.deepEqual(slide.imageEdits, []);
  }
});

test('HTML viewport fitting converts image-heavy layouts and caps visible density', () => {
  const slide = {
    type: 'content', layout: 'timeline', imageId: 'WEB01',
    title: '这是一段明显超过图片分栏安全宽度的刀剑神域时间线页面标题',
    headline: '这是一段很长的说明文字，需要在固定十六比九画布里保持完整可读而不是贴近页面底部或导航控件',
    bullets: Array.from({ length: 6 }, (_, index) => `第 ${index + 1} 条时间线说明包含较长的叙事内容与补充背景信息`)
  };
  fitHtmlSlideToViewport(slide);
  assert.equal(slide.layout, 'split');
  assert.ok(slide.title.length <= 34);
  assert.ok(slide.headline.length <= 76);
  assert.equal(slide.bullets.length, 3);
  assert.ok(slide.bullets.every(item => item.length <= 56));
});

test('HTML viewport fitting preserves safe image-hero and gallery silhouettes', () => {
  for (const layout of ['image-hero', 'gallery', 'evidence']) {
    const slide = {
      type: 'content', layout, imageId: 'WEB01', title: '一张图像的叙事',
      headline: '使用固定语义布局', bullets: ['核心证据', '紧凑说明']
    };
    fitHtmlSlideToViewport(slide);
    assert.equal(slide.layout, layout);
    assert.equal(slide.bullets.length, 2);
  }
});

test('HTML viewport fitting allows balanced comparison sides and four-step metrics', () => {
  const comparison = { type: 'content', layout: 'comparison', title: '对比', bullets: Array.from({ length: 8 }, (_, index) => `观点 ${index + 1}`) };
  fitHtmlSlideToViewport(comparison, { hasImage: false });
  assert.equal(comparison.bullets.length, 6);
  const stats = { type: 'content', layout: 'stats', title: '指标', bullets: Array.from({ length: 6 }, (_, index) => `${index + 1}:指标`) };
  fitHtmlSlideToViewport(stats, { hasImage: false });
  assert.equal(stats.bullets.length, 4);
});

test('HTML viewport fitting normalizes structured items when the model omits bullets', () => {
  const slide = {
    type: 'content', layout: 'stats', title: '关键指标', bullets: [],
    items: [
      { label: '覆盖率', value: '92%', detail: '完成核心场景验证' },
      { label: '效率', value: '3.4倍', detail: '相对原流程提升' }
    ]
  };
  fitHtmlSlideToViewport(slide, { hasImage: false });
  assert.deepEqual(slide.bullets, ['覆盖率：92% — 完成核心场景验证', '效率：3.4倍 — 相对原流程提升']);
  assert.equal('items' in slide, false);
});

test('SAO image diversification reranks VR, Aincrad and Alicization visuals by slide topic', () => {
  const plan = { slides: [
    { type: 'content', title: '作品概述与虚拟现实', imageId: 'WEB02', imageEdits: [{ imageId: 'WEB02' }] },
    { type: 'content', title: '艾恩葛朗特浮游城', imageId: 'WEB01', imageEdits: [{ imageId: 'WEB01' }] },
    { type: 'content', title: 'Alicization 与爱丽丝', imageId: 'WEB01', imageEdits: [{ imageId: 'WEB01' }] }
  ] };
  const images = [
    { id: 'WEB01', path: '/tmp/vr.jpg', title: 'VR', searchQuery: 'Sword Art Online virtual reality' },
    { id: 'WEB02', path: '/tmp/castle.jpg', title: 'Castle', searchQuery: 'Sword Art Online Aincrad fantasy castle' },
    { id: 'WEB03', path: '/tmp/forest.jpg', title: 'Forest', searchQuery: 'Sword Art Online Alicization fantasy forest' }
  ];
  diversifyPresentationImages(plan, 'html', images, { preferVisualAssets: true, visualTopic: '刀剑神域' });
  assert.deepEqual(plan.slides.map(slide => slide.imageId), ['WEB01', 'WEB02', 'WEB03']);
});

test('PPTX image fallback reroutes one content page to an inherited fillable frame', () => {
  const plan = { slides: [
    { type: 'cover', sourceSlide: 1 },
    { type: 'content', sourceSlide: 5, textEdits: [], imageEdits: [], imageHint: 'research image' }
  ] };
  ensurePptImageSlide(plan, {
    slides: [
      { slide: 5, visual: { role: 'content' }, imageSlots: [] },
      { slide: 16, visual: { role: 'content' }, imageSlots: [{ slotId: 's16-i1', fillable: true }] }
    ]
  }, [{ id: 'WEB01', path: '/tmp/WEB01.jpg', title: 'research image' }]);
  assert.equal(plan.slides[1].sourceSlide, 16);
  assert.deepEqual(plan.slides[1].textEdits, []);
  assert.deepEqual(plan.slides[1].imageEdits, [{ slotId: 's16-i1', imageId: 'WEB01' }]);
});

test('PPTX image fallback discards object-shaped model edit collections safely', () => {
  const plan = { slides: [{
    type: 'content', sourceSlide: 8,
    textEdits: { slotId: 'bad-text', text: 'wrong shape' },
    imageEdits: { slotId: 'bad-image', imageId: 'WEB01' }
  }] };
  ensurePptImageSlide(plan, { slides: [{
    slide: 8,
    visual: { role: 'content' },
    imageSlots: [{ slotId: 's8-i1', fillable: true }],
    textShapes: []
  }] }, [{ id: 'WEB01', path: '/tmp/WEB01.jpg', title: 'Sword Art Online' }], {
    preferVisualAssets: true,
    visualTopic: 'Sword Art Online'
  });
  assert.deepEqual(plan.slides[0].textEdits, []);
  assert.deepEqual(plan.slides[0].imageEdits, [{ slotId: 's8-i1', imageId: 'WEB01' }]);
});

test('PPTX image fallback prefers a source page without overlapping text geometry', () => {
  const plan = { slides: [{ type: 'content', sourceSlide: 4, textEdits: [], imageEdits: [] }] };
  ensurePptImageSlide(plan, { slides: [
    {
      slide: 4,
      visual: { role: 'content' },
      imageSlots: [{ slotId: 's4-i1', fillable: true }],
      textShapes: [
        { x: 0, y: 0, width: 100, height: 100 },
        { x: 10, y: 10, width: 100, height: 100 }
      ]
    },
    {
      slide: 13,
      visual: { role: 'content' },
      imageSlots: [{ slotId: 's13-i1', fillable: true }],
      textShapes: [
        { x: 0, y: 0, width: 100, height: 50 },
        { x: 200, y: 0, width: 100, height: 50 }
      ]
    }
  ] }, [{ id: 'WEB01', path: '/tmp/WEB01.jpg', title: 'Sword Art Online VR' }], {
    preferVisualAssets: true,
    visualTopic: 'Sword Art Online'
  });
  assert.equal(plan.slides[0].sourceSlide, 13);
  assert.deepEqual(plan.slides[0].imageEdits, [{ slotId: 's13-i1', imageId: 'WEB01' }]);
});

test('image-rich PPTX rejects an existing image edit on an unsafe source page and rebuilds model text', () => {
  const plan = { slides: [{
    type: 'content', sourceSlide: 8,
    title: '刀剑神域世界观', bullets: ['艾恩葛朗特'],
    textEdits: [{ slotId: 's8-t1', text: '复制错误' }],
    imageEdits: [{ slotId: 's8-i1', imageId: 'WEB01' }]
  }] };
  ensurePptImageSlide(plan, { slides: [
    {
      slide: 8, visual: { role: 'content' }, imageSlots: [{ slotId: 's8-i1', fillable: true }],
      textShapes: [
        { x: 0, y: 0, width: 100, height: 100 },
        { x: 10, y: 10, width: 100, height: 100 }
      ]
    },
    {
      slide: 13, visual: { role: 'content' }, imageSlots: [{ slotId: 's13-i1', fillable: true }],
      textShapes: [{ x: 0, y: 0, width: 100, height: 50 }]
    }
  ] }, [{ id: 'WEB01', path: '/tmp/WEB01.jpg', title: 'Sword Art Online VR' }], {
    preferVisualAssets: true,
    visualTopic: 'Sword Art Online'
  });
  assert.equal(plan.slides[0].sourceSlide, 13);
  assert.deepEqual(plan.slides[0].textEdits, []);
  assert.deepEqual(plan.slides[0].imageEdits, [{ slotId: 's13-i1', imageId: 'WEB01' }]);
});

test('image-rich PPTX prefers safe content pages over diagram-like evidence pages', () => {
  const plan = { slides: [{ type: 'evidence', sourceSlide: 14, textEdits: [], imageEdits: [] }] };
  ensurePptImageSlide(plan, { slides: [
    { slide: 13, visual: { role: 'content' }, imageSlots: [{ slotId: 's13-i1', fillable: true }], textShapes: [] },
    { slide: 14, visual: { role: 'evidence' }, imageSlots: [{ slotId: 's14-i1', fillable: true }], textShapes: [] }
  ] }, [{ id: 'WEB01', path: '/tmp/WEB01.jpg', title: 'Sword Art Online VR' }], {
    preferVisualAssets: true,
    visualTopic: 'Sword Art Online'
  });
  assert.equal(plan.slides[0].sourceSlide, 13);
});

test('image-rich PPTX avoids section-number and percentage-dashboard source pages', () => {
  const plan = { slides: [{ type: 'content', sourceSlide: 3, textEdits: [], imageEdits: [] }] };
  ensurePptImageSlide(plan, { slides: [
    {
      slide: 3, visual: { role: 'content' }, imageSlots: [{ slotId: 's3-i1', fillable: true }],
      textShapes: [{ text: '01', maxFontPt: 132, capacityChars: 4 }]
    },
    {
      slide: 13, visual: { role: 'content' }, imageSlots: [{ slotId: 's13-i1', fillable: true }],
      textShapes: [{ text: '标题', maxFontPt: 28, capacityChars: 12 }]
    },
    {
      slide: 14, visual: { role: 'content' }, imageSlots: [{ slotId: 's14-i1', fillable: true }],
      textShapes: ['12%', '9%', '6%'].map(text => ({ text, maxFontPt: 20, capacityChars: 4 }))
    }
  ] }, [{ id: 'WEB01', path: '/tmp/WEB01.jpg', title: 'Sword Art Online' }], {
    preferVisualAssets: true,
    visualTopic: 'Sword Art Online'
  });
  assert.equal(plan.slides[0].sourceSlide, 13);
});

test('image-rich PPTX prefers a clean split page over content pages with decorative icon pictures', () => {
  const plan = { slides: [{ type: 'content', sourceSlide: 13, textEdits: [], imageEdits: [] }] };
  ensurePptImageSlide(plan, { slides: [
    {
      slide: 8, visual: { role: 'content' }, imageSlots: [{ slotId: 's8-i1', fillable: true }],
      textShapes: []
    },
    {
      slide: 13, visual: { role: 'content' },
      imageSlots: [
        { slotId: 's13-i1', fillable: true },
        ...Array.from({ length: 3 }, (_, i) => ({ slotId: `decor-${i}`, fillable: false, fillableReason: 'too-small-for-content-image' }))
      ],
      textShapes: []
    }
  ] }, [{ id: 'WEB01', path: '/tmp/WEB01.jpg', title: 'Sword Art Online' }], {
    preferVisualAssets: true,
    visualTopic: 'Sword Art Online'
  });
  assert.equal(plan.slides[0].sourceSlide, 8);
});

test('image-rich PPTX keeps every content page on the clean split layout and only uses first-party assets when available', () => {
  const plan = { slides: Array.from({ length: 6 }, (_, index) => ({
    type: 'content', sourceSlide: index % 2 ? 13 : 7, textEdits: [{ slotId: 'old', text: 'old' }], imageEdits: []
  })) };
  const cleanTextShapes = [
    { x: 0, y: 0, width: 100, height: 100 },
    { x: 0, y: 78, width: 100, height: 100 }
  ];
  const decorativeSlots = [
    { slotId: 'hero', fillable: true },
    ...Array.from({ length: 3 }, (_, index) => ({
      slotId: `icon-${index}`, fillable: false, fillableReason: 'too-small-for-content-image'
    }))
  ];
  const images = [
    ...Array.from({ length: 4 }, (_, index) => ({
      id: `OFFICIAL${index + 1}`,
      path: `/tmp/official-${index + 1}.jpg`,
      title: 'Sword Art Online official visual',
      license: 'First-party source-page media; reuse terms require verification',
      sourceUrl: `https://${index % 2 ? 'sao-p.net' : 'sao-alicization.net'}/visual-${index + 1}`,
      origin: 'web-search'
    })),
    ...Array.from({ length: 4 }, (_, index) => ({
      id: `GENERIC${index + 1}`,
      path: `/tmp/generic-${index + 1}.jpg`,
      title: 'Sword Art Online contextual virtual reality stock photo'
    }))
  ];
  ensurePptImageSlide(plan, { slides: [
    { slide: 7, visual: { role: 'content' }, imageSlots: decorativeSlots, textShapes: [] },
    { slide: 8, visual: { role: 'content' }, imageSlots: [{ slotId: 's8-i2', fillable: true }], textShapes: cleanTextShapes },
    { slide: 13, visual: { role: 'content' }, imageSlots: decorativeSlots, textShapes: [] }
  ] }, images, { preferVisualAssets: true, visualTopic: 'Sword Art Online 刀剑神域' });
  assert.ok(plan.slides.every(slide => slide.sourceSlide === 8));
  assert.ok(plan.slides.every(slide => slide.textEdits.length === 0));
  assert.deepEqual(plan.slides.map(slide => slide.imageEdits?.[0]?.imageId || ''), [
    'OFFICIAL1', 'OFFICIAL2', 'OFFICIAL3', 'OFFICIAL4', 'OFFICIAL1', 'OFFICIAL2'
  ]);
});

test('first-party presentation images cannot be replaced by generic contextual stock during diversification', () => {
  const plan = { slides: [
    { type: 'content', title: '世界观', imageEdits: [{ slotId: 'hero', imageId: 'GENERIC' }] },
    { type: 'content', title: '角色', imageEdits: [{ slotId: 'hero', imageId: 'GENERIC' }] }
  ] };
  const images = [
    { id: 'OFFICIAL', path: '/tmp/official.jpg', title: 'Sword Art Online official visual', license: 'First-party source-page media; reuse terms require verification', origin: 'web-search', sourceUrl: 'https://sao-p.net/' },
    { id: 'GENERIC', path: '/tmp/generic.jpg', title: 'Sword Art Online virtual reality stock photo', origin: 'web-search', sourceUrl: 'https://stock.example/' }
  ];
  diversifyPresentationImages(plan, 'pptx', images, { preferVisualAssets: true, visualTopic: '刀剑神域' });
  assert.deepEqual(plan.slides.map(slide => slide.imageEdits[0].imageId), ['OFFICIAL', 'OFFICIAL']);
});

test('SAO image selection rejects aggregator media mislabeled as first-party and favors character artwork over the logo', () => {
  const plan = { slides: [{ type: 'content', sourceSlide: 8, title: '桐谷和人', textEdits: [], imageEdits: [] }] };
  const manifest = { slides: [{
    slide: 8, visual: { role: 'content' }, imageSlots: [{ slotId: 'hero', fillable: true }], textShapes: []
  }] };
  const images = [
    { id: 'LOGO', path: '/tmp/logo.jpg', title: 'Sword Art Online official visual', license: 'First-party source-page media; reuse terms require verification', sourceUrl: 'https://www.swordart-online.net/' },
    { id: 'CHARACTERS', path: '/tmp/characters.jpg', title: 'Sword Art Online Progressive official visual', license: 'First-party source-page media; reuse terms require verification', sourceUrl: 'https://sao-p.net/' },
    { id: 'AGGREGATOR', path: '/tmp/sina.jpg', title: 'Sword Art Online repost', license: 'First-party source-page media; reuse terms require verification', sourceUrl: 'https://k.sina.cn/article/demo' }
  ];
  ensurePptImageSlide(plan, manifest, images, { preferVisualAssets: true, visualTopic: '刀剑神域' });
  assert.equal(plan.slides[0].imageEdits[0].imageId, 'CHARACTERS');
});

test('image-rich PPTX plans use distinct assets on multiple compatible content pages', () => {
  const plan = { slides: [
    { type: 'cover', sourceSlide: 1, imageEdits: [] },
    { type: 'content', sourceSlide: 4, textEdits: [], imageEdits: [
      { slotId: 's4-i1', imageId: 'WEB01' },
      { slotId: 's4-i2', imageId: 'WEB01' }
    ] },
    { type: 'content', sourceSlide: 5, textEdits: [], imageEdits: [] },
    { type: 'evidence', sourceSlide: 6, textEdits: [], imageEdits: [] }
  ] };
  const manifest = { slides: [
    { slide: 1, visual: { role: 'cover' }, imageSlots: [] },
    { slide: 4, visual: { role: 'content' }, imageSlots: [
      { slotId: 's4-i1', fillable: true }, { slotId: 's4-i2', fillable: true }
    ] },
    { slide: 5, visual: { role: 'content' }, imageSlots: [] },
    { slide: 6, visual: { role: 'evidence' }, imageSlots: [] }
  ] };
  const images = ['WEB01', 'WEB02', 'WEB03'].map(id => ({
    id,
    path: `/tmp/${id}.jpg`,
    title: `Sword Art Online visual ${id}`,
    searchQuery: 'Sword Art Online virtual reality'
  }));
  ensurePptImageSlide(plan, manifest, images, {
    preferVisualAssets: true,
    visualTopic: 'Sword Art Online 图文并茂'
  });
  assert.deepEqual(plan.slides.slice(1).map(slide => slide.imageEdits?.[0]?.imageId), ['WEB01', 'WEB02', 'WEB03']);
  assert.ok(plan.slides.slice(1).every(slide => slide.imageEdits.length === 1));
});

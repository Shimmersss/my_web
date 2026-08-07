import assert from 'node:assert/strict';
import test from 'node:test';
import { combineChangedSlideNumbers, completeTextSlotEdits, ensureImageEdits, ensurePptImageSlide, fitNarrativeFields, isCompatibleSourceRole, isTemplatePlaceholder, mergeRepairBatch, removeFurnitureTextEdits, requestsVisualAssets } from './plan-utils.mjs';

test('revision QA batches retain pages changed by the initial revision plan', () => {
  assert.deepEqual(combineChangedSlideNumbers([2], [5], [2, '7']), [2, 5, 7]);
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

test('template sample labels are never accepted as presentation content', () => {
  for (const value of ['作品概述', 'Overview', '第一部分', '添加标题', 'Click here to add title text', '根据自己的需要添加适当的文字，此处添加详细文本描述']) {
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
});

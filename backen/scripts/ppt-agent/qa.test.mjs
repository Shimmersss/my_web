import assert from 'node:assert/strict';
import test from 'node:test';
import { deterministicQa, ensureReferenceCoverage, hasAcademicSources, isAcademicSource, reviewSlideNumbers } from './qa.mjs';

test('follow-up visual review scopes only valid repaired pages and falls back to full deck', () => {
  const plan = { slides: [{}, {}, {}, {}] };
  assert.deepEqual(reviewSlideNumbers(plan, [4, 2, 2, 99, 0, 'bad']), [2, 4]);
  assert.deepEqual(reviewSlideNumbers(plan, []), [1, 2, 3, 4]);
});

test('localized closing copy is not treated as an unfinished template placeholder', () => {
  const qa = deterministicQa({
    slides: [{ type: 'closing', title: '感谢观看', headline: '谢谢聆听', sourceIds: [], bullets: [] }]
  }, [], { valid: true });
  assert.equal(qa.valid, true);
});

test('academic detection accepts paper-like sources consistently', () => {
  assert.equal(isAcademicSource({ type: 'paper', title: 'Paper' }), true);
  assert.equal(isAcademicSource({ type: 'web', url: 'https://doi.org/10.1000/example' }), true);
  assert.equal(isAcademicSource({ type: 'web', url: 'https://example.com/article' }), false);
  assert.equal(hasAcademicSources([{ type: 'web', url: 'https://doi.org/10.1000/example' }]), true);
});

test('research sources do not force visible citation metadata onto delivery decks', () => {
  const qa = deterministicQa({
    slides: [{ type: 'content', title: '关键要素回顾', sourceIds: [], bullets: [] }]
  }, [{ id: 'S01', title: 'Paper', type: 'paper' }], { valid: true });
  assert.equal(qa.valid, true);
  assert.deepEqual(qa.citationIssues, []);
});

test('academic decks use slide metadata without a visible bibliography page', () => {
  const qa = deterministicQa({
    slides: [
      { type: 'evidence', title: '证据', sourceIds: ['S01'], bullets: [] },
      { type: 'content', title: '延伸解读', sourceIds: ['S02'], bullets: [] }
    ]
  }, [
    { id: 'S01', title: 'Paper A', type: 'paper' },
    { id: 'S02', title: 'Paper B', type: 'paper' }
  ], { valid: true });
  assert.equal(qa.valid, true);
  assert.deepEqual(qa.citationIssues, []);
});

test('reference normalization removes legacy visible bibliography pages', () => {
  const plan = {
    slides: [
      { type: 'evidence', title: '证据', sourceIds: ['S01', 'S03'], bullets: [] },
      { type: 'references', title: '参考文献', sourceIds: ['S01'], bullets: [] }
    ]
  };
  const sources = [
    { id: 'S01', title: 'Paper A', type: 'paper' },
    { id: 'S02', title: 'Paper B', type: 'paper' },
    { id: 'S03', title: 'Paper C', type: 'paper' }
  ];

  ensureReferenceCoverage(plan, sources);
  const qa = deterministicQa(plan, sources, { valid: true });

  assert.equal(plan.slides.length, 1);
  assert.equal(plan.slides.some(slide => slide.type === 'references'), false);
  assert.deepEqual(qa.citationIssues, []);
  assert.equal(qa.valid, true);
});

test('reference normalization does not add academic bibliography pages', () => {
  const plan = { slides: [{ type: 'content', title: '作品主题', sourceIds: ['S01'], bullets: [] }] };
  const sources = [
    { id: 'S01', title: 'Paper A', type: 'paper' },
    { id: 'S02', title: 'Paper B', type: 'paper' }
  ];
  ensureReferenceCoverage(plan, sources);
  assert.equal(plan.slides.length, 1);
  assert.deepEqual(deterministicQa(plan, sources, { valid: true }).citationIssues, []);
});

test('a closing slide mentioning references remains delivery content', () => {
  const plan = {
    slides: [{ type: 'closing', title: '参考文献 & Q&A', sourceIds: ['S01'], bullets: [] }]
  };
  const sources = [{ id: 'S01', title: 'Paper A', type: 'paper' }];
  ensureReferenceCoverage(plan, sources);
  assert.equal(plan.slides.length, 1);
  assert.equal(deterministicQa(plan, sources, { valid: true }).valid, true);
});

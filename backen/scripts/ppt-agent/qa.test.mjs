import assert from 'node:assert/strict';
import test from 'node:test';
import { deterministicQa } from './qa.mjs';

test('research sources cannot pass with zero slide citations', () => {
  const qa = deterministicQa({
    slides: [{ type: 'references', title: '关键要素回顾', sourceIds: [], bullets: [] }]
  }, [{ id: 'S01', title: 'Paper', type: 'paper' }], { valid: true });
  assert.equal(qa.valid, false);
  assert.ok(qa.citationIssues.some(issue => issue.includes('没有任何')));
  assert.ok(qa.citationIssues.some(issue => issue.includes('参考文献页缺少来源 S01')));
});

test('academic references pages must cover every retained source', () => {
  const qa = deterministicQa({
    slides: [
      { type: 'evidence', title: '证据', sourceIds: ['S01'], bullets: [] },
      { type: 'references', title: '参考文献', sourceIds: ['S01'], bullets: [] }
    ]
  }, [
    { id: 'S01', title: 'Paper A', type: 'paper' },
    { id: 'S02', title: 'Paper B', type: 'paper' }
  ], { valid: true });
  assert.equal(qa.valid, false);
  assert.ok(qa.citationIssues.some(issue => issue.includes('S02')));
});

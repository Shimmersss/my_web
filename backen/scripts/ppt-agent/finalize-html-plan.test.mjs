import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { finalizeHtmlPlan } from './finalize-html-plan.mjs';

const onePixelPng = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M/wHwAF/gL+Xv2JxQAAAABJRU5ErkJggg==',
  'base64'
);

test('fixed HTML finalizer normalizes a Codex JSON plan and renders real previews', async () => {
  const taskDir = await fs.mkdtemp(path.join(os.tmpdir(), 'html-finalize-test-'));
  try {
    await fs.mkdir(path.join(taskDir, 'images'));
    await fs.writeFile(path.join(taskDir, 'images', 'topic.png'), onePixelPng);
    const planFile = path.join(taskDir, 'codex-html-plan.json');
    await fs.writeFile(planFile, JSON.stringify({
      title: '安全的语义演示',
      slides: [
        { type: 'cover', layout: 'cover', title: '安全的语义演示', headline: 'Codex 只负责结构化创作' },
        { type: 'content', layout: 'stats', title: '三个质量门', items: [
          { value: '13', label: '语义布局' }, { value: '4', label: '动效档位' }, { value: '100%', label: '固定渲染' }
        ] },
        { type: 'content', layout: 'process', title: '受控流水线', imageId: 'U01', bullets: ['Codex 编排 JSON', '服务器验证结构', 'Reveal 固定渲染', 'Chrome 真机质检'] },
        { type: 'closing', layout: 'closing', title: '结束', headline: '谢谢聆听' }
      ]
    }));
    const result = await finalizeHtmlPlan({
      taskDir, planFile, templateKey: 'html-reveal-white', fontFamily: 'Microsoft YaHei',
      motionMode: 'subtle', visualMode: 'auto'
    });
    assert.equal(result.slideCount, 4);
    assert.equal(result.qa.valid, true);
    assert.ok((await fs.stat(path.join(taskDir, 'output.html'))).size > 1000);
    assert.ok((await fs.stat(path.join(taskDir, 'preview', 'slide-4.png'))).size > 1000);
    const plan = JSON.parse(await fs.readFile(path.join(taskDir, 'agent-plan.json'), 'utf8'));
    assert.deepEqual(plan.slides[1].bullets, ['语义布局：13', '动效档位：4', '固定渲染：100%']);
    assert.equal(plan.slides[2].layout, 'process');
    assert.equal(plan.slides[2].imageId, '');
    assert.equal(plan.slides[2].bullets.length, 4);
    assert.equal(plan.slides.at(-1).title, '感谢观看');
  } finally {
    await fs.rm(taskDir, { recursive: true, force: true });
  }
});

test('fixed HTML finalizer rejects plans outside the task directory', async () => {
  const taskDir = await fs.mkdtemp(path.join(os.tmpdir(), 'html-finalize-path-'));
  const outside = await fs.mkdtemp(path.join(os.tmpdir(), 'html-finalize-outside-'));
  try {
    const planFile = path.join(outside, 'plan.json');
    await fs.writeFile(planFile, JSON.stringify({ slides: [{}, {}, {}] }));
    await assert.rejects(() => finalizeHtmlPlan({ taskDir, planFile }), /路径不安全/);
  } finally {
    await fs.rm(taskDir, { recursive: true, force: true });
    await fs.rm(outside, { recursive: true, force: true });
  }
});

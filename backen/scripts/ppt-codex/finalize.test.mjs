import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { spawn } from 'node:child_process';

const script = path.resolve('scripts/ppt-codex/finalize.mjs');
const vendor = path.resolve('../vendor/open-kimi-ppt-skill');

async function runFixture(manifest, extra = {}) {
  const task = await fs.mkdtemp(path.join(os.tmpdir(), 'pptd-finalize-test-'));
  const project = path.join(task, 'pptd-project');
  await fs.mkdir(path.join(project, 'pages'), { recursive: true });
  await fs.writeFile(path.join(project, 'deck.pptd'), manifest);
  for (const [relative, content] of Object.entries(extra)) {
    const target = path.join(project, relative); await fs.mkdir(path.dirname(target), { recursive: true }); await fs.writeFile(target, content);
  }
  const result = await new Promise(resolve => {
    const child = spawn(process.execPath, [script, task, vendor], { stdio: ['ignore', 'pipe', 'pipe'] });
    let output = ''; child.stdout.on('data', value => { output += value; }); child.stderr.on('data', value => { output += value; });
    child.on('close', code => resolve({ code, output }));
  });
  await fs.rm(task, { recursive: true, force: true });
  return result;
}

test('finalizer rejects non-v2 manifests before export', async () => {
  const result = await runFixture('version: v1\nsize: [960, 540]\npages:\n  - pages/1.page\n  - pages/2.page\n  - pages/3.page\n');
  assert.notEqual(result.code, 0); assert.match(result.output, /version must be v2/);
});

test('finalizer rejects page traversal and executable project files', async () => {
  const traversal = await runFixture('version: v2\nsize: [960, 540]\npages:\n  - ..\/escape.page\n  - pages/2.page\n  - pages/3.page\n');
  assert.notEqual(traversal.code, 0); assert.match(traversal.output, /unsafe PPTD page path/);
  const executable = await runFixture('version: v2\nsize: [960, 540]\npages:\n  - pages/1.page\n  - pages/2.page\n  - pages/3.page\n', {
    'pages/1.page': 'elements: []', 'pages/2.page': 'elements: []', 'pages/3.page': 'elements: []', 'run.sh': 'exit 0'
  });
  assert.notEqual(executable.code, 0); assert.match(executable.output, /unsupported file/);
});

test('finalizer exports and real-renders a closed three-page PPTD project', async () => {
  const page = 'pageType: content\nbackground: {type: solid, color: "#F7F8FC"}\nelements:\n  - elementId: title\n    elementType: text\n    bounds: [100, 180, 760, 120]\n    content:\n      text: "<p>本地导出验证</p>"\n';
  const result = await runFixture('version: v2\ntitle: smoke\nsize: [960, 540]\npages:\n  - pages/1.page\n  - pages/2.page\n  - pages/3.page\n', {
    'pages/1.page': page, 'pages/2.page': page, 'pages/3.page': page,
  });
  assert.equal(result.code, 0, result.output);
});

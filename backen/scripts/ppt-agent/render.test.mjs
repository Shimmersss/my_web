import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';
import { captureScreenshot, resolveExecutable, runCommand } from './render.mjs';

test('captureScreenshot retries only the transient Chrome capture protocol failure', async () => {
  let shots = 0;
  let paints = 0;
  const page = {
    evaluate: async () => { paints += 1; },
    screenshot: async () => {
      shots += 1;
      if (shots === 1) throw new Error('Page.captureScreenshot: Unable to capture screenshot');
    },
    waitForTimeout: async () => {}
  };
  await captureScreenshot(page, '/tmp/slide.png');
  assert.equal(shots, 2);
  assert.equal(paints, 2);
});

test('captureScreenshot does not hide non-transient screenshot failures', async () => {
  const page = {
    evaluate: async () => {},
    screenshot: async () => { throw new Error('disk write failed'); },
    waitForTimeout: async () => { throw new Error('must not retry'); }
  };
  await assert.rejects(() => captureScreenshot(page, '/tmp/slide.png'), /disk write failed/);
});

test('resolveExecutable finds binaries that are available on PATH', async () => {
  const node = await resolveExecutable(process.execPath, 'Node');
  assert.equal(node, process.execPath);
});

test('resolveExecutable reports a missing binary before spawn', async () => {
  await assert.rejects(
    resolveExecutable('/definitely/missing/ppt-agent-binary', 'LibreOffice'),
    /LibreOffice不可用.*PPT_GENERATION_\*_COMMAND/
  );
});

test('timed out render command kills its process group', async () => {
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'ppt-agent-process-'));
  const pidFile = path.join(directory, 'grandchild.pid');
  try {
    const script = `
      const { spawn } = require('node:child_process');
      const fs = require('node:fs');
      const child = spawn(process.execPath, ['-e', 'setInterval(() => {}, 1000)'], { stdio: 'ignore' });
      fs.writeFileSync(process.argv[1], String(child.pid));
      setInterval(() => process.stderr.write('x'.repeat(8192)), 1);
    `;
    await assert.rejects(
      runCommand(process.execPath, ['-e', script, pidFile], directory, 250),
      /超时/
    );
    const grandchildPid = Number(await fs.readFile(pidFile, 'utf8'));
    await new Promise(resolve => setTimeout(resolve, 100));
    assert.throws(() => process.kill(grandchildPid, 0), error => error?.code === 'ESRCH');
  } finally {
    await fs.rm(directory, { recursive: true, force: true });
  }
});

test('failed command reports only a bounded diagnostic tail', async () => {
  const script = `process.stderr.write('x'.repeat(200000)); process.exit(7)`;
  await assert.rejects(
    runCommand(process.execPath, ['-e', script], process.cwd(), 5000),
    error => error.message.length < 3000 && /退出 7/.test(error.message)
  );
});

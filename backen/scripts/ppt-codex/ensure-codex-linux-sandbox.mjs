#!/usr/bin/env node
/**
 * Codex 0.147.0 is a multi-call Linux binary: agent shell calls invoke the
 * same executable again under the `codex-linux-sandbox` name. npm's platform
 * package currently ships the binary but not that PATH-visible alias.
 *
 * Keep the alias inside node_modules/.bin so it is replaced atomically by
 * every `npm ci`, rather than installing a mutable system-wide helper.
 */
import fs from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawn } from 'node:child_process';

const backendRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');

function linuxTarget() {
  if (process.platform !== 'linux') return null;
  if (process.arch === 'x64') return ['codex-linux-x64', 'x86_64-unknown-linux-musl'];
  if (process.arch === 'arm64') return ['codex-linux-arm64', 'aarch64-unknown-linux-musl'];
  throw new Error(`Unsupported Linux architecture for Codex sandbox helper: ${process.arch}`);
}

function paths() {
  const target = linuxTarget();
  if (!target) return null;
  const [packageName, triple] = target;
  const binary = path.join(backendRoot, 'node_modules', '@openai', packageName, 'vendor', triple, 'bin', 'codex');
  const helper = path.join(backendRoot, 'node_modules', '.bin', 'codex-linux-sandbox');
  return { binary, helper };
}

async function existsExecutable(value) {
  try {
    const stat = await fs.stat(value);
    return stat.isFile() && (stat.mode & 0o111) !== 0;
  } catch {
    return false;
  }
}

async function verifyHelper({ binary, helper }) {
  if (!(await existsExecutable(binary))) throw new Error(`Locked Codex Linux binary is missing: ${binary}`);
  if (!(await existsExecutable(helper))) throw new Error(`Codex Linux sandbox helper is missing: ${helper}`);
  const realBinary = await fs.realpath(binary);
  const realHelper = await fs.realpath(helper);
  if (realBinary !== realHelper) throw new Error('Codex Linux sandbox helper does not target the locked Codex binary');
  await new Promise((resolve, reject) => {
    const child = spawn(helper, ['--help'], { stdio: 'ignore' });
    child.once('error', reject);
    child.once('close', code => code === 0 ? resolve() : reject(new Error(`Codex Linux sandbox helper exited ${code}`)));
  });
}

async function main() {
  const target = paths();
  if (!target) {
    console.log('Codex Linux sandbox helper is not required on this platform.');
    return;
  }
  if (process.argv.includes('--check')) {
    await verifyHelper(target);
    console.log('Codex Linux sandbox helper is ready.');
    return;
  }
  if (!(await existsExecutable(target.binary))) throw new Error(`Locked Codex Linux binary is missing: ${target.binary}`);
  await fs.mkdir(path.dirname(target.helper), { recursive: true });
  await fs.rm(target.helper, { force: true });
  await fs.symlink(path.relative(path.dirname(target.helper), target.binary), target.helper);
  await verifyHelper(target);
  console.log('Installed Codex Linux sandbox helper.');
}

main().catch(error => {
  console.error(error instanceof Error ? error.message : String(error));
  process.exitCode = 1;
});

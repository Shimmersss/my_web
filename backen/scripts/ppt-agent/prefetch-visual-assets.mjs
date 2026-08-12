import fs from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  downloadResearchAssets,
  normalizeBraveImageQuery,
  searchVisualAssets
} from './research.mjs';

const MAX_REQUEST_BYTES = 32 * 1024;
const MAX_PROMPT_CHARS = 4_000;

function clampInteger(value, fallback, min, max) {
  const parsed = Number.parseInt(String(value ?? ''), 10);
  return Number.isFinite(parsed) ? Math.max(min, Math.min(max, parsed)) : fallback;
}

export function visualQueriesFromPrompt(prompt, maxQueries = 3) {
  const normalized = String(prompt || '').replace(/\s+/g, ' ').trim();
  if (!normalized || normalized.length > MAX_PROMPT_CHARS) return [];
  const limit = clampInteger(maxQueries, 3, 1, 3);
  const candidates = [
    normalized,
    ...normalized.split(/[。！？!?；;\n]+/).map(value => value.trim()).filter(value => value.length >= 4)
  ];
  const queries = [];
  const seen = new Set();
  for (const candidate of candidates) {
    const query = normalizeBraveImageQuery(candidate);
    const key = query.toLowerCase();
    if (!query || seen.has(key)) continue;
    seen.add(key);
    queries.push(query);
    if (queries.length >= limit) break;
  }
  return queries;
}

function emptyManifest(request, queries = [], failures = []) {
  return {
    schemaVersion: 1,
    provider: 'server-image-search',
    queryCount: queries.length,
    maxImages: clampInteger(request?.maxImages, 6, 1, 12),
    queries,
    rightsPolicy: 'Search results are indexed references; reuse rights require verification.',
    images: [],
    failures: failures.slice(0, 8)
  };
}

export async function prefetchVisualAssets(request, outputDir) {
  if (!request || typeof request !== 'object' || Array.isArray(request)) throw new Error('预取请求必须是 JSON 对象');
  const prompt = String(request.prompt || '').trim();
  if (!prompt || prompt.length > MAX_PROMPT_CHARS) throw new Error('预取提示词为空或超过 4000 字');
  const resolvedOutput = path.resolve(String(outputDir || ''));
  if (!outputDir || resolvedOutput === path.parse(resolvedOutput).root) throw new Error('预取输出目录不安全');
  await fs.mkdir(resolvedOutput, { recursive: true, mode: 0o700 });
  const outputStat = await fs.lstat(resolvedOutput);
  if (!outputStat.isDirectory() || outputStat.isSymbolicLink()) throw new Error('预取输出目录不安全');
  await fs.chmod(resolvedOutput, 0o700);

  // Reruns may reuse a task-owned directory. Remove only files this fixed
  // prefetcher can create; uploaded/user files and other task artifacts remain
  // untouched.
  for (const entry of await fs.readdir(resolvedOutput, { withFileTypes: true })) {
    if (/^WEB(?:0[1-9]|1[0-2])\.(?:png|jpg|gif)$/i.test(entry.name)
      || entry.name === 'image-assets.json') {
      if (!entry.isFile() || entry.isSymbolicLink()) throw new Error('预取输出目录包含不安全的保留文件');
      await fs.unlink(path.join(resolvedOutput, entry.name));
    }
  }

  const maxImages = clampInteger(request.maxImages, 6, 1, 12);
  const queries = visualQueriesFromPrompt(prompt, request.maxQueries);
  const search = await searchVisualAssets(queries, {
    maxQueries: clampInteger(request.maxQueries, 3, 1, 3),
    maxAssets: Math.min(12, maxImages * 2)
  });
  const downloaded = await downloadResearchAssets(search.assets, resolvedOutput, { maxCount: maxImages });
  const manifest = {
    ...emptyManifest(request, queries, search.failures.map(message => ({ message: String(message).slice(0, 240) }))),
    images: downloaded.images.map(({ path: _absolutePath, ...image }) => ({
      ...image,
      localPath: image.fileName
    })),
    failures: [...search.failures.map(message => ({ message: String(message).slice(0, 240) })), ...downloaded.failures.map(failure => ({
      url: failure.url,
      message: String(failure.message || '').slice(0, 240)
    }))].slice(0, 8)
  };
  await fs.writeFile(
    path.join(resolvedOutput, 'image-assets.json'),
    `${JSON.stringify(manifest, null, 2)}\n`,
    { encoding: 'utf8', flag: 'wx', mode: 0o600 }
  );
  return manifest;
}

async function main(argv) {
  if (argv.length !== 2) throw new Error('用法：prefetch-visual-assets.mjs <requestJsonPath> <outputDir>');
  const [requestPath, outputDir] = argv;
  const resolvedRequest = path.resolve(requestPath);
  const resolvedOutput = path.resolve(outputDir);
  const relativeOutput = path.relative(path.dirname(resolvedRequest), resolvedOutput);
  if (!relativeOutput || relativeOutput === '..' || relativeOutput.startsWith(`..${path.sep}`)
    || path.isAbsolute(relativeOutput)) throw new Error('预取输出目录必须位于任务目录内');
  const requestStat = await fs.lstat(resolvedRequest);
  if (requestStat.isSymbolicLink()) throw new Error('预取请求文件不能是符号链接');
  if (!requestStat.isFile() || requestStat.size < 2 || requestStat.size > MAX_REQUEST_BYTES) {
    throw new Error('预取请求文件无效或超过 32KB');
  }
  let request;
  try {
    request = JSON.parse(await fs.readFile(resolvedRequest, 'utf8'));
  } catch {
    throw new Error('预取请求不是有效 JSON');
  }
  await prefetchVisualAssets(request, resolvedOutput);
}

const invokedDirectly = process.argv[1]
  && path.resolve(process.argv[1]) === path.resolve(fileURLToPath(import.meta.url));
if (invokedDirectly) {
  main(process.argv.slice(2)).catch(error => {
    process.stderr.write(`${String(error?.message || error)}\n`);
    process.exitCode = 1;
  });
}

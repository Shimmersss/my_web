import crypto from 'node:crypto';
import fs from 'node:fs/promises';
import path from 'node:path';

const MAX_TEMPLATE_BYTES = 32 * 1024 * 1024;
const catalogFile = path.resolve('scripts/github_template_preview_catalog.json');
const outputDir = path.resolve(process.env.PPT_AGENT_TEMPLATE_CACHE || '../.run/ppt-generation-tasks/_template-cache');

async function download(url) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 120_000);
  try {
    const response = await fetch(url, { signal: controller.signal, redirect: 'follow' });
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    const length = Number(response.headers.get('content-length') || 0);
    if (length > MAX_TEMPLATE_BYTES) throw new Error('模板超过 32MB');
    const bytes = Buffer.from(await response.arrayBuffer());
    if (bytes.length > MAX_TEMPLATE_BYTES || bytes[0] !== 0x50 || bytes[1] !== 0x4b) throw new Error('模板不是受支持的 PPTX 包');
    return bytes;
  } finally {
    clearTimeout(timer);
  }
}

await fs.mkdir(outputDir, { recursive: true });
const catalog = JSON.parse(await fs.readFile(catalogFile, 'utf8'));
const manifest = [];
for (const item of catalog) {
  const bytes = await download(item.rawUrl);
  const sha256 = crypto.createHash('sha256').update(bytes).digest('hex');
  if (!item.sha256 || sha256 !== item.sha256) {
    throw new Error(`${item.key} SHA-256 不匹配，拒绝缓存上游变更`);
  }
  const target = path.join(outputDir, `${item.key}.pptx`);
  const temporary = `${target}.tmp-${process.pid}`;
  await fs.writeFile(temporary, bytes);
  await fs.rename(temporary, target);
  manifest.push({ key: item.key, file: path.basename(target), bytes: bytes.length, sha256, sourceUrl: item.rawUrl });
  process.stdout.write(`${item.key} ${bytes.length} ${sha256}\n`);
}
await fs.writeFile(path.join(outputDir, 'manifest.json'), JSON.stringify({ generatedAt: new Date().toISOString(), templates: manifest }, null, 2));

import dns from 'node:dns/promises';
import fs from 'node:fs/promises';
import net from 'node:net';
import path from 'node:path';
import { imageSize } from 'image-size';
import { Agent } from 'undici';
import { agentFetch, hasConfiguredProxy } from './net.mjs';
import { recordToolCall } from './model.mjs';

const MAX_RESPONSE_BYTES = 5 * 1024 * 1024;
const MAX_IMAGE_BYTES = 4 * 1024 * 1024;
const MAX_IMAGE_PIXELS = 16_000_000;
const MAX_REDIRECTS = 3;
const BRAVE_IMAGES_ENDPOINT = 'https://api.search.brave.com/res/v1/images/search';
const BRAVE_RIGHTS_NOTE = 'Brave indexed image; reuse rights require verification';
const ALLOWED_SEARCH_HOSTS = new Set([
  'api.search.brave.com',
  'api.tavily.com',
  'api.openalex.org',
  'api.crossref.org',
  'export.arxiv.org',
  'api.semanticscholar.org',
  'commons.wikimedia.org',
  'api.openverse.org',
  'unsplash.com'
]);
const TRUSTED_SYNTHETIC_ASSET_HOSTS = new Set([
  'imgs.search.brave.com',
  'api.openverse.org',
  'commons.wikimedia.org',
  'upload.wikimedia.org',
  'images.unsplash.com',
  'plus.unsplash.com'
]);

export function isSyntheticDnsAddress(address) {
  if (net.isIP(address) !== 4) return false;
  const [first, second] = String(address).split('.').map(Number);
  return first === 198 && second >= 18 && second <= 19;
}

export function isPrivateHost(hostname) {
  const host = String(hostname || '').toLowerCase().replace(/^\[|\]$/g, '');
  if (host === 'localhost' || host.endsWith('.localhost') || host.endsWith('.local') || host === '::1') return true;
  if (net.isIP(host) === 4) {
    const [a, b] = host.split('.').map(Number);
    return a === 0 || a === 10 || a === 127 || a >= 224
      || (a === 100 && b >= 64 && b <= 127)
      || (a === 169 && b === 254)
      || (a === 172 && b >= 16 && b <= 31)
      || (a === 192 && [0, 2, 168].includes(b))
      || (a === 198 && (b === 18 || b === 19 || b === 51))
      || (a === 203 && b === 0);
  }
  if (net.isIP(host) === 6) {
    return host === '::' || host === '::1' || host.startsWith('fc') || host.startsWith('fd')
      || /^fe[89ab]/.test(host) || host.startsWith('ff') || host.startsWith('2001:db8:')
      || host.startsWith('::ffff:');
  }
  return false;
}

export function safeEndpoint(value, fallback) {
  const url = new URL(value || fallback);
  const host = url.hostname.toLowerCase().replace(/^\[|\]$/g, '');
  if (url.protocol !== 'https:' || url.username || url.password || net.isIP(host)
      || isPrivateHost(host) || !ALLOWED_SEARCH_HOSTS.has(host)) {
    throw new Error('搜索 API 必须使用无凭据的公网 HTTPS 地址');
  }
  return url;
}

async function assertPublicResolution(url) {
  const endpoint = safeEndpoint(url);
  if (hasConfiguredProxy()) {
    // Mihomo is the trusted loopback egress. Its synthetic DNS answers can be
    // reserved proxy addresses (for example 198.18.0.0/15), so validating the
    // workstation resolver here would reject an otherwise safe allowlisted
    // request before the proxy gets a chance to resolve it.
    return { endpoint, address: '127.0.0.1', family: 4 };
  }
  const addresses = await dns.lookup(endpoint.hostname, { all: true, verbatim: true });
  if (!addresses.length || addresses.some(item => isPrivateHost(item.address) && !isSyntheticDnsAddress(item.address))) {
    throw new Error('搜索 API DNS 解析到私网或保留地址');
  }
  return { endpoint, address: addresses[0].address, family: addresses[0].family };
}

function safeAssetUrl(value) {
  const url = new URL(value);
  const host = url.hostname.toLowerCase().replace(/^\[|\]$/g, '');
  if (url.protocol !== 'https:' || url.username || url.password || net.isIP(host) || isPrivateHost(host)) {
    throw new Error('图片素材必须使用无凭据的公网 HTTPS 地址');
  }
  return url;
}

function safeProvenanceUrl(value) {
  try {
    return safeAssetUrl(value).toString();
  } catch {
    return '';
  }
}

async function assertPublicAssetResolution(value) {
  const endpoint = safeAssetUrl(value);
  if (hasConfiguredProxy()) return { endpoint, address: '127.0.0.1', family: 4 };
  const addresses = await dns.lookup(endpoint.hostname, { all: true, verbatim: true });
  const syntheticOnly = addresses.length > 0 && addresses.every(item => isSyntheticDnsAddress(item.address));
  const syntheticAllowed = TRUSTED_SYNTHETIC_ASSET_HOSTS.has(endpoint.hostname);
  if (!addresses.length || addresses.some(item => isPrivateHost(item.address)
    && !(isSyntheticDnsAddress(item.address) && syntheticAllowed))) {
    throw new Error('图片素材 DNS 解析到私网或保留地址');
  }
  if (syntheticOnly && !syntheticAllowed) throw new Error('图片素材 DNS 使用了未信任的合成地址');
  return { endpoint, address: addresses[0].address, family: addresses[0].family };
}

let researchFetch = agentFetch;
let resolveResearchEndpoint = assertPublicResolution;
let researchAssetFetch = agentFetch;
let resolveResearchAsset = assertPublicAssetResolution;

export function setResearchTransportForTest({ fetch, resolve, assetFetch, assetResolve } = {}) {
  researchFetch = fetch || agentFetch;
  resolveResearchEndpoint = resolve || assertPublicResolution;
  researchAssetFetch = assetFetch || agentFetch;
  resolveResearchAsset = assetResolve || assertPublicAssetResolution;
}

function pinnedDispatcher(resolution) {
  return new Agent({
    connect: {
      lookup(hostname, options, callback) {
        if (options?.all) callback(null, [{ address: resolution.address, family: resolution.family }]);
        else callback(null, resolution.address, resolution.family);
      }
    }
  });
}

export async function readBoundedBody(response, maxBytes = MAX_RESPONSE_BYTES) {
  if (!response.body) return '';
  const reader = response.body.getReader();
  const chunks = [];
  let total = 0;
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      total += value.byteLength;
      if (total > maxBytes) {
        await reader.cancel('response too large').catch(() => {});
        throw new Error('搜索响应超过 5MB');
      }
      chunks.push(value);
    }
  } finally {
    reader.releaseLock();
  }
  const bytes = new Uint8Array(total);
  let offset = 0;
  for (const chunk of chunks) {
    bytes.set(chunk, offset);
    offset += chunk.byteLength;
  }
  return new TextDecoder().decode(bytes);
}

async function fetchJson(url, options = {}) {
  return JSON.parse(await fetchText(url, options));
}

async function readBoundedBinary(response, maxBytes = MAX_IMAGE_BYTES) {
  if (!response.body) return Buffer.alloc(0);
  const reader = response.body.getReader();
  const chunks = [];
  let total = 0;
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      total += value.byteLength;
      if (total > maxBytes) {
        await reader.cancel('image too large').catch(() => {});
        throw new Error('图片素材超过 4MB');
      }
      chunks.push(Buffer.from(value));
    }
  } finally {
    reader.releaseLock();
  }
  return Buffer.concat(chunks, total);
}

/** Validate the actual bitmap signature and decoded dimensions, not HTTP metadata alone. */
export function inspectImageBytes(bytes, declaredContentType = '') {
  let dimensions;
  try {
    dimensions = imageSize(bytes);
  } catch {
    throw new Error('图片素材文件签名无效');
  }
  const type = String(dimensions?.type || '').toLowerCase().replace('jpeg', 'jpg');
  const contentTypes = { png: 'image/png', jpg: 'image/jpeg', gif: 'image/gif' };
  const contentType = contentTypes[type];
  if (!contentType) throw new Error('搜索结果不是支持的 PNG/JPEG/GIF 图片');
  const declared = String(declaredContentType || '').split(';')[0].trim().toLowerCase();
  if (declared && declared !== contentType) throw new Error('图片素材类型与文件签名不一致');
  const width = Number(dimensions?.width || 0);
  const height = Number(dimensions?.height || 0);
  if (!Number.isInteger(width) || !Number.isInteger(height) || width < 1 || height < 1
      || width * height > MAX_IMAGE_PIXELS) {
    throw new Error('图片素材像素尺寸超限');
  }
  return { contentType, width, height };
}

async function fetchImage(url) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 20_000);
  let dispatcher;
  let resolution = await resolveResearchAsset(url);
  let current = resolution.endpoint;
  const lockedAssetHost = current.hostname.toLowerCase() === 'imgs.search.brave.com'
    ? current.hostname.toLowerCase() : '';
  try {
    for (let redirect = 0; redirect <= MAX_REDIRECTS; redirect += 1) {
      recordToolCall('image-fetch');
      dispatcher = hasConfiguredProxy() ? undefined : pinnedDispatcher(resolution);
      const response = await researchAssetFetch(current, {
        dispatcher,
        redirect: 'manual',
        signal: controller.signal,
        headers: {
          accept: 'image/png,image/jpeg,image/gif;q=0.8,*/*;q=0.1',
          'user-agent': 'ShimmerPptAgent/1.0 (https://shimmer.help)'
        }
      });
      if ([301, 302, 303, 307, 308].includes(response.status)) {
        await response.body?.cancel('redirect').catch(() => {});
        if (redirect === MAX_REDIRECTS) throw new Error('图片素材重定向次数过多');
        const location = response.headers.get('location');
        if (!location) throw new Error('图片素材重定向缺少 Location');
        await dispatcher?.close().catch(() => {});
        dispatcher = undefined;
        const next = await resolveResearchAsset(new URL(location, current));
        if (lockedAssetHost && next.endpoint.hostname.toLowerCase() !== lockedAssetHost) {
          throw new Error('Brave 图片代理不允许跨域重定向');
        }
        resolution = next;
        current = resolution.endpoint;
        continue;
      }
      if (!response.ok) {
        await response.body?.cancel(`HTTP ${response.status}`).catch(() => {});
        throw new Error(`图片素材 HTTP ${response.status}`);
      }
      const contentType = String(response.headers.get('content-type') || '').split(';')[0].toLowerCase();
      if (!['image/png', 'image/jpeg', 'image/gif', 'application/octet-stream'].includes(contentType)) {
        await response.body?.cancel('not an image').catch(() => {});
        throw new Error('搜索结果不是支持的 PNG/JPEG/GIF 图片');
      }
      const declared = Number(response.headers.get('content-length') || 0);
      if (declared > MAX_IMAGE_BYTES) {
        await response.body?.cancel('declared image too large').catch(() => {});
        throw new Error('图片素材超过 4MB');
      }
      const bytes = await readBoundedBinary(response);
      return { bytes, ...inspectImageBytes(bytes, contentType === 'application/octet-stream' ? '' : contentType) };
    }
    throw new Error('图片素材下载失败');
  } finally {
    clearTimeout(timer);
    await dispatcher?.close().catch(() => {});
  }
}

export async function fetchText(url, options = {}) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 20_000);
  let dispatcher;
  try {
    let resolution = await resolveResearchEndpoint(url);
    let current = resolution.endpoint;
    let response;
    for (let redirect = 0; redirect <= MAX_REDIRECTS; redirect += 1) {
      recordToolCall('http-search');
      // A configured proxy is restricted to loopback in net.mjs. In that mode the
      // trusted local Mihomo process performs CONNECT/DNS for an exact official-host
      // allowlist. Direct mode binds TLS to the address validated above.
      dispatcher = hasConfiguredProxy() ? undefined : pinnedDispatcher(resolution);
      response = await researchFetch(current, {
        ...options,
        dispatcher,
        redirect: 'manual',
        signal: controller.signal,
        headers: { accept: 'application/json', ...(options.headers || {}) }
      });
      if (![301, 302, 303, 307, 308].includes(response.status)) break;
      // Redirect response bodies are never consumed. Cancel immediately so every
      // validation failure below can close/reuse the connection without hanging.
      await response.body?.cancel('redirect').catch(() => {});
      if (redirect === MAX_REDIRECTS) throw new Error('搜索 API 重定向次数过多');
      const location = response.headers.get('location');
      if (!location) throw new Error('搜索 API 重定向缺少 Location');
      const next = await resolveResearchEndpoint(new URL(location, current));
      const hasAuthentication = Object.keys(options.headers || {})
        .some(name => ['authorization', 'x-api-key', 'api-key', 'x-subscription-token'].includes(name.toLowerCase()));
      if (next.endpoint.origin !== current.origin && hasAuthentication) {
        throw new Error('搜索 API 不允许携带密钥跨域重定向');
      }
      await dispatcher?.close();
      dispatcher = undefined;
      resolution = next;
      current = next.endpoint;
    }
    if (!response.ok) {
      await response.body?.cancel(`HTTP ${response.status}`).catch(() => {});
      throw new Error(`HTTP ${response.status}`);
    }
    const declared = Number(response.headers.get('content-length') || 0);
    if (declared > MAX_RESPONSE_BYTES) {
      await response.body?.cancel('declared response too large').catch(() => {});
      throw new Error('搜索响应超过 5MB');
    }
    return await readBoundedBody(response);
  } finally {
    clearTimeout(timer);
    await dispatcher?.close().catch(() => {});
  }
}

async function fetchPublicHtml(value) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 20_000);
  let dispatcher;
  let resolution = await assertPublicAssetResolution(value);
  let current = resolution.endpoint;
  try {
    for (let redirect = 0; redirect <= MAX_REDIRECTS; redirect += 1) {
      recordToolCall('mimo-page');
      dispatcher = hasConfiguredProxy() ? undefined : pinnedDispatcher(resolution);
      const response = await researchFetch(current, {
        dispatcher,
        redirect: 'manual',
        signal: controller.signal,
        headers: { accept: 'text/html,application/xhtml+xml;q=0.9,*/*;q=0.1' }
      });
      if ([301, 302, 303, 307, 308].includes(response.status)) {
        await response.body?.cancel('redirect').catch(() => {});
        if (redirect === MAX_REDIRECTS) throw new Error('Mimo 来源页重定向次数过多');
        const location = response.headers.get('location');
        if (!location) throw new Error('Mimo 来源页重定向缺少 Location');
        await dispatcher?.close().catch(() => {});
        dispatcher = undefined;
        resolution = await assertPublicAssetResolution(new URL(location, current));
        current = resolution.endpoint;
        continue;
      }
      if (!response.ok) {
        await response.body?.cancel(`HTTP ${response.status}`).catch(() => {});
        throw new Error(`Mimo 来源页 HTTP ${response.status}`);
      }
      const contentType = String(response.headers.get('content-type') || '').toLowerCase();
      if (!contentType.includes('text/html') && !contentType.includes('application/xhtml')) {
        await response.body?.cancel('not html').catch(() => {});
        throw new Error('Mimo 来源页不是 HTML');
      }
      return await readBoundedBody(response, 1_500_000);
    }
    throw new Error('Mimo 来源页下载失败');
  } finally {
    clearTimeout(timer);
    await dispatcher?.close().catch(() => {});
  }
}

function htmlAttribute(html, attribute, value) {
  const escaped = String(attribute).replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  const escapedValue = String(value).replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  const match = String(html).match(new RegExp(`<meta[^>]+(?:${escaped}\\s*=\\s*["']${escapedValue}["'][^>]+content\\s*=\\s*["']([^"']+)["']|content\\s*=\\s*["']([^"']+)["'][^>]+${escaped}\\s*=\\s*["']${escapedValue}["'])`, 'i'));
  return match?.[1] || match?.[2] || '';
}

function htmlLinkHref(html, rel) {
  const expected = String(rel || '').toLowerCase();
  for (const match of String(html || '').matchAll(/<link\b([^>]*)>/gi)) {
    const attributes = {};
    for (const attribute of match[1].matchAll(/([:\w-]+)\s*=\s*(["'])(.*?)\2/gs)) {
      attributes[attribute[1].toLowerCase()] = attribute[3];
    }
    const relTokens = String(attributes.rel || '').toLowerCase().split(/\s+/).filter(Boolean);
    if (relTokens.includes(expected) && attributes.href) return attributes.href;
  }
  return '';
}

function htmlDecode(value) {
  return text(String(value || '').replace(/&amp;/g, '&').replace(/&quot;/g, '"').replace(/&#x27;/g, "'").replace(/&lt;/g, '<').replace(/&gt;/g, '>'));
}

/** Extract only explicit page preview images from Mimo-cited public pages. */
export async function discoverPageImages(sources, { maxPages = 4, maxAssets = 8 } = {}) {
  const assets = [];
  const failures = [];
  for (const source of (Array.isArray(sources) ? sources : []).slice(0, maxPages)) {
    const sourceUrl = String(source?.url || '').trim();
    if (!sourceUrl) continue;
    try {
      const html = await fetchPublicHtml(sourceUrl);
      const raw = htmlAttribute(html, 'property', 'og:image')
        || htmlAttribute(html, 'name', 'twitter:image')
        || htmlLinkHref(html, 'image_src');
      if (!raw) continue;
      const sourceAddress = new URL(sourceUrl);
      const imageAddress = new URL(htmlDecode(raw), sourceUrl);
      // Some older official sites publish an http:// OG URL even while the
      // same asset is available over HTTPS. Upgrade only same-host metadata;
      // cross-host assets still go through the normal HTTPS/SSRF checks.
      if (sourceAddress.protocol === 'https:' && imageAddress.protocol === 'http:'
        && sourceAddress.hostname === imageAddress.hostname) imageAddress.protocol = 'https:';
      const imageUrl = imageAddress.toString();
      assets.push({
        url: imageUrl,
        sourceUrl,
        title: `Mimo 搜索来源视觉：${text(source.title).slice(0, 150)}`,
        description: text(source.abstract || source.title).slice(0, 600),
        searchQuery: text(source.title).slice(0, 300),
        // The source page is retained for manual rights verification. Do not
        // present this as a blanket license for third-party copyrighted art.
        license: 'First-party source-page media; reuse terms require verification'
      });
      if (assets.length >= maxAssets) break;
    } catch (error) {
      failures.push(`${sourceUrl}: ${String(error?.message || error)}`);
    }
  }
  return { assets, failures: failures.slice(0, 8) };
}

/** Stable first-party media pages for known presentation franchises. */
export function knownFirstPartyMediaSources(topic) {
  const value = text(topic).toLowerCase();
  if (!/刀剑神域|sword\s*art\s*online/.test(value)) return [];
  return [
    ['Sword Art Online 官方动画站', 'https://www.swordart-online.net/'],
    ['Sword Art Online Alicization 官方站', 'https://sao-alicization.net/'],
    ['Sword Art Online Progressive 官方站', 'https://sao-p.net/'],
    ['Sword Art Online Ordinal Scale 官方站', 'https://sao-movie.net/']
  ].map(([title, url]) => ({
    title,
    url,
    abstract: `${title}发布的官方主视觉与作品信息。`,
    type: 'web',
    provider: 'first-party-media-catalog',
    query: 'Sword Art Online official anime',
    license: 'First-party source-page media; reuse terms require verification'
  }));
}

function topicTerms(value) {
  const normalized = text(value).toLowerCase();
  const terms = new Set();
  for (const token of normalized.match(/[a-z0-9][a-z0-9-]{2,}/g) || []) {
    if (!new Set(['presentation', 'ppt', 'introduction', 'overview', 'related', 'about', 'with', 'from', 'the', 'and']).has(token)) terms.add(token);
  }
  for (const block of normalized.match(/[\u3400-\u9fff]{2,}/g) || []) {
    for (let size = Math.min(6, block.length); size >= 2; size -= 1) {
      for (let index = 0; index <= block.length - size; index += 1) terms.add(block.slice(index, index + size));
    }
  }
  return [...terms];
}

function matchesSpecificSourceTopic(topic, item) {
  const requested = text(topic).toLowerCase();
  if (!/刀剑神域|sword\s*art\s*online/.test(requested)) return true;
  let decodedUrl = text(item?.url).toLowerCase();
  try { decodedUrl = decodeURIComponent(decodedUrl); } catch { /* retain raw URL */ }
  const sourceIdentity = text([item?.title, decodedUrl].join(' ')).toLowerCase();
  // "Sword Art Online" is a proper work title. Matching independent words
  // such as "sword" or "art" admitted medieval-weapon pages whose og:image
  // then outranked genuinely related visuals. Require the work/character arc
  // identity in the source page itself; the model's original query is not
  // evidence that a returned page is on-topic.
  return /刀剑神域|sword[\s_-]*art[\s_-]*online|\bsao\b|kirito|asuna|alicization|aincrad|eugeo/.test(sourceIdentity);
}

/**
 * Source-page media is useful only when the page itself is on-topic.  Research
 * sources are intentionally broad (papers, index records, web pages), so
 * taking the first few sources can turn an unrelated DOI or arXiv hit into a
 * slide image.  Restrict extraction to relevant web pages and rank exact
 * title/query matches before fetching any page.
 */
export function relevantPageImageSources(sources, topic, { maxPages = 4 } = {}) {
  const terms = topicTerms(topic);
  return (Array.isArray(sources) ? sources : [])
    .filter(item => String(item?.type || '').toLowerCase() === 'web' && String(item?.url || '').trim())
    .filter(item => matchesSpecificSourceTopic(topic, item))
    .map(item => {
      const haystack = text([item.title, item.url].join(' ')).toLowerCase();
      const sourceQueryTerms = topicTerms(item.query);
      const score = [...terms, ...sourceQueryTerms]
        .reduce((total, term) => total + (haystack.includes(term) ? term.length : 0), 0);
      return { item, score };
    })
    .filter(({ score }) => score > 0)
    .sort((left, right) => right.score - left.score)
    .slice(0, Math.max(0, maxPages))
    .map(({ item }) => item);
}

function xmlText(value) {
  return text(String(value || '')
    .replace(/<[^>]+>/g, ' ')
    .replace(/&amp;/g, '&').replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&quot;/g, '"'));
}

async function arxiv(query) {
  const url = new URL('https://export.arxiv.org/api/query');
  url.searchParams.set('search_query', `all:${query}`);
  url.searchParams.set('start', '0');
  url.searchParams.set('max_results', '5');
  const xml = await fetchText(url);
  return [...xml.matchAll(/<entry>([\s\S]*?)<\/entry>/g)].map(match => {
    const entry = match[1];
    const link = [...entry.matchAll(/<link\b[^>]*href="([^"]+)"[^>]*>/g)]
      .map(item => item[1]).find(value => /arxiv\.org\/(?:abs|pdf)\//.test(value));
    return {
      title: xmlText(entry.match(/<title>([\s\S]*?)<\/title>/)?.[1]),
      url: link || xmlText(entry.match(/<id>([\s\S]*?)<\/id>/)?.[1]),
      authors: [...entry.matchAll(/<author>[\s\S]*?<name>([\s\S]*?)<\/name>[\s\S]*?<\/author>/g)].map(item => xmlText(item[1])),
      year: xmlText(entry.match(/<published>(\d{4})-/)?.[1]),
      abstract: xmlText(entry.match(/<summary>([\s\S]*?)<\/summary>/)?.[1]),
      doi: xmlText(entry.match(/<arxiv:doi[^>]*>([\s\S]*?)<\/arxiv:doi>/)?.[1]),
      type: 'paper',
      license: 'arXiv'
    };
  });
}

function text(value) {
  return String(value || '').replace(/\s+/g, ' ').trim();
}

function normalizeTitle(value) {
  return text(value).toLowerCase().replace(/[^\p{L}\p{N}]+/gu, '');
}

function source(input) {
  return {
    id: '',
    title: text(input.title),
    url: text(input.url),
    authors: Array.isArray(input.authors) ? input.authors.map(text).filter(Boolean).slice(0, 8) : [],
    year: input.year ? String(input.year) : '',
    doi: text(input.doi).replace(/^https?:\/\/doi\.org\//i, ''),
    type: input.type || 'web',
    abstract: text(input.abstract).slice(0, 1800),
    provider: text(input.provider).slice(0, 80),
    query: text(input.query).slice(0, 300),
    accessedAt: new Date().toISOString(),
    license: text(input.license)
  };
}

export function dedupe(items, maxSources) {
  const seen = new Set();
  const output = [];
  for (const item of items.map(source)) {
    if (!item.title || !item.url) continue;
    const key = item.doi ? `doi:${item.doi.toLowerCase()}` : `title:${normalizeTitle(item.title)}:${item.authors[0] || ''}`;
    if (seen.has(key)) continue;
    seen.add(key);
    item.id = `S${String(output.length + 1).padStart(2, '0')}`;
    output.push(item);
    if (output.length >= maxSources) break;
  }
  return output;
}

export function roundRobin(buckets) {
  const results = [];
  for (let index = 0; buckets.some(bucket => index < bucket.length); index += 1) {
    for (const bucket of buckets) {
      if (bucket[index]) results.push(bucket[index]);
    }
  }
  return results;
}

async function openAlex(query) {
  const url = new URL('https://api.openalex.org/works');
  url.searchParams.set('search', query);
  url.searchParams.set('per-page', '5');
  url.searchParams.set('select', 'id,doi,title,publication_year,authorships,primary_location,open_access');
  const data = await fetchJson(url);
  return (data.results || []).map(work => ({
    title: work.title,
    url: work.doi || work.primary_location?.landing_page_url || work.id,
    authors: (work.authorships || []).map(item => item.author?.display_name),
    year: work.publication_year,
    doi: work.doi,
    type: 'paper',
    license: work.primary_location?.license || (work.open_access?.is_oa ? 'open-access' : '')
  }));
}

async function crossref(query) {
  const url = new URL('https://api.crossref.org/works');
  url.searchParams.set('query.bibliographic', query);
  url.searchParams.set('rows', '5');
  url.searchParams.set('select', 'DOI,title,author,published,URL,abstract,type');
  const data = await fetchJson(url);
  return (data.message?.items || []).map(work => ({
    title: work.title?.[0],
    url: work.URL || (work.DOI ? `https://doi.org/${work.DOI}` : ''),
    authors: (work.author || []).map(author => `${author.given || ''} ${author.family || ''}`.trim()),
    year: work.published?.['date-parts']?.[0]?.[0],
    doi: work.DOI,
    abstract: work.abstract,
    type: 'paper'
  }));
}

async function semanticScholar(query) {
  const url = new URL('https://api.semanticscholar.org/graph/v1/paper/search');
  url.searchParams.set('query', query);
  url.searchParams.set('limit', '5');
  url.searchParams.set('fields', 'title,url,authors,year,abstract,externalIds,openAccessPdf');
  const headers = process.env.PPT_AGENT_SEMANTIC_SCHOLAR_KEY ? { 'x-api-key': process.env.PPT_AGENT_SEMANTIC_SCHOLAR_KEY } : {};
  const data = await fetchJson(url, { headers });
  return (data.data || []).map(work => ({
    title: work.title,
    url: work.openAccessPdf?.url || work.url,
    authors: (work.authors || []).map(author => author.name),
    year: work.year,
    doi: work.externalIds?.DOI,
    abstract: work.abstract,
    type: 'paper',
    license: work.openAccessPdf?.url ? 'open-access' : ''
  }));
}

async function wikimediaImages(query) {
  const url = new URL('https://commons.wikimedia.org/w/api.php');
  url.searchParams.set('action', 'query');
  url.searchParams.set('generator', 'search');
  // Commons also returns PDF/document files for broad keywords. Asking for
  // bitmap files and checking the API MIME field keeps the downloaded asset
  // list usable by both the PPTX and HTML renderers.
  url.searchParams.set('gsrsearch', `${String(query || '').trim()} filetype:bitmap`);
  url.searchParams.set('gsrnamespace', '6');
  url.searchParams.set('gsrlimit', '6');
  url.searchParams.set('prop', 'imageinfo');
  url.searchParams.set('iiprop', 'url|mime|size|extmetadata');
  // Commons rounds requested widths to a generated thumbnail tier. 1024 keeps
  // most assets below the 4 MB worker limit and avoids downloading 1920 px
  // thumbnails that are larger than a slide needs.
  url.searchParams.set('iiurlwidth', '1024');
  url.searchParams.set('format', 'json');
  const data = await fetchJson(url);
  return Object.values(data.query?.pages || {}).map(page => {
    const info = page.imageinfo?.[0] || {};
    const mime = String(info.mime || '').toLowerCase();
    if (!mime.startsWith('image/')) return null;
    const metadata = info.extmetadata || {};
    const license = text(metadata.LicenseShortName?.value || metadata.UsageTerms?.value);
    const title = text(String(page.title || '').replace(/^File:/i, ''));
    return {
      url: info.thumburl || info.url,
      sourceUrl: `https://commons.wikimedia.org/wiki/${encodeURIComponent(String(page.title || '').replaceAll(' ', '_'))}`,
      title,
      description: text(metadata.ImageDescription?.value || title),
      searchQuery: String(query || '').trim(),
      provider: 'wikimedia-commons',
      license: license || 'Wikimedia Commons license metadata',
      rightsStatus: 'recorded',
      rightsNote: '',
      mime
    };
  }).filter(item => item?.url && item.sourceUrl && item.license);
}

async function openverseImages(query) {
  const url = new URL('https://api.openverse.org/v1/images/');
  url.searchParams.set('q', String(query || '').trim());
  url.searchParams.set('page_size', '8');
  const data = await fetchJson(url);
  return (data.results || []).map(item => ({
    // Openverse thumbnails are served through its own HTTPS API and retain
    // the upstream license/landing-page metadata, so the worker need not
    // trust arbitrary image URLs before downloading the bitmap.
    url: item.thumbnail || item.url,
    sourceUrl: item.foreign_landing_url || item.url,
    title: text(item.title),
    description: text(item.attribution || item.creator || item.title),
    searchQuery: String(query || '').trim(),
    provider: 'openverse',
    license: text(item.license || item.license_version || 'Openverse licensed image'),
    rightsStatus: 'recorded',
    rightsNote: ''
  })).filter(item => item.url && item.sourceUrl && item.license);
}

export function normalizeBraveImageQuery(value) {
  const compact = String(value || '').replace(/\s+/g, ' ').trim();
  if (!compact) return '';
  const words = compact.split(' ').slice(0, 50).join(' ');
  return [...words].slice(0, 400).join('').trim();
}

function boundedInteger(value, fallback, min, max) {
  const parsed = Number.parseInt(String(value ?? ''), 10);
  return Number.isFinite(parsed) ? Math.max(min, Math.min(max, parsed)) : fallback;
}

/** Map Brave's index metadata without implying that the result has a reuse license. */
export function parseBraveImageResults(payload, query) {
  const mightBeOffensive = payload?.extra?.might_be_offensive === true;
  if (mightBeOffensive) return [];
  return (Array.isArray(payload?.results) ? payload.results : []).map(item => {
    const thumbnailUrl = safeProvenanceUrl(item?.thumbnail?.src);
    let thumbnailHost = '';
    try { thumbnailHost = new URL(thumbnailUrl).hostname.toLowerCase(); } catch { /* filtered below */ }
    const sourceUrl = safeProvenanceUrl(item?.url);
    const originalUrl = safeProvenanceUrl(item?.properties?.url);
    let sourceHost = text(item?.source);
    try { sourceHost ||= new URL(sourceUrl).hostname; } catch { /* filtered below */ }
    const confidence = ['high', 'medium', 'low'].includes(String(item?.confidence || '').toLowerCase())
      ? String(item.confidence).toLowerCase() : '';
    return {
      url: thumbnailHost === 'imgs.search.brave.com' ? thumbnailUrl : '',
      sourceUrl,
      originalUrl,
      title: text(item?.title || item?.source || 'Brave image result').slice(0, 240),
      description: text(`Indexed by Brave Images from ${sourceHost}.`).slice(0, 600),
      searchQuery: normalizeBraveImageQuery(query),
      provider: 'brave-images',
      confidence,
      mightBeOffensive,
      width: boundedInteger(item?.properties?.width, 0, 0, 100_000),
      height: boundedInteger(item?.properties?.height, 0, 0, 100_000),
      thumbnailWidth: boundedInteger(item?.thumbnail?.width, 0, 0, 100_000),
      thumbnailHeight: boundedInteger(item?.thumbnail?.height, 0, 0, 100_000),
      rightsStatus: 'unverified',
      rightsNote: BRAVE_RIGHTS_NOTE,
      license: ''
    };
  }).filter(item => item.url && item.sourceUrl && item.confidence !== 'low');
}

export async function braveImages(query, { count } = {}) {
  const key = String(process.env.PPT_AGENT_BRAVE_IMAGES_KEY || process.env.BRAVE_SEARCH_API_KEY || '').trim();
  const normalizedQuery = normalizeBraveImageQuery(query);
  if (!key || !normalizedQuery) return [];
  const endpoint = safeEndpoint(BRAVE_IMAGES_ENDPOINT);
  endpoint.searchParams.set('q', normalizedQuery);
  endpoint.searchParams.set('country', 'ALL');
  endpoint.searchParams.set('search_lang', /[\u3400-\u9fff]/u.test(normalizedQuery) ? 'zh-hans' : 'en');
  endpoint.searchParams.set('count', String(boundedInteger(
    count ?? process.env.PPT_AGENT_BRAVE_IMAGES_COUNT,
    20,
    1,
    50
  )));
  endpoint.searchParams.set('safesearch', 'strict');
  const response = await fetchJson(endpoint, {
    headers: { 'X-Subscription-Token': key }
  });
  return parseBraveImageResults(response, normalizedQuery);
}

function contextualPhotoQuery(query) {
  const value = String(query || '').replace(/\s+/g, ' ').trim();
  // Copyrighted character art is rarely available under a reusable license.
  // A related real-world visual is preferable to an unrelated building or an
  // empty image frame. Keep the original topic in metadata for traceability
  // and for later slide-to-asset relevance matching.
  // Open media indexes are much more reliable with concise concepts. The
  // original, specific query is retained on every returned asset, so this
  // broadening improves recall without weakening later topic relevance checks.
  if (/alicization|eugeo|alice/i.test(value)) return 'fantasy forest';
  if (/aincrad|floating\s+castle/i.test(value)) return 'fantasy castle';
  if (/刀剑神域|sword\s*art\s*online|anime|动画/i.test(value)) return 'virtual reality';
  if (/元宇宙|metaverse/i.test(value)) return 'virtual reality';
  if (/游戏|game|gaming/i.test(value)) return 'gaming technology';
  return value;
}

/**
 * Unsplash provides a second, independent licensed-photo search path that
 * needs no user API key for this public search endpoint. We record the photo
 * landing page and the Unsplash license instead of treating the CDN URL as a
 * source. It is deliberately last in the chain: it supplies contextual
 * visuals, not copyrighted character art.
 */
async function unsplashImages(query, topic = query) {
  const url = new URL('https://unsplash.com/napi/search/photos');
  url.searchParams.set('query', contextualPhotoQuery(query));
  url.searchParams.set('per_page', '8');
  url.searchParams.set('page', '1');
  const data = await fetchJson(url);
  return (data.results || []).map(item => {
    const photoUrl = item.urls?.regular || item.urls?.full || item.urls?.small;
    const landing = item.links?.html;
    const alt = text(item.alt_description || item.description || item.slug || 'Unsplash photo');
    return {
      url: photoUrl,
      sourceUrl: landing,
      title: `Contextual visual for ${text(topic).slice(0, 100)}: ${alt}`.slice(0, 240),
      description: `Licensed Unsplash contextual visual for ${text(topic).slice(0, 180)}. ${alt}`.slice(0, 600),
      searchQuery: text(topic).slice(0, 300),
      license: 'Unsplash License'
    };
  }).filter(item => item.url && item.sourceUrl);
}

async function tavily(query) {
  const key = process.env.PPT_AGENT_TAVILY_KEY || '';
  if (!key) return [];
  const endpoint = safeEndpoint(process.env.PPT_AGENT_TAVILY_ENDPOINT, 'https://api.tavily.com/search');
  const response = await fetchJson(endpoint, {
    method: 'POST',
    headers: { 'content-type': 'application/json', authorization: `Bearer ${key}` },
    body: JSON.stringify({
      query,
      topic: 'general',
      search_depth: 'basic',
      max_results: 5,
      include_answer: false,
      include_raw_content: true,
      include_images: true,
      include_image_descriptions: true
    })
  });
  const sources = (response.results || []).map(item => ({
    title: item.title,
    url: item.url,
    abstract: item.raw_content || item.content,
    type: 'web'
  }));
  const assets = (response.images || []).slice(0, 8).map(item => {
    const rawUrl = typeof item === 'string' ? item : item?.url;
    const url = safeProvenanceUrl(rawUrl);
    const sourceUrl = safeProvenanceUrl(typeof item === 'string' ? rawUrl : (item?.source_url || rawUrl));
    const license = text(typeof item === 'string' ? '' : item?.license);
    return {
      url,
      description: text(typeof item === 'string' ? '' : item?.description).slice(0, 600),
      sourceUrl,
      searchQuery: String(query || '').trim(),
      provider: 'tavily-images',
      license,
      rightsStatus: license ? 'recorded' : 'unverified',
      rightsNote: license ? '' : 'Tavily indexed image; reuse rights require verification'
    };
  }).filter(item => item.url && item.sourceUrl && (item.license || item.rightsNote));
  return { sources, assets };
}

function matchesSpecificWorkQuery(query, asset) {
  const value = String(query || '').toLowerCase();
  if (!/刀剑神域|sword\s*art\s*online/.test(value)) return true;
  const mediaText = text([asset?.title, asset?.description].join(' ')).toLowerCase();
  return /刀剑神域|sword\s*art\s*online|kirito|asuna|alicization|aincrad|eugeo|alice/.test(mediaText);
}

function isUsefulContextualAsset(asset) {
  const value = text([asset?.title, asset?.description].join(' ')).toLowerCase();
  // Broad licensed searches occasionally return visually literal but
  // presentation-useless objects (for example a "fantasy castle" birthday
  // cake). Reject these before they receive topic-rich trace metadata that
  // would otherwise make them look relevant to later deterministic checks.
  return !/\b(?:birthday|cake|cupcake|food|lego|toy|figurine|miniature|costume|cosplay|city profile|zuylen)\b/i.test(value);
}

function isUsefulSpecificWorkAsset(asset) {
  const value = text([asset?.title, asset?.description].join(' ')).toLowerCase();
  return !/\b(?:logo|fukubukuro|shipping boxes|cardboard boxes|birthday|cake|toy|figurine)\b/i.test(value);
}

async function searchImages(query) {
  let tavilyResult = { sources: [], assets: [] };
  if (process.env.PPT_AGENT_TAVILY_KEY) {
    try { tavilyResult = await tavily(query); } catch { /* licensed fallbacks continue below */ }
  }
  // Commons and Openverse are complementary, not mutually exclusive. Stopping
  // after the first two Commons hits left image-rich decks with one or two
  // usable assets and encouraged repetition across every content page.
  const [brave, rawCommons, rawOpenverse] = await Promise.all([
    braveImages(query).catch(() => []),
    wikimediaImages(query).catch(() => []),
    openverseImages(query).catch(() => [])
  ]);
  const commons = rawCommons
    .filter(asset => matchesSpecificWorkQuery(query, asset) && isUsefulSpecificWorkAsset(asset));
  const openverse = rawOpenverse
    .filter(asset => matchesSpecificWorkQuery(query, asset) && isUsefulSpecificWorkAsset(asset));
  const relevantBrave = brave
    .filter(asset => matchesSpecificWorkQuery(query, asset) && isUsefulSpecificWorkAsset(asset));
  let contextual = [];
  const contextualQuery = contextualPhotoQuery(query);
  if ([...(tavilyResult.assets || []), ...commons, ...openverse].length < 4 && contextualQuery !== String(query || '').trim()) {
    const [contextCommons, contextOpenverse] = await Promise.all([
      wikimediaImages(contextualQuery).catch(() => []),
      openverseImages(contextualQuery).catch(() => [])
    ]);
    contextual = [...contextCommons, ...contextOpenverse].filter(isUsefulContextualAsset).map(item => ({
      ...item,
      title: `Contextual visual for ${String(query || '').trim()}: ${item.title || ''}`.slice(0, 240),
      description: `Reusable contextual visual for ${String(query || '').trim()}. ${item.description || ''}`.slice(0, 600),
      searchQuery: String(query || '').trim()
    }));
  }
  const assets = roundRobin([commons, openverse, tavilyResult.assets || [], relevantBrave, contextual])
    .filter((item, index, all) => item?.url && all.findIndex(other => other.url === item.url) === index)
    .slice(0, 8);
  // Unsplash's unauthenticated search rendition can carry visible provider
  // watermarks, so it remains excluded from the final asset chain.
  return { sources: tavilyResult.sources || [], assets };
}

export async function searchVisualAssets(queries, { maxQueries = 3, maxAssets = 8 } = {}) {
  const buckets = [];
  const failures = [];
  const queryLimit = boundedInteger(
    Math.min(Number(maxQueries) || 1, boundedInteger(process.env.PPT_AGENT_BRAVE_IMAGES_MAX_QUERIES, 3, 1, 3)),
    1,
    1,
    3
  );
  const assetLimit = boundedInteger(maxAssets, 8, 1, 12);
  for (const query of (Array.isArray(queries) ? queries : []).slice(0, queryLimit)) {
    const value = String(query || '').replace(/\s+/g, ' ').trim();
    if (!value) continue;
    try {
      const result = await searchImages(value);
      buckets.push(result.assets || []);
    } catch (error) {
      failures.push(`${value}: ${String(error?.message || error)}`);
    }
  }
  // Interleave query buckets so one broad query cannot consume the entire
  // asset budget. This produces genuinely varied decks while still allowing
  // a single successful provider/query to fill the budget when others fail.
  const output = [];
  const seen = new Set();
  for (const asset of roundRobin(buckets)) {
    if (!asset?.url || seen.has(asset.url)) continue;
    seen.add(asset.url);
    output.push(asset);
    if (output.length >= assetLimit) break;
  }
  return { assets: output, failures: failures.slice(0, 8) };
}

export function fallbackImageQuery(queries) {
  return (Array.isArray(queries) ? queries : [])
    .map(query => String(query || '').replace(/\s+/g, ' ').trim())
    .find(Boolean) || '';
}

export async function researchPresentation(queries, { includeWeb = true, maxSources = 12, maxSearches = 6 } = {}) {
  const budget = Math.max(1, Math.min(12, Number(maxSearches) || 1));
  const boundedQueries = queries.slice(0, 3);
  const candidates = [];
  for (const query of boundedQueries) {
    const tagged = (provider, promise) => promise.then(result => ({
      provider,
      query,
      sources: (result.sources || result || []).map(item => ({ ...item, provider, query })),
      assets: result.assets || []
    }));
    candidates.push(
      () => tagged('openalex', openAlex(query)),
      ...(includeWeb ? [() => tagged(process.env.PPT_AGENT_TAVILY_KEY ? 'tavily' : 'wikimedia-commons', searchImages(query))] : []),
      () => tagged('crossref', crossref(query)),
      () => tagged('arxiv', arxiv(query)),
      () => tagged('semantic-scholar', semanticScholar(query))
    );
  }
  const tasks = candidates.slice(0, budget).map(run => run());
  const settled = await Promise.allSettled(tasks);
  const buckets = settled
    .filter(item => item.status === 'fulfilled')
    .map(item => item.value.sources || []);
  const results = roundRobin(buckets);
  let assets = settled
    .filter(item => item.status === 'fulfilled')
    .flatMap(item => item.value.assets || [])
    .filter((item, index, all) => all.findIndex(other => other.url === item.url) === index)
    .slice(0, 8);
  // A model-generated query can be too abstract or return only documents.
  // Retry the complete licensed image chain with the user's own topic, never
  // with a generic stock subject. A hard-coded "AI laboratory" fallback made
  // unrelated building photos enter presentations about anime and books.
  if (includeWeb && !assets.length) {
    try {
      const fallbackQuery = fallbackImageQuery(boundedQueries);
      if (fallbackQuery) {
        const fallback = await searchImages(fallbackQuery);
        assets = (fallback.assets || []).slice(0, 8);
      }
    } catch (error) {
      settled.push({ status: 'rejected', reason: error });
    }
  }
  return {
    sources: dedupe(results, maxSources),
    assets,
    searchCount: tasks.length,
    degraded: includeWeb && !process.env.PPT_AGENT_TAVILY_KEY
      && !process.env.PPT_AGENT_BRAVE_IMAGES_KEY && !process.env.BRAVE_SEARCH_API_KEY,
    failures: settled.filter(item => item.status === 'rejected').map(item => String(item.reason?.message || item.reason)).slice(0, 8)
  };
}

function imageExtension(contentType, sourceUrl) {
  if (contentType === 'image/jpeg') return '.jpg';
  if (contentType === 'image/gif') return '.gif';
  if (contentType === 'image/webp') return '.webp';
  if (contentType === 'image/avif') return '.avif';
  if (contentType === 'image/svg+xml') return '.svg';
  const extension = path.extname(new URL(sourceUrl).pathname).toLowerCase();
  return ['.png', '.jpg', '.jpeg', '.gif', '.webp', '.avif'].includes(extension)
    ? extension.replace('.jpeg', '.jpg') : '.png';
}

/** Download only image results with an explicit origin and truthful reuse-status field. */
export async function downloadResearchAssets(assets, directory, { maxCount = 6 } = {}) {
  await fs.mkdir(directory, { recursive: true });
  const output = [];
  const failures = [];
  const seen = new Set();
  for (const asset of Array.isArray(assets) ? assets : []) {
    if (output.length >= maxCount) break;
    const url = String(asset?.url || '').trim();
    const sourceUrl = String(asset?.sourceUrl || '').trim();
    const license = text(asset?.license);
    const rightsStatus = text(asset?.rightsStatus || (license ? 'recorded' : '')).toLowerCase();
    const rightsNote = text(asset?.rightsNote);
    if (!url || !sourceUrl || (!license && !rightsNote) || seen.has(url)) continue;
    seen.add(url);
    try {
      let downloaded;
      let lastError;
      for (let attempt = 0; attempt < 3; attempt += 1) {
        try {
          downloaded = await fetchImage(url);
          break;
        } catch (error) {
          lastError = error;
          if (attempt === 2 || !/HTTP 429|aborted|fetch failed|timeout/i.test(String(error?.message || error))) throw error;
          await new Promise(resolve => setTimeout(resolve, 500 * (attempt + 1)));
        }
      }
      if (!downloaded) throw lastError || new Error('图片素材下载失败');
      const extension = imageExtension(downloaded.contentType, url);
      const id = `WEB${String(output.length + 1).padStart(2, '0')}`;
      const fileName = `${id}${extension}`;
      const filePath = path.resolve(directory, fileName);
      await fs.writeFile(filePath, downloaded.bytes, { flag: 'wx' });
      output.push({
        id,
        fileName,
        path: filePath,
        title: text(asset.title || asset.description).slice(0, 180),
        description: text(asset.description).slice(0, 600),
        searchQuery: text(asset.searchQuery || asset.query).slice(0, 300),
        sourceUrl,
        originalUrl: safeProvenanceUrl(asset.originalUrl),
        license,
        provider: text(asset.provider).slice(0, 80),
        confidence: text(asset.confidence).slice(0, 20),
        mightBeOffensive: asset.mightBeOffensive === true,
        rightsStatus: rightsStatus || 'unverified',
        rightsNote,
        originalWidth: Number(asset.width || 0),
        originalHeight: Number(asset.height || 0),
        width: downloaded.width,
        height: downloaded.height,
        accessedAt: new Date().toISOString(),
        origin: 'web-search'
      });
    } catch (error) {
      failures.push({ url, message: String(error?.message || error) });
    }
  }
  return { images: output, failures: failures.slice(0, 8) };
}

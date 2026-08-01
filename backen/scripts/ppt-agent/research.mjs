import dns from 'node:dns/promises';
import net from 'node:net';
import { Agent } from 'undici';
import { agentFetch, hasConfiguredProxy } from './net.mjs';
import { recordToolCall } from './model.mjs';

const MAX_RESPONSE_BYTES = 5 * 1024 * 1024;
const MAX_REDIRECTS = 3;
const ALLOWED_SEARCH_HOSTS = new Set([
  'api.tavily.com',
  'api.openalex.org',
  'api.crossref.org',
  'export.arxiv.org',
  'api.semanticscholar.org'
]);

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
  const addresses = await dns.lookup(endpoint.hostname, { all: true, verbatim: true });
  if (!addresses.length || addresses.some(item => isPrivateHost(item.address))) {
    throw new Error('搜索 API DNS 解析到私网或保留地址');
  }
  return { endpoint, address: addresses[0].address, family: addresses[0].family };
}

let researchFetch = agentFetch;
let resolveResearchEndpoint = assertPublicResolution;

export function setResearchTransportForTest({ fetch, resolve } = {}) {
  researchFetch = fetch || agentFetch;
  resolveResearchEndpoint = resolve || assertPublicResolution;
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
        .some(name => ['authorization', 'x-api-key', 'api-key'].includes(name.toLowerCase()));
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
  const assets = (response.images || []).slice(0, 8).map(item => ({
    url: typeof item === 'string' ? item : item.url,
    description: typeof item === 'string' ? '' : (item.description || ''),
    sourceUrl: typeof item === 'string' ? '' : (item.source_url || ''),
    license: typeof item === 'string' ? '' : (item.license || '')
  })).filter(item => item.url && item.sourceUrl && item.license);
  return { sources, assets };
}

export async function researchPresentation(queries, { includeWeb = true, maxSources = 12, maxSearches = 6 } = {}) {
  const budget = Math.max(1, Math.min(12, Number(maxSearches) || 1));
  const boundedQueries = queries.slice(0, 3);
  const candidates = [];
  for (const query of boundedQueries) {
    candidates.push(
      () => openAlex(query).then(sources => ({ provider: 'openalex', sources, assets: [] })),
      ...(includeWeb ? [() => tavily(query).then(result => ({ provider: 'tavily', ...result }))] : []),
      () => crossref(query).then(sources => ({ provider: 'crossref', sources, assets: [] })),
      () => arxiv(query).then(sources => ({ provider: 'arxiv', sources, assets: [] })),
      () => semanticScholar(query).then(sources => ({ provider: 'semantic-scholar', sources, assets: [] }))
    );
  }
  const tasks = candidates.slice(0, budget).map(run => run());
  const settled = await Promise.allSettled(tasks);
  const buckets = settled
    .filter(item => item.status === 'fulfilled')
    .map(item => item.value.sources || []);
  const results = roundRobin(buckets);
  const assets = settled
    .filter(item => item.status === 'fulfilled')
    .flatMap(item => item.value.assets || [])
    .filter((item, index, all) => all.findIndex(other => other.url === item.url) === index)
    .slice(0, 8);
  return {
    sources: dedupe(results, maxSources),
    assets,
    searchCount: tasks.length,
    degraded: includeWeb && !process.env.PPT_AGENT_TAVILY_KEY,
    failures: settled.filter(item => item.status === 'rejected').map(item => String(item.reason?.message || item.reason)).slice(0, 8)
  };
}

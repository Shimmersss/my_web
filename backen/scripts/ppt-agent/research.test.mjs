import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { braveImages, dedupe, discoverPageImages, downloadResearchAssets, fallbackImageQuery, fetchText, inspectImageBytes, isSyntheticDnsAddress, knownFirstPartyMediaSources, normalizeBraveImageQuery, parseBraveImageResults, readBoundedBody, relevantPageImageSources, roundRobin, safeEndpoint, searchVisualAssets, setResearchTransportForTest } from './research.mjs';
import { prefetchVisualAssets, visualQueriesFromPrompt } from './prefetch-visual-assets.mjs';

const ONE_PIXEL_PNG = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=', 'base64');

test('fallback image search keeps the presentation topic', () => {
  assert.equal(fallbackImageQuery([' Sword Art Online 角色关系 ']), 'Sword Art Online 角色关系');
  assert.equal(fallbackImageQuery(['', '第二查询']), '第二查询');
  assert.equal(fallbackImageQuery([]), '');
});

test('known media franchises provide verified first-party pages before generic image search', () => {
  const sources = knownFirstPartyMediaSources('生成刀剑神域ppt,图文并茂');
  assert.equal(sources.length, 4);
  assert.ok(sources.every(item => item.type === 'web' && item.url.startsWith('https://')));
  assert.deepEqual(knownFirstPartyMediaSources('量子计算研究'), []);
});

test('safeEndpoint blocks SSRF targets and credentials', () => {
  for (const url of [
    'http://api.example.com/search',
    'https://127.0.0.1/search',
    'https://10.1.2.3/search',
    'https://172.16.1.1/search',
    'https://192.168.1.1/search',
    'https://user:secret@example.com/search',
    'https://[::1]/search',
    'https://[::ffff:172.16.0.1]/search',
    'https://100.64.0.1/search',
    'https://198.18.0.1/search',
    'https://224.0.0.1/search',
    'https://search.attacker.example/search'
  ]) assert.throws(() => safeEndpoint(url), /公网 HTTPS/);
  assert.equal(safeEndpoint('https://api.tavily.com/search').hostname, 'api.tavily.com');
  assert.equal(safeEndpoint('https://api.search.brave.com/res/v1/images/search').hostname, 'api.search.brave.com');
});

test('Brave queries are bounded to 400 characters and 50 words', () => {
  const words = Array.from({ length: 80 }, (_, index) => `word${index}`).join(' ');
  assert.equal(normalizeBraveImageQuery(words).split(' ').length, 50);
  assert.equal([...normalizeBraveImageQuery('图'.repeat(800))].length, 400);
  assert.deepEqual(visualQueriesFromPrompt('主标题。第一部分；第二部分', 9), ['主标题。第一部分；第二部分', '第一部分', '第二部分']);
});

test('Brave image metadata uses trusted thumbnails and never invents a license', () => {
  const result = parseBraveImageResults({
    extra: { might_be_offensive: false },
    results: [
      {
        title: 'Verified topic match',
        url: 'https://example.com/article',
        source: 'example.com',
        thumbnail: { src: 'https://imgs.search.brave.com/token/image.jpg', width: 500, height: 281 },
        properties: { url: 'https://cdn.example.com/original.jpg', width: 1920, height: 1080 },
        confidence: 'high'
      },
      {
        title: 'Untrusted thumbnail',
        url: 'https://example.com/article-2',
        thumbnail: { src: 'https://cdn.example.com/thumb.jpg' },
        confidence: 'medium'
      },
      {
        title: 'Low confidence',
        url: 'https://example.com/article-3',
        thumbnail: { src: 'https://imgs.search.brave.com/token/low.jpg' },
        confidence: 'low'
      }
    ]
  }, 'presentation image');
  assert.equal(result.length, 1);
  assert.equal(result[0].provider, 'brave-images');
  assert.equal(result[0].license, '');
  assert.equal(result[0].rightsStatus, 'unverified');
  assert.match(result[0].rightsNote, /reuse rights require verification/);
  assert.equal(result[0].originalUrl, 'https://cdn.example.com/original.jpg');
  assert.deepEqual(parseBraveImageResults({ extra: { might_be_offensive: true }, results: [{}] }, 'query'), []);
});

test('Brave image search fixes its endpoint, strict SafeSearch and secret header', { concurrency: false }, async () => {
  const previousProxy = process.env.PPT_AGENT_PROXY_URL;
  const previousKey = process.env.PPT_AGENT_BRAVE_IMAGES_KEY;
  process.env.PPT_AGENT_PROXY_URL = 'http://127.0.0.1:7890';
  process.env.PPT_AGENT_BRAVE_IMAGES_KEY = 'test-secret';
  let received;
  setResearchTransportForTest({
    resolve: async value => ({ endpoint: new URL(value), address: '93.184.216.34', family: 4 }),
    fetch: async (url, options) => {
      received = { url: new URL(url), options };
      return new Response(JSON.stringify({ results: [] }), { status: 200 });
    }
  });
  try {
    assert.deepEqual(await braveImages('safe image', { count: 999 }), []);
    assert.equal(received.url.origin, 'https://api.search.brave.com');
    assert.equal(received.url.pathname, '/res/v1/images/search');
    assert.equal(received.url.searchParams.get('safesearch'), 'strict');
    assert.equal(received.url.searchParams.get('count'), '50');
    assert.equal(received.options.headers['X-Subscription-Token'], 'test-secret');
    assert.doesNotMatch(received.url.toString(), /test-secret/);
  } finally {
    if (previousProxy === undefined) delete process.env.PPT_AGENT_PROXY_URL;
    else process.env.PPT_AGENT_PROXY_URL = previousProxy;
    if (previousKey === undefined) delete process.env.PPT_AGENT_BRAVE_IMAGES_KEY;
    else process.env.PPT_AGENT_BRAVE_IMAGES_KEY = previousKey;
    setResearchTransportForTest();
  }
});

test('image downloads validate file signature and retain unverified rights provenance', { concurrency: false }, async () => {
  const dimensions = inspectImageBytes(ONE_PIXEL_PNG, 'image/png');
  assert.deepEqual(dimensions, { contentType: 'image/png', width: 1, height: 1 });
  assert.throws(() => inspectImageBytes(ONE_PIXEL_PNG, 'image/jpeg'), /签名不一致/);
  assert.throws(() => inspectImageBytes(Buffer.from('not an image'), 'image/png'), /签名无效/);
  const outputDir = await fs.mkdtemp(path.join(os.tmpdir(), 'brave-image-download-'));
  setResearchTransportForTest({
    assetResolve: async value => ({ endpoint: new URL(value), address: '93.184.216.34', family: 4 }),
    assetFetch: async () => new Response(ONE_PIXEL_PNG, {
      status: 200,
      headers: { 'content-type': 'image/png', 'content-length': String(ONE_PIXEL_PNG.length) }
    })
  });
  try {
    const result = await downloadResearchAssets([{
      url: 'https://imgs.search.brave.com/token/image.png',
      sourceUrl: 'https://example.com/article',
      originalUrl: 'https://cdn.example.com/original.png',
      title: 'Indexed image',
      provider: 'brave-images',
      rightsStatus: 'unverified',
      rightsNote: 'Brave indexed image; reuse rights require verification'
    }], outputDir, { maxCount: 1 });
    assert.equal(result.images.length, 1);
    assert.equal(result.images[0].license, '');
    assert.equal(result.images[0].rightsStatus, 'unverified');
    assert.equal(result.images[0].originalUrl, 'https://cdn.example.com/original.png');
    assert.equal(result.images[0].mightBeOffensive, false);
    assert.equal(result.images[0].width, 1);
    assert.equal(result.images[0].height, 1);
  } finally {
    setResearchTransportForTest();
    await fs.rm(outputDir, { recursive: true, force: true });
  }
});

test('PPTX prefetch succeeds without a Brave key by using licensed shared fallbacks', { concurrency: false }, async () => {
  const previousProxy = process.env.PPT_AGENT_PROXY_URL;
  const previousBrave = process.env.PPT_AGENT_BRAVE_IMAGES_KEY;
  const previousBraveFallback = process.env.BRAVE_SEARCH_API_KEY;
  const previousTavily = process.env.PPT_AGENT_TAVILY_KEY;
  process.env.PPT_AGENT_PROXY_URL = 'http://127.0.0.1:7890';
  delete process.env.PPT_AGENT_BRAVE_IMAGES_KEY;
  delete process.env.BRAVE_SEARCH_API_KEY;
  delete process.env.PPT_AGENT_TAVILY_KEY;
  const outputDir = await fs.mkdtemp(path.join(os.tmpdir(), 'visual-prefetch-'));
  setResearchTransportForTest({
    resolve: async value => ({ endpoint: new URL(value), address: '93.184.216.34', family: 4 }),
    fetch: async url => {
      const endpoint = new URL(url);
      if (endpoint.hostname === 'commons.wikimedia.org') {
        return new Response(JSON.stringify({ query: { pages: { 1: {
          title: 'File:Quantum processor.jpg',
          imageinfo: [{
            mime: 'image/jpeg',
            thumburl: 'https://upload.wikimedia.org/quantum.png',
            url: 'https://upload.wikimedia.org/quantum.png',
            extmetadata: { LicenseShortName: { value: 'CC BY 4.0' } }
          }]
        } } } }), { status: 200, headers: { 'content-type': 'application/json' } });
      }
      if (endpoint.hostname === 'api.openverse.org') {
        return new Response(JSON.stringify({ results: [] }), { status: 200, headers: { 'content-type': 'application/json' } });
      }
      throw new Error(`unexpected host ${endpoint.hostname}`);
    },
    assetResolve: async value => ({ endpoint: new URL(value), address: '93.184.216.34', family: 4 }),
    assetFetch: async () => new Response(ONE_PIXEL_PNG, { status: 200, headers: { 'content-type': 'image/png' } })
  });
  try {
    const manifest = await prefetchVisualAssets({ prompt: 'quantum processor architecture', maxImages: 1, maxQueries: 1 }, outputDir);
    assert.equal(manifest.provider, 'server-image-search');
    assert.equal(manifest.images.length, 1);
    assert.equal(manifest.images[0].provider, 'wikimedia-commons');
    assert.equal(manifest.images[0].rightsStatus, 'recorded');
    assert.equal(manifest.images[0].license, 'CC BY 4.0');
    assert.equal(manifest.images[0].localPath, 'WEB01.png');
    assert.deepEqual(JSON.parse(await fs.readFile(path.join(outputDir, 'image-assets.json'), 'utf8')).images, manifest.images);
  } finally {
    setResearchTransportForTest();
    if (previousProxy === undefined) delete process.env.PPT_AGENT_PROXY_URL;
    else process.env.PPT_AGENT_PROXY_URL = previousProxy;
    if (previousBrave === undefined) delete process.env.PPT_AGENT_BRAVE_IMAGES_KEY;
    else process.env.PPT_AGENT_BRAVE_IMAGES_KEY = previousBrave;
    if (previousBraveFallback === undefined) delete process.env.BRAVE_SEARCH_API_KEY;
    else process.env.BRAVE_SEARCH_API_KEY = previousBraveFallback;
    if (previousTavily === undefined) delete process.env.PPT_AGENT_TAVILY_KEY;
    else process.env.PPT_AGENT_TAVILY_KEY = previousTavily;
    await fs.rm(outputDir, { recursive: true, force: true });
  }
});

test('Tavily string images survive with truthful unverified provenance', { concurrency: false }, async () => {
  const previousProxy = process.env.PPT_AGENT_PROXY_URL;
  const previousKey = process.env.PPT_AGENT_TAVILY_KEY;
  process.env.PPT_AGENT_PROXY_URL = 'http://127.0.0.1:7890';
  process.env.PPT_AGENT_TAVILY_KEY = 'test-tavily';
  setResearchTransportForTest({
    resolve: async value => ({ endpoint: new URL(value), address: '93.184.216.34', family: 4 }),
    fetch: async url => {
      const endpoint = new URL(url);
      if (endpoint.hostname === 'api.tavily.com') {
        return new Response(JSON.stringify({ images: ['https://images.example.com/topic.jpg'], results: [] }), {
          status: 200,
          headers: { 'content-type': 'application/json' }
        });
      }
      if (endpoint.hostname === 'commons.wikimedia.org') {
        return new Response(JSON.stringify({ query: { pages: {} } }), { status: 200 });
      }
      if (endpoint.hostname === 'api.openverse.org') {
        return new Response(JSON.stringify({ results: [] }), { status: 200 });
      }
      throw new Error(`unexpected host ${endpoint.hostname}`);
    }
  });
  try {
    const result = await searchVisualAssets(['specific topic'], { maxQueries: 1, maxAssets: 2 });
    assert.equal(result.assets.length, 1);
    assert.equal(result.assets[0].provider, 'tavily-images');
    assert.equal(result.assets[0].rightsStatus, 'unverified');
    assert.equal(result.assets[0].license, '');
  } finally {
    setResearchTransportForTest();
    if (previousProxy === undefined) delete process.env.PPT_AGENT_PROXY_URL;
    else process.env.PPT_AGENT_PROXY_URL = previousProxy;
    if (previousKey === undefined) delete process.env.PPT_AGENT_TAVILY_KEY;
    else process.env.PPT_AGENT_TAVILY_KEY = previousKey;
  }
});

test('synthetic public DNS answers are recognized separately from private targets', () => {
  assert.equal(isSyntheticDnsAddress('198.18.1.80'), true);
  assert.equal(isSyntheticDnsAddress('198.19.255.254'), true);
  assert.equal(isSyntheticDnsAddress('198.20.1.1'), false);
  assert.equal(isSyntheticDnsAddress('10.0.0.1'), false);
});

test('chunked response is cancelled before exceeding the byte limit', async () => {
  const response = new Response(new ReadableStream({
    pull(controller) {
      controller.enqueue(new Uint8Array(1024));
    }
  }));
  await assert.rejects(() => readBoundedBody(response, 2048), /超过 5MB/);
});

test('research sources dedupe by DOI then normalized title and keep limits', () => {
  const items = [
    { title: 'Agent Systems', url: 'https://doi.org/10.1/x', doi: '10.1/x', authors: ['A'] },
    { title: 'Duplicate', url: 'https://example.com/duplicate', doi: '10.1/X', authors: ['B'] },
    { title: 'A second paper', url: 'https://example.com/2', authors: ['C'] }
  ];
  const output = dedupe(items, 2);
  assert.equal(output.length, 2);
  assert.deepEqual(output.map(item => item.id), ['S01', 'S02']);
});

test('provider results are interleaved before the source limit is applied', () => {
  assert.deepEqual(roundRobin([
    ['openalex-1', 'openalex-2'],
    ['crossref-1', 'crossref-2'],
    ['tavily-1', 'tavily-2']
  ]), [
    'openalex-1', 'crossref-1', 'tavily-1',
    'openalex-2', 'crossref-2', 'tavily-2'
  ]);
});

test('visual search combines Commons and Openverse assets for image diversity', { concurrency: false }, async () => {
  const previousProxy = process.env.PPT_AGENT_PROXY_URL;
  const previousTavily = process.env.PPT_AGENT_TAVILY_KEY;
  process.env.PPT_AGENT_PROXY_URL = 'http://127.0.0.1:7890';
  delete process.env.PPT_AGENT_TAVILY_KEY;
  setResearchTransportForTest({
    resolve: async value => ({ endpoint: new URL(value), address: '93.184.216.34', family: 4 }),
    fetch: async url => {
      const endpoint = new URL(url);
      if (endpoint.hostname === 'commons.wikimedia.org') {
        return new Response(JSON.stringify({ query: { pages: { 1: {
          title: 'File:VR headset.jpg',
          imageinfo: [{
            mime: 'image/jpeg',
            thumburl: 'https://upload.wikimedia.org/vr.jpg',
            url: 'https://upload.wikimedia.org/vr.jpg',
            extmetadata: { LicenseShortName: { value: 'CC BY 4.0' } }
          }]
        } } } }), { status: 200, headers: { 'content-type': 'application/json' } });
      }
      if (endpoint.hostname === 'api.openverse.org') {
        return new Response(JSON.stringify({ results: [{
          title: 'Digital world',
          thumbnail: 'https://images.example.com/digital-world.jpg',
          foreign_landing_url: 'https://photos.example.com/digital-world',
          license: 'by-sa',
          license_version: '4.0'
        }] }), { status: 200, headers: { 'content-type': 'application/json' } });
      }
      throw new Error(`unexpected host ${endpoint.hostname}`);
    }
  });
  try {
    const result = await searchVisualAssets(['virtual reality digital world'], { maxQueries: 1, maxAssets: 8 });
    assert.equal(result.assets.length, 2);
    assert.deepEqual(result.assets.map(item => item.title), ['VR headset.jpg', 'Digital world']);
  } finally {
    if (previousProxy === undefined) delete process.env.PPT_AGENT_PROXY_URL;
    else process.env.PPT_AGENT_PROXY_URL = previousProxy;
    if (previousTavily === undefined) delete process.env.PPT_AGENT_TAVILY_KEY;
    else process.env.PPT_AGENT_TAVILY_KEY = previousTavily;
    setResearchTransportForTest();
  }
});

test('anime searches fall back to reusable contextual VR visuals when copyrighted art has no open results', { concurrency: false }, async () => {
  const previousProxy = process.env.PPT_AGENT_PROXY_URL;
  const previousTavily = process.env.PPT_AGENT_TAVILY_KEY;
  process.env.PPT_AGENT_PROXY_URL = 'http://127.0.0.1:7890';
  delete process.env.PPT_AGENT_TAVILY_KEY;
  const requestedQueries = [];
  setResearchTransportForTest({
    resolve: async value => ({ endpoint: new URL(value), address: '93.184.216.34', family: 4 }),
    fetch: async url => {
      const endpoint = new URL(url);
      const query = endpoint.searchParams.get('gsrsearch') || endpoint.searchParams.get('q') || '';
      requestedQueries.push(query);
      const contextual = /^virtual reality(?: filetype:bitmap)?$/i.test(query);
      if (endpoint.hostname === 'commons.wikimedia.org') {
        return new Response(JSON.stringify(contextual ? { query: { pages: { 1: {
          title: 'File:Reusable VR installation.jpg',
          imageinfo: [{
            mime: 'image/jpeg',
            thumburl: 'https://upload.wikimedia.org/reusable-vr.jpg',
            url: 'https://upload.wikimedia.org/reusable-vr.jpg',
            extmetadata: { LicenseShortName: { value: 'CC BY-SA 4.0' } }
          }]
        } } } } : { query: { pages: { 2: {
          title: 'File:Drawing the Sword.jpg',
          imageinfo: [{
            mime: 'image/jpeg',
            thumburl: 'https://upload.wikimedia.org/unrelated-sword.jpg',
            url: 'https://upload.wikimedia.org/unrelated-sword.jpg',
            extmetadata: { LicenseShortName: { value: 'CC BY 4.0' } }
          }]
        } } } }), { status: 200, headers: { 'content-type': 'application/json' } });
      }
      if (endpoint.hostname === 'api.openverse.org') {
        return new Response(JSON.stringify({ results: [] }), { status: 200, headers: { 'content-type': 'application/json' } });
      }
      throw new Error(`unexpected host ${endpoint.hostname}`);
    }
  });
  try {
    const original = 'Sword Art Online anime character key visual Kirito Asuna';
    const result = await searchVisualAssets([original], { maxQueries: 1, maxAssets: 8 });
    assert.equal(result.assets.length, 1);
    assert.match(result.assets[0].title, /Contextual visual for Sword Art Online/);
    assert.doesNotMatch(result.assets[0].url, /unrelated-sword/);
    assert.equal(result.assets[0].searchQuery, original);
    assert.ok(requestedQueries.some(query => /^virtual reality(?: filetype:bitmap)?$/i.test(query)));
  } finally {
    if (previousProxy === undefined) delete process.env.PPT_AGENT_PROXY_URL;
    else process.env.PPT_AGENT_PROXY_URL = previousProxy;
    if (previousTavily === undefined) delete process.env.PPT_AGENT_TAVILY_KEY;
    else process.env.PPT_AGENT_TAVILY_KEY = previousTavily;
    setResearchTransportForTest();
  }
});

test('visual search interleaves distinct queries instead of exhausting the first result bucket', { concurrency: false }, async () => {
  const previousProxy = process.env.PPT_AGENT_PROXY_URL;
  const previousTavily = process.env.PPT_AGENT_TAVILY_KEY;
  process.env.PPT_AGENT_PROXY_URL = 'http://127.0.0.1:7890';
  delete process.env.PPT_AGENT_TAVILY_KEY;
  setResearchTransportForTest({
    resolve: async value => ({ endpoint: new URL(value), address: '93.184.216.34', family: 4 }),
    fetch: async url => {
      const endpoint = new URL(url);
      const query = endpoint.searchParams.get('gsrsearch') || endpoint.searchParams.get('q') || '';
      const label = query.includes('castle') ? 'castle' : query.includes('forest') ? 'forest' : 'vr';
      if (endpoint.hostname === 'commons.wikimedia.org') {
        return new Response(JSON.stringify({ query: { pages: {
          1: { title: `File:${label}-1.jpg`, imageinfo: [{ mime: 'image/jpeg', thumburl: `https://upload.wikimedia.org/${label}-1.jpg`, url: `https://upload.wikimedia.org/${label}-1.jpg`, extmetadata: { LicenseShortName: { value: 'CC BY 4.0' } } }] },
          2: { title: `File:${label}-2.jpg`, imageinfo: [{ mime: 'image/jpeg', thumburl: `https://upload.wikimedia.org/${label}-2.jpg`, url: `https://upload.wikimedia.org/${label}-2.jpg`, extmetadata: { LicenseShortName: { value: 'CC BY 4.0' } } }] },
          ...(label === 'castle' ? { 3: { title: 'File:Fantasy Castle Cake.jpg', imageinfo: [{ mime: 'image/jpeg', thumburl: 'https://upload.wikimedia.org/castle-cake.jpg', url: 'https://upload.wikimedia.org/castle-cake.jpg', extmetadata: { LicenseShortName: { value: 'CC BY 4.0' } } }] } } : {})
        } } }), { status: 200, headers: { 'content-type': 'application/json' } });
      }
      if (endpoint.hostname === 'api.openverse.org') {
        return new Response(JSON.stringify({ results: [] }), { status: 200, headers: { 'content-type': 'application/json' } });
      }
      throw new Error(`unexpected host ${endpoint.hostname}`);
    }
  });
  try {
    const result = await searchVisualAssets(['virtual reality', 'fantasy castle', 'fantasy forest'], { maxQueries: 3, maxAssets: 6 });
    assert.deepEqual(result.assets.map(item => item.title), [
      'vr-1.jpg', 'castle-1.jpg', 'forest-1.jpg',
      'vr-2.jpg', 'castle-2.jpg', 'forest-2.jpg'
    ]);
    assert.ok(result.assets.every(item => !/cake/i.test(item.title)));
  } finally {
    if (previousProxy === undefined) delete process.env.PPT_AGENT_PROXY_URL;
    else process.env.PPT_AGENT_PROXY_URL = previousProxy;
    if (previousTavily === undefined) delete process.env.PPT_AGENT_TAVILY_KEY;
    else process.env.PPT_AGENT_TAVILY_KEY = previousTavily;
    setResearchTransportForTest();
  }
});

test('source-page image extraction excludes unrelated papers and ranks topical web pages', () => {
  const selected = relevantPageImageSources([
    { title: 'Introduction to Electromagnetism', url: 'https://arxiv.org/abs/2109.00606', type: 'paper' },
    { title: 'Generic virtual reality review', url: 'https://example.com/vr', type: 'web' },
    { title: 'Medieval swords are more deadly than you think', url: 'https://example.com/medieval-swords', type: 'web', query: 'Sword Art Online official' },
    { title: 'Sword Art Online official chronology', url: 'https://www.swordart-onlineusa.com/chronology', type: 'web', query: 'Sword Art Online official' },
    { title: '刀剑神域 - 萌娘百科', url: 'https://zh.moegirl.org.cn/刀剑神域', type: 'web' }
  ], '生成刀剑神域相关 PPT，要求图文并茂');
  assert.deepEqual(selected.map(item => item.title), ['Sword Art Online official chronology', '刀剑神域 - 萌娘百科']);
});

test('trusted loopback proxy remains active for allowlisted research requests', { concurrency: false }, async () => {
  const previous = process.env.PPT_AGENT_PROXY_URL;
  process.env.PPT_AGENT_PROXY_URL = 'http://127.0.0.1:7890';
  let receivedDispatcher = 'not-called';
  setResearchTransportForTest({
    resolve: async value => ({ endpoint: new URL(value), address: '93.184.216.34', family: 4 }),
    fetch: async (url, options) => {
      receivedDispatcher = options.dispatcher;
      return new Response('{}', { status: 200, headers: { 'content-type': 'application/json' } });
    }
  });
  try {
    assert.equal(await fetchText('https://api.tavily.com/search'), '{}');
    assert.equal(receivedDispatcher, undefined);
  } finally {
    if (previous === undefined) delete process.env.PPT_AGENT_PROXY_URL;
    else process.env.PPT_AGENT_PROXY_URL = previous;
    setResearchTransportForTest();
  }
});

test('Mimo source pages expose only explicit preview image metadata', { concurrency: false }, async () => {
  const previous = process.env.PPT_AGENT_PROXY_URL;
  process.env.PPT_AGENT_PROXY_URL = 'http://127.0.0.1:7890';
  setResearchTransportForTest({
    resolve: async value => ({ endpoint: new URL(value), address: '93.184.216.34', family: 4 }),
    fetch: async (_url, options) => {
      assert.match(String(options.headers.accept), /text\/html/);
      return new Response('<html><head><meta property="og:image" content="https://cdn.example.com/sao.jpg"></head></html>', {
        status: 200,
        headers: { 'content-type': 'text/html; charset=utf-8' }
      });
    }
  });
  try {
    const result = await discoverPageImages([{ title: 'Sword Art Online official page', url: 'https://example.com/sao' }]);
    assert.equal(result.failures.length, 0);
    assert.equal(result.assets[0].url, 'https://cdn.example.com/sao.jpg');
    assert.equal(result.assets[0].sourceUrl, 'https://example.com/sao');
    assert.match(result.assets[0].license, /verification/);
  } finally {
    if (previous === undefined) delete process.env.PPT_AGENT_PROXY_URL;
    else process.env.PPT_AGENT_PROXY_URL = previous;
    setResearchTransportForTest();
  }
});

test('source-page image extraction supports link rel=image_src href metadata', { concurrency: false }, async () => {
  const previous = process.env.PPT_AGENT_PROXY_URL;
  process.env.PPT_AGENT_PROXY_URL = 'http://127.0.0.1:7890';
  setResearchTransportForTest({
    resolve: async value => ({ endpoint: new URL(value), address: '93.184.216.34', family: 4 }),
    fetch: async () => new Response('<html><head><link href="/preview.jpg" media="screen" rel="alternate image_src"></head></html>', {
      status: 200,
      headers: { 'content-type': 'text/html; charset=utf-8' }
    })
  });
  try {
    const result = await discoverPageImages([{ title: 'Topic page', url: 'https://example.com/topic' }]);
    assert.equal(result.assets[0].url, 'https://example.com/preview.jpg');
  } finally {
    if (previous === undefined) delete process.env.PPT_AGENT_PROXY_URL;
    else process.env.PPT_AGENT_PROXY_URL = previous;
    setResearchTransportForTest();
  }
});

test('HTTP errors and declared oversized bodies are cancelled promptly', { concurrency: false }, async () => {
  for (const status of [500, 200]) {
    let cancelled = false;
    process.env.PPT_AGENT_PROXY_URL = 'http://127.0.0.1:7890';
    setResearchTransportForTest({
      resolve: async value => ({ endpoint: new URL(value), address: '93.184.216.34', family: 4 }),
      fetch: async () => new Response(new ReadableStream({ pull() {}, cancel() { cancelled = true; } }), {
        status,
        headers: status === 200 ? { 'content-length': String(6 * 1024 * 1024) } : {}
      })
    });
    await assert.rejects(() => fetchText('https://api.tavily.com/search'), /HTTP 500|超过 5MB/);
    assert.equal(cancelled, true);
    setResearchTransportForTest();
    delete process.env.PPT_AGENT_PROXY_URL;
  }
});

test('rejected redirects cancel streaming bodies and protect every authentication header', { concurrency: false }, async () => {
  const cases = [
    { headers: {}, location: null, expected: /缺少 Location/ },
    { headers: {}, location: 'https://attacker.example/path', expected: /blocked next hop/ },
    { headers: { 'x-api-key': 'secret' }, location: 'https://api.tavily.com/path', expected: /携带密钥跨域/ },
    { headers: { 'X-Subscription-Token': 'secret' }, location: 'https://api.tavily.com/path', expected: /携带密钥跨域/ }
  ];
  for (const item of cases) {
    let cancelled = false;
    process.env.PPT_AGENT_PROXY_URL = 'http://127.0.0.1:7890';
    setResearchTransportForTest({
      resolve: async value => {
        const endpoint = new URL(value);
        if (endpoint.hostname === 'attacker.example') throw new Error('blocked next hop');
        return { endpoint, address: '93.184.216.34', family: 4 };
      },
      fetch: async () => new Response(new ReadableStream({ pull() {}, cancel() { cancelled = true; } }), {
        status: 302,
        headers: item.location ? { location: item.location } : {}
      })
    });
    await assert.rejects(() => fetchText('https://api.semanticscholar.org/graph/v1/paper/search', {
      headers: item.headers
    }), item.expected);
    assert.equal(cancelled, true);
    setResearchTransportForTest();
    delete process.env.PPT_AGENT_PROXY_URL;
  }
});

test('redirect limit cancels every response before rejecting', { concurrency: false }, async () => {
  let cancelled = 0;
  process.env.PPT_AGENT_PROXY_URL = 'http://127.0.0.1:7890';
  setResearchTransportForTest({
    resolve: async value => ({ endpoint: new URL(value), address: '93.184.216.34', family: 4 }),
    fetch: async () => new Response(new ReadableStream({ pull() {}, cancel() { cancelled += 1; } }), {
      status: 302,
      headers: { location: '/next' }
    })
  });
  try {
    await assert.rejects(() => fetchText('https://api.tavily.com/search'), /重定向次数过多/);
    assert.equal(cancelled, 4);
  } finally {
    setResearchTransportForTest();
    delete process.env.PPT_AGENT_PROXY_URL;
  }
});

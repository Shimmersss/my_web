import test from 'node:test';
import assert from 'node:assert/strict';
import { dedupe, discoverPageImages, fallbackImageQuery, fetchText, isSyntheticDnsAddress, readBoundedBody, relevantPageImageSources, roundRobin, safeEndpoint, setResearchTransportForTest } from './research.mjs';

test('fallback image search keeps the presentation topic', () => {
  assert.equal(fallbackImageQuery([' Sword Art Online 角色关系 ']), 'Sword Art Online 角色关系');
  assert.equal(fallbackImageQuery(['', '第二查询']), '第二查询');
  assert.equal(fallbackImageQuery([]), '');
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

test('source-page image extraction excludes unrelated papers and ranks topical web pages', () => {
  const selected = relevantPageImageSources([
    { title: 'Introduction to Electromagnetism', url: 'https://arxiv.org/abs/2109.00606', type: 'paper' },
    { title: 'Generic virtual reality review', url: 'https://example.com/vr', type: 'web' },
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
    { headers: { 'x-api-key': 'secret' }, location: 'https://api.tavily.com/path', expected: /携带密钥跨域/ }
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

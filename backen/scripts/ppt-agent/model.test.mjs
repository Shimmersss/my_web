import test from 'node:test';
import assert from 'node:assert/strict';
import { completeJson, completeMimoWebSearch, detectImageMediaType, extractJson, recordToolCall, resetAgentLimitsForTest, setModelFetchForTest } from './model.mjs';

test('vision MIME is detected from image bytes', () => {
  assert.equal(detectImageMediaType(Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a])), 'image/png');
  assert.equal(detectImageMediaType(Buffer.from([0xff, 0xd8, 0xff, 0xe0])), 'image/jpeg');
  assert.equal(detectImageMediaType(Buffer.from('GIF89a')), 'image/gif');
  assert.throws(() => detectImageMediaType(Buffer.from('not-image'), 'bad.bin'), /受支持/);
});

test('extractJson accepts plain and fenced model actions', () => {
  assert.equal(extractJson('{"action":"research","args":{"queries":["agent"]}}').action, 'research');
  assert.equal(extractJson('```json\n{"action":"final","args":{}}\n```').action, 'final');
});

test('extractJson deterministically repairs missing commas and truncated model JSON', () => {
  const missingComma = '{"action":"final","args":{"presentation":{"slides":[{"title":"封面"} {"title":"内容"}]}}}';
  assert.deepEqual(
    extractJson(missingComma).args.presentation.slides.map(slide => slide.title),
    ['封面', '内容']
  );
  const truncated = '{"action":"final","args":{"presentation":{"slides":[{"title":"封面"},{"title":"内容"}]';
  assert.equal(extractJson(truncated).args.presentation.slides.length, 2);
});

test('extractJson rejects prose without a complete object', () => {
  assert.throws(() => extractJson('I cannot comply'), /没有返回 JSON/);
  assert.throws(() => extractJson('{"action":'), /JSON/);
});

async function withModelServer(protocol, responses, callback) {
  let calls = 0;
  setModelFetchForTest(async () => {
    const payload = responses[Math.min(calls++, responses.length - 1)];
    return new Response(JSON.stringify(protocol === 'CLAUDE'
      ? { content: [{ type: 'text', text: payload }] }
      : { choices: [{ message: { content: payload } }] }), {
      status: 200,
      headers: { 'content-type': 'application/json' }
    });
  });
  const previous = { ...process.env };
  process.env.PPT_AGENT_LLM_ENDPOINT = 'https://model.test/v1/messages';
  process.env.PPT_AGENT_LLM_KEY = 'test-secret';
  process.env.PPT_AGENT_LLM_MODEL = 'test-model';
  process.env.PPT_AGENT_LLM_PROTOCOL = protocol;
  try {
    await callback(() => calls);
  } finally {
    process.env = previous;
    setModelFetchForTest();
  }
}

test('provider-neutral adapter parses OpenAI and Claude responses', { concurrency: false }, async () => {
  resetAgentLimitsForTest();
  for (const protocol of ['OPENAI', 'CLAUDE']) {
    await withModelServer(protocol, ['{"action":"final","args":{"protocol":"ok"}}'], async () => {
      const result = await completeJson({ system: 'system', user: 'user' });
      assert.equal(result.args.protocol, 'ok');
    });
  }
});

test('OpenAI-compatible array content is normalized before JSON parsing', { concurrency: false }, async () => {
  resetAgentLimitsForTest();
  const previous = { ...process.env };
  setModelFetchForTest(async () => new Response(JSON.stringify({
    choices: [{ message: { content: [{ type: 'output_text', text: '{"action":"final","args":{"array":true}}' }] } }]
  }), { status: 200, headers: { 'content-type': 'application/json' } }));
  process.env.PPT_AGENT_LLM_ENDPOINT = 'https://model.test/v1/chat/completions';
  process.env.PPT_AGENT_LLM_KEY = 'test-secret';
  process.env.PPT_AGENT_LLM_MODEL = 'test-model';
  process.env.PPT_AGENT_LLM_PROTOCOL = 'OPENAI';
  try {
    const result = await completeJson({ system: 'system', user: 'user' });
    assert.equal(result.args.array, true);
  } finally {
    process.env = previous;
    setModelFetchForTest();
  }
});

test('MiMo structured requests disable thinking and expand max-token retries', { concurrency: false }, async () => {
  resetAgentLimitsForTest();
  const previous = { ...process.env };
  const bodies = [];
  setModelFetchForTest(async (_url, options) => {
    bodies.push(JSON.parse(options.body));
    if (bodies.length === 1) {
      return new Response(JSON.stringify({ content: [], stop_reason: 'max_tokens' }), {
        status: 200,
        headers: { 'content-type': 'application/json' }
      });
    }
    return new Response(JSON.stringify({ content: [{ type: 'text', text: '{"action":"final","args":{"ok":true}}' }] }), {
      status: 200,
      headers: { 'content-type': 'application/json' }
    });
  });
  process.env.PPT_AGENT_LLM_ENDPOINT = 'https://token-plan-cn.xiaomimimo.com/anthropic/v1/messages';
  process.env.PPT_AGENT_LLM_KEY = 'test-secret';
  process.env.PPT_AGENT_LLM_MODEL = 'mimo-v2.5-pro';
  process.env.PPT_AGENT_LLM_PROTOCOL = 'CLAUDE';
  try {
    const result = await completeJson({ system: 'system', user: 'user', maxTokens: 9000 });
    assert.equal(result.args.ok, true);
    assert.deepEqual(bodies.map(body => body.thinking), [
      { type: 'disabled' },
      { type: 'disabled' }
    ]);
    assert.deepEqual(bodies.map(body => body.max_tokens), [9000, 13500]);
  } finally {
    process.env = previous;
    setModelFetchForTest();
  }
});

test('Mimo native web search sends the documented tool payload', { concurrency: false }, async () => {
  const previous = { ...process.env };
  let requestBody;
  setModelFetchForTest(async (_url, options) => {
    requestBody = JSON.parse(options.body);
    return new Response(JSON.stringify({
      choices: [{ message: {
        content: '搜索结果',
        annotations: [{ type: 'url_citation', url: 'https://example.com/source', title: 'Example source' }]
      } }],
      usage: { total_tokens: 12 }
    }), { status: 200, headers: { 'content-type': 'application/json' } });
  });
  process.env.PPT_AGENT_MIMO_SEARCH_ENDPOINT = 'https://mimo.test/v1/chat/completions';
  process.env.PPT_AGENT_MIMO_SEARCH_KEY = 'test-search-key';
  process.env.PPT_AGENT_MIMO_SEARCH_MODEL = 'mimo-v2.5-pro';
  try {
    const result = await completeMimoWebSearch({ system: 'system', user: 'search this', maxKeyword: 3, limit: 6 });
    assert.equal(result.content, '搜索结果');
    assert.equal(result.annotations[0].url, 'https://example.com/source');
    assert.equal(requestBody.model, 'mimo-v2.5-pro');
    assert.equal(requestBody.thinking.type, 'disabled');
    assert.equal(requestBody.tool_choice, 'auto');
    assert.deepEqual(requestBody.tools, [{ type: 'web_search', max_keyword: 3, force_search: true, limit: 6 }]);
  } finally {
    process.env = previous;
    setModelFetchForTest();
  }
});

test('invalid model action is repaired and bounded', { concurrency: false }, async () => {
  resetAgentLimitsForTest();
  await withModelServer('OPENAI', ['not json', '{"action":"final","args":{}}'], async calls => {
    assert.equal((await completeJson({ system: 'system', user: 'user' })).action, 'final');
    assert.equal(calls(), 2);
  });
});

test('agent action limit stops after 24 rounds', { concurrency: false }, async () => {
  resetAgentLimitsForTest();
  await withModelServer('OPENAI', ['{"action":"final","args":{}}'], async calls => {
    for (let index = 0; index < 24; index += 1) {
      assert.equal((await completeJson({ system: 'system', user: 'user' })).action, 'final');
    }
    await assert.rejects(() => completeJson({ system: 'system', user: 'user' }), /超过 24 个回合/);
    assert.equal(calls(), 24);
  });
});

test('model request limit stops after 48 calls including repairs', { concurrency: false }, async () => {
  resetAgentLimitsForTest();
  await withModelServer('OPENAI', ['not json'], async calls => {
    for (let index = 0; index < 16; index += 1) {
      await assert.rejects(() => completeJson({ system: 'system', user: 'user' }), /连续修复失败/);
    }
    await assert.rejects(
      () => completeJson({ system: 'system', user: 'user' }),
      /超过 48 次模型\/工具调用/
    );
    assert.equal(calls(), 48);
  });
});

test('network requests have a separate bounded budget from authoring tools', () => {
  resetAgentLimitsForTest();
  for (let index = 0; index < 96; index += 1) recordToolCall('http-search');
  for (let index = 0; index < 48; index += 1) recordToolCall('compose-html');
  assert.throws(() => recordToolCall('http-search'), /超过 96 次联网请求/);

  resetAgentLimitsForTest();
  for (let index = 0; index < 48; index += 1) recordToolCall('render-html');
  assert.throws(() => recordToolCall('compose-html'), /超过 48 次工具调用/);
});

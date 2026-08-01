import test from 'node:test';
import assert from 'node:assert/strict';
import { completeJson, detectImageMediaType, extractJson, resetAgentLimitsForTest, setModelFetchForTest } from './model.mjs';

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

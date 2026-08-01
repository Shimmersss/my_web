import fs from 'node:fs/promises';
import { agentFetch } from './net.mjs';

const JSON_BLOCK = /```(?:json)?\s*([\s\S]*?)```/i;
const MAX_AGENT_ACTIONS = 24;
const MAX_MODEL_REQUESTS = 48;
const MAX_TOOL_CALLS = 48;
let actionCount = 0;
let requestCount = 0;
let toolCallCount = 0;
let modelFetch = agentFetch;

export function resetAgentLimitsForTest() {
  actionCount = 0;
  requestCount = 0;
  toolCallCount = 0;
}

export function setModelFetchForTest(fetchImplementation) {
  modelFetch = fetchImplementation || agentFetch;
}

export function recordToolCall(name = 'tool') {
  toolCallCount += 1;
  if (toolCallCount > MAX_TOOL_CALLS) throw new Error(`Agent 超过 ${MAX_TOOL_CALLS} 次工具调用: ${name}`);
}

function parseContent(payload, protocol) {
  if (protocol === 'CLAUDE') {
    const blocks = Array.isArray(payload?.content) ? payload.content : [];
    return blocks.filter(item => item?.type === 'text').map(item => item.text || '').join('\n');
  }
  return payload?.choices?.[0]?.message?.content || '';
}

export function extractJson(text) {
  const raw = String(text || '').trim();
  const fenced = raw.match(JSON_BLOCK)?.[1]?.trim();
  const candidate = fenced || raw.slice(raw.indexOf('{'), raw.lastIndexOf('}') + 1);
  if (!candidate || !candidate.startsWith('{')) throw new Error('模型没有返回 JSON 对象');
  return JSON.parse(candidate);
}

export async function completeJson({ system, user, maxTokens = 12000, repairContext = '' }) {
  return completeStructured({ system, user, maxTokens, repairContext, imageFiles: [] });
}

export async function completeVisionJson({ system, user, imageFiles, maxTokens = 4000, repairContext = '' }) {
  return completeStructured({ system, user, maxTokens, repairContext, imageFiles: imageFiles || [] });
}

export function detectImageMediaType(buffer, file = '') {
  if (buffer.length >= 8 && buffer.subarray(0, 8).equals(Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]))) {
    return 'image/png';
  }
  if (buffer.length >= 3 && buffer[0] === 0xff && buffer[1] === 0xd8 && buffer[2] === 0xff) return 'image/jpeg';
  const signature = buffer.subarray(0, 6).toString('ascii');
  if (signature === 'GIF87a' || signature === 'GIF89a') return 'image/gif';
  throw new Error(`视觉输入不是受支持的 PNG/JPEG/GIF: ${file}`);
}

async function completeStructured({ system, user, maxTokens, repairContext, imageFiles }) {
  actionCount += 1;
  if (actionCount > MAX_AGENT_ACTIONS) throw new Error(`Agent 超过 ${MAX_AGENT_ACTIONS} 个回合`);
  const endpoint = process.env.PPT_AGENT_LLM_ENDPOINT || '';
  const key = process.env.PPT_AGENT_LLM_KEY || '';
  const model = imageFiles.length
    ? (process.env.PPT_AGENT_VISION_MODEL || process.env.PPT_AGENT_LLM_MODEL || '')
    : (process.env.PPT_AGENT_LLM_MODEL || '');
  const protocol = (process.env.PPT_AGENT_LLM_PROTOCOL || 'OPENAI').toUpperCase();
  if (!endpoint || !key || !model) throw new Error('Agent LLM 配置不完整');

  let lastError;
  let prompt = user;
  for (let attempt = 0; attempt < 3; attempt += 1) {
    requestCount += 1;
    if (requestCount > MAX_MODEL_REQUESTS) throw new Error(`Agent 超过 ${MAX_MODEL_REQUESTS} 次模型/工具调用`);
    const imageBlocks = await Promise.all(imageFiles.map(async file => {
      const buffer = await fs.readFile(file);
      return {
        data: buffer.toString('base64'),
        mediaType: detectImageMediaType(buffer, file)
      };
    }));
    const userContent = imageBlocks.length
      ? (protocol === 'CLAUDE'
          ? [
              { type: 'text', text: prompt },
              ...imageBlocks.map(item => ({ type: 'image', source: { type: 'base64', media_type: item.mediaType, data: item.data } }))
            ]
          : [
              { type: 'text', text: prompt },
              ...imageBlocks.map(item => ({ type: 'image_url', image_url: { url: `data:${item.mediaType};base64,${item.data}`, detail: 'high' } }))
            ])
      : prompt;
    const body = protocol === 'CLAUDE'
      ? { model, max_tokens: maxTokens, system, messages: [{ role: 'user', content: userContent }] }
      : { model, max_tokens: maxTokens, messages: [{ role: 'system', content: system }, { role: 'user', content: userContent }] };
    const headers = {
      'content-type': 'application/json',
      ...(protocol === 'CLAUDE'
        ? { 'x-api-key': key, 'anthropic-version': '2023-06-01' }
        : { authorization: `Bearer ${key}` })
    };
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), 180_000);
    try {
      const response = await modelFetch(endpoint, { method: 'POST', headers, body: JSON.stringify(body), signal: controller.signal });
      const payload = await response.json().catch(() => ({}));
      if (!response.ok) throw new Error(`LLM HTTP ${response.status}: ${JSON.stringify(payload).slice(0, 800)}`);
      const content = parseContent(payload, protocol);
      return extractJson(content);
    } catch (error) {
      lastError = error;
      prompt = `${user}\n\nYour previous response was invalid: ${error.message}. ${repairContext} Return one complete JSON object only.`;
    } finally {
      clearTimeout(timer);
    }
  }
  throw new Error(`模型 JSON 连续修复失败: ${lastError?.message || 'unknown error'}`);
}

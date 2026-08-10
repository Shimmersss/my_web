import fs from 'node:fs/promises';
import { agentFetch } from './net.mjs';

const JSON_BLOCK = /```(?:json)?\s*([\s\S]*?)```/i;
const MAX_AGENT_ACTIONS = 24;
const MAX_MODEL_REQUESTS = 48;
const MAX_TOOL_CALLS = 48;
const MAX_NETWORK_TOOL_CALLS = 96;
const NETWORK_TOOL_NAMES = new Set(['http-search', 'image-fetch', 'mimo-page']);
let actionCount = 0;
let requestCount = 0;
let toolCallCount = 0;
let networkToolCallCount = 0;
let modelFetch = agentFetch;

export function resetAgentLimitsForTest() {
  actionCount = 0;
  requestCount = 0;
  toolCallCount = 0;
  networkToolCallCount = 0;
}

export function setModelFetchForTest(fetchImplementation) {
  modelFetch = fetchImplementation || agentFetch;
}

export function recordToolCall(name = 'tool') {
  if (NETWORK_TOOL_NAMES.has(name)) {
    networkToolCallCount += 1;
    if (networkToolCallCount > MAX_NETWORK_TOOL_CALLS) {
      throw new Error(`Agent 超过 ${MAX_NETWORK_TOOL_CALLS} 次联网请求: ${name}`);
    }
    return;
  }
  toolCallCount += 1;
  if (toolCallCount > MAX_TOOL_CALLS) throw new Error(`Agent 超过 ${MAX_TOOL_CALLS} 次工具调用: ${name}`);
}

function contentText(value) {
  if (typeof value === 'string') return value;
  if (Array.isArray(value)) {
    return value.map(item => contentText(item?.text ?? item?.content ?? item)).filter(Boolean).join('\n');
  }
  if (value && typeof value === 'object') return contentText(value.text ?? value.content ?? '');
  return '';
}

function parseContent(payload, protocol) {
  if (protocol === 'CLAUDE') {
    const blocks = Array.isArray(payload?.content) ? payload.content : [];
    return blocks
      .filter(item => !item?.type || ['text', 'output_text'].includes(item.type))
      .map(item => contentText(item))
      .filter(Boolean)
      .join('\n');
  }
  const message = payload?.choices?.[0]?.message || {};
  return contentText(message.content ?? message.output_text ?? payload?.output_text ?? '');
}

export function extractJson(text) {
  const raw = String(text || '').trim();
  const fenced = raw.match(JSON_BLOCK)?.[1]?.trim();
  const candidate = extractJsonCandidate(fenced || raw);
  if (!candidate || !candidate.startsWith('{')) throw new Error('模型没有返回 JSON 对象');
  try {
    return JSON.parse(candidate);
  } catch (error) {
    try {
      return JSON.parse(repairJsonCandidate(candidate));
    } catch {
      try {
        return JSON.parse(repairStructuralJsonCandidate(candidate));
      } catch {
        throw error;
      }
    }
  }
}

function extractJsonCandidate(value) {
  const start = value.indexOf('{');
  if (start < 0) return '';
  let depth = 0;
  let inString = false;
  let escaped = false;
  for (let index = start; index < value.length; index += 1) {
    const char = value[index];
    if (inString) {
      if (escaped) escaped = false;
      else if (char === '\\') escaped = true;
      else if (char === '"') inString = false;
      continue;
    }
    if (char === '"') inString = true;
    else if (char === '{') depth += 1;
    else if (char === '}' && --depth === 0) return value.slice(start, index + 1);
  }
  // Keep a truncated root object intact so deterministic repair can append the
  // missing delimiters instead of cutting it at the last nested object.
  return value.slice(start).replace(/```\s*$/, '').trim();
}

function repairJsonCandidate(candidate) {
  let output = '';
  let inString = false;
  let escaped = false;
  for (let index = 0; index < candidate.length; index += 1) {
    const char = candidate[index];
    if (!inString) {
      output += char;
      if (char === '"') inString = true;
      continue;
    }
    if (escaped) {
      output += char;
      escaped = false;
      continue;
    }
    if (char === '\\') {
      output += char;
      escaped = true;
      continue;
    }
    if (char === '"') {
      const next = candidate.slice(index + 1).match(/\S/)?.[0] || '';
      // Model responses occasionally contain an unescaped ASCII quote in a
      // Chinese text value. Treat it as content unless it closes a JSON value.
      if (next && ![':', ',', '}', ']', '"'].includes(next)) {
        output += '\\"';
      } else {
        output += char;
        inString = false;
      }
      continue;
    }
    if (char === '\n') output += '\\n';
    else if (char === '\r') output += '\\r';
    else if (char === '\t') output += '\\t';
    else if (char.charCodeAt(0) < 0x20) output += `\\u${char.charCodeAt(0).toString(16).padStart(4, '0')}`;
    else output += char;
  }
  return output.replace(/,\s*([}\]])/g, '$1');
}

function repairStructuralJsonCandidate(candidate) {
  let output = repairJsonCandidate(candidate);
  // The long presentation plans returned by MiMo occasionally omit a comma
  // between adjacent array objects or object properties. These boundaries are
  // unambiguous after string/control-character normalization.
  output = output
    .replace(/([}\]])(\s*)(?=[{[])/g, '$1,$2')
    .replace(/([}\]])(\s*)(?="(?:[^"\\]|\\.)*"\s*:)/g, '$1,$2')
    .replace(/("|\b(?:true|false|null)|-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?)(\s+)(?=[{[])/g, '$1,$2');

  const stack = [];
  let inString = false;
  let escaped = false;
  for (const char of output) {
    if (inString) {
      if (escaped) escaped = false;
      else if (char === '\\') escaped = true;
      else if (char === '"') inString = false;
      continue;
    }
    if (char === '"') inString = true;
    else if (char === '{' || char === '[') stack.push(char);
    else if (char === '}' && stack.at(-1) === '{') stack.pop();
    else if (char === ']' && stack.at(-1) === '[') stack.pop();
  }
  if (inString) output += '"';
  while (stack.length) output += stack.pop() === '{' ? '}' : ']';
  return output.replace(/,\s*([}\]])/g, '$1');
}

export async function completeJson({ system, user, maxTokens = 12000, repairContext = '', requestTimeoutMs = 180_000, maxAttempts = 3 }) {
  return completeStructured({ system, user, maxTokens, repairContext, imageFiles: [], requestTimeoutMs, maxAttempts });
}

export async function completeVisionJson({ system, user, imageFiles, maxTokens = 4000, repairContext = '', requestTimeoutMs = 180_000, maxAttempts = 3 }) {
  return completeStructured({ system, user, maxTokens, repairContext, imageFiles: imageFiles || [], requestTimeoutMs, maxAttempts });
}

/** Call MiMo's native OpenAI-compatible web-search tool. This requires the
 * pay-as-you-go API plugin and its matching sk- key, not a Token Plan tp- key. */
export async function completeMimoWebSearch({ system, user, maxTokens = 1800, maxKeyword = 3, limit = 6 }) {
  const endpoint = process.env.PPT_AGENT_MIMO_SEARCH_ENDPOINT || '';
  const key = process.env.PPT_AGENT_MIMO_SEARCH_KEY || '';
  const model = process.env.PPT_AGENT_MIMO_SEARCH_MODEL || 'mimo-v2.5';
  if (!endpoint || !key) throw new Error('Mimo 原生联网搜索未配置 endpoint/key');
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 90_000);
  try {
    const response = await modelFetch(endpoint, {
      method: 'POST',
      headers: { 'content-type': 'application/json', 'api-key': key },
      body: JSON.stringify({
        model,
        messages: [
          { role: 'system', content: system },
          { role: 'user', content: user }
        ],
        max_completion_tokens: maxTokens,
        thinking: { type: 'disabled' },
        tools: [{ type: 'web_search', max_keyword: maxKeyword, force_search: true, limit }],
        tool_choice: 'auto'
      }),
      signal: controller.signal
    });
    const payload = await response.json().catch(() => ({}));
    if (!response.ok) throw new Error(`Mimo 搜索 HTTP ${response.status}: ${JSON.stringify(payload).slice(0, 800)}`);
    const message = payload?.choices?.[0]?.message || {};
    return {
      content: String(message.content || ''),
      annotations: Array.isArray(message.annotations) ? message.annotations : [],
      usage: payload.usage || null
    };
  } finally {
    clearTimeout(timer);
  }
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

async function completeStructured({ system, user, maxTokens, repairContext, imageFiles, requestTimeoutMs, maxAttempts }) {
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
  let tokenBudget = maxTokens;
  const timeoutMs = Math.max(1_000, Number(requestTimeoutMs) || 180_000);
  const attempts = Math.max(1, Math.min(3, Number(maxAttempts) || 3));
  for (let attempt = 0; attempt < attempts; attempt += 1) {
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
    const mimoRequest = /^mimo-/i.test(model) || /xiaomimimo|token-plan[^/]*\.xiaomimimo/i.test(endpoint);
    const body = protocol === 'CLAUDE'
      ? {
          model,
          max_tokens: tokenBudget,
          system,
          messages: [{ role: 'user', content: userContent }],
          ...(mimoRequest ? { thinking: { type: 'disabled' } } : {})
        }
      : {
          model,
          max_tokens: tokenBudget,
          messages: [{ role: 'system', content: system }, { role: 'user', content: userContent }],
          ...(mimoRequest ? { thinking: { type: 'disabled' } } : {})
        };
    const headers = {
      'content-type': 'application/json',
      ...(protocol === 'CLAUDE'
        ? { 'x-api-key': key, 'anthropic-version': '2023-06-01' }
        : { authorization: `Bearer ${key}` })
    };
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), timeoutMs);
    try {
      const response = await modelFetch(endpoint, { method: 'POST', headers, body: JSON.stringify(body), signal: controller.signal });
      const payload = await response.json().catch(() => ({}));
      if (!response.ok) throw new Error(`LLM HTTP ${response.status}: ${JSON.stringify(payload).slice(0, 800)}`);
      const content = parseContent(payload, protocol);
      if (!String(content || '').trim()) {
        const finishReason = payload?.choices?.[0]?.finish_reason || payload?.stop_reason || 'unknown';
        if (String(finishReason).toLowerCase() === 'max_tokens') {
          tokenBudget = Math.min(16_000, Math.max(tokenBudget + 2_000, Math.ceil(tokenBudget * 1.5)));
        }
        throw new Error(`模型没有返回正文 JSON（finish_reason=${finishReason}）`);
      }
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

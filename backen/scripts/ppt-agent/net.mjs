import { ProxyAgent, fetch as undiciFetch } from 'undici';

let cachedProxyUrl = null;
let cachedDispatcher = null;

function proxyDispatcher() {
  const proxyUrl = String(process.env.PPT_AGENT_PROXY_URL || '').trim();
  if (!proxyUrl) return undefined;
  const parsed = new URL(proxyUrl);
  if (!['http:', 'https:'].includes(parsed.protocol)) {
    throw new Error('PPT Agent 代理仅支持 HTTP/HTTPS');
  }
  const proxyHost = parsed.hostname.toLowerCase().replace(/^\[|\]$/g, '');
  if (!['localhost', '127.0.0.1', '::1'].includes(proxyHost)) {
    throw new Error('PPT Agent 仅信任本机回环代理');
  }
  if (cachedProxyUrl !== parsed.href) {
    cachedDispatcher?.close?.().catch?.(() => {});
    cachedProxyUrl = parsed.href;
    cachedDispatcher = new ProxyAgent(parsed.href);
  }
  return cachedDispatcher;
}

export function hasConfiguredProxy() {
  return Boolean(String(process.env.PPT_AGENT_PROXY_URL || '').trim());
}

export function agentFetch(url, options = {}) {
  const dispatcher = options.dispatcher || proxyDispatcher();
  return undiciFetch(url, dispatcher ? { ...options, dispatcher } : options);
}

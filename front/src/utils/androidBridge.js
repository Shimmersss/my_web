const ANDROID_USER_AGENT_TOKEN = 'ShimmerAndroid/'
const PRIMARY_ORIGIN = 'https://shimmer.help'

function runtime(scope = globalThis) {
  return scope?.window || scope
}

function sendNative(host, payload) {
  if (!isShimmerAndroid(host?.navigator?.userAgent)) return false
  if (typeof host?.ShimmerNative?.postMessage === 'function') {
    host.ShimmerNative.postMessage(JSON.stringify(payload))
    return true
  }
  if (typeof host?.postMessage !== 'function' || host?.location?.origin !== PRIMARY_ORIGIN) return false
  host.postMessage({ __shimmerGeckoNative: true, payload }, PRIMARY_ORIGIN)
  return true
}

export function isShimmerAndroid(userAgent = runtime()?.navigator?.userAgent || '') {
  return String(userAgent).includes(ANDROID_USER_AGENT_TOKEN)
}

export function isAndroidMobileWeb(userAgent = runtime()?.navigator?.userAgent || '') {
  const value = String(userAgent)
  return /Android/i.test(value) && /Mobile/i.test(value) && !isShimmerAndroid(value)
}

export function requestAndroidDownload({ url, filename, mimeType = 'application/octet-stream' }, scope = globalThis) {
  const host = runtime(scope)
  if (!isShimmerAndroid(host?.navigator?.userAgent)) return false

  let resolved
  try {
    resolved = new URL(url, host.location?.href || `${PRIMARY_ORIGIN}/`)
  } catch {
    return false
  }
  if (resolved.origin !== PRIMARY_ORIGIN) return false

  return sendNative(host, {
    protocolVersion: 1,
    type: 'download',
    url: resolved.href,
    filename: String(filename || 'shimmer-download'),
    mimeType: String(mimeType || 'application/octet-stream')
  })
}

export function requestAndroidTextSave({ text, filename, mimeType = 'text/plain;charset=utf-8' }, scope = globalThis) {
  const host = runtime(scope)
  return sendNative(host, {
    protocolVersion: 1,
    type: 'saveText',
    text: String(text ?? ''),
    filename: String(filename || 'shimmer-export.txt'),
    mimeType: String(mimeType || 'text/plain;charset=utf-8')
  })
}

export function downloadUrl(url, filename, mimeType) {
  if (requestAndroidDownload({ url, filename, mimeType })) return true
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = filename || ''
  anchor.rel = 'noopener'
  document.body.appendChild(anchor)
  anchor.click()
  anchor.remove()
  return false
}

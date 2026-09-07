/**
 * HTTP 请求工具
 */

export const BASE_URL = import.meta.env.VITE_API_BASE_URL || '/api'

export function apiUrl(path = '') {
  const suffix = String(path || '')
  return `${BASE_URL}${suffix.startsWith('/') ? suffix : `/${suffix}`}`
}

/**
 * 通用请求方法
 * @param {string} url - 请求地址
 * @param {object} options - 请求配置
 * @returns {Promise}
 */
async function request(url, options = {}) {
  const headers = {
    'Content-Type': 'application/json',
    ...options.headers
  }
  const csrfToken = localStorage.getItem('csrfToken')
  const method = (options.method || 'GET').toUpperCase()
  if (csrfToken && !['GET', 'HEAD', 'OPTIONS'].includes(method)) {
    headers['X-CSRF-Token'] = csrfToken
  }
  const controller = new AbortController()
  const abort = () => controller.abort()
  options.signal?.addEventListener('abort', abort, { once: true })
  if (options.signal?.aborted) abort()
  const timer = options.timeoutMs > 0 ? setTimeout(abort, options.timeoutMs) : null
  const config = {
    ...options,
    signal: controller.signal,
    credentials: /^https?:\/\//i.test(BASE_URL) ? 'include' : 'same-origin',
    headers
  }

  try {
    const response = await fetch(apiUrl(url), config)

    if (!response.ok) {
      let message = `HTTP error! status: ${response.status}`
      try {
        const data = await response.json()
        message = data.message || message
      } catch {}
      const error = new Error(message)
      error.status = response.status
      throw error
    }

    const data = await response.json()
    return data
  } catch (error) {
    if (error.name !== 'AbortError') console.error('Request failed:', error)
    throw error
  } finally {
    if (timer) clearTimeout(timer)
    options.signal?.removeEventListener('abort', abort)
  }
}

/**
 * GET 请求
 */
export function get(url, params = {}) {
  const query = new URLSearchParams(params).toString()
  const queryString = query ? `?${query}` : ''
  return request(url + queryString, { method: 'GET' })
}

/**
 * POST 请求
 */
export function post(url, data = {}) {
  return request(url, {
    method: 'POST',
    body: JSON.stringify(data)
  })
}

/**
 * PUT 请求
 */
export function put(url, data = {}) {
  return request(url, {
    method: 'PUT',
    body: JSON.stringify(data)
  })
}

/**
 * DELETE 请求
 */
export function del(url) {
  return request(url, { method: 'DELETE' })
}

/**
 * 带自定义配置的原始请求
 */
export function requestWithOptions(url, options = {}) {
  return request(url, options)
}

export default request

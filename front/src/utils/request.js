/**
 * HTTP 请求工具
 */

const BASE_URL = import.meta.env.VITE_API_BASE_URL || '/api'

/**
 * 通用请求方法
 * @param {string} url - 请求地址
 * @param {object} options - 请求配置
 * @returns {Promise}
 */
async function request(url, options = {}) {
  const config = {
    ...options,
    headers: {
      'Content-Type': 'application/json',
      ...options.headers
    }
  }

  // 添加 token（如果有）
  const token = localStorage.getItem('token')
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }

  try {
    const response = await fetch(`${BASE_URL}${url}`, config)

    if (!response.ok) {
      throw new Error(`HTTP error! status: ${response.status}`)
    }

    const data = await response.json()
    return data
  } catch (error) {
    console.error('Request failed:', error)
    throw error
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

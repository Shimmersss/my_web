/**
 * API 接口示例
 * 实际项目中，请替换为真实的API调用
 */

import { apiUrl, get, post, put, del, requestWithOptions } from '@/utils/request'

// 模拟延迟
const delay = (ms = 500) => new Promise(resolve => setTimeout(resolve, ms))

// ==================== 业务模块 API ====================

/**
 * 获取业务列表
 * @returns {Promise}
 */
export async function getBusinessList() {
  await delay()
  // 实际项目中调用真实API
  // return get('/business/list')
  return {
    code: 200,
    data: [],
    message: 'success'
  }
}

/**
 * 获取业务详情
 * @param {number} id - 业务ID
 * @returns {Promise}
 */
export async function getBusinessDetail(id) {
  await delay()
  // return get(`/business/${id}`)
  return {
    code: 200,
    data: {},
    message: 'success'
  }
}

// ==================== 案例模块 API ====================

/**
 * 获取案例列表
 * @param {object} params - 查询参数
 * @returns {Promise}
 */
export async function getCasesList(params) {
  await delay()
  // return get('/cases/list', params)
  return {
    code: 200,
    data: {
      list: [],
      total: 0
    },
    message: 'success'
  }
}

/**
 * 获取案例详情
 * @param {number} id - 案例ID
 * @returns {Promise}
 */
export async function getCaseDetail(id) {
  await delay()
  // return get(`/cases/${id}`)
  return {
    code: 200,
    data: {},
    message: 'success'
  }
}

/**
 * 下载案例
 * @param {number} id - 案例ID
 * @returns {Promise}
 */
export async function downloadCase(id) {
  await delay()
  // return get(`/cases/${id}/download`)
  return {
    code: 200,
    data: null,
    message: 'success'
  }
}

// ==================== 新闻模块 API ====================

/**
 * 获取新闻列表
 * @param {object} params - 查询参数
 * @returns {Promise}
 */
export async function getNewsList(params) {
  await delay()
  // return get('/news/list', params)
  return {
    code: 200,
    data: {
      list: [],
      total: 0
    },
    message: 'success'
  }
}

/**
 * 获取新闻详情
 * @param {number} id - 新闻ID
 * @returns {Promise}
 */
export async function getNewsDetail(id) {
  await delay()
  // return get(`/news/${id}`)
  return {
    code: 200,
    data: {},
    message: 'success'
  }
}

// ==================== 联系模块 API ====================

/**
 * 提交留言
 * @param {object} data - 留言数据
 * @returns {Promise}
 */
export async function submitMessage(data) {
  await delay()
  // return post('/contact/message', data)
  return {
    code: 200,
    data: null,
    message: '提交成功'
  }
}

/**
 * 提交预约
 * @param {object} data - 预约数据
 * @returns {Promise}
 */
export async function submitAppointment(data) {
  await delay()
  // return post('/contact/appointment', data)
  return {
    code: 200,
    data: null,
    message: '预约成功'
  }
}

// ==================== Zotero 文献 API ====================

/**
 * 获取 Zotero 文献列表（已精简字段）
 * @param {number} limit - 拉取条数，默认 50
 */
export function getZoteroItems(limit = 50) {
  return get('/zotero/items', { limit })
}

/**
 * 获取 Zotero 文献集合（folder）
 */
export function getZoteroCollections(refresh = false) {
  return get('/zotero/collections', refresh ? { refresh: true } : {})
}

// ==================== GitHub 开源项目 API ====================

export function getGithubProjects() {
  return get('/github-projects')
}

export function getGithubRankings(refresh = false) {
  return get('/github-projects/rankings', refresh ? { refresh: true } : {})
}

export function loginGithubProjectsAdmin(key) {
  return post('/github-projects/login', { key })
}

export function saveGithubProjects(projects, adminKey) {
  return requestWithOptions('/github-projects', {
    method: 'PUT',
    headers: {
      'X-Admin-Key': adminKey
    },
    body: JSON.stringify(projects)
  })
}

// ==================== 账号与额度 API ====================

export function getCurrentUser() {
  return get('/auth/me')
}

export function loginAccount(username, password) {
  return post('/auth/login', { username, password })
}

export function registerAccount(username, password, inviteCode) {
  return post('/auth/register', { username, password, inviteCode })
}

export function logoutAccount() {
  return post('/auth/logout', {})
}

export function getQuotaSettings() {
  return get('/auth/quota-settings')
}

export function getSiteSettings() {
  return get('/auth/site-settings')
}

export function getAdminAccounts() {
  return get('/admin/accounts')
}

export function createInviteCode({ code, credits, maxUses, expiresAt = '' }) {
  return post('/admin/accounts/invites', { code, credits, maxUses, expiresAt })
}

export function adjustUserCredits({ userId, amount, note }) {
  return post('/admin/accounts/credits', { userId, amount, note })
}

export function updateQuotaSettings({ translationCreditPerPage, pptCreditPerTask }) {
  return put('/admin/accounts/settings', { translationCreditPerPage, pptCreditPerTask })
}

export function updateAdminApiSettings(settings) {
  return put('/admin/accounts/api-settings', settings)
}

export function testAdminApiSettings(provider, config) {
  return post('/admin/accounts/api-settings/test', { provider, config })
}

export function updateInviteStatus(id, enabled, expiresAt = '') {
  return requestWithOptions(`/admin/accounts/invites/${id}`, {
    method: 'PATCH',
    body: JSON.stringify({ enabled, expiresAt })
  })
}

export function updateAdminUserStatus(id, enabled) {
  return requestWithOptions(`/admin/accounts/users/${id}`, {
    method: 'PATCH',
    body: JSON.stringify({ enabled })
  })
}

export async function getGithubProjectReadme(fullName) {
  const res = await fetch(`/api/github-projects/${fullName}/readme`)
  if (!res.ok) throw new Error(await res.text())
  return res.text()
}

// ==================== PDF 翻译 API ====================

/**
 * 上传 PDF 文件（获取页数信息，不立即翻译）
 * @param {File} file - PDF 文件
 * @returns {Promise}
 */
export async function uploadPdf(file) {
  const formData = new FormData()
  formData.append('file', file)
  const csrfToken = localStorage.getItem('csrfToken')
  const res = await fetch('/api/translate/upload', {
    method: 'POST',
    credentials: 'same-origin',
    headers: csrfToken ? { 'X-CSRF-Token': csrfToken } : {},
    body: formData
  })
  return res.json()
}

/**
 * 开始翻译（指定页面范围）
 * @param {string} taskId
 * @param {number} startPage - 起始页码（从 1 开始）
 * @param {number} endPage - 结束页码
 * @returns {Promise}
 */
export async function startTranslation(taskId, startPage, endPage, fontFamily = 'auto', qps = 4) {
  const csrfToken = localStorage.getItem('csrfToken')
  const res = await fetch(`/api/translate/start/${taskId}?startPage=${startPage}&endPage=${endPage}&fontFamily=${encodeURIComponent(fontFamily)}&qps=${qps}`, {
    method: 'POST',
    credentials: 'same-origin',
    headers: csrfToken ? { 'X-CSRF-Token': csrfToken } : {}
  })
  return res.json()
}

/**
 * 获取翻译任务状态（断线重连用）
 * @param {string} taskId
 * @returns {Promise}
 */
export function getTranslationStatus(taskId) {
  return get(`/translate/status/${taskId}`)
}

/**
 * 获取最近翻译任务
 * @returns {Promise}
 */
export function getRecentTranslations() {
  return requestWithOptions('/translate/recent', {
    method: 'GET',
    cache: 'no-store'
  })
}

/**
 * 下载翻译结果
 * @param {string} taskId
 */
export function downloadTranslation(taskId) {
  window.open(`/api/translate/download/${taskId}`)
}

/**
 * 获取翻译后的 PDF Blob（用于页面内预览）
 * @param {string} taskId
 * @returns {Promise<Blob>}
 */
export async function getTranslatedPdfBlob(taskId, mode = 'translated') {
  const res = await fetch(`/api/translate/download-pdf/${taskId}?mode=${encodeURIComponent(mode)}`, {
    credentials: 'same-origin'
  })
  if (!res.ok) {
    let message = '生成翻译 PDF 失败'
    try {
      const data = await res.json()
      message = data.message || message
    } catch {}
    throw new Error(message)
  }
  return res.blob()
}

/**
 * 下载翻译后的 PDF（译文填回原位置）
 * @param {string} taskId
 */
export function downloadTranslatedPdf(taskId, mode = 'translated') {
  window.open(`/api/translate/download-pdf/${taskId}?mode=${encodeURIComponent(mode)}`)
}

// ==================== PPT 生成 API ====================

export async function createPptGenerationTask({ prompt, templateKey, outputFormat = 'pptx', researchMode = 'auto', templateFile, sourceFile, paperFile, idempotencyKey }) {
  const formData = new FormData()
  if (prompt?.trim()) formData.append('prompt', prompt.trim())
  if (templateKey) formData.append('templateKey', templateKey)
  if (outputFormat) formData.append('outputFormat', outputFormat)
  formData.append('researchMode', researchMode === 'off' ? 'off' : 'auto')
  if (templateFile) formData.append('templateFile', templateFile)
  if (sourceFile || paperFile) formData.append('sourceFile', sourceFile || paperFile)

  const csrfToken = localStorage.getItem('csrfToken')
  const res = await fetch(apiUrl('/ppt-generate/tasks'), {
    method: 'POST',
    credentials: /^https?:\/\//i.test(apiUrl('')) ? 'include' : 'same-origin',
    headers: {
      ...(csrfToken ? { 'X-CSRF-Token': csrfToken } : {}),
      ...(idempotencyKey ? { 'X-Ppt-Idempotency-Key': idempotencyKey } : {})
    },
    body: formData
  })
  const data = await res.json().catch(() => ({}))
  if (!res.ok) {
    throw new Error(data.message || `HTTP error! status: ${res.status}`)
  }
  return data
}

export function getPptTemplates() {
  return get('/ppt-generate/templates')
}

export function getPptPreview(taskId, accessToken, options = {}) {
  return requestWithOptions(`/ppt-generate/preview/${encodeURIComponent(taskId)}`, {
    ...options,
    method: 'GET',
    headers: pptTaskHeaders(accessToken),
    cache: 'no-store'
  })
}

export async function getPptHtmlPreview(taskId, accessToken, options = {}) {
  const res = await fetch(apiUrl(`/ppt-generate/preview-html/${encodeURIComponent(taskId)}`), {
    ...options,
    method: 'GET',
    credentials: /^https?:\/\//i.test(apiUrl('')) ? 'include' : 'same-origin',
    headers: pptTaskHeaders(accessToken),
    cache: 'no-store'
  })
  if (!res.ok) {
    const data = await res.json().catch(() => ({}))
    throw new Error(data.message || `HTML 预览加载失败（HTTP ${res.status}）`)
  }
  return URL.createObjectURL(await res.blob())
}

export async function getPptPreviewImage(taskId, fileName, accessToken, options = {}) {
  const res = await fetch(apiUrl(`/ppt-generate/preview/${encodeURIComponent(taskId)}/images/${encodeURIComponent(fileName)}`), {
    ...options,
    method: 'GET',
    credentials: /^https?:\/\//i.test(apiUrl('')) ? 'include' : 'same-origin',
    headers: pptTaskHeaders(accessToken),
    cache: 'no-store'
  })
  if (!res.ok) {
    const data = await res.json().catch(() => ({}))
    throw new Error(data.message || `预览素材加载失败（HTTP ${res.status}）`)
  }
  return URL.createObjectURL(await res.blob())
}

function pptTaskHeaders(accessToken) {
  return accessToken ? { 'X-Ppt-Task-Token': accessToken } : {}
}

export function getPptGenerationStatus(taskId, accessToken) {
  return requestWithOptions(`/ppt-generate/status/${taskId}`, {
    method: 'GET',
    headers: pptTaskHeaders(accessToken)
  })
}

export async function revisePptGenerationTask(taskId, accessToken, { prompt = '', idempotencyKey } = {}) {
  const csrfToken = localStorage.getItem('csrfToken')
  const res = await fetch(apiUrl(`/ppt-generate/tasks/${encodeURIComponent(taskId)}/revise`), {
    method: 'POST',
    credentials: /^https?:\/\//i.test(apiUrl('')) ? 'include' : 'same-origin',
    headers: {
      'Content-Type': 'application/json',
      ...(accessToken ? { 'X-Ppt-Task-Token': accessToken } : {}),
      ...(csrfToken ? { 'X-CSRF-Token': csrfToken } : {}),
      ...(idempotencyKey ? { 'X-Ppt-Idempotency-Key': idempotencyKey } : {})
    },
    body: JSON.stringify({ prompt: prompt.trim() })
  })
  const data = await res.json().catch(() => ({}))
  if (!res.ok) throw new Error(data.message || `二次修改提交失败（HTTP ${res.status}）`)
  return data
}

export function getRecentPptGenerations(accessTokens = []) {
  return requestWithOptions('/ppt-generate/recent', {
    method: 'GET',
    headers: accessTokens.length ? { 'X-Ppt-Task-Tokens': accessTokens.join(',') } : {},
    cache: 'no-store'
  })
}

export async function downloadGeneratedPpt(taskId, accessToken, outputFormat = 'pptx') {
  const csrfToken = localStorage.getItem('csrfToken')
  const headers = {
    ...(accessToken ? { 'X-Ppt-Task-Token': accessToken } : {}),
    ...(csrfToken ? { 'X-CSRF-Token': csrfToken } : {})
  }
  const res = await fetch(apiUrl(`/ppt-generate/download/${encodeURIComponent(taskId)}`), {
    method: 'GET',
    credentials: /^https?:\/\//i.test(apiUrl('')) ? 'include' : 'same-origin',
    headers
  })
  if (!res.ok) {
    const data = await res.json().catch(() => ({}))
    throw new Error(data.message || `下载失败（HTTP ${res.status}）`)
  }
  const blob = await res.blob()
  const disposition = res.headers.get('Content-Disposition') || ''
  const encoded = disposition.match(/filename\*=UTF-8''([^;]+)/i)?.[1]
  const filename = encoded ? decodeURIComponent(encoded) : `AI生成PPT-${taskId}.${String(outputFormat).toLowerCase() === 'html' ? 'html' : 'pptx'}`
  const url = URL.createObjectURL(blob)
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = filename
  document.body.appendChild(anchor)
  anchor.click()
  anchor.remove()
  window.setTimeout(() => URL.revokeObjectURL(url), 1000)
}

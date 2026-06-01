/**
 * API 接口示例
 * 实际项目中，请替换为真实的API调用
 */

import { get, post, put, del, requestWithOptions } from '@/utils/request'

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

// ==================== 招商加盟 API ====================

/**
 * 提交加盟申请
 * @param {object} data - 申请数据
 * @returns {Promise}
 */
export async function submitFranchise(data) {
  await delay()
  // return post('/franchise/apply', data)
  return {
    code: 200,
    data: null,
    message: '申请成功'
  }
}

/**
 * 获取加盟案例
 * @returns {Promise}
 */
export async function getFranchiseCases() {
  await delay()
  // return get('/franchise/cases')
  return {
    code: 200,
    data: [],
    message: 'success'
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
export function getZoteroCollections() {
  return get('/zotero/collections')
}

// ==================== GitHub 开源项目 API ====================

export function getGithubProjects() {
  return get('/github-projects')
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

export async function getGithubProjectReadme(fullName) {
  const res = await fetch(`/api/github-projects/${fullName}/readme`)
  if (!res.ok) throw new Error(await res.text())
  return res.text()
}

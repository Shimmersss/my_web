/**
 * 格式化工具函数
 */

/**
 * 格式化日期
 * @param {string|Date} date - 日期
 * @param {string} format - 格式 'YYYY-MM-DD' | 'YYYY-MM-DD HH:mm:ss'
 * @returns {string}
 */
export function formatDate(date, format = 'YYYY-MM-DD') {
  const d = new Date(date)
  const year = d.getFullYear()
  const month = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  const hours = String(d.getHours()).padStart(2, '0')
  const minutes = String(d.getMinutes()).padStart(2, '0')
  const seconds = String(d.getSeconds()).padStart(2, '0')

  switch (format) {
    case 'YYYY-MM-DD HH:mm:ss':
      return `${year}-${month}-${day} ${hours}:${minutes}:${seconds}`
    case 'YYYY-MM-DD':
    default:
      return `${year}-${month}-${day}`
  }
}

/**
 * 格式化数字
 * @param {number} num - 数字
 * @returns {string}
 */
export function formatNumber(num) {
  if (num >= 10000) {
    return (num / 10000).toFixed(1) + '万'
  } else if (num >= 1000) {
    return (num / 1000).toFixed(1) + 'k'
  }
  return num.toString()
}

/**
 * 格式化文件大小
 * @param {number} bytes - 字节数
 * @returns {string}
 */
export function formatFileSize(bytes) {
  if (bytes === 0) return '0 B'
  const k = 1024
  const sizes = ['B', 'KB', 'MB', 'GB', 'TB']
  const i = Math.floor(Math.log(bytes) / Math.log(k))
  return Math.round(bytes / Math.pow(k, i) * 100) / 100 + ' ' + sizes[i]
}

/**
 * 截断文本
 * @param {string} text - 文本
 * @param {number} length - 最大长度
 * @param {string} suffix - 后缀
 * @returns {string}
 */
export function truncateText(text, length = 100, suffix = '...') {
  if (!text) return ''
  if (text.length <= length) return text
  return text.substring(0, length) + suffix
}

/**
 * 生成随机ID
 * @returns {string}
 */
export function generateId() {
  return Math.random().toString(36).substring(2, 9)
}

/**
 * 防抖函数
 * @param {Function} fn - 函数
 * @param {number} delay - 延迟时间
 * @returns {Function}
 */
export function debounce(fn, delay = 300) {
  let timer = null
  return function (...args) {
    if (timer) clearTimeout(timer)
    timer = setTimeout(() => {
      fn.apply(this, args)
    }, delay)
  }
}

/**
 * 节流函数
 * @param {Function} fn - 函数
 * @param {number} delay - 延迟时间
 * @returns {Function}
 */
export function throttle(fn, delay = 300) {
  let lastTime = 0
  return function (...args) {
    const now = Date.now()
    if (now - lastTime >= delay) {
      fn.apply(this, args)
      lastTime = now
    }
  }
}

/**
 * 深拷贝
 * @param {*} obj - 对象
 * @returns {*}
 */
export function deepClone(obj) {
  if (obj === null || typeof obj !== 'object') return obj
  if (obj instanceof Date) return new Date(obj.getTime())
  if (obj instanceof Array) return obj.map(item => deepClone(item))
  if (obj instanceof Object) {
    const copy = {}
    Object.keys(obj).forEach(key => {
      copy[key] = deepClone(obj[key])
    })
    return copy
  }
}

/**
 * 检查是否为移动设备
 * @returns {boolean}
 */
export function isMobile() {
  return /Android|webOS|iPhone|iPad|iPod|BlackBerry|IEMobile|Opera Mini/i.test(
    navigator.userAgent
  )
}

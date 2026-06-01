/**
 * 验证工具函数
 */

/**
 * 验证邮箱
 * @param {string} email - 邮箱地址
 * @returns {boolean}
 */
export function isEmail(email) {
  const reg = /^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}$/
  return reg.test(email)
}

/**
 * 验证手机号
 * @param {string} phone - 手机号
 * @returns {boolean}
 */
export function isPhone(phone) {
  const reg = /^1[3-9]\d{9}$/
  return reg.test(phone)
}

/**
 * 验证身份证号
 * @param {string} idCard - 身份证号
 * @returns {boolean}
 */
export function isIdCard(idCard) {
  const reg = /(^\d{15}$)|(^\d{18}$)|(^\d{17}(\d|X|x)$)/
  return reg.test(idCard)
}

/**
 * 验证URL
 * @param {string} url - URL地址
 * @returns {boolean}
 */
export function isUrl(url) {
  const reg = /^https?:\/\/.+/
  return reg.test(url)
}

/**
 * 验证IP地址
 * @param {string} ip - IP地址
 * @returns {boolean}
 */
export function isIP(ip) {
  const reg = /^(\d{1,3}\.){3}\d{1,3}$/
  return reg.test(ip)
}

/**
 * 验证中文
 * @param {string} str - 字符串
 * @returns {boolean}
 */
export function isChinese(str) {
  const reg = /^[\u4e00-\u9fa5]+$/
  return reg.test(str)
}

/**
 * 验证数字
 * @param {string|number} num - 数字
 * @returns {boolean}
 */
export function isNumber(num) {
  return !isNaN(parseFloat(num)) && isFinite(num)
}

/**
 * 验证整数
 * @param {string|number} num - 数字
 * @returns {boolean}
 */
export function isInteger(num) {
  return Number.isInteger(Number(num))
}

/**
 * 验证密码强度
 * @param {string} password - 密码
 * @returns {object} { valid: boolean, level: number, message: string }
 */
export function checkPassword(password) {
  let level = 0
  let message = ''

  if (password.length < 6) {
    return { valid: false, level: 0, message: '密码长度不能少于6位' }
  }

  if (/[a-z]/.test(password)) level++
  if (/[A-Z]/.test(password)) level++
  if (/[0-9]/.test(password)) level++
  if (/[^a-zA-Z0-9]/.test(password)) level++

  if (level < 2) {
    message = '密码强度较弱'
  } else if (level < 4) {
    message = '密码强度中等'
  } else {
    message = '密码强度较强'
  }

  return { valid: true, level, message }
}

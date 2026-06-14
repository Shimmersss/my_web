/**
 * SEO 工具函数
 */

/**
 * 设置页面标题
 * @param {string} title - 标题
 */
export function setPageTitle(title) {
  document.title = `${title} - 研究工具台`
}

/**
 * 设置页面描述
 * @param {string} description - 描述
 */
export function setPageDescription(description) {
  const meta = document.querySelector('meta[name="description"]')
  if (meta) {
    meta.setAttribute('content', description)
  }
}

/**
 * 设置页面关键词
 * @param {string} keywords - 关键词
 */
export function setPageKeywords(keywords) {
  const meta = document.querySelector('meta[name="keywords"]')
  if (meta) {
    meta.setAttribute('content', keywords)
  }
}

/**
 * 设置页面 Meta 信息
 * @param {object} options - Meta 配置
 */
export function setPageMeta({ title, description, keywords }) {
  if (title) setPageTitle(title)
  if (description) setPageDescription(description)
  if (keywords) setPageKeywords(keywords)
}

/**
 * 设置 Open Graph 信息
 * @param {object} options - OG 配置
 */
export function setOGMeta(options) {
  const { title, description, image, url } = options

  const updateMeta = (property, content) => {
    let meta = document.querySelector(`meta[property="${property}"]`)
    if (!meta) {
      meta = document.createElement('meta')
      meta.setAttribute('property', property)
      document.head.appendChild(meta)
    }
    if (content) {
      meta.setAttribute('content', content)
    }
  }

  updateMeta('og:title', title)
  updateMeta('og:description', description)
  updateMeta('og:image', image)
  updateMeta('og:url', url)
}

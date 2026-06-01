/**
 * 滚动相关 Composable
 */

import { ref, onMounted, onUnmounted } from 'vue'

/**
 * 返回到顶部
 * @param {object} options - 配置
 * @returns {object}
 */
export function useScrollToTop(options = {}) {
  const { threshold = 300, behavior = 'smooth' } = options
  const showButton = ref(false)

  const scrollToTop = () => {
    window.scrollTo({
      top: 0,
      behavior
    })
  }

  const handleScroll = () => {
    showButton.value = window.scrollY > threshold
  }

  onMounted(() => {
    window.addEventListener('scroll', handleScroll)
  })

  onUnmounted(() => {
    window.removeEventListener('scroll', handleScroll)
  })

  return {
    showButton,
    scrollToTop
  }
}

/**
 * 滚动到元素
 * @param {string|HTMLElement} target - 目标元素
 * @param {object} options - 配置
 */
export function scrollIntoView(target, options = {}) {
  const { behavior = 'smooth', block = 'start', inline = 'nearest' } = options

  if (typeof target === 'string') {
    const element = document.querySelector(target)
    if (element) {
      element.scrollIntoView({ behavior, block, inline })
    }
  } else if (target instanceof HTMLElement) {
    target.scrollIntoView({ behavior, block, inline })
  }
}

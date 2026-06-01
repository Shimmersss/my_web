/**
 * SEO Composable
 * 用于在组件中管理SEO信息
 */

import { onMounted, watch } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { setPageMeta } from '@/utils/seo'

/**
 * SEO Hook
 * @param {object} options - SEO配置
 * @returns {object}
 */
export function useSEO(options = {}) {
  const router = useRouter()
  const route = useRoute()

  const updateSEO = () => {
    const meta = {
      title: options.title,
      description: options.description,
      keywords: options.keywords
    }

    // 支持动态配置
    if (typeof meta.title === 'function') {
      meta.title = meta.title(route)
    }
    if (typeof meta.description === 'function') {
      meta.description = meta.description(route)
    }
    if (typeof meta.keywords === 'function') {
      meta.keywords = meta.keywords(route)
    }

    setPageMeta(meta)
  }

  // 监听路由变化
  watch(() => route.path, updateSEO)

  // 初始化
  onMounted(updateSEO)

  return {
    updateSEO
  }
}

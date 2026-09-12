import { ref, computed, onScopeDispose } from 'vue'
import { getPptPreview, getPptPreviewImage, getPptHtmlPreview } from '@/api'

/** Owns preview requests and all Blob URLs, including partially loaded batches. */
export function usePptPreview({ taskId, taskAccessToken, activeTask, outputFormatLabel }) {
  const previewData = ref(null)

  const previewImageUrls = ref({})

  const htmlPreviewUrl = ref('')

  const previewLoading = ref(false)

  const previewError = ref('')

  const previewSelectedIndex = ref(0)

  let previewGeneration = 0

  let previewAbortController = null

  const previewSlides = computed(() => previewData.value?.slides || [])

  const selectedPreviewSlide = computed(() => previewSlides.value[previewSelectedIndex.value] || null)

  async function loadPreview() {
    clearPreview()
    previewError.value = ''
    if (!taskId.value || activeTask.value?.status !== 'completed') return
    const requestedTaskId = taskId.value
    const requestedToken = taskAccessToken.value
    const generation = ++previewGeneration
    const controller = new AbortController()
    previewAbortController = controller
    previewLoading.value = true
    const pendingUrls = new Set()
    controller.signal.addEventListener('abort', () => {
      pendingUrls.forEach(url => URL.revokeObjectURL(url))
      pendingUrls.clear()
    }, { once: true })
    try {
      const res = await getPptPreview(requestedTaskId, requestedToken, { signal: controller.signal })
      if (generation !== previewGeneration || taskId.value !== requestedTaskId || taskAccessToken.value !== requestedToken) return
      const data = res.data || {}
      data.slides = Array.isArray(data.slides) ? data.slides : []
      previewData.value = data
      previewSelectedIndex.value = Math.min(previewSelectedIndex.value, Math.max(0, data.slides.length - 1))
      const imageFiles = [...new Set(data.slides.map(slide => slide.imageFile).filter(Boolean))].slice(0, 24)
      const loaded = await Promise.all(imageFiles.map(async fileName => {
        try {
          const url = await getPptPreviewImage(requestedTaskId, fileName, requestedToken, { signal: controller.signal })
          if (generation !== previewGeneration || taskId.value !== requestedTaskId || taskAccessToken.value !== requestedToken) {
            URL.revokeObjectURL(url)
            return null
          }
          pendingUrls.add(url)
          return [fileName, url]
        } catch {
          return null
        }
      }))
      if (generation !== previewGeneration || taskId.value !== requestedTaskId || taskAccessToken.value !== requestedToken) return
      previewImageUrls.value = Object.fromEntries(loaded.filter(Boolean))
      pendingUrls.clear()
      if (outputFormatLabel(activeTask.value) === 'HTML') {
        const url = await getPptHtmlPreview(requestedTaskId, requestedToken, { signal: controller.signal })
        if (generation !== previewGeneration || taskId.value !== requestedTaskId || taskAccessToken.value !== requestedToken) {
          URL.revokeObjectURL(url)
          return
        }
        if (htmlPreviewUrl.value) URL.revokeObjectURL(htmlPreviewUrl.value)
        htmlPreviewUrl.value = url
      }
    } catch (error) {
      if (error?.name === 'AbortError') return
      if (generation !== previewGeneration || taskId.value !== requestedTaskId) return
      previewError.value = error.message || '网页预览加载失败'
    } finally {
      pendingUrls.forEach(url => URL.revokeObjectURL(url))
      if (generation === previewGeneration) {
        previewLoading.value = false
        previewAbortController = null
      }
    }
  }

  function clearPreview() {
    previewGeneration += 1
    previewLoading.value = false
    if (previewAbortController) {
      previewAbortController.abort()
      previewAbortController = null
    }
    Object.values(previewImageUrls.value || {}).forEach(url => URL.revokeObjectURL(url))
    previewImageUrls.value = {}
    if (htmlPreviewUrl.value) URL.revokeObjectURL(htmlPreviewUrl.value)
    htmlPreviewUrl.value = ''
    previewData.value = null
    previewError.value = ''
    previewSelectedIndex.value = 0
  }
  onScopeDispose(clearPreview)
  return { previewData, previewImageUrls, htmlPreviewUrl, previewLoading, previewError, previewSelectedIndex, previewSlides, selectedPreviewSlide, loadPreview, clearPreview };
}

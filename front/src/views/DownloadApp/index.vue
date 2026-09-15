<template>
  <section class="download-page">
    <div class="download-card">
      <p class="eyebrow">ANDROID · EMBEDDED GECKO</p>
      <h1>下载 Shimmer App</h1>
      <p class="lead">独立 App 内置 Gecko 浏览器内核，不依赖手机上的系统 WebView 或本地浏览器。</p>

      <n-alert v-if="error" type="error" :title="error" />
      <n-spin :show="loading">
        <div class="release-panel">
          <div>
            <small>当前版本</small>
            <strong>{{ release?.versionName || '正在读取…' }}</strong>
          </div>
          <p>{{ release?.notes || '正在获取最新版本信息。' }}</p>
          <n-button type="primary" size="large" :disabled="!downloadUrl" tag="a" :href="downloadUrl">
            下载 Android APK
          </n-button>
        </div>
      </n-spin>

      <div class="address-panel">
        <small>下载网页地址</small>
        <code>{{ pageUrl }}</code>
        <n-button secondary @click="copyPageUrl">复制地址</n-button>
      </div>

      <ul>
        <li>适用于 Android 8.0 及以上的 64 位 ARM 手机。</li>
        <li>首次安装需按系统提示允许浏览器安装 APK；之后仍由 Android 确认安装。</li>
        <li>从旧版 App 内更新时，会校验版本、SHA-256、包名和签名证书。</li>
      </ul>
    </div>
  </section>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { NAlert, NButton, NSpin, useMessage } from 'naive-ui'
import { getLatestAndroidApp } from '@/api'

const message = useMessage()
const loading = ref(true)
const error = ref('')
const release = ref(null)
const pageUrl = 'https://shimmer.help/download-app'
const downloadUrl = computed(() => {
  const value = String(release.value?.apkUrl || '')
  try {
    const url = new URL(value, window.location.origin)
    return url.origin === 'https://shimmer.help' && url.pathname.startsWith('/api/app-update/apk/')
      ? url.href : ''
  } catch {
    return ''
  }
})

onMounted(async () => {
  try {
    const response = await getLatestAndroidApp()
    release.value = response.data || response || null
    if (!downloadUrl.value) throw new Error('下载地址不可用')
  } catch (reason) {
    error.value = reason.message || '暂时无法获取 App 下载信息'
  } finally {
    loading.value = false
  }
})

async function copyPageUrl() {
  try {
    await navigator.clipboard.writeText(pageUrl)
    message.success('下载网页地址已复制')
  } catch {
    message.error('复制失败，请长按地址复制')
  }
}
</script>

<style scoped>
.download-page { min-height: calc(100vh - 80px); padding: 42px 16px 72px; background: var(--desk-bg); color: var(--desk-text); }
.download-card { max-width: 680px; margin: 0 auto; padding: 34px; border: 1px solid #cfc4b2; background: var(--desk-surface); box-shadow: 0 18px 46px rgba(70, 55, 34, .09); }
.eyebrow { margin: 0 0 10px; color: #a03f30; font-size: 11px; font-weight: 700; letter-spacing: .15em; }
h1 { margin: 0; font: 500 42px/1.15 Georgia, serif; }
.lead { margin: 16px 0 26px; color: var(--desk-muted); line-height: 1.75; }
.release-panel { display: grid; gap: 16px; padding: 22px; border: 1px solid var(--desk-border); background: var(--desk-surface); }
.release-panel div { display: grid; gap: 4px; }
.release-panel small, .address-panel small { color: #8b7e6d; font-size: 12px; }
.release-panel strong { font-size: 22px; }
.release-panel p { margin: 0; line-height: 1.7; }
.release-panel a { text-decoration: none; }
.address-panel { display: grid; gap: 10px; margin-top: 20px; padding: 18px; border-left: 3px solid var(--desk-accent); background: var(--desk-soft); }
.address-panel code { overflow-wrap: anywhere; font-size: 14px; }
ul { margin: 24px 0 0; padding-left: 20px; color: var(--desk-muted); line-height: 1.9; }
@media (max-width: 600px) { .download-page { padding: 22px 10px 54px; } .download-card { padding: 22px 18px; } h1 { font-size: 34px; } }
</style>

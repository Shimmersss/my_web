<template>
  <div class="publications-page tool-page">
    <section>
      <div class="container">
        <div class="page-header tool-page__header">
          <h1>文献库</h1>
          <p>同步自 Zotero · 共 {{ total }} 条 · 显示 {{ filtered.length }} 条匹配</p>
        </div>

        <n-alert v-if="error" type="error" title="加载失败" style="margin-bottom: 16px">
          {{ error }}
        </n-alert>

        <n-spin :show="loading">
          <div class="layout" :class="{ 'sidebar-collapsed': sidebarCollapsed }">
            <div ref="sidebarSlot" class="sidebar-slot">
              <aside
                class="sidebar"
                :class="{ collapsed: sidebarCollapsed, following: sidebarFollowing }"
                :style="sidebarFollowStyle"
              >
                <button
                  class="sidebar-toggle"
                  type="button"
                  :title="sidebarCollapsed ? '展开分组栏' : '折叠分组栏'"
                  @click="sidebarCollapsed = !sidebarCollapsed"
                >
                  <n-icon size="18">
                    <ChevronForwardOutline v-if="sidebarCollapsed" />
                    <ChevronBackOutline v-else />
                  </n-icon>
                </button>
                <template v-if="!sidebarCollapsed">
                  <h3>Collections</h3>
                  <n-input
                    v-model:value="treeFilter"
                    aria-label="过滤文献分组"
                    placeholder="过滤分组..."
                    size="small"
                    clearable
                    style="margin-bottom: 12px"
                  />
                  <div class="coll-list">
                    <button
                      type="button"
                      class="coll-item"
                      :class="{ active: selectedKey === null }"
                      :aria-pressed="selectedKey === null"
                      @click="selectedKey = null"
                    >
                      <span class="coll-name">全部</span>
                      <span class="count">{{ total }}</span>
                    </button>
                    <button
                      v-for="c in visibleCollections"
                      :key="c.key"
                      type="button"
                      class="coll-item"
                      :class="{ active: selectedKey === c.key }"
                      :style="{ paddingLeft: 12 + c.depth * 16 + 'px' }"
                      :aria-pressed="selectedKey === c.key"
                      @click="selectedKey = c.key"
                    >
                      <span class="coll-name" :title="c.name">{{ c.name }}</span>
                      <span class="count">{{ countMap[c.key] || 0 }}</span>
                    </button>
                  </div>
                </template>
              </aside>
            </div>

            <div class="content">
              <div class="filter-bar">
                <n-input
                  v-model:value="keyword"
                  aria-label="搜索文献"
                  placeholder="搜索标题 / 作者 / 期刊 / 摘要..."
                  clearable
                  style="max-width: 360px"
                >
                  <template #prefix>
                    <n-icon><SearchOutline /></n-icon>
                  </template>
                </n-input>
                <n-select
                  v-model:value="typeFilter"
                  aria-label="按文献类型筛选"
                  :options="typeOptions"
                  placeholder="文献类型"
                  clearable
                  style="width: 180px"
                />
                <n-button @click="load" :loading="loading" size="small">刷新</n-button>
              </div>

              <div v-if="filtered.length === 0 && !loading" class="empty">
                <n-empty description="该分组下没有匹配的文献" />
              </div>

              <div v-else class="pub-list">
                <div v-for="item in pagedItems" :key="item.key" class="pub-item">
                  <div class="pub-row">
                    <div class="pub-type">{{ typeLabel(item.itemType) }}</div>
                    <div v-if="hasPdf(item)" class="pdf-badge">PDF</div>
                  </div>
                  <h3 class="pub-title">
                    <a v-if="item.url" :href="item.url" target="_blank" rel="noopener">{{ item.title || '(无标题)' }}</a>
                    <span v-else>{{ item.title || '(无标题)' }}</span>
                  </h3>
                  <div class="pub-meta">
                    <span v-if="formatCreators(item.creators)">{{ formatCreators(item.creators) }}</span>
                    <span v-if="item.publicationTitle"> · <em>{{ item.publicationTitle }}</em></span>
                    <span v-if="item.date"> · {{ item.date }}</span>
                  </div>

                  <div v-if="item.abstractNote" class="pub-abstract">
                    <p :class="{ collapsed: !expanded[item.key] }">{{ item.abstractNote }}</p>
                    <button
                      v-if="item.abstractNote.length > 200"
                      type="button"
                      class="toggle"
                      :aria-expanded="Boolean(expanded[item.key])"
                      @click="expanded[item.key] = !expanded[item.key]"
                    >{{ expanded[item.key] ? '收起' : '展开' }}</button>
                  </div>

                  <div class="pub-foot">
                    <a v-if="item.DOI" :href="`https://doi.org/${item.DOI}`" target="_blank" rel="noopener" class="doi-link">DOI: {{ item.DOI }}</a>
                    <span v-if="itemCollections(item).length" class="item-colls">
                      <n-icon size="14"><FolderOutline /></n-icon>
                      <span
                        v-for="cn in itemCollections(item)"
                        :key="cn"
                        class="item-coll"
                      >{{ cn }}</span>
                    </span>
                    <span v-if="item.tags && item.tags.length" class="tags">
                      <span v-for="t in item.tags" :key="t.tag" class="tag">{{ t.tag }}</span>
                    </span>
                  </div>

                  <div class="pub-actions">
                    <n-button
                      v-for="att in viewableAttachments(item)"
                      :key="att.key"
                      size="small"
                      type="primary"
                      ghost
                      @click="togglePdf(item.key, att)"
                    >
                      <template #icon><n-icon><DocumentTextOutline /></n-icon></template>
                      {{ attachmentButtonLabel(item.key, att) }}
                    </n-button>
                    <n-dropdown
                      :options="exportOptions"
                      trigger="click"
                      @select="opt => doExport(item.key, opt)"
                    >
                      <n-button size="small">
                        <template #icon><n-icon><DownloadOutline /></n-icon></template>
                        导出
                      </n-button>
                    </n-dropdown>
                  </div>

                  <div
                    v-if="activeDownload(item)"
                    class="attachment-progress"
                    role="status"
                    aria-live="polite"
                  >
                    <div class="attachment-progress__head">
                      <span>{{ downloadProgressText(activeDownload(item)) }}</span>
                      <span>{{ downloadSizeText(activeDownload(item)) }}</span>
                    </div>
                    <n-progress
                      type="line"
                      :percentage="activeDownload(item).percentage"
                      :show-indicator="false"
                    />
                  </div>

                  <div v-if="openedPdf[item.key]" class="pdf-frame">
                    <iframe
                      v-if="!openedMd[item.key]"
                      :src="`${openedPdfUrl[item.key]}#toolbar=1&navpanes=0`"
                      :title="`${item.title || '文献'}附件预览`"
                      frameborder="0"
                      loading="lazy"
                    />
                    <div
                      v-else
                      class="md-render markdown-body"
                      v-html="openedMd[item.key]"
                    />
                  </div>
                </div>
              </div>

              <div v-if="filtered.length > PAGE_SIZE" class="pagination-wrap">
                <n-pagination
                  v-model:page="currentPage"
                  :page-count="totalPages"
                  :page-size="PAGE_SIZE"
                  show-quick-jumper
                  @update:page="scrollToListTop"
                />
              </div>
            </div>
          </div>
        </n-spin>
      </div>
    </section>
  </div>
</template>

<script setup>
import { ref, computed, nextTick, onMounted, onBeforeUnmount, watch, reactive } from 'vue'
import { useMessage } from 'naive-ui'
import {
  SearchOutline,
  FolderOutline,
  DocumentTextOutline,
  DownloadOutline,
  ChevronBackOutline,
  ChevronForwardOutline
} from '@vicons/ionicons5'
import { getZoteroItems, getZoteroCollections } from '@/api'
import { marked } from 'marked'
import DOMPurify from 'dompurify'

const message = useMessage()

const items = ref([])
const collectionsRaw = ref([])
const loading = ref(false)
const error = ref('')
const keyword = ref('')
const typeFilter = ref(null)
const treeFilter = ref('')
const selectedKey = ref(null)
const sidebarCollapsed = ref(false)
const sidebarSlot = ref(null)
const sidebarFollowing = ref(false)
const sidebarFollowLeft = ref(0)
const sidebarFollowWidth = ref(0)
let sidebarResizeTimer = null
let sidebarScrollContainers = []

const PAGE_SIZE = 20
const currentPage = ref(1)
const expanded = reactive({})
const openedPdf = reactive({})
const openedPdfUrl = reactive({})
const openedMd = reactive({})
const attachmentDownloads = reactive({})
const attachmentAbortControllers = new Map()

const typeMap = {
  journalArticle: '期刊论文',
  conferencePaper: '会议论文',
  book: '书籍',
  bookSection: '书章节',
  thesis: '学位论文',
  preprint: '预印本',
  webpage: '网页',
  report: '报告',
  manuscript: '手稿',
  document: '文档',
  attachment: '附件'
}

const exportOptions = [
  { label: 'BibTeX', key: 'bibtex' },
  { label: 'RIS', key: 'ris' },
  { label: 'APA 引用（复制）', key: 'apa' }
]

const typeOptions = computed(() => {
  const set = new Set(items.value.map(i => i.itemType).filter(Boolean))
  return Array.from(set).map(t => ({ label: typeLabel(t), value: t }))
})

const collectionMap = computed(() => {
  const m = {}
  for (const c of collectionsRaw.value) m[c.key] = c
  return m
})

const collectionsTree = computed(() => {
  const map = collectionMap.value
  const out = []
  const visit = (key, depth) => {
    const c = map[key]
    if (!c) return
    out.push({ key: c.key, name: c.name, depth })
    collectionsRaw.value
      .filter(x => x.parentCollection === key)
      .sort((a, b) => a.name.localeCompare(b.name, 'zh'))
      .forEach(child => visit(child.key, depth + 1))
  }
  collectionsRaw.value
    .filter(c => !c.parentCollection)
    .sort((a, b) => a.name.localeCompare(b.name, 'zh'))
    .forEach(root => visit(root.key, 0))
  return out
})

const visibleCollections = computed(() => {
  const f = treeFilter.value.trim().toLowerCase()
  if (!f) return collectionsTree.value
  return collectionsTree.value.filter(c => c.name.toLowerCase().includes(f))
})

const total = computed(() => items.value.length)

const countMap = computed(() => {
  const m = {}
  for (const item of items.value) {
    for (const k of (item.collections || [])) {
      m[k] = (m[k] || 0) + 1
    }
  }
  return m
})

function itemMatchesPdfFirst(a, b) {
  const ap = hasPdf(a) ? 1 : 0
  const bp = hasPdf(b) ? 1 : 0
  return bp - ap
}

const filtered = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  const list = items.value.filter(item => {
    if (selectedKey.value && !(item.collections || []).includes(selectedKey.value)) return false
    if (typeFilter.value && item.itemType !== typeFilter.value) return false
    if (!kw) return true
    const hay = [
      item.title,
      item.publicationTitle,
      formatCreators(item.creators),
      item.abstractNote
    ].filter(Boolean).join(' ').toLowerCase()
    return hay.includes(kw)
  })
  return [...list].sort(itemMatchesPdfFirst)
})

const totalPages = computed(() => Math.max(1, Math.ceil(filtered.value.length / PAGE_SIZE)))

const pagedItems = computed(() => {
  const start = (currentPage.value - 1) * PAGE_SIZE
  return filtered.value.slice(start, start + PAGE_SIZE)
})

const sidebarFollowStyle = computed(() => {
  if (!sidebarFollowing.value) return undefined
  return {
    left: `${sidebarFollowLeft.value}px`,
    width: `${sidebarFollowWidth.value}px`
  }
})

watch([keyword, typeFilter, selectedKey], () => {
  currentPage.value = 1
})

watch(sidebarCollapsed, () => {
  nextTick(updateSidebarFollow)
  clearTimeout(sidebarResizeTimer)
  sidebarResizeTimer = setTimeout(updateSidebarFollow, 220)
})

watch(totalPages, pages => {
  if (currentPage.value > pages) currentPage.value = pages
})

function scrollToListTop() {
  const top = document.querySelector('.content')?.getBoundingClientRect().top
  if (top === undefined) return
  window.scrollTo({ top: window.scrollY + top - 100, behavior: 'smooth' })
}

function updateSidebarFollow() {
  const slot = sidebarSlot.value
  if (!slot || window.innerWidth <= 720) {
    sidebarFollowing.value = false
    return
  }
  const rect = slot.getBoundingClientRect()
  sidebarFollowing.value = rect.top <= 100
  sidebarFollowLeft.value = rect.left
  sidebarFollowWidth.value = rect.width
}

function bindSidebarFollowListeners() {
  let parent = sidebarSlot.value?.parentElement
  while (parent) {
    if (parent.classList.contains('n-layout-scroll-container')) {
      parent.addEventListener('scroll', updateSidebarFollow, { passive: true })
      sidebarScrollContainers.push(parent)
    }
    parent = parent.parentElement
  }
}

function typeLabel(t) {
  return typeMap[t] || t || '未知'
}

function formatCreators(creators) {
  if (!Array.isArray(creators) || creators.length === 0) return ''
  return creators
    .map(c => [c.firstName, c.lastName].filter(Boolean).join(' ') || c.name)
    .filter(Boolean)
    .join(', ')
}

function itemCollections(item) {
  return (item.collections || []).map(k => collectionMap.value[k]?.name).filter(Boolean)
}

function hasPdf(item) {
  return (item.attachments || []).some(a => a.isPdf)
}

function viewableAttachments(item) {
  return item.attachments || []
}

function attachmentLabel(att) {
  if (att.isPdf) return '查看 PDF'
  const fn = att.filename || att.title || ''
  if (/\.md$/i.test(fn)) return '查看 Markdown'
  if (/\.(txt|csv|json|xml|html?)$/i.test(fn)) return '查看文件'
  return '查看附件'
}

function attachmentButtonLabel(itemKey, att) {
  if (attachmentDownloads[att.key]?.status === 'loading') return '取消下载'
  return openedPdf[itemKey] === att.key ? '收起' : attachmentLabel(att)
}

function activeDownload(item) {
  return (item.attachments || [])
    .map(att => attachmentDownloads[att.key])
    .find(download => download?.status === 'loading')
}

function formatBytes(bytes) {
  if (!Number.isFinite(bytes) || bytes <= 0) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB']
  const unit = Math.min(Math.floor(Math.log(bytes) / Math.log(1024)), units.length - 1)
  return `${(bytes / (1024 ** unit)).toFixed(unit === 0 ? 0 : 1)} ${units[unit]}`
}

function downloadProgressText(download) {
  if (download.loaded === 0) return '正在连接附件服务器'
  return download.total > 0 ? `正在下载附件 ${download.percentage.toFixed(1)}%` : '正在下载附件'
}

function downloadSizeText(download) {
  if (download.loaded === 0) return '等待首批数据'
  const size = download.total > 0
    ? `${formatBytes(download.loaded)} / ${formatBytes(download.total)}`
    : `${formatBytes(download.loaded)} 已下载`
  return download.bytesPerSecond > 0 ? `${size} · ${formatBytes(download.bytesPerSecond)}/s` : size
}

function pdfAttachments(item) {
  return (item.attachments || []).filter(a => a.isPdf)
}

function isMarkdown(att) {
  const fn = att.filename || att.title || ''
  return /\.(md|markdown)$/i.test(fn)
}

async function togglePdf(itemKey, att) {
  if (attachmentDownloads[att.key]?.status === 'loading') {
    attachmentAbortControllers.get(att.key)?.abort()
    return
  }
  if (openedPdf[itemKey] === att.key) {
    closeAttachment(itemKey)
    return
  }

  if (isMarkdown(att)) {
    openedPdf[itemKey] = att.key
    delete openedMd[itemKey]
    try {
      const res = await fetch(`/api/zotero/file/${att.key}`)
      const text = await res.text()
      const html = marked.parse(text, { breaks: true, gfm: true })
      openedMd[itemKey] = DOMPurify.sanitize(html)
    } catch (e) {
      openedMd[itemKey] = `<p style="color:#d03050">加载失败：${e.message}</p>`
    }
    return
  }

  const controller = new AbortController()
  attachmentAbortControllers.set(att.key, controller)
  const startedAt = Date.now()
  attachmentDownloads[att.key] = {
    status: 'loading',
    loaded: 0,
    total: 0,
    percentage: 0,
    bytesPerSecond: 0
  }
  try {
    const res = await fetch(`/api/zotero/file/${att.key}`, { signal: controller.signal })
    if (!res.ok) throw new Error('HTTP ' + res.status)
    const total = Number(res.headers.get('content-length')) || 0
    const reader = res.body?.getReader()
    if (!reader) throw new Error('浏览器不支持流式下载')
    const chunks = []
    let loaded = 0
    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      chunks.push(value)
      loaded += value.byteLength
      const elapsedSeconds = Math.max((Date.now() - startedAt) / 1000, 0.001)
      attachmentDownloads[att.key] = {
        status: 'loading',
        loaded,
        total,
        percentage: total > 0 ? Math.min(99.9, loaded / total * 100) : 0,
        bytesPerSecond: loaded / elapsedSeconds
      }
    }
    closeAttachment(itemKey)
    openedPdf[itemKey] = att.key
    openedPdfUrl[itemKey] = URL.createObjectURL(new Blob(chunks, {
      type: res.headers.get('content-type') || 'application/pdf'
    }))
    attachmentDownloads[att.key] = {
      status: 'done',
      loaded,
      total,
      percentage: 100,
      bytesPerSecond: loaded / Math.max((Date.now() - startedAt) / 1000, 0.001)
    }
  } catch (e) {
    delete attachmentDownloads[att.key]
    if (e.name !== 'AbortError') message.error('附件下载失败：' + e.message)
  } finally {
    attachmentAbortControllers.delete(att.key)
  }
}

function closeAttachment(itemKey) {
  if (openedPdfUrl[itemKey]) URL.revokeObjectURL(openedPdfUrl[itemKey])
  delete openedPdf[itemKey]
  delete openedPdfUrl[itemKey]
  delete openedMd[itemKey]
}

async function doExport(itemKey, opt) {
  const isApa = opt === 'apa'
  const format = isApa ? 'bibliography' : opt
  try {
    const res = await fetch(`/api/zotero/items/${itemKey}/export?format=${format}&style=apa`)
    if (!res.ok) throw new Error('HTTP ' + res.status)
    const text = await res.text()
    if (isApa) {
      const stripped = text.replace(/<[^>]+>/g, '').trim()
      await navigator.clipboard.writeText(stripped)
      message.success('APA 引用已复制到剪贴板')
    } else {
      const blob = new Blob([text], { type: 'text/plain;charset=utf-8' })
      const url = URL.createObjectURL(blob)
      const a = document.createElement('a')
      a.href = url
      a.download = `${itemKey}.${opt === 'bibtex' ? 'bib' : 'ris'}`
      document.body.appendChild(a)
      a.click()
      a.remove()
      URL.revokeObjectURL(url)
      message.success(`${opt.toUpperCase()} 已下载`)
    }
  } catch (e) {
    message.error('导出失败：' + e.message)
  }
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const [itemsRes, collRes] = await Promise.all([
      getZoteroItems(200),
      getZoteroCollections()
    ])
    if (itemsRes.code === 200) {
      items.value = itemsRes.data || []
      // 后端缓存还在预热（首次启动后 3-15 秒），轮询一次
      if (itemsRes.warmedUp === false || items.value.length === 0) {
        setTimeout(load, 2000)
      }
    } else {
      error.value = itemsRes.message || '拉取文献失败'
    }
    collectionsRaw.value = (collRes?.data || []).map(c => c.data || c)
  } catch (e) {
    error.value = e.message || '网络错误'
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  load()
  nextTick(() => {
    bindSidebarFollowListeners()
    updateSidebarFollow()
  })
  window.addEventListener('scroll', updateSidebarFollow, { passive: true })
  window.addEventListener('resize', updateSidebarFollow)
})

onBeforeUnmount(() => {
  attachmentAbortControllers.forEach(controller => controller.abort())
  Object.values(openedPdfUrl).forEach(url => URL.revokeObjectURL(url))
  clearTimeout(sidebarResizeTimer)
  sidebarScrollContainers.forEach(container => {
    container.removeEventListener('scroll', updateSidebarFollow)
  })
  sidebarScrollContainers = []
  window.removeEventListener('scroll', updateSidebarFollow)
  window.removeEventListener('resize', updateSidebarFollow)
})
</script>

<style scoped>
.publications-page {
  background: #eee9df;
}

.container {
  max-width: 1280px;
  margin: 0 auto;
  padding: 0 24px;
}

.page-header { margin-bottom: 24px; }
.page-header p { color: #666; margin: 0; }

.layout {
  display: grid;
  grid-template-columns: 240px 1fr;
  gap: 24px;
  align-items: start;
  transition: grid-template-columns 0.2s ease;
}

.layout.sidebar-collapsed {
  grid-template-columns: 48px 1fr;
}

.sidebar-slot {
  min-width: 0;
}

.sidebar {
  background: #fbf9f3;
  border: 1px solid #d2cabc;
  border-radius: 2px;
  padding: 16px;
  box-shadow: 2px 3px 0 rgba(95, 86, 65, 0.1);
  width: 100%;
  max-height: calc(100vh - 120px);
  overflow-y: auto;
}

.sidebar.following {
  position: fixed;
  top: 100px;
  z-index: 5;
}

.sidebar.collapsed {
  padding: 8px;
  overflow: hidden;
}

.sidebar-toggle {
  width: 30px;
  height: 30px;
  margin: 0 0 10px auto;
  border: 0;
  border-radius: 6px;
  color: #666;
  background: #f5f5f5;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: background 0.15s, color 0.15s;
}

.sidebar-toggle:hover {
  color: #1677ff;
  background: #e6f0ff;
}

.sidebar.collapsed .sidebar-toggle {
  margin-bottom: 0;
}

.sidebar h3 { margin: 0 0 12px; font-size: 15px; color: #555; }

.coll-list { display: flex; flex-direction: column; }

.coll-item {
  appearance: none;
  width: 100%;
  border: 0;
  background: transparent;
  color: inherit;
  font: inherit;
  text-align: left;
  padding: 8px 12px;
  border-radius: 6px;
  cursor: pointer;
  font-size: 14px;
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 8px;
  transition: background 0.15s;
}

.coll-item:hover { background: #f3f4f6; }
.coll-item:focus-visible,
.sidebar-toggle:focus-visible,
.toggle:focus-visible {
  outline: 3px solid rgba(22, 119, 255, 0.28);
  outline-offset: 2px;
}
.coll-item.active { background: #f0e5d9; color: #b83126; font-weight: 500; }

.coll-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  flex: 1;
}

.count {
  font-size: 12px;
  color: #999;
  background: #f5f5f5;
  padding: 1px 8px;
  border-radius: 10px;
  flex-shrink: 0;
}

.coll-item.active .count { background: #e5d8c9; color: #b83126; }

.content {
  background: transparent;
}

.filter-bar {
  display: flex;
  gap: 12px;
  margin-bottom: 16px;
  flex-wrap: wrap;
  align-items: center;
  background: #fbf9f3;
  border: 1px solid #d2cabc;
  padding: 12px 16px;
  border-radius: 2px;
  box-shadow: 2px 3px 0 rgba(95, 86, 65, 0.1);
}

.filter-bar :deep(.n-input),
.filter-bar :deep(.n-select) {
  min-width: 0;
}

.empty { padding: 48px 0; }

.pub-list {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.pub-item {
  background: #fbf9f3;
  border: 1px solid #d2cabc;
  border-radius: 2px;
  padding: 20px 24px;
  box-shadow: 2px 3px 0 rgba(95, 86, 65, 0.1);
  transition: box-shadow 0.2s;
}

.pub-item:hover {
  box-shadow: 0 6px 24px rgba(0, 0, 0, 0.08);
}

.pub-row {
  display: flex;
  gap: 8px;
  margin-bottom: 8px;
}

.pub-type {
  display: inline-block;
  font-size: 12px;
  color: #52624e;
  background: #edf0e8;
  padding: 2px 10px;
  border-radius: 4px;
}

.pdf-badge {
  font-size: 12px;
  color: #d4380d;
  background: #fff1f0;
  padding: 2px 10px;
  border-radius: 4px;
  font-weight: 600;
}

.pub-title {
  font-size: 17px;
  margin: 0 0 8px;
  line-height: 1.5;
  overflow-wrap: anywhere;
}

.pub-title a {
  color: #1f1f1f;
  text-decoration: none;
}

.pub-title a:hover { color: #1677ff; }

.pub-meta {
  color: #666;
  font-size: 13px;
  margin-bottom: 10px;
}

.pub-abstract {
  position: relative;
  margin: 8px 0 12px;
}

.pub-abstract p {
  color: #555;
  font-size: 13px;
  line-height: 1.7;
  margin: 0;
}

.pub-abstract p.collapsed {
  display: -webkit-box;
  -webkit-line-clamp: 3;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.toggle {
  appearance: none;
  border: 0;
  padding: 0;
  background: transparent;
  font: inherit;
  display: inline-block;
  margin-top: 4px;
  font-size: 12px;
  color: #1677ff;
  cursor: pointer;
}

.pub-foot {
  display: flex;
  gap: 12px;
  align-items: center;
  flex-wrap: wrap;
  font-size: 12px;
  color: #888;
  margin-bottom: 12px;
}

.doi-link { color: #1677ff; text-decoration: none; overflow-wrap: anywhere; }

.item-colls {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.item-coll {
  background: #f5f5f5;
  padding: 1px 8px;
  border-radius: 4px;
  font-size: 12px;
}

.tags { display: flex; gap: 6px; flex-wrap: wrap; }

.tag {
  background: #fff7e6;
  color: #d4880c;
  padding: 1px 8px;
  border-radius: 4px;
  font-size: 12px;
}

.pub-actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.attachment-progress {
  margin-top: 12px;
  padding: 10px 12px;
  border: 1px solid #d2cabc;
  background: #f7f2e8;
}

.attachment-progress__head {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 7px;
  color: #5f5641;
  font-size: 12px;
}

.pdf-frame {
  margin-top: 14px;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  overflow: hidden;
}

.pdf-frame iframe {
  width: 100%;
  height: 720px;
  display: block;
}

.md-render {
  padding: 24px 28px;
  background: #fff;
  max-height: 720px;
  overflow: auto;
  font-size: 14px;
  line-height: 1.7;
  color: #1f2937;
}
.md-render :deep(h1),
.md-render :deep(h2),
.md-render :deep(h3),
.md-render :deep(h4) {
  margin: 1.2em 0 0.6em;
  font-weight: 600;
  line-height: 1.3;
}
.md-render :deep(h1) { font-size: 1.6em; border-bottom: 1px solid #eee; padding-bottom: 0.3em; }
.md-render :deep(h2) { font-size: 1.35em; border-bottom: 1px solid #eee; padding-bottom: 0.25em; }
.md-render :deep(h3) { font-size: 1.15em; }
.md-render :deep(p) { margin: 0.6em 0; }
.md-render :deep(ul),
.md-render :deep(ol) { padding-left: 1.6em; margin: 0.6em 0; }
.md-render :deep(code) {
  background: #f3f4f6;
  padding: 2px 6px;
  border-radius: 4px;
  font-family: 'SF Mono', Consolas, monospace;
  font-size: 0.9em;
}
.md-render :deep(pre) {
  background: #0f172a;
  color: #e2e8f0;
  padding: 14px 16px;
  border-radius: 6px;
  overflow-x: auto;
  margin: 0.8em 0;
}
.md-render :deep(pre code) {
  background: transparent;
  color: inherit;
  padding: 0;
}
.md-render :deep(blockquote) {
  border-left: 4px solid #d1d5db;
  padding-left: 12px;
  color: #6b7280;
  margin: 0.6em 0;
}
.md-render :deep(table) {
  display: block;
  max-width: 100%;
  overflow-x: auto;
  border-collapse: collapse;
  margin: 0.8em 0;
}
.md-render :deep(th),
.md-render :deep(td) {
  border: 1px solid #e5e7eb;
  padding: 6px 10px;
}
.md-render :deep(a) { color: #2563eb; text-decoration: none; }
.md-render :deep(a:hover) { text-decoration: underline; }
.md-render :deep(img) { max-width: 100%; }

.pagination-wrap {
  display: flex;
  justify-content: center;
  margin: 32px 0;
}

@media (max-width: 720px) {
  .layout,
  .layout.sidebar-collapsed {
    grid-template-columns: 1fr;
    gap: 12px;
  }

  .sidebar {
    position: relative;
    z-index: 2;
    max-height: 50vh;
    width: 100%;
  }

  .sidebar.collapsed {
    width: 100%;
    height: 48px;
  }

  .filter-bar {
    display: grid;
    grid-template-columns: 1fr;
    padding: 12px;
  }

  .filter-bar :deep(.n-input),
  .filter-bar :deep(.n-select),
  .filter-bar :deep(.n-button) {
    width: 100% !important;
    max-width: none !important;
  }

  .pub-item {
    padding: 16px;
  }

  .pub-actions {
    display: grid;
    grid-template-columns: 1fr;
  }

  .pub-actions :deep(.n-button) {
    width: 100%;
  }

  .pdf-frame iframe {
    height: 62vh;
    min-height: 360px;
  }

  .md-render {
    padding: 16px;
    max-height: 62vh;
  }

  .pagination-wrap :deep(.n-pagination) {
    flex-wrap: wrap;
    justify-content: center;
  }
}
</style>

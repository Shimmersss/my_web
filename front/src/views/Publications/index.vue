<template>
  <div class="publications-page">
    <section class="section">
      <div class="container">
        <div class="page-header">
          <h2>文献库</h2>
          <p>同步自 Zotero · 共 {{ total }} 条 · 显示 {{ filtered.length }} 条匹配</p>
        </div>

        <n-alert v-if="error" type="error" title="加载失败" style="margin-bottom: 16px">
          {{ error }}
        </n-alert>

        <n-spin :show="loading">
          <div class="layout">
            <aside class="sidebar">
              <h3>Collections</h3>
              <n-input
                v-model:value="treeFilter"
                placeholder="过滤分组..."
                size="small"
                clearable
                style="margin-bottom: 12px"
              />
              <div class="coll-list">
                <div
                  class="coll-item"
                  :class="{ active: selectedKey === null }"
                  @click="selectedKey = null"
                >
                  <span class="coll-name">全部</span>
                  <span class="count">{{ total }}</span>
                </div>
                <div
                  v-for="c in visibleCollections"
                  :key="c.key"
                  class="coll-item"
                  :class="{ active: selectedKey === c.key }"
                  :style="{ paddingLeft: 12 + c.depth * 16 + 'px' }"
                  @click="selectedKey = c.key"
                >
                  <span class="coll-name" :title="c.name">{{ c.name }}</span>
                  <span class="count">{{ countMap[c.key] || 0 }}</span>
                </div>
              </div>
            </aside>

            <main class="content">
              <div class="filter-bar">
                <n-input
                  v-model:value="keyword"
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
                <div v-for="item in visibleItems" :key="item.key" class="pub-item">
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
                    <a
                      v-if="item.abstractNote.length > 200"
                      class="toggle"
                      @click="expanded[item.key] = !expanded[item.key]"
                    >{{ expanded[item.key] ? '收起' : '展开' }}</a>
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
                      {{ openedPdf[item.key] === att.key ? '收起' : attachmentLabel(att) }}
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

                  <div v-if="openedPdf[item.key]" class="pdf-frame">
                    <iframe
                      v-if="!openedMd[item.key]"
                      :src="`/api/zotero/file/${openedPdf[item.key]}#toolbar=1&navpanes=0`"
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

              <div v-if="hasMore" class="load-more">
                <n-button @click="loadMore" size="medium" type="primary" ghost>
                  加载更多（剩余 {{ filtered.length - visibleItems.length }} 条）
                </n-button>
              </div>
            </main>
          </div>
        </n-spin>
      </div>
    </section>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onBeforeUnmount, watch, reactive } from 'vue'
import { useMessage } from 'naive-ui'
import {
  SearchOutline,
  FolderOutline,
  DocumentTextOutline,
  DownloadOutline
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

const PAGE_SIZE = 20
const pageCount = ref(1)
const expanded = reactive({})
const openedPdf = reactive({})
const openedMd = reactive({})

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

const visibleItems = computed(() => filtered.value.slice(0, pageCount.value * PAGE_SIZE))

const hasMore = computed(() => visibleItems.value.length < filtered.value.length)

watch([keyword, typeFilter, selectedKey], () => {
  pageCount.value = 1
})

function loadMore() {
  pageCount.value += 1
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

function pdfAttachments(item) {
  return (item.attachments || []).filter(a => a.isPdf)
}

function isMarkdown(att) {
  const fn = att.filename || att.title || ''
  return /\.(md|markdown)$/i.test(fn)
}

async function togglePdf(itemKey, att) {
  if (openedPdf[itemKey] === att.key) {
    delete openedPdf[itemKey]
    delete openedMd[itemKey]
    return
  }
  openedPdf[itemKey] = att.key
  delete openedMd[itemKey]

  if (isMarkdown(att)) {
    try {
      const res = await fetch(`/api/zotero/file/${att.key}`)
      const text = await res.text()
      const html = marked.parse(text, { breaks: true, gfm: true })
      openedMd[itemKey] = DOMPurify.sanitize(html)
    } catch (e) {
      openedMd[itemKey] = `<p style="color:#d03050">加载失败：${e.message}</p>`
    }
  }
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

function onScroll() {
  if (!hasMore.value || loading.value) return
  const scrollBottom = window.innerHeight + window.scrollY
  if (scrollBottom >= document.body.offsetHeight - 400) {
    pageCount.value += 1
  }
}

onMounted(() => {
  load()
  window.addEventListener('scroll', onScroll, { passive: true })
})

onBeforeUnmount(() => {
  window.removeEventListener('scroll', onScroll)
})
</script>

<style scoped>
.publications-page {
  padding-top: 80px;
  min-height: 100vh;
  background: #f7f7f9;
}

.section { padding: 60px 0; }

.container {
  max-width: 1280px;
  margin: 0 auto;
  padding: 0 24px;
}

.page-header { margin-bottom: 24px; }
.page-header h2 { font-size: 32px; margin: 0 0 6px; }
.page-header p { color: #888; margin: 0; }

.layout {
  display: grid;
  grid-template-columns: 240px 1fr;
  gap: 24px;
  align-items: start;
}

.sidebar {
  background: #fff;
  border-radius: 12px;
  padding: 16px;
  box-shadow: 0 2px 12px rgba(0, 0, 0, 0.04);
  position: sticky;
  top: 100px;
  max-height: calc(100vh - 120px);
  overflow-y: auto;
}

.sidebar h3 { margin: 0 0 12px; font-size: 15px; color: #555; }

.coll-list { display: flex; flex-direction: column; }

.coll-item {
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
.coll-item.active { background: #e6f0ff; color: #1677ff; font-weight: 500; }

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

.coll-item.active .count { background: #cfe1ff; color: #1677ff; }

.content {
  background: transparent;
}

.filter-bar {
  display: flex;
  gap: 12px;
  margin-bottom: 16px;
  flex-wrap: wrap;
  align-items: center;
  background: #fff;
  padding: 12px 16px;
  border-radius: 12px;
  box-shadow: 0 2px 12px rgba(0, 0, 0, 0.04);
}

.empty { padding: 48px 0; }

.pub-list {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.pub-item {
  background: #fff;
  border-radius: 12px;
  padding: 20px 24px;
  box-shadow: 0 2px 12px rgba(0, 0, 0, 0.04);
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
  color: #1677ff;
  background: #e6f0ff;
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

.doi-link { color: #1677ff; text-decoration: none; }

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

.load-more {
  display: flex;
  justify-content: center;
  margin: 32px 0;
}
</style>

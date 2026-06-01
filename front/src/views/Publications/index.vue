<template>
  <div class="publications-page">
    <section class="section">
      <div class="container">
        <div class="page-header">
          <h2>文献库（自建 / 分支 B）</h2>
          <p>左侧 Zotero collection 树 · 右侧文献卡片 · 共 {{ total }} 条</p>
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
                <n-button @click="load" :loading="loading">刷新</n-button>
                <span class="result-count">{{ filtered.length }} 条结果</span>
              </div>

              <div v-if="filtered.length === 0 && !loading" class="empty">
                <n-empty description="该分组下没有匹配的文献" />
              </div>

              <div v-else class="pub-list">
                <div v-for="item in filtered" :key="item.key" class="pub-item">
                  <div class="pub-type">{{ typeLabel(item.itemType) }}</div>
                  <h3 class="pub-title">
                    <a v-if="item.url" :href="item.url" target="_blank" rel="noopener">{{ item.title || '(无标题)' }}</a>
                    <span v-else>{{ item.title || '(无标题)' }}</span>
                  </h3>
                  <div class="pub-meta">
                    <span v-if="formatCreators(item.creators)">{{ formatCreators(item.creators) }}</span>
                    <span v-if="item.publicationTitle"> · {{ item.publicationTitle }}</span>
                    <span v-if="item.date"> · {{ item.date }}</span>
                  </div>
                  <p v-if="item.abstractNote" class="pub-abstract">{{ truncate(item.abstractNote, 280) }}</p>
                  <div class="pub-foot">
                    <a v-if="item.DOI" :href="`https://doi.org/${item.DOI}`" target="_blank" rel="noopener">DOI: {{ item.DOI }}</a>
                    <span v-if="itemCollections(item).length" class="item-colls">
                      <n-icon><FolderOutline /></n-icon>
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
                </div>
              </div>
            </main>
          </div>
        </n-spin>
      </div>
    </section>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { SearchOutline, FolderOutline } from '@vicons/ionicons5'
import { getZoteroCollections } from '@/api'

const items = ref([])
const collectionsRaw = ref([])
const loading = ref(false)
const error = ref('')
const keyword = ref('')
const typeFilter = ref(null)
const treeFilter = ref('')
const selectedKey = ref(null)

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
  document: '文档'
}

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

const filtered = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  return items.value.filter(item => {
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
})

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

function truncate(s, n) {
  if (!s) return ''
  return s.length > n ? s.slice(0, n) + '…' : s
}

function itemCollections(item) {
  return (item.collections || []).map(k => collectionMap.value[k]?.name).filter(Boolean)
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const [itemsRes, collRes] = await Promise.all([
      fetch('/api/zotero/items?limit=200').then(r => r.json()),
      getZoteroCollections()
    ])
    if (itemsRes.code === 200) {
      items.value = (itemsRes.data || []).filter(i => i.itemType !== 'attachment' && i.itemType !== 'note')
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

onMounted(load)
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
.page-header h2 { font-size: 28px; margin: 0 0 6px; }
.page-header p { color: #888; margin: 0; }

.layout {
  display: grid;
  grid-template-columns: 280px 1fr;
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
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
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

.content { min-width: 0; }

.filter-bar {
  display: flex;
  gap: 12px;
  margin-bottom: 20px;
  flex-wrap: wrap;
  align-items: center;
}

.result-count {
  color: #999;
  font-size: 13px;
  margin-left: auto;
}

.empty { padding: 48px 0; }

.pub-list { display: flex; flex-direction: column; gap: 16px; }

.pub-item {
  background: #fff;
  border-radius: 12px;
  padding: 22px 26px;
  box-shadow: 0 2px 12px rgba(0, 0, 0, 0.04);
  transition: box-shadow 0.2s, transform 0.2s;
}

.pub-item:hover {
  box-shadow: 0 6px 24px rgba(0, 0, 0, 0.08);
  transform: translateY(-2px);
}

.pub-type {
  display: inline-block;
  font-size: 12px;
  color: #1677ff;
  background: #e6f0ff;
  padding: 2px 10px;
  border-radius: 4px;
  margin-bottom: 8px;
}

.pub-title { font-size: 17px; margin: 0 0 8px; line-height: 1.5; }
.pub-title a { color: #1f1f1f; text-decoration: none; }
.pub-title a:hover { color: #1677ff; }

.pub-meta { color: #666; font-size: 14px; margin-bottom: 10px; }

.pub-abstract {
  color: #555;
  font-size: 14px;
  line-height: 1.7;
  margin: 8px 0 12px;
}

.pub-foot {
  display: flex;
  gap: 12px;
  align-items: center;
  flex-wrap: wrap;
  font-size: 13px;
  color: #888;
}

.pub-foot a { color: #1677ff; text-decoration: none; }

.item-colls {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  color: #888;
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
</style>

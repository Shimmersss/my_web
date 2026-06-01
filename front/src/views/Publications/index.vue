<template>
  <div class="publications-page">
    <section class="section">
      <div class="container">
        <div class="page-header">
          <h2>文献库</h2>
          <p>同步自 Zotero · 共 {{ items.length }} 条</p>
        </div>

        <div class="filter-bar">
          <n-input
            v-model:value="keyword"
            placeholder="搜索标题 / 作者 / 期刊..."
            size="large"
            clearable
            style="max-width: 480px"
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
            style="width: 200px"
          />
          <n-button @click="load" :loading="loading">刷新</n-button>
        </div>

        <n-spin :show="loading">
          <div v-if="error" class="empty">
            <n-alert type="error" :title="'拉取失败'">{{ error }}</n-alert>
          </div>

          <div v-else-if="filtered.length === 0 && !loading" class="empty">
            <n-empty description="还没有匹配的文献" />
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
                <span v-if="item.tags && item.tags.length" class="tags">
                  <span v-for="t in item.tags" :key="t.tag" class="tag">{{ t.tag }}</span>
                </span>
              </div>
            </div>
          </div>
        </n-spin>
      </div>
    </section>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { SearchOutline } from '@vicons/ionicons5'
import { getZoteroItems } from '@/api'

const items = ref([])
const loading = ref(false)
const error = ref('')
const keyword = ref('')
const typeFilter = ref(null)

const typeMap = {
  journalArticle: '期刊论文',
  conferencePaper: '会议论文',
  book: '书籍',
  bookSection: '书章节',
  thesis: '学位论文',
  preprint: '预印本',
  webpage: '网页',
  report: '报告',
  attachment: '附件',
  note: '笔记'
}

const typeOptions = computed(() => {
  const set = new Set(items.value.map(i => i.itemType).filter(Boolean))
  return Array.from(set).map(t => ({ label: typeLabel(t), value: t }))
})

const filtered = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  return items.value.filter(item => {
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

async function load() {
  loading.value = true
  error.value = ''
  try {
    const res = await getZoteroItems(100)
    if (res.code === 200) {
      items.value = res.data || []
    } else {
      error.value = res.message || '请求失败'
    }
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

.section {
  padding: 60px 0;
}

.container {
  max-width: 1100px;
  margin: 0 auto;
  padding: 0 24px;
}

.page-header {
  text-align: center;
  margin-bottom: 36px;
}

.page-header h2 {
  font-size: 36px;
  margin: 0 0 8px;
}

.page-header p {
  color: #888;
  margin: 0;
}

.filter-bar {
  display: flex;
  gap: 16px;
  margin-bottom: 32px;
  flex-wrap: wrap;
  align-items: center;
}

.empty {
  padding: 48px 0;
}

.pub-list {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.pub-item {
  background: #fff;
  border-radius: 12px;
  padding: 24px 28px;
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

.pub-title {
  font-size: 18px;
  margin: 0 0 8px;
  line-height: 1.5;
}

.pub-title a {
  color: #1f1f1f;
  text-decoration: none;
}

.pub-title a:hover {
  color: #1677ff;
}

.pub-meta {
  color: #666;
  font-size: 14px;
  margin-bottom: 10px;
}

.pub-abstract {
  color: #555;
  font-size: 14px;
  line-height: 1.7;
  margin: 8px 0 12px;
}

.pub-foot {
  display: flex;
  gap: 16px;
  align-items: center;
  flex-wrap: wrap;
  font-size: 13px;
  color: #888;
}

.pub-foot a {
  color: #1677ff;
  text-decoration: none;
}

.tags {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
}

.tag {
  background: #f0f0f0;
  padding: 2px 8px;
  border-radius: 4px;
  font-size: 12px;
}
</style>

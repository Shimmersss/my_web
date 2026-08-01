<template>
  <div class="open-source-page">
    <section class="section">
      <div class="container">
        <div class="page-head">
          <div>
            <p class="eyebrow">GitHub Open Source</p>
            <h1>GitHub 项目开源</h1>
            <p>展示指定项目，并自动同步全 GitHub 新项目周榜/月榜与 AI 项目总结。</p>
          </div>
          <div class="page-actions">
            <n-button
              tag="a"
              href="https://github.com"
              target="_blank"
              rel="noopener"
              type="primary"
              size="large"
            >
              <template #icon><n-icon><LogoGithub /></n-icon></template>
              GitHub
            </n-button>
            <n-button size="large" secondary @click="adminVisible = true">
              <template #icon><n-icon><SettingsOutline /></n-icon></template>
              后台管理
            </n-button>
          </div>
        </div>

        <div class="toolbar">
          <n-input
            v-model:value="searchText"
            placeholder="搜索项目 / 描述 / 技术栈..."
            size="large"
            clearable
          >
            <template #prefix>
              <n-icon><SearchOutline /></n-icon>
            </template>
          </n-input>
          <n-select
            v-model:value="languageFilter"
            :options="languageOptions"
            placeholder="语言"
            clearable
            size="large"
            class="language-select"
          />
          <n-button size="large" :loading="loading" @click="loadProjects">
            <template #icon><n-icon><RefreshOutline /></n-icon></template>
            刷新
          </n-button>
        </div>

        <section class="ranking-panel" aria-labelledby="ranking-title">
          <div class="ranking-head">
            <div>
              <p class="ranking-eyebrow">Global GitHub Radar</p>
              <h2 id="ranking-title">全 GitHub Stars 排行榜</h2>
              <p>按本周 / 本月新建的公开仓库统计，记录当前周期 stars 最高项目。</p>
            </div>
            <div class="ranking-actions">
              <span v-if="rankingData.updatedAt" class="ranking-updated">
                更新于 {{ formatDateTime(rankingData.updatedAt) }}
              </span>
              <n-button secondary :loading="rankingLoading" @click="loadRankings(true)">
                <template #icon><n-icon><RefreshOutline /></n-icon></template>
                更新榜单
              </n-button>
            </div>
          </div>

          <div class="ranking-tabs" role="tablist" aria-label="排行榜周期">
            <button
              type="button"
              class="ranking-tab"
              :class="{ active: rankingPeriod === 'weekly' }"
              role="tab"
              :aria-selected="rankingPeriod === 'weekly'"
              @click="rankingPeriod = 'weekly'"
            >
              <strong>全周榜</strong>
              <span>{{ rankingData.weekly?.periodStart || '等待同步' }}</span>
            </button>
            <button
              type="button"
              class="ranking-tab"
              :class="{ active: rankingPeriod === 'monthly' }"
              role="tab"
              :aria-selected="rankingPeriod === 'monthly'"
              @click="rankingPeriod = 'monthly'"
            >
              <strong>本月榜</strong>
              <span>{{ rankingData.monthly?.periodStart || '等待同步' }}</span>
            </button>
          </div>

          <n-spin :show="rankingLoading && !currentRanking.projects?.length">
            <div v-if="currentRanking.projects?.length" class="ranking-grid">
              <article v-for="project in currentRanking.projects" :key="project.full_name" class="ranking-card">
                <div class="ranking-card-head">
                  <span class="ranking-rank">#{{ project.rank }}</span>
                  <a :href="project.html_url" target="_blank" rel="noopener" class="ranking-repo">
                    {{ project.full_name }}
                  </a>
                  <span class="ranking-stars"><n-icon><StarOutline /></n-icon>{{ formatNumber(project.stargazers_count) }}</span>
                </div>
                <p class="ranking-description">{{ project.aiSummary || project.description || '暂无项目简介' }}</p>
                <div class="ranking-meta">
                  <span>{{ project.language || 'Unknown' }}</span>
                  <span>创建于 {{ formatDate(project.created_at) }}</span>
                </div>
                <div v-if="project.topics?.length" class="ranking-topics">
                  <span v-for="topic in project.topics.slice(0, 5)" :key="topic">{{ topic }}</span>
                </div>
              </article>
            </div>
            <n-empty v-else description="榜单正在同步 GitHub 数据" class="ranking-empty" />
          </n-spin>
        </section>

        <section v-if="projects.length" class="recommendation-section" aria-labelledby="recommendation-title">
          <div class="recommendation-head">
            <div>
              <p class="recommendation-eyebrow">Curated by webmaster</p>
              <h2 id="recommendation-title">站长推荐</h2>
              <p>仅展示后台手动添加的项目。</p>
            </div>
          </div>
          <n-spin :show="loading">
            <div class="project-grid">
              <article
                v-for="project in filteredProjects"
                :key="project.full_name"
                class="project-card"
              >
                <div class="card-top">
                  <div class="repo-mark">
                    <n-icon size="26"><LogoGithub /></n-icon>
                  </div>
                  <div class="repo-title">
                    <p>{{ project.owner }}</p>
                    <h3 @click="openReadme(project)">{{ project.name }}</h3>
                  </div>
                  <span class="category">{{ project.category }}</span>
                </div>

                <p class="description">{{ project.description || project.highlight }}</p>
                <p v-if="project.highlight" class="highlight">{{ project.highlight }}</p>

                <div class="metrics">
                  <span><n-icon><StarOutline /></n-icon>{{ formatNumber(project.stargazers_count) }}</span>
                  <span><n-icon><GitBranchOutline /></n-icon>{{ formatNumber(project.forks_count) }}</span>
                  <span><n-icon><CodeSlashOutline /></n-icon>{{ project.language || 'Unknown' }}</span>
                  <span><n-icon><TimeOutline /></n-icon>{{ formatDate(project.pushed_at) }}</span>
                </div>

                <div v-if="project.topics?.length" class="topics">
                  <span v-for="topic in project.topics.slice(0, 6)" :key="topic">{{ topic }}</span>
                </div>

                <div class="card-actions">
                  <n-button @click="openReadme(project)" :loading="readmeLoading && readmeProject?.full_name === project.full_name">
                    <template #icon><n-icon><DocumentTextOutline /></n-icon></template>
                    README
                  </n-button>
                  <n-button
                    tag="a"
                    :href="project.html_url"
                    target="_blank"
                    rel="noopener"
                    type="primary"
                  >
                    <template #icon><n-icon><LogoGithub /></n-icon></template>
                    仓库
                  </n-button>
                  <n-button
                    v-if="project.homepage"
                    tag="a"
                    :href="project.homepage"
                    target="_blank"
                    rel="noopener"
                  >
                    <template #icon><n-icon><OpenOutline /></n-icon></template>
                    Demo
                  </n-button>
                </div>
              </article>
            </div>

            <n-empty
              v-if="filteredProjects.length === 0 && !loading"
              description="没有匹配的站长推荐项目"
              class="empty"
            />
          </n-spin>
        </section>

        <n-modal v-model:show="adminVisible" preset="card" title="开源项目后台管理" class="admin-modal">
          <div v-if="!isAdmin" class="login-panel">
            <n-input
              v-model:value="adminKeyInput"
              type="password"
              show-password-on="click"
              placeholder="请输入管理员密钥"
              @keyup.enter="loginAdmin"
            />
            <n-button type="primary" :loading="adminLoading" @click="loginAdmin">进入后台</n-button>
          </div>

          <div v-else class="admin-panel">
            <div class="admin-actions">
              <n-button type="primary" @click="addProject">
                <template #icon><n-icon><AddOutline /></n-icon></template>
                添加项目
              </n-button>
              <n-button :loading="adminLoading" @click="saveAdminProjects">保存展示列表</n-button>
              <n-button text @click="logoutAdmin">退出后台</n-button>
            </div>

            <div class="admin-list">
              <div v-for="(project, index) in editableProjects" :key="index" class="admin-row">
                <label>
                  <span>GitHub 仓库地址</span>
                  <n-input v-model:value="project.repo" placeholder="https://github.com/vuejs/core 或 vuejs/core" />
                </label>
                <label>
                  <span>分类</span>
                  <n-input v-model:value="project.category" placeholder="例如 Frontend / Tooling / Backend" />
                </label>
                <label>
                  <span>展示说明</span>
                  <n-input v-model:value="project.highlight" type="textarea" placeholder="写一句你想展示在卡片上的说明" :autosize="{ minRows: 2, maxRows: 4 }" />
                </label>
                <div class="row-foot">
                  <n-checkbox v-model:checked="project.featured">首页展示</n-checkbox>
                  <div class="row-actions">
                    <n-button size="small" :disabled="index === 0" @click="moveProject(index, -1)">上移</n-button>
                    <n-button size="small" :disabled="index === editableProjects.length - 1" @click="moveProject(index, 1)">下移</n-button>
                    <n-button size="small" quaternary type="error" @click="removeProject(index)">删除</n-button>
                  </div>
                </div>
              </div>
            </div>
          </div>
        </n-modal>

        <n-modal v-model:show="readmeVisible" preset="card" :title="readmeTitle" class="readme-modal">
          <n-spin :show="readmeLoading">
            <n-alert v-if="readmeError" type="warning" title="README 读取失败" class="status-alert">
              {{ readmeError }}
            </n-alert>
            <div v-else class="readme-render markdown-body" v-html="readmeHtml" />
          </n-spin>
        </n-modal>
      </div>
    </section>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useMessage } from 'naive-ui'
import {
  NAlert,
  NButton,
  NCheckbox,
  NEmpty,
  NIcon,
  NInput,
  NModal,
  NSelect,
  NSpin
} from 'naive-ui'
import {
  AddOutline,
  CodeSlashOutline,
  GitBranchOutline,
  LogoGithub,
  OpenOutline,
  RefreshOutline,
  SearchOutline,
  SettingsOutline,
  StarOutline,
  TimeOutline,
  DocumentTextOutline
} from '@vicons/ionicons5'
import { marked } from 'marked'
import DOMPurify from 'dompurify'
import { getGithubProjectReadme, getGithubProjects, getGithubRankings, loginGithubProjectsAdmin, saveGithubProjects } from '@/api'
import { defaultGithubProjects, githubProjectFallback } from '@/config/githubProjects'

const message = useMessage()
const loading = ref(false)
const configLoading = ref(false)
const error = ref('')
const searchText = ref('')
const languageFilter = ref(null)
const projectConfigs = ref(defaultGithubProjects)
const projects = ref(buildFallbackProjects())
const adminVisible = ref(false)
const adminLoading = ref(false)
const adminKeyInput = ref('')
const adminKey = ref(localStorage.getItem('githubProjectsAdminKey') || '')
const editableProjects = ref([])
const readmeVisible = ref(false)
const readmeLoading = ref(false)
const readmeError = ref('')
const readmeHtml = ref('')
const readmeProject = ref(null)
const rankingData = ref({})
const rankingPeriod = ref('weekly')
const rankingLoading = ref(false)
let rankingRefreshTimer = null
let rankingWarmupTimer = null

const currentRanking = computed(() => rankingData.value[rankingPeriod.value] || { projects: [] })

const isAdmin = computed(() => Boolean(adminKey.value))
const readmeTitle = computed(() => readmeProject.value ? `${readmeProject.value.full_name} README` : 'README')

const languageOptions = computed(() => {
  const languages = new Set(projects.value.map(project => project.language).filter(Boolean))
  return Array.from(languages)
    .sort((a, b) => a.localeCompare(b))
    .map(language => ({ label: language, value: language }))
})

const filteredProjects = computed(() => {
  const kw = searchText.value.trim().toLowerCase()
  return projects.value.filter(project => {
    if (languageFilter.value && project.language !== languageFilter.value) return false
    if (!kw) return true
    const haystack = [
      project.full_name,
      project.description,
      project.highlight,
      project.language,
      project.category,
      ...(project.topics || [])
    ].filter(Boolean).join(' ').toLowerCase()
    return haystack.includes(kw)
  })
})

function buildFallbackProjects() {
  return projectConfigs.value.map(item => {
    const repoName = normalizeRepoInput(item.repo)
    const config = { ...item, repo: repoName }
    return normalizeProject({
      ...githubProjectFallback,
      ...config,
      full_name: repoName,
      html_url: `https://github.com/${repoName}`
    }, config)
  })
}

function normalizeProject(repo, config) {
  const repoName = normalizeRepoInput(config.repo || repo.full_name || '')
  const [owner, name] = repoName.split('/')
  return {
    ...repo,
    ...config,
    owner,
    name,
    repo: repoName,
    full_name: repoName || repo.full_name,
    description: repo.description || config.highlight,
    html_url: repo.html_url || `https://github.com/${repoName}`,
    topics: Array.isArray(repo.topics) ? repo.topics : [],
    language: repo.language || githubProjectFallback.language
  }
}

function normalizeRepoInput(value) {
  return String(value || '')
    .trim()
    .replace(/^https?:\/\/github\.com\//i, '')
    .replace(/^git@github\.com:/i, '')
    .replace(/\.git$/i, '')
    .replace(/[?#].*$/, '')
    .split('/')
    .slice(0, 2)
    .join('/')
}

async function loadProjects() {
  loading.value = true
  error.value = ''
  try {
    await loadProjectConfigs()
    projects.value = projectConfigs.value.map(item => normalizeProject(item, item))
  } catch (e) {
    projects.value = buildFallbackProjects()
  } finally {
    loading.value = false
  }
}

async function openReadme(project) {
  readmeProject.value = project
  readmeVisible.value = true
  readmeLoading.value = true
  readmeError.value = ''
  readmeHtml.value = ''
  try {
    const markdown = await getGithubProjectReadme(project.full_name)
    const html = marked.parse(markdown, { breaks: true, gfm: true })
    readmeHtml.value = DOMPurify.sanitize(html)
  } catch (e) {
    readmeError.value = e.message || '无法读取 README.md'
  } finally {
    readmeLoading.value = false
  }
}

async function loadProjectConfigs() {
  if (configLoading.value) return
  configLoading.value = true
  try {
    const res = await getGithubProjects()
    if (res.code === 200 && Array.isArray(res.data)) {
      projectConfigs.value = res.data
    }
  } catch (e) {
    projectConfigs.value = defaultGithubProjects
  } finally {
    configLoading.value = false
  }
}

async function loadRankings(refresh = false) {
  rankingLoading.value = true
  try {
    const res = await getGithubRankings(refresh)
    if (res.code === 200 && res.data) rankingData.value = res.data
  } catch (e) {
    // 榜单失败不影响指定项目展示，下一次定时刷新继续尝试。
    console.warn('GitHub 排行榜读取失败:', e)
  } finally {
    rankingLoading.value = false
  }
  if (refresh) {
    if (rankingWarmupTimer) window.clearTimeout(rankingWarmupTimer)
    rankingWarmupTimer = window.setTimeout(() => loadRankings(), 2500)
  }
}

async function loginAdmin() {
  adminLoading.value = true
  try {
    const res = await loginGithubProjectsAdmin(adminKeyInput.value)
    if (res.code !== 200) throw new Error(res.message || '管理员密钥错误')
    adminKey.value = res.data.token
    localStorage.setItem('githubProjectsAdminKey', adminKey.value)
    editableProjects.value = projectConfigs.value.map(item => ({ ...item }))
    message.success('已进入后台模式')
  } catch (e) {
    message.error(e.message || '登录失败')
  } finally {
    adminLoading.value = false
  }
}

function logoutAdmin() {
  adminKey.value = ''
  adminKeyInput.value = ''
  localStorage.removeItem('githubProjectsAdminKey')
}

function addProject() {
  editableProjects.value.push({
    repo: 'https://github.com/',
    highlight: '',
    category: 'Open Source',
    featured: true
  })
}

function removeProject(index) {
  editableProjects.value.splice(index, 1)
}

function moveProject(index, direction) {
  const nextIndex = index + direction
  if (nextIndex < 0 || nextIndex >= editableProjects.value.length) return
  const next = [...editableProjects.value]
  const current = next[index]
  next[index] = next[nextIndex]
  next[nextIndex] = current
  editableProjects.value = next
}

async function saveAdminProjects() {
  adminLoading.value = true
  try {
    const payload = editableProjects.value.map(item => ({
      repo: normalizeRepoInput(item.repo),
      highlight: item.highlight,
      category: item.category,
      featured: Boolean(item.featured)
    })).filter(item => item.repo)
    const res = await saveGithubProjects(payload, adminKey.value)
    if (res.code !== 200) throw new Error(res.message || '保存失败')
    projectConfigs.value = res.data
    editableProjects.value = res.data.map(item => ({ ...item }))
    message.success('开源项目展示列表已保存')
    await loadProjects()
  } catch (e) {
    message.error(e.message || '保存失败')
  } finally {
    adminLoading.value = false
  }
}

function formatNumber(value) {
  const n = Number(value || 0)
  if (n >= 1000) return `${(n / 1000).toFixed(n >= 10000 ? 0 : 1)}k`
  return String(n)
}

function formatDate(value) {
  if (!value) return '未同步'
  return new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit'
  }).format(new Date(value))
}

function formatDateTime(value) {
  if (!value) return '未同步'
  return new Intl.DateTimeFormat('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit'
  }).format(new Date(value))
}

onMounted(async () => {
  await Promise.all([loadProjects(), loadRankings()])
  editableProjects.value = projectConfigs.value.map(item => ({ ...item }))
  rankingRefreshTimer = window.setInterval(() => loadRankings(), 10 * 60 * 1000)
  document.addEventListener('visibilitychange', handleRankingVisibility)
})

function handleRankingVisibility() {
  if (document.visibilityState === 'visible') loadRankings()
}

onBeforeUnmount(() => {
  if (rankingRefreshTimer) window.clearInterval(rankingRefreshTimer)
  if (rankingWarmupTimer) window.clearTimeout(rankingWarmupTimer)
  document.removeEventListener('visibilitychange', handleRankingVisibility)
})
</script>

<style scoped lang="scss">
@use '@/assets/styles/variables' as *;

.open-source-page {
  min-height: 100vh;
  background: #eee9df;
  padding: 32px 0 56px;
}

.page-head {
  display: flex;
  justify-content: space-between;
  gap: $spacing-lg;
  align-items: flex-end;
  margin-bottom: $spacing-xl;

  h1 {
    font-family: Georgia, 'Noto Serif SC', 'Songti SC', serif;
    font-size: 40px;
    margin: 4px 0 8px;
    color: $text-color;
  }

  p {
    margin: 0;
    color: $text-color-secondary;
    line-height: 1.7;
  }

  .eyebrow {
    color: #b83126;
    font-weight: 700;
    letter-spacing: 0.12em;
    font-size: 12px;
    text-transform: uppercase;
  }
}

.page-actions {
  display: flex;
  gap: $spacing-sm;
  flex-wrap: wrap;
  justify-content: flex-end;
}

.toolbar {
  display: grid;
  grid-template-columns: minmax(240px, 1fr) 180px auto;
  gap: $spacing-md;
  align-items: center;
  background: #fbf9f3;
  border: 1px solid #d2cabc;
  border-radius: 2px;
  padding: $spacing-md;
  margin-bottom: $spacing-lg;
}

.status-alert {
  margin-bottom: $spacing-lg;
}

.ranking-panel {
  background: #373730;
  color: #f8f4ea;
  border: 1px solid #373730;
  border-radius: 2px;
  padding: $spacing-lg;
  margin-bottom: $spacing-xl;
  box-shadow: $shadow-lg;
}

.ranking-head {
  display: flex;
  justify-content: space-between;
  gap: $spacing-lg;
  align-items: flex-end;
  margin-bottom: $spacing-lg;

  h2 {
    margin: 4px 0 8px;
    font-family: Georgia, 'Noto Serif SC', 'Songti SC', serif;
    font-size: 28px;
  }

  p {
    margin: 0;
    color: #c6c1b6;
    line-height: 1.6;
  }
}

.ranking-eyebrow {
  color: #edb35a !important;
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.12em;
  text-transform: uppercase;
}

.ranking-actions {
  display: flex;
  align-items: center;
  gap: $spacing-md;
  flex-wrap: wrap;
  justify-content: flex-end;
}

.ranking-updated {
  color: #c6c1b6;
  font-size: 13px;
}

.ranking-tabs {
  display: flex;
  gap: 8px;
  margin-bottom: $spacing-lg;
}

.ranking-tab {
  display: grid;
  gap: 3px;
  min-width: 130px;
  padding: 10px 14px;
  border: 1px solid #68675d;
  border-radius: 2px;
  color: #c6c1b6;
  background: transparent;
  text-align: left;
  cursor: pointer;

  strong {
    color: inherit;
    font-size: 15px;
  }

  span {
    font-size: 12px;
  }

  &.active {
    color: #373730;
    background: #edb35a;
    border-color: #edb35a;
  }
}

.ranking-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
}

.recommendation-section {
  margin-bottom: $spacing-xl;

  .recommendation-head {
    display: flex;
    justify-content: space-between;
    align-items: flex-end;
    gap: $spacing-lg;
    margin-bottom: $spacing-lg;

    h2 {
      margin: 4px 0 6px;
      font-family: Georgia, 'Noto Serif SC', 'Songti SC', serif;
      font-size: 28px;
      color: $text-color;
    }

    p:last-child {
      margin: 0;
      color: $text-color-secondary;
      line-height: 1.6;
    }
  }
}

.recommendation-eyebrow {
  color: #b83126;
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.12em;
  text-transform: uppercase;
}

.ranking-card {
  min-width: 0;
  padding: 15px;
  border: 1px solid #57574d;
  background: #43433b;
}

.ranking-card-head {
  display: flex;
  align-items: center;
  gap: 9px;
  min-width: 0;
}

.ranking-rank {
  color: #edb35a;
  font-family: Georgia, serif;
  font-size: 18px;
  font-weight: 700;
}

.ranking-repo {
  min-width: 0;
  flex: 1;
  overflow-wrap: anywhere;
  color: #fffaf0;
  font-weight: 700;
  text-decoration: none;

  &:hover {
    color: #edb35a;
  }
}

.ranking-stars {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  flex-shrink: 0;
  color: #edb35a;
  font-size: 13px;
}

.ranking-description {
  min-height: 48px;
  margin: 12px 0 10px;
  color: #e4dfd3;
  font-size: 14px;
  line-height: 1.6;
}

.ranking-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  color: #b6b3a9;
  font-size: 12px;
}

.ranking-topics {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 10px;

  span {
    padding: 2px 7px;
    border: 1px solid #68675d;
    color: #c6d7ca;
    font-size: 11px;
  }
}

.ranking-empty {
  padding: 36px 0;
}

.project-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: $spacing-lg;
}

.project-card {
  min-height: 360px;
  display: flex;
  flex-direction: column;
  background: #fbf9f3;
  border: 1px solid #d2cabc;
  border-radius: 2px;
  padding: $spacing-lg;
  box-shadow: $shadow-sm;
  transition: transform $transition-base, box-shadow $transition-base, border-color $transition-base;

  &:hover {
    transform: translateY(-3px);
    border-color: #b83126;
    box-shadow: $shadow-lg;
  }
}

.card-top {
  display: flex;
  gap: $spacing-md;
  align-items: center;
  margin-bottom: $spacing-md;
}

.repo-mark {
  width: 48px;
  height: 48px;
  border-radius: 2px;
  display: grid;
  place-items: center;
  color: #fff;
  background: #373730;
  flex-shrink: 0;
}

.repo-title {
  min-width: 0;
  flex: 1;

  p {
    margin: 0 0 2px;
    color: $text-color-light;
    font-size: 13px;
  }

  h3 {
    margin: 0;
    font-size: 22px;
    color: $text-color;
    overflow-wrap: anywhere;
    cursor: pointer;

    &:hover {
      color: $primary-color;
    }
  }
}

.category {
  flex-shrink: 0;
  background: #f0e5d9;
  color: $primary-color;
  border-radius: 4px;
  padding: 3px 9px;
  font-size: 12px;
  font-weight: 600;
}

.description {
  margin: 0 0 $spacing-sm;
  color: $text-color;
  line-height: 1.65;
}

.highlight {
  margin: 0 0 $spacing-md;
  color: $text-color-secondary;
  line-height: 1.65;
  font-size: 14px;
}

.metrics {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: $spacing-sm;
  margin: auto 0 $spacing-md;

  span {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    min-width: 0;
    color: $text-color-secondary;
    font-size: 14px;
  }
}

.topics {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: $spacing-lg;

  span {
    color: #476582;
    background: #edf0e8;
    border: 1px solid #c6ccbf;
    border-radius: 2px;
    padding: 3px 8px;
    font-size: 12px;
  }
}

.card-actions {
  display: flex;
  gap: $spacing-sm;
  margin-top: auto;
  flex-wrap: wrap;
}

.empty {
  padding: 80px 0;
}

.admin-modal {
  width: min(960px, calc(100vw - 32px));
}

.readme-modal {
  width: min(1040px, calc(100vw - 32px));
}

.readme-render {
  max-height: min(72vh, 760px);
  overflow: auto;
  padding: $spacing-lg;
  border: 1px solid #e6ebf2;
  border-radius: 8px;
  background: #fff;
  color: #1f2937;
  font-size: 15px;
  line-height: 1.75;

  :deep(h1),
  :deep(h2),
  :deep(h3),
  :deep(h4) {
    margin: 1.2em 0 0.6em;
    line-height: 1.3;
  }

  :deep(h1) {
    font-size: 1.8em;
    border-bottom: 1px solid #e5e7eb;
    padding-bottom: 0.3em;
  }

  :deep(h2) {
    font-size: 1.45em;
    border-bottom: 1px solid #e5e7eb;
    padding-bottom: 0.25em;
  }

  :deep(p) {
    margin: 0.7em 0;
  }

  :deep(a) {
    color: $primary-color;
    text-decoration: none;
  }

  :deep(a:hover) {
    text-decoration: underline;
  }

  :deep(pre) {
    overflow: auto;
    background: #0f172a;
    color: #e2e8f0;
    border-radius: 6px;
    padding: 14px 16px;
  }

  :deep(code) {
    background: #f1f5f9;
    border-radius: 4px;
    padding: 2px 5px;
    font-family: 'SF Mono', Consolas, monospace;
    font-size: 0.9em;
  }

  :deep(pre code) {
    background: transparent;
    color: inherit;
    padding: 0;
  }

  :deep(img) {
    max-width: 100%;
  }

  :deep(table) {
    display: block;
    max-width: 100%;
    overflow: auto;
    border-collapse: collapse;
  }

  :deep(th),
  :deep(td) {
    border: 1px solid #e5e7eb;
    padding: 6px 10px;
  }
}

.login-panel {
  display: grid;
  grid-template-columns: minmax(220px, 1fr) auto;
  gap: $spacing-md;
}

.admin-panel {
  display: grid;
  gap: $spacing-md;
}

.admin-actions {
  display: flex;
  gap: $spacing-sm;
  flex-wrap: wrap;
  align-items: center;
}

.admin-list {
  display: grid;
  gap: $spacing-sm;
}

.admin-row {
  display: grid;
  grid-template-columns: minmax(240px, 1.2fr) minmax(160px, 0.7fr);
  gap: $spacing-md;
  align-items: start;
  padding: $spacing-md;
  border: 1px solid #e6ebf2;
  border-radius: 8px;
  background: #fbfcfe;

  label {
    display: grid;
    gap: 6px;
    min-width: 0;

    span {
      font-size: 13px;
      color: $text-color-secondary;
      font-weight: 600;
    }

    &:nth-child(3) {
      grid-column: 1 / -1;
    }
  }
}

.row-foot {
  grid-column: 1 / -1;
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: $spacing-sm;
}

.row-actions {
  display: flex;
  gap: $spacing-sm;
  flex-wrap: wrap;
  justify-content: flex-end;
}

@media (max-width: 1100px) {
  .project-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 768px) {
  .open-source-page {
    overflow-x: hidden;
    padding: 22px 0 40px;
  }

  .page-head {
    display: block;

    .page-actions {
      margin-top: $spacing-md;
      justify-content: flex-start;
      flex-wrap: wrap;
    }

    h1 {
      font-size: 32px;
      line-height: 1.16;
      overflow-wrap: anywhere;
    }

    p {
      font-size: 14px;
    }
  }

  .toolbar {
    grid-template-columns: 1fr;
  }

  .ranking-head {
    display: block;

    .ranking-actions {
      margin-top: $spacing-md;
      justify-content: flex-start;
    }
  }

  .ranking-grid {
    grid-template-columns: 1fr;
  }

  .language-select {
    width: 100%;
  }

  .project-grid {
    grid-template-columns: 1fr;
  }

  .project-card {
    padding: $spacing-md;
  }

  .card-top {
    align-items: flex-start;
    flex-wrap: wrap;
  }

  .repo-title {
    flex: 1 1 180px;
  }

  .metrics {
    grid-template-columns: 1fr;
  }

  .card-actions :deep(.n-button) {
    flex: 1 1 120px;
  }

  .readme-render {
    padding: $spacing-md;
    font-size: 14px;
  }

  .login-panel,
  .admin-row {
    grid-template-columns: 1fr;
  }

  .admin-row label:nth-child(3),
  .row-foot {
    grid-column: auto;
  }

  .row-foot {
    align-items: flex-start;
    flex-direction: column;
  }

  .row-actions {
    justify-content: flex-start;
  }

  .ranking-panel {
    padding: $spacing-md;
  }

  .recommendation-section .recommendation-head {
    display: block;
  }

}

@media (max-width: 360px) {
  .page-head h1 {
    font-size: 28px;
  }

  .page-actions :deep(.n-button),
  .toolbar :deep(.n-button) {
    width: 100%;
  }

  .category {
    max-width: 100%;
    overflow-wrap: anywhere;
  }
}
</style>

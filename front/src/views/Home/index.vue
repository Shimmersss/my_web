<template>
  <div class="home-page">
    <section class="desk-hero">
      <div class="container desk-grid">
        <article class="paper-panel current-work">
          <p class="section-kicker">01 / 当前工作</p>
          <h1>把研究资料，整理成可以继续推进的工作。</h1>
          <p class="lead">集中管理文献、翻译论文、生成答辩 PPT，并随时回到你的开源项目。</p>

          <div class="topic-tags" aria-label="研究主题">
            <span>文献综述</span>
            <span>论文翻译</span>
            <span>学术表达</span>
          </div>

          <nav class="quick-actions" aria-label="核心工具快捷入口">
            <a v-for="tool in tools" :key="tool.path" :href="tool.path" @click.prevent="navigateTo(tool.path)">
              <n-icon size="25"><component :is="tool.icon" /></n-icon>
              <span><strong>{{ tool.title }}</strong><small>{{ tool.description }}</small></span>
            </a>
          </nav>
        </article>

        <aside class="paper-panel progress-panel">
          <p class="section-kicker">02 / 研究进度</p>
          <div v-for="item in progress" :key="item.label" class="progress-row">
            <div class="progress-head">
              <span>{{ item.label }}</span>
              <strong>{{ item.value }}</strong>
            </div>
            <div class="progress-track"><span :style="{ width: item.percent + '%' }"></span></div>
            <small>{{ item.note }}</small>
          </div>
          <div class="paper-note">本周目标：完成论文整理，并把核心结果汇总为可分享材料。</div>
        </aside>

        <article class="paper-panel recent-panel">
          <div class="panel-heading">
            <div>
              <p class="section-kicker">03 / 快速入口</p>
              <h2>研究工作流</h2>
            </div>
            <a href="/publications" @click.prevent="navigateTo('/publications')">打开文献库 →</a>
          </div>
          <div class="workflow-list">
            <a v-for="(tool, index) in tools" :key="tool.path" :href="tool.path" @click.prevent="navigateTo(tool.path)">
              <span class="row-index">{{ String(index + 1).padStart(2, '0') }}</span>
              <n-icon size="22"><component :is="tool.icon" /></n-icon>
              <span><strong>{{ tool.title }}</strong><small>{{ tool.description }}</small></span>
              <span class="row-arrow">→</span>
            </a>
          </div>
        </article>

        <article class="paper-panel github-panel">
          <div class="panel-heading">
            <div>
              <p class="section-kicker">04 / GitHub</p>
              <h2>我的开源项目</h2>
            </div>
            <a href="/news" @click.prevent="navigateTo('/news')">查看全部 →</a>
          </div>

          <div v-if="featuredProject" class="featured-repo">
            <div class="repo-title">
              <n-icon size="22"><LogoGithub /></n-icon>
              <div>
                <strong>{{ featuredProject.full_name }}</strong>
                <small>{{ featuredProject.category || 'Open Source' }}</small>
              </div>
            </div>
            <p>{{ featuredProject.highlight || featuredProject.description }}</p>
            <div class="repo-meta">
              <span>{{ featuredProject.language || 'Unknown' }}</span>
              <span>★ {{ formatNumber(featuredProject.stargazers_count) }}</span>
              <span>⑂ {{ formatNumber(featuredProject.forks_count) }}</span>
            </div>
            <a class="repo-link" href="/news" @click.prevent="navigateTo('/news')">浏览项目与 README <span>→</span></a>
          </div>
          <div v-else class="featured-repo repo-loading">正在整理 GitHub 项目索引…</div>
        </article>
      </div>
    </section>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { NIcon } from 'naive-ui'
import {
  BookOutline,
  DocumentTextOutline,
  LogoGithub,
  SchoolOutline
} from '@vicons/ionicons5'
import { getGithubProjects } from '@/api'
import { defaultGithubProjects, githubProjectFallback } from '@/config/githubProjects'

const router = useRouter()
const projects = ref([])

const tools = [
  { title: '文献库', description: '管理与阅读学术文献', path: '/publications', icon: BookOutline },
  { title: '论文翻译', description: '保留版式输出双语 PDF', path: '/translate', icon: DocumentTextOutline },
  { title: 'PPT 生成', description: '从论文生成答辩材料', path: '/contact', icon: SchoolOutline },
  { title: '开源项目', description: '浏览 GitHub 仓库与 README', path: '/news', icon: LogoGithub }
]

const progress = [
  { label: '文献阅读与整理', value: '18 / 24', percent: 75, note: '已整理 18 篇，待处理 6 篇' },
  { label: '论文翻译', value: '7 / 10', percent: 70, note: '已完成 7 篇，排队中 3 篇' },
  { label: 'PPT 资料准备', value: '2 / 4', percent: 50, note: '已完成 2 份，进行中 1 份' }
]

const featuredProject = computed(() => projects.value[0] || null)

onMounted(async () => {
  try {
    const response = await getGithubProjects()
    const items = response?.data || response || []
    projects.value = items
      .filter(item => item.featured !== false)
      .map(item => ({
        ...item,
        full_name: item.full_name || item.repo || '未命名仓库'
      }))
  } catch {
    projects.value = defaultGithubProjects.map(item => {
      const fullName = item.repo.replace(/^https?:\/\/github\.com\//, '')
      return { ...githubProjectFallback, ...item, full_name: fullName, language: githubProjectFallback.language }
    })
  }
})

function navigateTo(path) {
  router.push(path)
}

function formatNumber(value) {
  const number = Number(value || 0)
  return number >= 1000 ? `${(number / 1000).toFixed(1)}k` : String(number)
}
</script>

<style scoped lang="scss">
@use '@/assets/styles/variables' as *;

.home-page {
  background: #eee9df;
  color: #25251f;
}

.desk-hero {
  min-height: calc(100vh - 81px);
  padding: 28px 0 42px;
}

.desk-grid {
  display: grid;
  grid-template-columns: 1.42fr 1fr;
  gap: 14px;
}

.paper-panel {
  position: relative;
  border: 1px solid #cfc7b7;
  border-radius: 2px;
  background: #fbf9f3;
  box-shadow: 2px 3px 0 rgba(95, 86, 65, 0.12);
  padding: 28px;
}

.section-kicker {
  margin: 0 0 14px;
  color: #b83126;
  font: 700 12px/1.2 $font-en;
  letter-spacing: 0.12em;
  text-transform: uppercase;
}

.current-work {
  min-height: 390px;
  padding-left: 72px;

  &::before {
    content: '01';
    position: absolute;
    left: 25px;
    top: 74px;
    color: #c04a3e;
    font: 400 20px/1 Georgia, serif;
  }

  h1 {
    max-width: 700px;
    margin: 0;
    font-family: Georgia, 'Noto Serif SC', 'Songti SC', serif;
    font-size: clamp(34px, 4vw, 58px);
    line-height: 1.16;
    letter-spacing: -0.035em;
  }

  .lead {
    max-width: 690px;
    margin: 22px 0;
    color: #68645b;
    font-size: 16px;
    line-height: 1.8;
  }
}

.topic-tags {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;

  span {
    border: 1px solid #bfc4b6;
    background: #edf0e8;
    color: #52624e;
    padding: 4px 9px;
    font-size: 12px;
  }
}

.quick-actions {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  margin-top: 46px;
  border-top: 1px solid #d9d2c5;

  a {
    display: flex;
    gap: 10px;
    align-items: center;
    color: inherit;
    padding: 20px 14px 0;
    text-decoration: none;
    border-right: 1px solid #ded7ca;

    &:first-child { padding-left: 0; }
    &:last-child { border-right: 0; }
    &:hover strong { color: #b83126; }
  }

  strong, small { display: block; }
  strong { margin-bottom: 4px; font-size: 14px; }
  small { color: #858077; font-size: 11px; }
}

.progress-panel {
  display: grid;
  align-content: start;
  gap: 22px;
}

.progress-row {
  small { display: block; margin-top: 7px; color: #8b867c; text-align: right; }
}

.progress-head {
  display: flex;
  justify-content: space-between;
  margin-bottom: 8px;
  font-size: 14px;
}

.progress-track {
  height: 5px;
  background: #e4dfd4;

  span { display: block; height: 100%; background: #607b56; }
}

.paper-note {
  margin-top: 3px;
  border: 1px solid #ddd1b9;
  background: #f3ead6;
  padding: 12px 14px;
  color: #6f634e;
  font-family: Georgia, 'Noto Serif SC', serif;
  font-size: 13px;
}

.panel-heading {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 20px;
  padding-bottom: 14px;
  border-bottom: 1px solid #d8d1c5;

  h2 { margin: 0; font-family: Georgia, 'Noto Serif SC', serif; font-size: 25px; }
  a { color: #656158; text-decoration: none; font-size: 13px; }
  a:hover { color: #b83126; }
}

.workflow-list {
  a {
    display: grid;
    grid-template-columns: 34px 28px 1fr auto;
    gap: 10px;
    align-items: center;
    min-height: 64px;
    color: inherit;
    text-decoration: none;
    border-bottom: 1px solid #e3ddd2;
  }

  strong, small { display: block; }
  strong { font-size: 14px; }
  small { margin-top: 3px; color: #89847a; font-size: 11px; }
  a:hover .row-arrow { color: #b83126; transform: translateX(3px); }
}

.row-index { color: #a49d90; font: 12px/1 Georgia, serif; }
.row-arrow { transition: 0.2s ease; }

.featured-repo {
  margin-top: 18px;
  border: 1px solid #d8d1c5;
  padding: 18px;

  p { min-height: 48px; color: #69645b; line-height: 1.65; }
}

.repo-title {
  display: flex;
  align-items: center;
  gap: 10px;

  strong, small { display: block; }
  strong { overflow-wrap: anywhere; font: 700 16px/1.3 $font-en; }
  small { margin-top: 3px; color: #999287; font-size: 11px; }
}

.repo-meta {
  display: flex;
  gap: 18px;
  color: #6c7565;
  font-size: 12px;
  padding: 12px 0;
  border-top: 1px solid #e3ddd2;
}

.repo-link {
  display: flex;
  justify-content: space-between;
  border: 1px solid #d8d1c5;
  padding: 11px 12px;
  color: #b83126;
  font-weight: 600;
  text-decoration: none;
}

.repo-loading { color: #817b70; }

@media (max-width: 1000px) {
  .desk-grid { grid-template-columns: 1fr; }
}

@media (max-width: 700px) {
  .desk-hero { padding-top: 14px; }
  .paper-panel { padding: 20px; }
  .current-work { min-height: auto; padding-left: 20px; }
  .current-work::before { display: none; }
  .current-work h1 { font-size: 34px; }
  .quick-actions { grid-template-columns: 1fr 1fr; margin-top: 26px; }
  .quick-actions a { padding: 15px 8px; border-bottom: 1px solid #ded7ca; }
}
</style>

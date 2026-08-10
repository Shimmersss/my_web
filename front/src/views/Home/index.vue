<template>
  <div class="home-page">
    <section class="desk-hero">
      <div class="container hero-layout">
        <article class="hero-copy">
          <p class="section-kicker">01 / 首页</p>
          <h1>闪闪的个人小站</h1>
          <p class="lead">网站试运营中</p>

          <div class="hero-actions" aria-label="首页快捷操作">
            <a class="primary-action" href="/publications" @click.prevent="navigateTo('/publications')">
              进入工作台 <span aria-hidden="true">→</span>
            </a>
          </div>

          <div class="hero-status" aria-label="网站状态">
            <span class="status-dot" aria-hidden="true"></span>
            <span>站点状态</span>
            <strong>试运营中</strong>
          </div>
        </article>

        <div
          class="hero-visual"
          :style="{
            '--pointer-x': `${heroPointer.x}px`,
            '--pointer-y': `${heroPointer.y}px`
          }"
          @pointermove="handleHeroMove"
          @pointerleave="resetHeroMove"
        >
          <div class="visual-signal" aria-hidden="true"></div>
          <img
            class="workspace-image"
            :src="workspaceImage"
            alt="研究工作台预览，包含文献阅读、研究网络和进度信息"
          />
          <div class="visual-status">
            <span class="status-dot" aria-hidden="true"></span>
            <span>同步中</span>
            <strong>68%</strong>
          </div>
          <div class="visual-caption">
            <span>LIVE WORKSPACE</span>
            <strong>从阅读，到输出</strong>
          </div>
        </div>
      </div>
    </section>

    <section class="workflow-section" aria-labelledby="workflow-title">
      <div class="container workflow-layout">
        <article class="paper-panel workflow-panel">
          <div class="panel-heading">
            <div>
              <p class="section-kicker">02 / 快速入口</p>
              <h2 id="workflow-title">研究工作流</h2>
            </div>
            <a href="/publications" @click.prevent="navigateTo('/publications')">打开文献库 <span aria-hidden="true">→</span></a>
          </div>

          <nav class="workflow-list" aria-label="研究工作流入口">
            <a v-for="(tool, index) in tools" :key="tool.path" :href="tool.path" @click.prevent="navigateTo(tool.path)">
              <span class="row-index">{{ String(index + 1).padStart(2, '0') }}</span>
              <span class="tool-icon"><n-icon size="22"><component :is="tool.icon" /></n-icon></span>
              <span class="tool-copy"><strong>{{ tool.title }}</strong><small>{{ tool.description }}</small></span>
              <span class="row-arrow" aria-hidden="true">→</span>
            </a>
          </nav>
        </article>

        <aside class="paper-panel progress-panel">
          <div class="panel-heading">
            <div>
              <p class="section-kicker">03 / 研究进度</p>
              <h2>正在进行</h2>
            </div>
            <span class="panel-pulse" aria-hidden="true"></span>
          </div>

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

        <article class="paper-panel github-panel">
          <div class="panel-heading">
            <div>
              <p class="section-kicker">04 / GitHub</p>
              <h2>我的开源项目</h2>
            </div>
            <a href="/news" @click.prevent="navigateTo('/news')">查看全部 <span aria-hidden="true">→</span></a>
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
            <a class="repo-link" href="/news" @click.prevent="navigateTo('/news')">浏览项目与 README <span aria-hidden="true">→</span></a>
          </div>
          <div v-else class="featured-repo repo-loading">正在整理 GitHub 项目索引…</div>
        </article>
      </div>
    </section>

    <section class="checkin-section" aria-labelledby="checkin-title">
      <div class="container">
        <article class="paper-panel checkin-panel">
          <div class="panel-heading"><div><p class="section-kicker">05 / DAILY CHECK-IN</p><h2 id="checkin-title">今日签到榜</h2></div><span>前 10 名</span></div>
          <ol v-if="checkinLeaders.length" class="checkin-list"><li v-for="(item, index) in checkinLeaders" :key="`${item.username}-${index}`"><b>{{ String(index + 1).padStart(2, '0') }}</b><strong>{{ item.username }}</strong><span>+{{ item.amount }} 积分</span></li></ol>
          <p v-else class="checkin-empty">今天还没有签到记录，来抢第一名吧。</p>
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
import { getDailyCheckinLeaderboard, getGithubProjects } from '@/api'
import { defaultGithubProjects, githubProjectFallback } from '@/config/githubProjects'
import workspaceImage from '@/assets/images/home-workspace-aurora.png'

const router = useRouter()
const projects = ref([])
const checkinLeaders = ref([])
const heroPointer = ref({ x: 0, y: 0 })

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
  getDailyCheckinLeaderboard().then(response => { checkinLeaders.value = response?.data || [] }).catch(() => {})
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

function handleHeroMove(event) {
  const rect = event.currentTarget.getBoundingClientRect()
  heroPointer.value = {
    x: ((event.clientX - rect.left) / rect.width - 0.5) * 18,
    y: ((event.clientY - rect.top) / rect.height - 0.5) * 12
  }
}

function resetHeroMove() {
  heroPointer.value = { x: 0, y: 0 }
}

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
  min-height: calc(100vh - 81px);
  overflow: hidden;
  background: #eee9df;
  color: #25251f;
}

.checkin-section { padding: 0 0 72px; background:#eee9df; }
.checkin-panel { max-width:760px; margin:auto; padding:24px; }
.checkin-panel .panel-heading>span { color:#756f64; font-size:13px; }
.checkin-list { list-style:none; margin:16px 0 0; padding:0; display:grid; gap:2px; }
.checkin-list li { display:grid; grid-template-columns:42px 1fr auto; gap:12px; align-items:center; padding:12px 8px; border-bottom:1px solid #e7e0d5; }
.checkin-list b { color:#b83126; font-size:12px; }.checkin-list span { color:#58745f; font-weight:700; }.checkin-empty { color:#756f64; margin:18px 0 4px; }

.desk-hero {
  position: relative;
  min-height: 650px;
  padding: 76px 0 92px;
  isolation: isolate;

  &::before {
    position: absolute;
    inset: 0;
    z-index: -1;
    background: #f8f5ee;
    content: '';
  }
}

.hero-layout {
  display: grid;
  grid-template-columns: minmax(430px, 0.88fr) minmax(0, 1.12fr);
  gap: 24px;
  align-items: center;
}

.hero-copy {
  position: relative;
  z-index: 2;
  padding: 28px 0 28px 54px;

  &::before {
    position: absolute;
    left: 0;
    top: 40px;
    width: 3px;
    height: 102px;
    background: #b83126;
    content: '';
  }

  h1 {
    max-width: 600px;
    margin: 0;
    color: #25251f;
    font-family: Georgia, 'Noto Serif SC', 'Songti SC', serif;
    font-size: clamp(52px, 5.1vw, 74px);
    font-weight: 500;
    letter-spacing: -0.075em;
    line-height: 1.08;
  }

  .lead {
    margin: 22px 0 0;
    color: #756f64;
    font-family: Georgia, 'Noto Serif SC', serif;
    font-size: clamp(20px, 2vw, 27px);
    letter-spacing: 0.02em;
  }
}

.section-kicker {
  margin: 0 0 18px;
  color: #b83126;
  font: 700 12px/1.2 $font-en;
  letter-spacing: 0.13em;
  text-transform: uppercase;
}

.hero-actions {
  display: flex;
  align-items: center;
  gap: 26px;
  margin-top: 54px;
}

.primary-action {
  text-decoration: none;
  transition: color $transition-fast, transform $transition-fast, background $transition-fast;
}

.primary-action {
  display: inline-flex;
  align-items: center;
  gap: 22px;
  min-height: 52px;
  padding: 0 22px;
  background: #b83126;
  color: #fffaf1;
  font-size: 15px;
  font-weight: 700;
  box-shadow: 4px 5px 0 rgba(95, 86, 65, 0.12);

  span { font-size: 21px; line-height: 1; }

  &:hover {
    background: #92271f;
    transform: translateY(-2px);
  }
}

.hero-status,
.visual-status {
  display: flex;
  align-items: center;
  gap: 9px;
  color: #8a8377;
  font-size: 12px;
}

.hero-status {
  margin-top: 34px;

  strong {
    color: #607b56;
    font-weight: 600;
  }
}

.status-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: #607b56;
  box-shadow: 0 0 0 4px rgba(96, 123, 86, 0.1);
  animation: statusPulse 2.8s ease-in-out infinite;
}

.hero-visual {
  position: relative;
  min-height: 570px;
  margin-right: 0;
  cursor: crosshair;
  transform: translate(calc(var(--pointer-x) * -0.16), calc(var(--pointer-y) * -0.16));
  transition: transform 0.8s cubic-bezier(0.22, 1, 0.36, 1);
}

.workspace-image {
  position: absolute;
  top: 50%;
  left: 50%;
  z-index: 1;
  width: min(650px, 100%);
  max-width: none;
  transform: translate(calc(-50% + var(--pointer-x)), calc(-50% + var(--pointer-y))) rotate(-1deg);
  filter: drop-shadow(0 22px 20px rgba(98, 83, 58, 0.14));
  transition: transform 0.8s cubic-bezier(0.22, 1, 0.36, 1), filter 0.8s ease;
  animation: workspaceFloat 9s ease-in-out infinite;
}

.hero-visual:hover .workspace-image {
  filter: drop-shadow(0 30px 30px rgba(98, 83, 58, 0.2));
}

.visual-signal {
  position: absolute;
  inset: 14% 3% 11% 8%;
  z-index: 0;
  border: 1px solid rgba(188, 137, 93, 0.22);
  border-radius: 50%;
  transform: rotate(-13deg) scaleX(1.2);
  animation: signalDrift 10s ease-in-out infinite;

  &::before,
  &::after {
    position: absolute;
    inset: 8% 4%;
    border: inherit;
    border-radius: inherit;
    content: '';
  }

  &::after {
    inset: 16% -2%;
    border-color: rgba(96, 123, 86, 0.15);
  }
}

.visual-status {
  position: absolute;
  top: 18%;
  right: 14%;
  z-index: 2;
  padding: 9px 12px;
  border: 1px solid rgba(207, 199, 183, 0.9);
  background: rgba(251, 249, 243, 0.88);
  box-shadow: 3px 4px 0 rgba(95, 86, 65, 0.08);
  backdrop-filter: blur(7px);

  strong {
    color: #b83126;
    font-weight: 700;
  }
}

.visual-caption {
  position: absolute;
  bottom: 8%;
  left: 9%;
  z-index: 2;
  display: grid;
  gap: 5px;
  color: #767064;
  font: 11px/1.2 $font-en;
  letter-spacing: 0.12em;

  strong {
    color: #25251f;
    font-family: Georgia, 'Noto Serif SC', serif;
    font-size: 17px;
    font-weight: 400;
    letter-spacing: 0;
  }
}

.workflow-section {
  padding: 34px 0 76px;
  background: #eee9df;
}

.workflow-layout {
  display: grid;
  grid-template-columns: minmax(0, 1.2fr) minmax(320px, 0.8fr);
  gap: 14px;
}

.paper-panel {
  position: relative;
  border: 1px solid #cfc7b7;
  border-radius: 2px;
  background: #fbf9f3;
  box-shadow: 2px 3px 0 rgba(95, 86, 65, 0.12);
  padding: 26px 28px;
}

.workflow-panel { grid-row: span 2; }

.progress-panel {
  display: grid;
  align-content: start;
  gap: 20px;
}

.github-panel { min-height: 292px; }

.panel-heading {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 20px;
  padding-bottom: 14px;
  border-bottom: 1px solid #d8d1c5;

  .section-kicker { margin-bottom: 10px; }

  h2 {
    margin: 0;
    color: #25251f;
    font-family: Georgia, 'Noto Serif SC', serif;
    font-size: 25px;
    font-weight: 500;
  }

  a {
    color: #656158;
    font-size: 13px;
    text-decoration: none;
  }

  a:hover { color: #b83126; }
}

.panel-pulse {
  width: 8px;
  height: 8px;
  margin: 8px 5px 0 0;
  border: 1px solid #607b56;
  border-radius: 50%;
  animation: statusPulse 2.8s ease-in-out infinite;
}

.workflow-list {
  a {
    display: grid;
    grid-template-columns: 34px 28px minmax(0, 1fr) auto;
    gap: 10px;
    align-items: center;
    min-height: 68px;
    color: inherit;
    text-decoration: none;
    border-bottom: 1px solid #e3ddd2;
    transition: padding 0.25s ease, color 0.25s ease;
  }

  a:hover {
    padding-left: 5px;
    color: #b83126;
  }

  strong,
  small { display: block; }
  strong { font-size: 14px; }
  small { margin-top: 3px; color: #89847a; font-size: 11px; }
}

.row-index { color: #a49d90; font: 12px/1 Georgia, serif; }
.tool-icon { color: #4f5f4b; }
.row-arrow { transition: transform 0.25s ease; }
.workflow-list a:hover .row-arrow { transform: translateX(4px); }

.progress-row {
  small {
    display: block;
    margin-top: 7px;
    color: #8b867c;
    text-align: right;
  }
}

.progress-head {
  display: flex;
  justify-content: space-between;
  margin-bottom: 8px;
  font-size: 14px;

  strong { font-family: $font-en; }
}

.progress-track {
  height: 5px;
  overflow: hidden;
  background: #e4dfd4;

  span {
    display: block;
    height: 100%;
    background: #607b56;
    transform-origin: left center;
    animation: progressReveal 1.2s cubic-bezier(0.22, 1, 0.36, 1) both;
  }
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

  strong,
  small { display: block; }
  strong { overflow-wrap: anywhere; font: 700 16px/1.3 $font-en; }
  small { margin-top: 3px; color: #999287; font-size: 11px; }
}

.repo-meta {
  display: flex;
  gap: 18px;
  border-top: 1px solid #e3ddd2;
  padding: 12px 0;
  color: #6c7565;
  font-size: 12px;
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

.repo-link:hover { background: #f7efe2; }
.repo-loading { color: #817b70; }

@keyframes workspaceFloat {
  0%, 100% { margin-top: 0; }
  50% { margin-top: -10px; }
}

@keyframes signalDrift {
  0%, 100% { transform: rotate(-13deg) scaleX(1.2) translateX(0); opacity: 0.72; }
  50% { transform: rotate(-9deg) scaleX(1.24) translateX(12px); opacity: 1; }
}

@keyframes statusPulse {
  0%, 100% { opacity: 0.5; transform: scale(0.86); }
  50% { opacity: 1; transform: scale(1); }
}

@keyframes progressReveal {
  from { transform: scaleX(0); }
  to { transform: scaleX(1); }
}

@media (max-width: 1100px) {
  .hero-layout { grid-template-columns: minmax(360px, 0.9fr) minmax(0, 1.1fr); }
  .hero-copy { padding-left: 38px; }
  .workspace-image { width: min(620px, 100%); }
}

@media (max-width: 900px) {
  .desk-hero { padding: 48px 0 58px; }
  .hero-layout,
  .workflow-layout { grid-template-columns: 1fr; }
  .hero-copy { padding: 14px 0 0 34px; }
  .hero-copy::before { top: 22px; }
  .hero-visual { min-height: 470px; margin: -8px 0 -20px; }
  .workspace-image { width: min(720px, 110vw); }
  .workflow-panel { grid-row: auto; }
}

@media (max-width: 640px) {
  .desk-hero { padding: 30px 0 18px; }
  .hero-copy { padding: 12px 20px 0 28px; }
  .hero-copy::before { top: 20px; width: 2px; height: 70px; }
  .hero-copy h1 {
    font-size: clamp(36px, 12vw, 48px);
    letter-spacing: -0.05em;
    line-height: 1.16;
    white-space: nowrap;
  }
  .hero-copy .lead { margin-top: 14px; font-size: 19px; }
  .hero-actions { align-items: flex-start; flex-direction: column; gap: 18px; margin-top: 34px; }
  .hero-status { margin-top: 26px; }
  .hero-visual { min-height: 320px; margin: 8px -28px -8px; }
  .workspace-image { width: 680px; }
  .visual-status { top: 18%; right: 6%; transform: scale(0.86); transform-origin: top right; }
  .visual-caption { bottom: 7%; left: 14%; }
  .paper-panel { padding: 20px; }
  .panel-heading { gap: 12px; }
  .workflow-list a { grid-template-columns: 28px 26px minmax(0, 1fr) auto; min-height: 62px; }
  .github-panel { min-height: auto; }
}

@media (prefers-reduced-motion: reduce) {
  .status-dot,
  .panel-pulse,
  .workspace-image,
  .visual-signal,
  .progress-track span {
    animation: none;
  }

  .hero-visual,
  .workspace-image { transition: none; }
}
</style>

<template>
  <div class="about-page">
    <!-- 项目简介 -->
    <section class="section">
      <div class="container">
        <h2 class="section__title">{{ $t('about.intro') }}</h2>
        <div class="intro-content">
          <div class="intro-text">
            <p>这个站点是个人研究与自动化工作台，集中承载 Zotero 文献库、PDF 论文翻译、PPT 生成和 OpenClaw 对话入口。</p>
            <p>前端负责可视化操作，后端代理 Zotero、GitHub、BabelDOC、mimo 和本机 OpenClaw Gateway，把密钥、队列和大文件处理留在服务端。</p>
            <div class="intro-stats">
              <div class="stat-item">
                <div class="stat-number">4</div>
                <div class="stat-label">核心工具</div>
              </div>
              <div class="stat-item">
                <div class="stat-number">2C4G</div>
                <div class="stat-label">生产基线</div>
              </div>
              <div class="stat-item">
                <div class="stat-number">1</div>
                <div class="stat-label">后台 worker</div>
              </div>
              <div class="stat-item">
                <div class="stat-number">5</div>
                <div class="stat-label">最近任务</div>
              </div>
            </div>
          </div>
          <div class="intro-image">
            <img :src="pipelineImage" alt="个人研究工具链">
          </div>
        </div>
      </div>
    </section>

    <!-- 演进记录 -->
    <section class="section" style="background: #f5f5f5">
      <div class="container">
        <h2 class="section__title">{{ $t('about.history') }}</h2>
        <n-timeline>
          <n-timeline-item v-for="item in history" :key="item.year" :title="item.year">
            <div class="timeline-content">
              <h3>{{ item.title }}</h3>
              <p>{{ item.description }}</p>
            </div>
          </n-timeline-item>
        </n-timeline>
      </div>
    </section>

    <!-- 系统结构 -->
    <section class="section">
      <div class="container">
        <h2 class="section__title">{{ $t('about.organization') }}</h2>
        <div class="org-chart">
          <div class="org-level">
            <div class="org-box ceo">
              <n-icon size="40"><PersonCircleOutline /></n-icon>
              <h4>浏览器</h4>
              <p>Vue 3 + Naive UI</p>
            </div>
          </div>
          <div class="org-level">
            <div class="org-box cto">
              <n-icon size="32"><CodeSlashOutline /></n-icon>
              <h4>后端 API</h4>
              <p>Spring Boot 3</p>
            </div>
            <div class="org-box coo">
              <n-icon size="32"><BuildOutline /></n-icon>
              <h4>任务队列</h4>
              <p>单 worker 有界执行</p>
            </div>
            <div class="org-box cfo">
              <n-icon size="32"><CashOutline /></n-icon>
              <h4>文件存储</h4>
              <p>.run 任务目录</p>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- 核心模块 -->
    <section class="section" style="background: #f5f5f5">
      <div class="container">
        <h2 class="section__title">{{ $t('about.team') }}</h2>
        <n-grid :x-gap="24" :y-gap="24" :cols="4">
          <n-grid-item v-for="member in team" :key="member.id">
            <div class="team-card">
              <div class="team-avatar">
                <n-icon size="48">
                  <component :is="member.icon" />
                </n-icon>
              </div>
              <h3>{{ member.name }}</h3>
              <p class="team-position">{{ member.position }}</p>
              <p class="team-bio">{{ member.bio }}</p>
            </div>
          </n-grid-item>
        </n-grid>
      </div>
    </section>

    <!-- 维护重点 -->
    <section class="section">
      <div class="container">
        <h2 class="section__title">{{ $t('about.honors') }}</h2>
        <n-grid :x-gap="24" :y-gap="24" :cols="4">
          <n-grid-item v-for="honor in honors" :key="honor.id">
            <div class="honor-card">
              <n-icon size="48" color="#ffd700"><TrophyOutline /></n-icon>
              <h3>{{ honor.title }}</h3>
              <p>{{ honor.year }}</p>
            </div>
          </n-grid-item>
        </n-grid>
      </div>
    </section>

    <!-- 工作原则 -->
    <section class="section" style="background: #f5f5f5">
      <div class="container">
        <h2 class="section__title">{{ $t('about.culture') }}</h2>
        <div class="culture-grid">
          <div v-for="item in culture" :key="item.id" class="culture-item">
            <div class="culture-icon" :style="{ backgroundColor: item.color }">
              <n-icon size="32" color="#fff">
                <component :is="item.icon" />
              </n-icon>
            </div>
            <h3>{{ item.title }}</h3>
            <p>{{ item.description }}</p>
          </div>
        </div>
      </div>
    </section>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { NTimeline, NTimelineItem, NGrid, NGridItem, NIcon } from 'naive-ui'
import {
  PersonCircleOutline,
  CodeSlashOutline,
  BuildOutline,
  CashOutline,
  TrophyOutline,
  HeartOutline,
  BulbOutline,
  RocketOutline,
  PeopleOutline
} from '@vicons/ionicons5'
import pipelineImage from '@/assets/images/research-pipeline-panel.jpg'

const history = ref([
  { year: '文献', title: 'Zotero 缓存展示', description: '后端预热和刷新 Zotero 条目，前端展示文献、分组与附件代理结果' },
  { year: '翻译', title: 'BabelDOC 版式链路', description: '按页码范围生成纯中文和双语 PDF，并保留结果预览与下载' },
  { year: 'PPT', title: '论文到答辩稿', description: 'mimo 规划结构，结合论文图片、模板和 renderer 生成 PPTX' },
  { year: '对话', title: 'OpenClaw 站内控制台', description: '用后端代理本机 Gateway 的会话、模型、历史与产物下载' },
  { year: '部署', title: '低资源服务器约束', description: '以 2 核 CPU / 4 GB 内存为生产基线，控制 worker、队列和大文件内存占用' }
])

const team = ref([
  {
    id: 1,
    name: '文献库',
    position: 'Publications',
    bio: '展示 Zotero 条目、附件、分组和引用导出',
    icon: PersonCircleOutline
  },
  {
    id: 2,
    name: '论文翻译',
    position: 'Translate',
    bio: 'BabelDOC 生成可预览的保留版式 PDF',
    icon: CodeSlashOutline
  },
  {
    id: 3,
    name: 'PPT 生成',
    position: 'PptGenerate',
    bio: '论文、模板和提示词直出 PPTX',
    icon: BuildOutline
  },
  {
    id: 4,
    name: 'OpenClaw',
    position: 'WebChat',
    bio: '站内访问本机 AI 对话与产物',
    icon: RocketOutline
  }
])

const honors = ref([
  { id: 1, title: '服务端密钥隔离', year: '后端代理' },
  { id: 2, title: '大文件落盘', year: '.run 任务目录' },
  { id: 3, title: '资源保护', year: '2C4G 基线' },
  { id: 4, title: '产物可复查', year: 'manifest / 日志 / 输出' }
])

const culture = ref([
  {
    id: 1,
    title: '真实工作流',
    description: '围绕论文阅读、翻译、答辩和对话使用，不保留无关样板内容',
    icon: HeartOutline,
    color: '#ff4d4f'
  },
  {
    id: 2,
    title: '服务端优先',
    description: '外部 API、密钥和重任务都由后端统一管理',
    icon: BulbOutline,
    color: '#1890ff'
  },
  {
    id: 3,
    title: '资源克制',
    description: '新功能默认考虑 2 核 4GB 生产环境',
    icon: RocketOutline,
    color: '#52c41a'
  },
  {
    id: 4,
    title: '可验证',
    description: '重要改动保留构建、接口或浏览器验证记录',
    icon: PeopleOutline,
    color: '#faad14'
  }
])
</script>

<style scoped lang="scss">
@use '@/assets/styles/variables' as *;

.intro-content {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: $spacing-xl;
  align-items: center;

  .intro-text {
    p {
      font-size: 16px;
      line-height: 1.8;
      color: $text-color-secondary;
      margin-bottom: $spacing-md;
    }

    .intro-stats {
      display: grid;
      grid-template-columns: repeat(4, 1fr);
      gap: $spacing-lg;
      margin-top: $spacing-xl;

      .stat-item {
        text-align: center;

        .stat-number {
          font-size: 36px;
          font-weight: 700;
          color: $primary-color;
          margin-bottom: $spacing-xs;
        }

        .stat-label {
          font-size: 14px;
          color: $text-color-secondary;
        }
      }
    }
  }

  .intro-image {
    img {
      width: 100%;
      border-radius: $border-radius-lg;
      box-shadow: $shadow-lg;
    }
  }
}

.timeline-content {
  h3 {
    font-size: 18px;
    font-weight: 600;
    margin-bottom: $spacing-xs;
  }

  p {
    color: $text-color-secondary;
  }
}

.org-chart {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: $spacing-xl;

  .org-level {
    display: flex;
    gap: $spacing-xl;
  }

  .org-box {
    text-align: center;
    padding: $spacing-xl;
    background: #fff;
    border-radius: $border-radius-lg;
    box-shadow: $shadow-sm;
    width: 200px;

    &.ceo {
      background: linear-gradient(135deg, #1890ff 0%, #722ed1 100%);
      color: #fff;
    }

    h4 {
      font-size: 18px;
      margin: $spacing-sm 0 $spacing-xs;
    }

    p {
      font-size: 14px;
      opacity: 0.8;
    }
  }
}

.team-card {
  text-align: center;
  padding: $spacing-xl;
  background: #fff;
  border-radius: $border-radius-lg;
  box-shadow: $shadow-sm;
  transition: all $transition-base;

  &:hover {
    transform: translateY(-8px);
    box-shadow: $shadow-lg;
  }

  .team-avatar {
    width: 120px;
    height: 120px;
    border-radius: 50%;
    margin: 0 auto $spacing-md;
    display: flex;
    align-items: center;
    justify-content: center;
    color: $primary-color;
    background: rgba(24, 144, 255, 0.08);
    border: 1px solid rgba(24, 144, 255, 0.18);
  }

  h3 {
    font-size: 18px;
    font-weight: 600;
    margin-bottom: $spacing-xs;
  }

  .team-position {
    color: $primary-color;
    font-weight: 500;
    margin-bottom: $spacing-sm;
  }

  .team-bio {
    font-size: 14px;
    color: $text-color-secondary;
    line-height: 1.6;
  }
}

.honor-card {
  text-align: center;
  padding: $spacing-xl;
  background: #fff;
  border-radius: $border-radius-lg;
  box-shadow: $shadow-sm;
  transition: all $transition-base;

  &:hover {
    transform: scale(1.05);
    box-shadow: $shadow-md;
  }

  h3 {
    font-size: 16px;
    font-weight: 600;
    margin: $spacing-md 0 $spacing-sm;
  }

  p {
    color: $text-color-secondary;
  }
}

.culture-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: $spacing-xl;

  .culture-item {
    text-align: center;
    padding: $spacing-xl;
    background: #fff;
    border-radius: $border-radius-lg;
    box-shadow: $shadow-sm;

    .culture-icon {
      width: 80px;
      height: 80px;
      border-radius: 50%;
      display: flex;
      align-items: center;
      justify-content: center;
      margin: 0 auto $spacing-md;
    }

    h3 {
      font-size: 20px;
      font-weight: 600;
      margin-bottom: $spacing-sm;
    }

    p {
      color: $text-color-secondary;
      font-size: 14px;
    }
  }
}

@media (max-width: 992px) {
  .intro-content {
    grid-template-columns: 1fr;
  }

  .intro-stats {
    grid-template-columns: repeat(2, 1fr) !important;
  }

  .culture-grid {
    grid-template-columns: repeat(2, 1fr) !important;
  }
}

@media (max-width: 768px) {
  .org-level {
    flex-direction: column !important;
  }

  .intro-stats {
    grid-template-columns: repeat(2, 1fr) !important;
  }
}
</style>

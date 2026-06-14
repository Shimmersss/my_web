<template>
  <div class="case-detail">
    <!-- 任务样例详情 -->
    <section class="section">
      <div class="container">
        <div class="case-hero">
          <img :src="caseData.image" :alt="caseData.title">
          <div class="case-info">
            <div class="info-item">
              <n-icon><CalendarOutline /></n-icon>
              <span>{{ caseData.date }}</span>
            </div>
            <div class="info-item">
              <n-icon><PersonOutline /></n-icon>
              <span>{{ caseData.client }}</span>
            </div>
            <div class="info-item">
              <n-icon><BusinessOutline /></n-icon>
              <span>{{ caseData.industry }}</span>
            </div>
            <div class="info-item">
              <n-icon><PricetagsOutline /></n-icon>
              <span>{{ caseData.type }}</span>
            </div>
          </div>
        </div>

        <n-grid :x-gap="48" :y-gap="24" :cols="2">
          <n-grid-item>
            <div class="case-section">
              <h2>{{ $t('cases.background') }}</h2>
              <p>{{ caseData.background }}</p>
            </div>

            <div class="case-section">
              <h2>{{ $t('cases.solution') }}</h2>
              <p>{{ caseData.solution }}</p>
            </div>
          </n-grid-item>

          <n-grid-item>
            <div class="case-section">
              <h2>{{ $t('cases.result') }}</h2>
              <p>{{ caseData.result }}</p>

              <div class="result-stats">
                <div class="stat-card">
                  <div class="stat-value">{{ caseData.stats.efficiency }}%</div>
                  <div class="stat-label">效率提升</div>
                </div>
                <div class="stat-card">
                  <div class="stat-value">{{ caseData.stats.cost }}%</div>
                  <div class="stat-label">成本降低</div>
                </div>
                <div class="stat-card">
                  <div class="stat-value">{{ caseData.stats.satisfaction }}%</div>
                  <div class="stat-label">复查完整度</div>
                </div>
              </div>
            </div>
          </n-grid-item>
        </n-grid>

        <div class="case-actions">
          <n-button type="primary" size="large" @click="navigateTo('/contact')">
            生成类似 PPT
          </n-button>
          <n-button size="large" @click="downloadCase">
            <template #icon>
              <n-icon><DownloadOutline /></n-icon>
            </template>
            {{ $t('cases.download') }}
          </n-button>
        </div>
      </div>
    </section>

    <!-- 相关样例 -->
    <section class="section" style="background: #f5f5f5">
      <div class="container">
        <h2 class="section__title">相关样例</h2>
        <n-grid :x-gap="24" :y-gap="24" :cols="3">
          <n-grid-item v-for="item in relatedCases" :key="item.id">
            <div class="related-card" @click="navigateTo(`/cases/${item.id}`)">
              <img :src="item.image" :alt="item.title">
              <h3>{{ item.title }}</h3>
              <p>{{ item.summary }}</p>
            </div>
          </n-grid-item>
        </n-grid>
      </div>
    </section>

  </div>
</template>

<script setup>
import { ref, onMounted, computed } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useMessage } from 'naive-ui'
import { NButton, NGrid, NGridItem, NIcon } from 'naive-ui'
import {
  CalendarOutline,
  PersonOutline,
  BusinessOutline,
  PricetagsOutline,
  DownloadOutline
} from '@vicons/ionicons5'
import heroImage from '@/assets/images/research-workbench-hero.jpg'
import pipelineImage from '@/assets/images/research-pipeline-panel.jpg'

const router = useRouter()
const route = useRoute()
const message = useMessage()

const caseData = ref({
  id: 1,
  title: '论文到答辩 PPT 工作流',
  client: '研究资料',
  industry: 'PPT',
  type: '产物生成',
  date: '2026-06-11',
  image: heroImage,
  summary: '上传论文和可选模板，生成可编辑答辩 PPTX',
  background: '旧样例页已从主入口折叠，但直接访问时仍不应展示无关样板内容。因此这里改为研究工具台的任务样例，说明如何把论文材料转为答辩产物。',
  solution: '用户在 PPT 生成页提交提示词、论文 PDF/DOCX 和可选 PPTX 模板。后端保存任务目录，抽取文本、图片和模板槽位，调用 mimo 规划结构，再用自由 renderer 或模板原生填充链路输出 PPTX。',
  result: '产物以可下载 PPTX 形式返回，并保留 deck JSON、image-manifest、fill-plan、日志和输出文件，方便复查页面质量、图片使用和资源问题。',
  stats: {
    efficiency: 60,
    cost: 40,
    satisfaction: 95
  }
})

const relatedCases = computed(() => [
  {
    id: 2,
    title: 'PDF 翻译预览',
    summary: '生成纯中文或双语 PDF',
    image: pipelineImage
  },
  {
    id: 3,
    title: 'Zotero 文献代理',
    summary: '展示条目、分组和附件',
    image: heroImage
  },
  {
    id: 4,
    title: 'OpenClaw 对话',
    summary: '站内访问本机会话',
    image: pipelineImage
  }
])

onMounted(() => {
  const id = route.params.id
  console.log('Loading case detail:', id)
})

const navigateTo = (path) => {
  router.push(path)
}

const downloadCase = () => {
  message.info('样例归档下载暂未开放，请使用对应工具页面生成产物。')
}
</script>

<style scoped lang="scss">
@use '@/assets/styles/variables' as *;

.case-hero {
  margin-bottom: $spacing-xl;

  img {
    width: 100%;
    border-radius: $border-radius-lg;
    box-shadow: $shadow-lg;
  }

  .case-info {
    display: flex;
    gap: $spacing-xl;
    margin-top: $spacing-lg;
    flex-wrap: wrap;

    .info-item {
      display: flex;
      align-items: center;
      gap: $spacing-sm;
      font-size: 16px;
      color: $text-color-secondary;

      .n-icon {
        font-size: 20px;
        color: $primary-color;
      }
    }
  }
}

.case-section {
  margin-bottom: $spacing-xl;

  h2 {
    font-size: 28px;
    font-weight: 600;
    margin-bottom: $spacing-md;
    color: $primary-color;
  }

  p {
    font-size: 16px;
    line-height: 1.8;
    color: $text-color-secondary;
    margin-bottom: $spacing-md;
  }

  .result-stats {
    display: grid;
    grid-template-columns: repeat(3, 1fr);
    gap: $spacing-md;
    margin-top: $spacing-lg;

    .stat-card {
      text-align: center;
      padding: $spacing-lg;
      background: linear-gradient(135deg, #1890ff 0%, #722ed1 100%);
      border-radius: $border-radius-lg;
      color: #fff;

      .stat-value {
        font-size: 48px;
        font-weight: 700;
        margin-bottom: $spacing-xs;
      }

      .stat-label {
        font-size: 14px;
        opacity: 0.9;
      }
    }
  }
}

.case-actions {
  display: flex;
  gap: $spacing-md;
  justify-content: center;
  margin-top: $spacing-xl;
}

.related-card {
  background: #fff;
  border-radius: $border-radius-lg;
  overflow: hidden;
  box-shadow: $shadow-sm;
  cursor: pointer;
  transition: all $transition-base;

  &:hover {
    transform: translateY(-4px);
    box-shadow: $shadow-md;
  }

  img {
    width: 100%;
    height: 200px;
    object-fit: cover;
  }

  h3 {
    font-size: 18px;
    font-weight: 600;
    margin: $spacing-md 0 $spacing-sm;
    padding: 0 $spacing-md;
  }

  p {
    color: $text-color-secondary;
    font-size: 14px;
    padding: 0 $spacing-md $spacing-md;
    line-height: 1.6;
  }
}

@media (max-width: 992px) {
  .case-section {
    .result-stats {
      grid-template-columns: 1fr;
    }
  }
}

@media (max-width: 768px) {
  .case-info {
    flex-direction: column;
    gap: $spacing-sm !important;
  }
}
</style>

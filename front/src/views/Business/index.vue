<template>
  <div class="business-page">
    <!-- 工具分类导航 -->
    <section class="section">
      <div class="container">
        <div class="category-nav">
          <n-button
            v-for="cat in categories"
            :key="cat.id"
            :type="selectedCategory === cat.id ? 'primary' : 'default'"
            @click="selectedCategory = cat.id"
          >
            {{ cat.name }}
          </n-button>
        </div>

        <n-grid :x-gap="24" :y-gap="24" :cols="3" class="business-grid">
          <n-grid-item v-for="service in filteredServices" :key="service.id">
            <div class="service-card" @click="navigateTo(`/business/${service.id}`)">
              <div class="service-image">
                <img :src="service.image" :alt="service.title">
                <div class="service-overlay">
                  <n-button type="primary" size="large">
                    {{ $t('common.readMore') }}
                  </n-button>
                </div>
              </div>
              <div class="service-content">
                <div class="service-icon" :style="{ backgroundColor: service.color }">
                  <n-icon size="24" color="#fff">
                    <component :is="service.icon" />
                  </n-icon>
                </div>
                <h3>{{ service.title }}</h3>
                <p>{{ service.description }}</p>
              </div>
            </div>
          </n-grid-item>
        </n-grid>
      </div>
    </section>

    <!-- 工作流组合 -->
    <section class="section" style="background: #f5f5f5">
      <div class="container">
        <h2 class="section__title">{{ $t('business.solution') }}</h2>
        <n-grid :x-gap="24" :y-gap="24" :cols="2">
          <n-grid-item v-for="solution in solutions" :key="solution.id">
            <div class="solution-card">
              <div class="solution-header">
                <div class="solution-icon">
                  <n-icon size="40" :color="solution.color">
                    <component :is="solution.icon" />
                  </n-icon>
                </div>
                <h3>{{ solution.title }}</h3>
              </div>
              <p>{{ solution.description }}</p>
              <ul class="solution-features">
                <li v-for="feature in solution.features" :key="feature">
                  <n-icon><CheckmarkCircleOutline /></n-icon>
                  {{ feature }}
                </li>
              </ul>
            </div>
          </n-grid-item>
        </n-grid>
      </div>
    </section>

    <!-- 使用流程 -->
    <section class="section">
      <div class="container">
        <h2 class="section__title">{{ $t('business.process') }}</h2>
        <div class="process-steps">
          <div v-for="(step, index) in process" :key="step.id" class="process-step">
            <div class="step-number">{{ index + 1 }}</div>
            <div class="step-icon">
              <n-icon size="40" color="#1890ff">
                <component :is="step.icon" />
              </n-icon>
            </div>
            <h3>{{ step.title }}</h3>
            <p>{{ step.description }}</p>
            <div v-if="index < process.length - 1" class="step-arrow">
              <n-icon size="32" color="#e0e0e0">
                <ArrowForwardOutline />
              </n-icon>
            </div>
          </div>
        </div>
      </div>
    </section>
  </div>
</template>

<script setup>
import { ref, computed } from 'vue'
import { useRouter } from 'vue-router'
import { NButton, NGrid, NGridItem, NIcon } from 'naive-ui'
import {
  CloudOutline,
  AnalyticsOutline,
  BuildOutline,
  RocketOutline,
  DocumentTextOutline,
  ChatbubbleEllipsesOutline,
  CheckmarkCircleOutline,
  ArrowForwardOutline
} from '@vicons/ionicons5'
import heroImage from '@/assets/images/research-workbench-hero.jpg'
import pipelineImage from '@/assets/images/research-pipeline-panel.jpg'

const router = useRouter()
const selectedCategory = ref('all')

const categories = ref([
  { id: 'all', name: '全部' },
  { id: 'library', name: '文献' },
  { id: 'translate', name: '翻译' },
  { id: 'ppt', name: 'PPT' },
  { id: 'chat', name: '对话' },
  { id: 'open-source', name: '开源' },
  { id: 'ops', name: '运维' }
])

const services = ref([
  {
    id: 1,
    category: 'library',
    title: 'Zotero 文献库',
    description: '展示私有 Zotero 库、附件代理和引用导出',
    icon: CloudOutline,
    color: '#1890ff',
    image: pipelineImage
  },
  {
    id: 2,
    category: 'translate',
    title: 'PDF 论文翻译',
    description: '按页面范围生成保留版式的中文或双语 PDF',
    icon: AnalyticsOutline,
    color: '#52c41a',
    image: heroImage
  },
  {
    id: 3,
    category: 'ppt',
    title: 'PPT 生成',
    description: '论文、提示词和 PPTX 模板生成可编辑演示稿',
    icon: BuildOutline,
    color: '#faad14',
    image: pipelineImage
  },
  {
    id: 4,
    category: 'chat',
    title: 'OpenClaw 对话',
    description: '站内管理 OpenClaw 会话、模型、历史和产物',
    icon: RocketOutline,
    color: '#722ed1',
    image: heroImage
  },
  {
    id: 5,
    category: 'open-source',
    title: 'GitHub 项目展示',
    description: '后端代理仓库元数据和 README，避免前端直连 GitHub',
    icon: CloudOutline,
    color: '#eb2f96',
    image: pipelineImage
  },
  {
    id: 6,
    category: 'ops',
    title: '部署与资源保护',
    description: '围绕 2 核 4GB 服务器限制控制队列、内存和文件保留',
    icon: CheckmarkCircleOutline,
    color: '#fa541c',
    image: heroImage
  }
])

const solutions = ref([
  {
    id: 1,
    title: '论文到答辩工作流',
    description: '从 Zotero 和论文文件开始，完成阅读、翻译、素材抽取、PPT 生成和结果复查',
    icon: RocketOutline,
    color: '#1890ff',
    features: ['文献检索', 'PDF 翻译', '图片抽取', 'PPTX 输出']
  },
  {
    id: 2,
    title: '本机 AI 工具链',
    description: '把 OpenClaw、mimo、BabelDOC 和 GitHub/Zotero API 统一收束到站内入口',
    icon: BuildOutline,
    color: '#52c41a',
    features: ['后端代理', '任务排队', '结果落盘', '低资源保护']
  }
])

const process = ref([
  {
    id: 1,
    title: '选择入口',
    description: '从文献、翻译、PPT 或 OpenClaw 进入对应任务',
    icon: ChatbubbleEllipsesOutline
  },
  {
    id: 2,
    title: '上传资料',
    description: '提交 PDF、DOCX、PPTX 模板或直接输入提示词',
    icon: DocumentTextOutline
  },
  {
    id: 3,
    title: '后台处理',
    description: '后端按有界队列执行解析、翻译、视觉筛选和渲染',
    icon: BuildOutline
  },
  {
    id: 4,
    title: '预览下载',
    description: '在页面检查 PDF、PPTX 或聊天产物后下载结果',
    icon: CheckmarkCircleOutline
  },
  {
    id: 5,
    title: '复查记录',
    description: '通过任务目录、日志和 manifest 定位质量或资源问题',
    icon: RocketOutline
  }
])

const filteredServices = computed(() => {
  if (selectedCategory.value === 'all') return services.value
  return services.value.filter(s => s.category === selectedCategory.value)
})

const navigateTo = (path) => {
  router.push(path)
}
</script>

<style scoped lang="scss">
@use '@/assets/styles/variables' as *;

.category-nav {
  display: flex;
  gap: $spacing-sm;
  flex-wrap: wrap;
  justify-content: center;
  margin-bottom: $spacing-xl;
}

.business-grid {
  .service-card {
    background: #fff;
    border-radius: $border-radius-lg;
    overflow: hidden;
    box-shadow: $shadow-sm;
    cursor: pointer;
    transition: all $transition-base;

    &:hover {
      transform: translateY(-8px);
      box-shadow: $shadow-lg;

      .service-overlay {
        opacity: 1;
      }
    }

    .service-image {
      position: relative;
      height: 200px;
      overflow: hidden;

      img {
        width: 100%;
        height: 100%;
        object-fit: cover;
      }

      .service-overlay {
        position: absolute;
        top: 0;
        left: 0;
        right: 0;
        bottom: 0;
        background: rgba(0, 0, 0, 0.6);
        display: flex;
        align-items: center;
        justify-content: center;
        opacity: 0;
        transition: all $transition-base;
      }
    }

    .service-content {
      padding: $spacing-lg;
      display: flex;
      flex-direction: column;
      align-items: center;
      text-align: center;

      .service-icon {
        width: 60px;
        height: 60px;
        border-radius: 50%;
        display: flex;
        align-items: center;
        justify-content: center;
        margin: -$spacing-lg 0 $spacing-md;
        box-shadow: $shadow-md;
      }

      h3 {
        font-size: 18px;
        font-weight: 600;
        margin-bottom: $spacing-sm;
      }

      p {
        color: $text-color-secondary;
        font-size: 14px;
        line-height: 1.6;
      }
    }
  }
}

.solution-card {
  background: #fff;
  padding: $spacing-xl;
  border-radius: $border-radius-lg;
  box-shadow: $shadow-sm;
  transition: all $transition-base;

  &:hover {
    box-shadow: $shadow-md;
  }

  .solution-header {
    display: flex;
    align-items: center;
    gap: $spacing-md;
    margin-bottom: $spacing-md;

    .solution-icon {
      flex-shrink: 0;
    }

    h3 {
      font-size: 20px;
      font-weight: 600;
    }
  }

  p {
    color: $text-color-secondary;
    margin-bottom: $spacing-md;
    line-height: 1.6;
  }

  .solution-features {
    list-style: none;
    padding: 0;

    li {
      display: flex;
      align-items: center;
      gap: $spacing-sm;
      margin-bottom: $spacing-sm;
      color: $text-color;

      .n-icon {
        flex-shrink: 0;
        color: $success-color;
      }
    }
  }
}

.process-steps {
  display: flex;
  justify-content: space-between;
  gap: $spacing-lg;
  position: relative;

  .process-step {
    flex: 1;
    text-align: center;
    position: relative;
    padding: $spacing-lg;

    .step-number {
      width: 50px;
      height: 50px;
      border-radius: 50%;
      background: linear-gradient(135deg, #1890ff 0%, #722ed1 100%);
      color: #fff;
      font-size: 24px;
      font-weight: 700;
      display: flex;
      align-items: center;
      justify-content: center;
      margin: 0 auto $spacing-md;
      box-shadow: $shadow-md;
    }

    .step-icon {
      margin-bottom: $spacing-md;
    }

    h3 {
      font-size: 18px;
      font-weight: 600;
      margin-bottom: $spacing-sm;
    }

    p {
      color: $text-color-secondary;
      font-size: 14px;
      line-height: 1.6;
    }

    .step-arrow {
      position: absolute;
      top: 50%;
      right: -$spacing-lg;
      transform: translateY(-50%);
      z-index: 1;
    }
  }
}

@media (max-width: 992px) {
  .business-grid {
    :deep(.n-grid-item) {
      width: 50% !important;
    }
  }
}

@media (max-width: 768px) {
  .process-steps {
    flex-direction: column;

    .process-step {
      .step-arrow {
        display: none;
      }
    }
  }

  .business-grid {
    :deep(.n-grid-item) {
      width: 100% !important;
    }
  }
}
</style>

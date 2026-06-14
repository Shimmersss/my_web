<template>
  <div class="cases-page">
    <!-- 筛选器 -->
    <section class="section">
      <div class="container">
        <div class="filter-section">
          <div class="filter-group">
            <span class="filter-label">{{ $t('cases.filterIndustry') }}:</span>
            <n-button
              v-for="industry in industries"
              :key="industry.id"
              :type="selectedIndustry === industry.id ? 'primary' : 'default'"
              @click="selectedIndustry = industry.id"
              size="small"
            >
              {{ industry.name }}
            </n-button>
          </div>
          <div class="filter-group">
            <span class="filter-label">{{ $t('cases.filterType') }}:</span>
            <n-button
              v-for="type in types"
              :key="type.id"
              :type="selectedType === type.id ? 'primary' : 'default'"
              @click="selectedType = type.id"
              size="small"
            >
              {{ type.name }}
            </n-button>
          </div>
        </div>

        <n-grid :x-gap="24" :y-gap="24" :cols="3" class="cases-grid">
          <n-grid-item v-for="caseItem in filteredCases" :key="caseItem.id">
            <div class="case-card" @click="navigateTo(`/cases/${caseItem.id}`)">
              <div class="case-image">
                <img :src="caseItem.image" :alt="caseItem.title">
                <div class="case-tags">
                  <n-tag size="small" type="info">{{ caseItem.industry }}</n-tag>
                  <n-tag size="small" type="success">{{ caseItem.type }}</n-tag>
                </div>
              </div>
              <div class="case-content">
                <h3>{{ caseItem.title }}</h3>
                <p class="case-summary">{{ caseItem.summary }}</p>
                <div class="case-meta">
                  <span><n-icon><CalendarOutline /></n-icon> {{ caseItem.date }}</span>
                  <span><n-icon><PersonOutline /></n-icon> {{ caseItem.client }}</span>
                </div>
                <n-button text type="primary">
                  {{ $t('common.readMore') }} →
                </n-button>
              </div>
            </div>
          </n-grid-item>
        </n-grid>

        <div class="pagination">
          <n-pagination v-model:page="page" :page-count="10" show-quick-jumper />
        </div>
      </div>
    </section>
  </div>
</template>

<script setup>
import { ref, computed } from 'vue'
import { useRouter } from 'vue-router'
import { NButton, NGrid, NGridItem, NTag, NPagination, NIcon } from 'naive-ui'
import { CalendarOutline, PersonOutline } from '@vicons/ionicons5'
import heroImage from '@/assets/images/research-workbench-hero.jpg'
import pipelineImage from '@/assets/images/research-pipeline-panel.jpg'

const router = useRouter()
const page = ref(1)
const selectedIndustry = ref('all')
const selectedType = ref('all')

const industries = ref([
  { id: 'all', name: '全部' },
  { id: 'library', name: '文献' },
  { id: 'translate', name: '翻译' },
  { id: 'ppt', name: 'PPT' },
  { id: 'chat', name: '对话' },
  { id: 'ops', name: '运维' }
])

const types = ref([
  { id: 'all', name: '全部' },
  { id: 'frontend', name: '前端体验' },
  { id: 'backend', name: '后端链路' },
  { id: 'asset', name: '产物生成' },
  { id: 'deploy', name: '部署验证' }
])

const cases = ref([
  {
    id: 1,
    title: 'Zotero 文献检索样例',
    summary: '通过缓存接口查看条目、附件和引用导出',
    industry: '文献',
    type: '前端体验',
    date: '2026-06-11',
    client: '研究资料',
    image: pipelineImage
  },
  {
    id: 2,
    title: '论文翻译预览样例',
    summary: '选择页码范围并检查纯中文 / 双语 PDF',
    industry: '翻译',
    type: '产物生成',
    date: '2026-06-11',
    client: 'PDF 论文',
    image: heroImage
  },
  {
    id: 3,
    title: '答辩 PPT 生成样例',
    summary: '上传论文和可选模板，生成可编辑 PPTX',
    industry: 'PPT',
    type: '产物生成',
    date: '2026-06-11',
    client: '答辩材料',
    image: pipelineImage
  },
  {
    id: 4,
    title: 'OpenClaw 会话样例',
    summary: '读取模型、历史和产物下载入口',
    industry: '对话',
    type: '后端链路',
    date: '2026-06-11',
    client: '本机会话',
    image: heroImage
  },
  {
    id: 5,
    title: 'GitHub README 代理样例',
    summary: '由后端获取仓库信息和 README',
    industry: '运维',
    type: '后端链路',
    date: '2026-06-11',
    client: '开源项目',
    image: pipelineImage
  },
  {
    id: 6,
    title: '低资源部署样例',
    summary: '按 2 核 4GB 基线限制队列、内存和任务保留',
    industry: '运维',
    type: '部署验证',
    date: '2026-06-11',
    client: '生产基线',
    image: heroImage
  }
])

const filteredCases = computed(() => {
  let result = cases.value
  if (selectedIndustry.value !== 'all') {
    result = result.filter(c => c.industry === industries.value.find(i => i.id === selectedIndustry.value).name)
  }
  if (selectedType.value !== 'all') {
    result = result.filter(c => c.type === types.value.find(t => t.id === selectedType.value).name)
  }
  return result
})

const navigateTo = (path) => {
  router.push(path)
}
</script>

<style scoped lang="scss">
@use '@/assets/styles/variables' as *;

.filter-section {
  background: #f5f5f5;
  padding: $spacing-lg;
  border-radius: $border-radius-lg;
  margin-bottom: $spacing-xl;

  .filter-group {
    display: flex;
    align-items: center;
    gap: $spacing-sm;
    flex-wrap: wrap;
    margin-bottom: $spacing-md;

    &:last-child {
      margin-bottom: 0;
    }

    .filter-label {
      font-weight: 600;
      color: $text-color;
    }
  }
}

.cases-grid {
  margin-bottom: $spacing-xl;
}

.case-card {
  background: #fff;
  border-radius: $border-radius-lg;
  overflow: hidden;
  box-shadow: $shadow-sm;
  cursor: pointer;
  transition: all $transition-base;

  &:hover {
    transform: translateY(-8px);
    box-shadow: $shadow-lg;

    .case-image img {
      transform: scale(1.1);
    }
  }

  .case-image {
    position: relative;
    height: 220px;
    overflow: hidden;

    img {
      width: 100%;
      height: 100%;
      object-fit: cover;
      transition: transform $transition-base;
    }

    .case-tags {
      position: absolute;
      top: $spacing-sm;
      left: $spacing-sm;
      display: flex;
      gap: $spacing-xs;
    }
  }

  .case-content {
    padding: $spacing-md;

    h3 {
      font-size: 18px;
      font-weight: 600;
      margin-bottom: $spacing-sm;
    }

    .case-summary {
      color: $text-color-secondary;
      font-size: 14px;
      line-height: 1.6;
      margin-bottom: $spacing-md;
    }

    .case-meta {
      display: flex;
      gap: $spacing-md;
      margin-bottom: $spacing-md;
      font-size: 14px;
      color: $text-color-light;

      span {
        display: flex;
        align-items: center;
        gap: 4px;

        .n-icon {
          font-size: 16px;
        }
      }
    }
  }
}

.pagination {
  display: flex;
  justify-content: center;
}

@media (max-width: 992px) {
  .cases-grid {
    :deep(.n-grid-item) {
      width: 50% !important;
    }
  }
}

@media (max-width: 768px) {
  .cases-grid {
    :deep(.n-grid-item) {
      width: 100% !important;
    }
  }

  .filter-section {
    .filter-group {
      flex-direction: column;
      align-items: flex-start;

      .filter-label {
        margin-bottom: $spacing-xs;
      }
    }
  }
}
</style>

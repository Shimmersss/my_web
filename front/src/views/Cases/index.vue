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

const router = useRouter()
const page = ref(1)
const selectedIndustry = ref('all')
const selectedType = ref('all')

const industries = ref([
  { id: 'all', name: '全部' },
  { id: 'finance', name: '金融' },
  { id: 'retail', name: '零售' },
  { id: 'manufacturing', name: '制造' },
  { id: 'technology', name: '科技' },
  { id: 'education', name: '教育' }
])

const types = ref([
  { id: 'all', name: '全部' },
  { id: 'transformation', name: '数字化转型' },
  { id: 'ai', name: '人工智能' },
  { id: 'cloud', name: '云计算' },
  { id: 'data', name: '数据分析' }
])

const cases = ref([
  {
    id: 1,
    title: '某大型银行数字化转型',
    summary: '帮助客户实现全渠道数字化升级，提升客户体验',
    industry: '金融',
    type: '数字化转型',
    date: '2024-01-15',
    client: '某大型银行',
    image: 'https://picsum.photos/400/300'
  },
  {
    id: 2,
    title: '电商平台智能推荐系统',
    summary: 'AI驱动的个性化推荐，提升转化率30%',
    industry: '零售',
    type: '人工智能',
    date: '2024-01-10',
    client: '某电商平台',
    image: 'https://picsum.photos/400/300'
  },
  {
    id: 3,
    title: '智能制造平台搭建',
    summary: '实现生产流程数字化，效率提升50%',
    industry: '制造',
    type: '数字化转型',
    date: '2024-01-05',
    client: '某制造企业',
    image: 'https://picsum.photos/400/300'
  },
  {
    id: 4,
    title: '企业云迁移项目',
    summary: '平稳迁移至云端，成本降低40%',
    industry: '科技',
    type: '云计算',
    date: '2023-12-20',
    client: '某科技公司',
    image: 'https://picsum.photos/400/300'
  },
  {
    id: 5,
    title: '在线教育平台开发',
    summary: '打造一站式在线学习平台',
    industry: '教育',
    type: '数字化转型',
    date: '2023-12-15',
    client: '某教育机构',
    image: 'https://picsum.photos/400/300'
  },
  {
    id: 6,
    title: '大数据分析平台',
    summary: '构建企业级数据分析平台，辅助决策',
    industry: '金融',
    type: '数据分析',
    date: '2023-12-10',
    client: '某金融机构',
    image: 'https://picsum.photos/400/300'
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

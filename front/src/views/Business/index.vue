<template>
  <div class="business-page">
    <!-- 业务分类导航 -->
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

    <!-- 解决方案 -->
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

    <!-- 服务流程 -->
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

const router = useRouter()
const selectedCategory = ref('all')

const categories = ref([
  { id: 'all', name: '全部' },
  { id: 'cloud', name: '云计算' },
  { id: 'data', name: '数据分析' },
  { id: 'consult', name: '技术咨询' },
  { id: 'ai', name: '人工智能' },
  { id: 'mobile', name: '移动开发' },
  { id: 'security', name: '安全服务' }
])

const services = ref([
  {
    id: 1,
    category: 'cloud',
    title: '云计算服务',
    description: '提供稳定可靠的云计算解决方案，帮助企业实现云转型',
    icon: CloudOutline,
    color: '#1890ff',
    image: 'https://picsum.photos/400/300'
  },
  {
    id: 2,
    category: 'data',
    title: '数据分析',
    description: '专业的数据分析与商业智能服务，洞察数据价值',
    icon: AnalyticsOutline,
    color: '#52c41a',
    image: 'https://picsum.photos/400/300'
  },
  {
    id: 3,
    category: 'consult',
    title: '技术咨询',
    description: '全方位的技术咨询与架构设计，赋能企业发展',
    icon: BuildOutline,
    color: '#faad14',
    image: 'https://picsum.photos/400/300'
  },
  {
    id: 4,
    category: 'ai',
    title: '人工智能',
    description: 'AI驱动的智能化解决方案，引领行业创新',
    icon: RocketOutline,
    color: '#722ed1',
    image: 'https://picsum.photos/400/300'
  },
  {
    id: 5,
    category: 'mobile',
    title: '移动开发',
    description: '跨平台移动应用开发服务，覆盖iOS和Android',
    icon: CloudOutline,
    color: '#eb2f96',
    image: 'https://picsum.photos/400/300'
  },
  {
    id: 6,
    category: 'security',
    title: '安全服务',
    description: '企业级安全防护体系，保障业务安全',
    icon: CheckmarkCircleOutline,
    color: '#fa541c',
    image: 'https://picsum.photos/400/300'
  }
])

const solutions = ref([
  {
    id: 1,
    title: '企业数字化转型',
    description: '提供从战略规划到落地实施的全流程数字化转型服务，帮助企业提升竞争力',
    icon: RocketOutline,
    color: '#1890ff',
    features: ['战略规划', '流程优化', '技术实施', '持续优化']
  },
  {
    id: 2,
    title: '智能制造解决方案',
    description: '结合IoT、AI等技术，打造智能化生产体系，提升生产效率和产品质量',
    icon: BuildOutline,
    color: '#52c41a',
    features: ['设备联网', '数据分析', '预测维护', '质量监控']
  }
])

const process = ref([
  {
    id: 1,
    title: '需求分析',
    description: '深入了解客户需求，制定详细的解决方案',
    icon: ChatbubbleEllipsesOutline
  },
  {
    id: 2,
    title: '方案设计',
    description: '设计专业的技术方案和实施计划',
    icon: DocumentTextOutline
  },
  {
    id: 3,
    title: '开发实施',
    description: '按照方案进行开发，确保项目质量',
    icon: BuildOutline
  },
  {
    id: 4,
    title: '测试验收',
    description: '进行全面的测试，确保系统稳定可靠',
    icon: CheckmarkCircleOutline
  },
  {
    id: 5,
    title: '上线运维',
    description: '系统上线并提供持续的技术支持',
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

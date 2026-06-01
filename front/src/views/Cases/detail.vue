<template>
  <div class="case-detail">
    <!-- 案例详情 -->
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
                  <div class="stat-label">客户满意度</div>
                </div>
              </div>
            </div>
          </n-grid-item>
        </n-grid>

        <div class="case-actions">
          <n-button type="primary" size="large" @click="showContact = true">
            类似需求，联系我
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

    <!-- 相关案例 -->
    <section class="section" style="background: #f5f5f5">
      <div class="container">
        <h2 class="section__title">相关案例</h2>
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

    <!-- 联系弹窗 -->
    <n-modal v-model:show="showContact" preset="card" title="立即咨询" :style="{ width: '500px' }">
      <n-form ref="formRef" :model="formData" :rules="rules">
        <n-form-item label="姓名" path="name">
          <n-input v-model:value="formData.name" placeholder="请输入您的姓名" />
        </n-form-item>
        <n-form-item label="电话" path="phone">
          <n-input v-model:value="formData.phone" placeholder="请输入您的电话" />
        </n-form-item>
        <n-form-item label="需求描述" path="message">
          <n-input type="textarea" v-model:value="formData.message" placeholder="请简要描述您的需求" :rows="4" />
        </n-form-item>
        <n-button type="primary" block size="large" @click="handleSubmit">
          提交咨询
        </n-button>
      </n-form>
    </n-modal>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted, computed } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useMessage } from 'naive-ui'
import { NModal, NForm, NFormItem, NInput, NButton, NGrid, NGridItem, NIcon } from 'naive-ui'
import {
  CalendarOutline,
  PersonOutline,
  BusinessOutline,
  PricetagsOutline,
  DownloadOutline
} from '@vicons/ionicons5'

const router = useRouter()
const route = useRoute()
const message = useMessage()
const showContact = ref(false)
const formRef = ref(null)

const caseData = ref({
  id: 1,
  title: '某大型银行数字化转型',
  client: '某大型银行',
  industry: '金融',
  type: '数字化转型',
  date: '2024-01-15',
  image: 'https://picsum.photos/1200/600',
  summary: '帮助客户实现全渠道数字化升级，提升客户体验',
  background: '该银行作为国内领先的金融机构，拥有庞大的客户群体和复杂的业务体系。随着数字化浪潮的到来，传统银行业务模式面临巨大挑战。客户期望更便捷、更个性化的服务体验，同时市场竞争日益激烈。银行需要通过数字化转型来提升运营效率、降低成本、增强核心竞争力。',
  solution: '我们为该银行提供了一站式数字化转型解决方案：1. 构建全渠道数字化服务平台，整合线上线下资源；2. 实施大数据分析平台，深入挖掘客户需求；3. 引入人工智能技术，实现智能客服和智能风控；4. 建立敏捷开发体系，快速响应业务变化。整个项目历时18个月，涉及300+系统改造，服务覆盖5000万+客户。',
  result: '项目实施后，该银行数字化转型取得显著成效：1. 客户满意度从75%提升到95%；2. 线上业务占比从30%提升到65%；3. 运营成本降低40%；4. 新产品上线周期从6个月缩短到2个月；5. 风险识别准确率提升30%。该项目成为行业标杆，获得多项创新奖项。',
  stats: {
    efficiency: 60,
    cost: 40,
    satisfaction: 95
  }
})

const formData = reactive({
  name: '',
  phone: '',
  message: ''
})

const rules = {
  name: { required: true, message: '请输入姓名', trigger: 'blur' },
  phone: { required: true, message: '请输入电话', trigger: 'blur' },
  message: { required: true, message: '请描述您的需求', trigger: 'blur' }
}

const relatedCases = computed(() => [
  {
    id: 2,
    title: '电商平台智能推荐系统',
    summary: 'AI驱动的个性化推荐，提升转化率30%',
    image: 'https://picsum.photos/400/300?random=1'
  },
  {
    id: 3,
    title: '智能制造平台搭建',
    summary: '实现生产流程数字化，效率提升50%',
    image: 'https://picsum.photos/400/300?random=2'
  },
  {
    id: 4,
    title: '企业云迁移项目',
    summary: '平稳迁移至云端，成本降低40%',
    image: 'https://picsum.photos/400/300?random=3'
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
  message.info('案例下载功能开发中...')
}

const handleSubmit = () => {
  formRef.value?.validate((errors) => {
    if (!errors) {
      message.success('提交成功！我们会尽快联系您。')
      showContact.value = false
      Object.assign(formData, { name: '', phone: '', message: '' })
    }
  })
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

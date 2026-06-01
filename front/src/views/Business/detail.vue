<template>
  <div class="business-detail">
    <!-- 业务详情 -->
    <section class="section">
      <div class="container">
        <div class="detail-content">
          <div class="detail-image">
            <img :src="businessData.image" :alt="businessData.title">
          </div>
          <div class="detail-info">
            <h2>业务介绍</h2>
            <p>{{ businessData.introduction }}</p>

            <h3>核心功能</h3>
            <ul class="feature-list">
              <li v-for="feature in businessData.features" :key="feature">
                <n-icon><CheckmarkCircleOutline /></n-icon>
                {{ feature }}
              </li>
            </ul>

            <h3>应用场景</h3>
            <div class="scenarios">
              <n-tag v-for="scenario in businessData.scenarios" :key="scenario" type="info" size="large">
                {{ scenario }}
              </n-tag>
            </div>

            <div class="detail-actions">
              <n-button type="primary" size="large" @click="showContact = true">
                立即咨询
              </n-button>
              <n-button size="large" @click="navigateTo('/contact')">
                联系我们
              </n-button>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- 相关业务 -->
    <section class="section" style="background: #f5f5f5">
      <div class="container">
        <h2 class="section__title">相关业务</h2>
        <n-grid :x-gap="24" :y-gap="24" :cols="3">
          <n-grid-item v-for="item in relatedBusiness" :key="item.id">
            <div class="related-card" @click="navigateTo(`/business/${item.id}`)">
              <img :src="item.image" :alt="item.title">
              <h3>{{ item.title }}</h3>
              <p>{{ item.description }}</p>
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
import { NModal, NForm, NFormItem, NInput, NButton, NGrid, NGridItem, NTag, NIcon } from 'naive-ui'
import { CheckmarkCircleOutline } from '@vicons/ionicons5'

const router = useRouter()
const route = useRoute()
const message = useMessage()
const showContact = ref(false)
const formRef = ref(null)

const businessData = ref({
  id: 1,
  title: '云计算服务',
  description: '提供稳定可靠的云计算解决方案',
  introduction: '我们的云计算服务提供稳定、安全、可扩展的云基础设施，帮助企业快速部署应用，降低IT成本，提升业务灵活性。我们拥有经验丰富的技术团队，为您提供7x24小时的技术支持。',
  features: [
    '弹性计算，按需扩容',
    '高可用架构，保障业务稳定',
    '安全防护，多重安全保障',
    '全球加速，快速响应',
    '自动化运维，降低人工成本'
  ],
  scenarios: ['企业上云', '应用部署', '数据备份', '负载均衡', '容灾恢复'],
  image: 'https://picsum.photos/800/600'
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

const relatedBusiness = computed(() => [
  {
    id: 2,
    title: '数据分析',
    description: '专业的数据分析与商业智能服务',
    image: 'https://picsum.photos/400/300?random=4'
  },
  {
    id: 3,
    title: '技术咨询',
    description: '全方位的技术咨询与架构设计',
    image: 'https://picsum.photos/400/300?random=5'
  },
  {
    id: 4,
    title: '人工智能',
    description: 'AI驱动的智能化解决方案',
    image: 'https://picsum.photos/400/300?random=6'
  }
])

onMounted(() => {
  const id = route.params.id
  // 实际项目中这里应该从API获取数据
  console.log('Loading business detail:', id)
})

const navigateTo = (path) => {
  router.push(path)
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

.detail-content {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: $spacing-xl;
  align-items: start;

  .detail-image {
    img {
      width: 100%;
      border-radius: $border-radius-lg;
      box-shadow: $shadow-lg;
    }
  }

  .detail-info {
    h2 {
      font-size: 32px;
      font-weight: 600;
      margin-bottom: $spacing-md;
      color: $primary-color;
    }

    h3 {
      font-size: 24px;
      font-weight: 600;
      margin: $spacing-xl 0 $spacing-md;
    }

    p {
      font-size: 16px;
      line-height: 1.8;
      color: $text-color-secondary;
      margin-bottom: $spacing-md;
    }

    .feature-list {
      list-style: none;
      padding: 0;

      li {
        display: flex;
        align-items: center;
        gap: $spacing-sm;
        margin-bottom: $spacing-sm;
        color: $text-color;
        font-size: 16px;

        .n-icon {
          flex-shrink: 0;
          color: $success-color;
          font-size: 20px;
        }
      }
    }

    .scenarios {
      display: flex;
      flex-wrap: wrap;
      gap: $spacing-sm;
      margin-bottom: $spacing-xl;
    }

    .detail-actions {
      display: flex;
      gap: $spacing-md;
    }
  }
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
  .detail-content {
    grid-template-columns: 1fr;
  }
}
</style>

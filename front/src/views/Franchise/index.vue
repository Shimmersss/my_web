<template>
  <div class="franchise-page">
    <!-- 加盟优势 -->
    <section class="section">
      <div class="container">
        <h2 class="section__title">{{ $t('franchise.benefits') }}</h2>
        <n-grid :x-gap="24" :y-gap="24" :cols="3">
          <n-grid-item v-for="benefit in benefits" :key="benefit.id">
            <div class="benefit-card">
              <div class="benefit-icon">
                <n-icon size="48" :color="benefit.color">
                  <component :is="benefit.icon" />
                </n-icon>
              </div>
              <h3>{{ benefit.title }}</h3>
              <p>{{ benefit.description }}</p>
            </div>
          </n-grid-item>
        </n-grid>
      </div>
    </section>

    <!-- 加盟流程 -->
    <section class="section" style="background: #f5f5f5">
      <div class="container">
        <h2 class="section__title">{{ $t('franchise.process') }}</h2>
        <div class="process-timeline">
          <div v-for="(step, index) in process" :key="step.id" class="process-item">
            <div class="process-number">{{ index + 1 }}</div>
            <div class="process-content">
              <h3>{{ step.title }}</h3>
              <p>{{ step.description }}</p>
            </div>
            <div v-if="index < process.length - 1" class="process-line"></div>
          </div>
        </div>
      </div>
    </section>

    <!-- 加盟条件 -->
    <section class="section">
      <div class="container">
        <h2 class="section__title">{{ $t('franchise.requirements') }}</h2>
        <div class="requirements-grid">
          <div v-for="req in requirements" :key="req.id" class="requirement-card">
            <div class="requirement-header">
              <n-icon size="32" color="#1890ff"><CheckmarkCircleOutline /></n-icon>
              <h3>{{ req.title }}</h3>
            </div>
            <p>{{ req.content }}</p>
          </div>
        </div>
      </div>
    </section>

    <!-- 加盟申请 -->
    <section class="section" style="background: linear-gradient(135deg, #1890ff 0%, #722ed1 100%); color: #fff">
      <div class="container">
        <div class="apply-wrapper">
          <div class="apply-info">
            <h2>立即申请加盟</h2>
            <p>填写以下信息，我们的招商经理会尽快与您联系</p>
            <ul>
              <li><n-icon><CheckmarkCircleOutline /></n-icon> 品牌支持</li>
              <li><n-icon><CheckmarkCircleOutline /></n-icon> 培训支持</li>
              <li><n-icon><CheckmarkCircleOutline /></n-icon> 运营支持</li>
              <li><n-icon><CheckmarkCircleOutline /></n-icon> 技术支持</li>
            </ul>
          </div>
          <div class="apply-form">
            <n-form ref="formRef" :model="formData" :rules="rules" label-placement="top">
              <n-form-item label="姓名" path="name">
                <n-input v-model:value="formData.name" placeholder="请输入您的姓名" size="large" />
              </n-form-item>
              <n-form-item label="电话" path="phone">
                <n-input v-model:value="formData.phone" placeholder="请输入您的电话" size="large" />
              </n-form-item>
              <n-form-item label="城市" path="city">
                <n-input v-model:value="formData.city" placeholder="请输入您所在城市" size="large" />
              </n-form-item>
              <n-form-item label="意向区域" path="area">
                <n-input v-model:value="formData.area" placeholder="请输入意向加盟区域" size="large" />
              </n-form-item>
              <n-form-item label="投资预算" path="budget">
                <n-select
                  v-model:value="formData.budget"
                  :options="budgetOptions"
                  placeholder="请选择投资预算"
                  size="large"
                />
              </n-form-item>
              <n-form-item label="备注" path="note">
                <n-input
                  type="textarea"
                  v-model:value="formData.note"
                  placeholder="请备注其他信息（选填）"
                  :rows="4"
                  size="large"
                />
              </n-form-item>
              <n-form-item>
                <n-button type="primary" size="large" block @click="handleSubmit" :loading="submitting">
                  {{ $t('franchise.apply') }}
                </n-button>
              </n-form-item>
            </n-form>
          </div>
        </div>
      </div>
    </section>

    <!-- 成功案例 -->
    <section class="section">
      <div class="container">
        <h2 class="section__title">加盟成功案例</h2>
        <n-grid :x-gap="24" :y-gap="24" :cols="3">
          <n-grid-item v-for="item in successCases" :key="item.id">
            <div class="success-card">
              <div class="success-image">
                <img :src="item.image" :alt="item.name">
              </div>
              <div class="success-content">
                <h3>{{ item.name }}</h3>
                <p>{{ item.location }}</p>
                <div class="success-stats">
                  <div class="stat">
                    <span class="stat-value">{{ item.month }}</span>
                    <span class="stat-label">月营业额</span>
                  </div>
                  <div class="stat">
                    <span class="stat-value">{{ item.profit }}</span>
                    <span class="stat-label">月利润</span>
                  </div>
                </div>
              </div>
            </div>
          </n-grid-item>
        </n-grid>
      </div>
    </section>
  </div>
</template>

<script setup>
import { ref, reactive } from 'vue'
import { useMessage } from 'naive-ui'
import { NGrid, NGridItem, NForm, NFormItem, NInput, NButton, NSelect, NIcon } from 'naive-ui'
import {
  CheckmarkCircleOutline,
  RibbonOutline,
  PeopleOutline,
  TrendingUpOutline,
  SchoolOutline,
  ConstructOutline
} from '@vicons/ionicons5'

const message = useMessage()
const submitting = ref(false)
const formRef = ref(null)

const benefits = ref([
  {
    id: 1,
    title: '品牌优势',
    description: '知名品牌，市场认可度高，快速建立信任',
    icon: RibbonOutline,
    color: '#1890ff'
  },
  {
    id: 2,
    title: '技术支持',
    description: '专业技术团队提供全方位技术支持',
    icon: ConstructOutline,
    color: '#52c41a'
  },
  {
    id: 3,
    title: '培训体系',
    description: '完善的培训体系，快速掌握运营技巧',
    icon: SchoolOutline,
    color: '#faad14'
  },
  {
    id: 4,
    title: '运营指导',
    description: '专业团队提供运营指导，降低经营风险',
    icon: TrendingUpOutline,
    color: '#722ed1'
  },
  {
    id: 5,
    title: '市场推广',
    description: '总部统一市场推广，提升品牌影响力',
    icon: PeopleOutline,
    color: '#eb2f96'
  },
  {
    id: 6,
    title: '利润空间',
    description: '合理的利润空间，实现共赢发展',
    icon: CheckmarkCircleOutline,
    color: '#fa541c'
  }
])

const process = ref([
  { id: 1, title: '在线咨询', description: '通过官网或电话咨询加盟事宜' },
  { id: 2, title: '提交申请', description: '填写加盟申请表，提交相关资料' },
  { id: 3, title: '资质审核', description: '总部对申请人资质进行审核' },
  { id: 4, title: '实地考察', description: '预约总部实地考察，了解详情' },
  { id: 5, title: '签约合作', description: '签订合作协议，缴纳加盟费用' },
  { id: 6, title: '开业筹备', description: '总部协助选址装修，培训员工' },
  { id: 7, title: '正式开业', description: '正式开业运营，总部持续支持' }
])

const requirements = ref([
  {
    id: 1,
    title: '资金要求',
    content: '具备一定的启动资金，能够承担初期投资和运营成本'
  },
  {
    id: 2,
    title: '经验要求',
    content: '有相关行业经验者优先，或具备较强的学习能力'
  },
  {
    id: 3,
    title: '管理能力',
    content: '具备基本的团队管理和运营管理能力'
  },
  {
    id: 4,
    title: '经营理念',
    content: '认同公司品牌理念和经营模式，愿意共同发展'
  },
  {
    id: 5,
    title: '商业信誉',
    content: '具有良好的商业信誉和个人信用记录'
  },
  {
    id: 6,
    title: '精力投入',
    content: '能够投入足够的时间和精力经营管理'
  }
])

const formData = reactive({
  name: '',
  phone: '',
  city: '',
  area: '',
  budget: null,
  note: ''
})

const budgetOptions = [
  { label: '10万以下', value: '10' },
  { label: '10-30万', value: '10-30' },
  { label: '30-50万', value: '30-50' },
  { label: '50-100万', value: '50-100' },
  { label: '100万以上', value: '100+' }
]

const rules = {
  name: { required: true, message: '请输入姓名', trigger: 'blur' },
  phone: { required: true, message: '请输入电话', trigger: 'blur' },
  city: { required: true, message: '请输入城市', trigger: 'blur' },
  area: { required: true, message: '请输入意向区域', trigger: 'blur' },
  budget: { required: true, message: '请选择投资预算', trigger: 'change' }
}

const successCases = ref([
  {
    id: 1,
    name: '张先生',
    location: '北京市朝阳区',
    month: '50万',
    profit: '15万',
    image: 'https://picsum.photos/200/200'
  },
  {
    id: 2,
    name: '李女士',
    location: '上海市浦东新区',
    month: '80万',
    profit: '25万',
    image: 'https://picsum.photos/200/200'
  },
  {
    id: 3,
    name: '王先生',
    location: '广州市天河区',
    month: '60万',
    profit: '18万',
    image: 'https://picsum.photos/200/200'
  }
])

const handleSubmit = () => {
  formRef.value?.validate((errors) => {
    if (!errors) {
      submitting.value = true
      setTimeout(() => {
        message.success('提交成功！我们的招商经理会尽快与您联系。')
        submitting.value = false
        Object.assign(formData, { name: '', phone: '', city: '', area: '', budget: null, note: '' })
      }, 1000)
    }
  })
}
</script>

<style scoped lang="scss">
@use '@/assets/styles/variables' as *;

.benefit-card {
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

  .benefit-icon {
    margin-bottom: $spacing-md;
  }

  h3 {
    font-size: 20px;
    font-weight: 600;
    margin-bottom: $spacing-sm;
  }

  p {
    color: $text-color-secondary;
    font-size: 14px;
    line-height: 1.6;
  }
}

.process-timeline {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: $spacing-xl;
  position: relative;

  .process-item {
    position: relative;
    text-align: center;
    padding: $spacing-lg;

    .process-number {
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

    .process-content {
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

    .process-line {
      position: absolute;
      top: 25px;
      right: -20px;
      width: 40px;
      height: 2px;
      background: linear-gradient(90deg, #1890ff, #722ed1);
    }
  }
}

.requirements-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: $spacing-lg;

  .requirement-card {
    background: #fff;
    padding: $spacing-lg;
    border-radius: $border-radius-lg;
    box-shadow: $shadow-sm;
    transition: all $transition-base;

    &:hover {
      box-shadow: $shadow-md;
    }

    .requirement-header {
      display: flex;
      align-items: center;
      gap: $spacing-md;
      margin-bottom: $spacing-sm;

      h3 {
        font-size: 18px;
        font-weight: 600;
      }
    }

    p {
      color: $text-color-secondary;
      line-height: 1.6;
    }
  }
}

.apply-wrapper {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: $spacing-xxl;
  align-items: center;

  .apply-info {
    h2 {
      font-size: 36px;
      margin-bottom: $spacing-md;
    }

    p {
      font-size: 18px;
      opacity: 0.9;
      margin-bottom: $spacing-xl;
    }

    ul {
      list-style: none;
      padding: 0;

      li {
        display: flex;
        align-items: center;
        gap: $spacing-sm;
        margin-bottom: $spacing-md;
        font-size: 18px;
      }
    }
  }

  .apply-form {
    background: #fff;
    padding: $spacing-xl;
    border-radius: $border-radius-lg;
    box-shadow: $shadow-lg;
  }
}

.success-card {
  background: #fff;
  border-radius: $border-radius-lg;
  overflow: hidden;
  box-shadow: $shadow-sm;
  transition: all $transition-base;

  &:hover {
    transform: translateY(-4px);
    box-shadow: $shadow-md;
  }

  .success-image {
    height: 200px;
    overflow: hidden;

    img {
      width: 100%;
      height: 100%;
      object-fit: cover;
    }
  }

  .success-content {
    padding: $spacing-md;

    h3 {
      font-size: 18px;
      font-weight: 600;
      margin-bottom: $spacing-xs;
    }

    p {
      color: $text-color-secondary;
      font-size: 14px;
      margin-bottom: $spacing-md;
    }

    .success-stats {
      display: grid;
      grid-template-columns: 1fr 1fr;
      gap: $spacing-md;

      .stat {
        text-align: center;
        padding: $spacing-sm;
        background: #f5f5f5;
        border-radius: $border-radius-base;

        .stat-value {
          display: block;
          font-size: 20px;
          font-weight: 700;
          color: $primary-color;
          margin-bottom: $spacing-xs;
        }

        .stat-label {
          font-size: 12px;
          color: $text-color-secondary;
        }
      }
    }
  }
}

@media (max-width: 992px) {
  .process-timeline {
    grid-template-columns: repeat(2, 1fr);
  }

  .apply-wrapper {
    grid-template-columns: 1fr;
  }

  .requirements-grid {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 768px) {
  .process-timeline {
    grid-template-columns: 1fr;

    .process-line {
      display: none !important;
    }
  }
}
</style>

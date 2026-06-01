<template>
  <div class="contact-page">
    <!-- 联系内容 -->
    <section class="section">
      <div class="container">
        <n-grid :x-gap="48" :y-gap="24" :cols="2">
          <n-grid-item>
            <div class="contact-info">
              <h2>联系方式</h2>
              <p>如果您有任何疑问或需求，欢迎随时联系我们</p>

              <div class="contact-items">
                <div class="contact-item">
                  <div class="contact-icon">
                    <n-icon size="32"><LocationOutline /></n-icon>
                  </div>
                  <div class="contact-content">
                    <span>{{ $t('contact.address') }}</span>
                    <p>北京市朝阳区XX路XX号XX大厦XX层</p>
                  </div>
                </div>
                <div class="contact-item">
                  <div class="contact-icon">
                    <n-icon size="32"><CallOutline /></n-icon>
                  </div>
                  <div class="contact-content">
                    <span>{{ $t('contact.phone') }}</span>
                    <p>400-XXX-XXXX</p>
                  </div>
                </div>
                <div class="contact-item">
                  <div class="contact-icon">
                    <n-icon size="32"><MailOutline /></n-icon>
                  </div>
                  <div class="contact-content">
                    <span>{{ $t('contact.email') }}</span>
                    <p>contact@company.com</p>
                  </div>
                </div>
                <div class="contact-item">
                  <div class="contact-icon">
                    <n-icon size="32"><TimeOutline /></n-icon>
                  </div>
                  <div class="contact-content">
                    <span>工作时间</span>
                    <p>周一至周五 9:00-18:00</p>
                  </div>
                </div>
              </div>

              <div class="contact-map">
                <div class="map-placeholder">
                  <n-icon size="80" color="#1890ff"><MapOutline /></n-icon>
                  <p>地图位置</p>
                </div>
              </div>

              <div class="social-links">
                <span>关注我们：</span>
                <n-button circle size="large" class="social-btn">
                  <n-icon size="20"><LogoWechat /></n-icon>
                </n-button>
                <n-button circle size="large" class="social-btn">
                  <n-icon size="20"><LogoWeibo /></n-icon>
                </n-button>
                <n-button circle size="large" class="social-btn">
                  <n-icon size="20"><LogoLinkedin /></n-icon>
                </n-button>
              </div>
            </div>
          </n-grid-item>

          <n-grid-item>
            <div class="contact-form-wrapper">
              <div class="contact-tabs">
                <n-tabs v-model:value="activeTab" type="segment">
                  <n-tab-pane name="message" tab="在线留言">
                    <n-form ref="formRef" :model="formData" :rules="rules" label-placement="left">
                      <n-form-item label="姓名" path="name">
                        <n-input v-model:value="formData.name" placeholder="请输入您的姓名" size="large" />
                      </n-form-item>
                      <n-form-item label="电话" path="phone">
                        <n-input v-model:value="formData.phone" placeholder="请输入您的电话" size="large" />
                      </n-form-item>
                      <n-form-item label="邮箱" path="email">
                        <n-input v-model:value="formData.email" placeholder="请输入您的邮箱" size="large" />
                      </n-form-item>
                      <n-form-item label="公司" path="company">
                        <n-input v-model:value="formData.company" placeholder="请输入您的公司名称（选填）" size="large" />
                      </n-form-item>
                      <n-form-item label="需求" path="type">
                        <n-select
                          v-model:value="formData.type"
                          :options="typeOptions"
                          placeholder="请选择需求类型"
                          size="large"
                        />
                      </n-form-item>
                      <n-form-item label="留言" path="message">
                        <n-input
                          type="textarea"
                          v-model:value="formData.message"
                          placeholder="请描述您的需求或问题"
                          :rows="5"
                          size="large"
                        />
                      </n-form-item>
                      <n-form-item>
                        <n-button type="primary" size="large" block @click="handleSubmit" :loading="submitting">
                          {{ $t('contact.submit') }}
                        </n-button>
                      </n-form-item>
                    </n-form>
                  </n-tab-pane>
                  <n-tab-pane name="appointment" tab="预约咨询">
                    <n-form ref="appointmentRef" :model="appointmentData" :rules="appointmentRules" label-placement="left">
                      <n-form-item label="姓名" path="name">
                        <n-input v-model:value="appointmentData.name" placeholder="请输入您的姓名" size="large" />
                      </n-form-item>
                      <n-form-item label="电话" path="phone">
                        <n-input v-model:value="appointmentData.phone" placeholder="请输入您的电话" size="large" />
                      </n-form-item>
                      <n-form-item label="预约时间" path="date">
                        <n-date-picker
                          v-model:value="appointmentData.date"
                          type="datetime"
                          placeholder="请选择预约时间"
                          size="large"
                          style="width: 100%"
                        />
                      </n-form-item>
                      <n-form-item label="咨询事项" path="topic">
                        <n-select
                          v-model:value="appointmentData.topic"
                          :options="topicOptions"
                          placeholder="请选择咨询事项"
                          size="large"
                        />
                      </n-form-item>
                      <n-form-item label="备注" path="note">
                        <n-input
                          type="textarea"
                          v-model:value="appointmentData.note"
                          placeholder="请备注其他信息（选填）"
                          :rows="4"
                          size="large"
                        />
                      </n-form-item>
                      <n-form-item>
                        <n-button type="primary" size="large" block @click="handleAppointment" :loading="submitting">
                          提交预约
                        </n-button>
                      </n-form-item>
                    </n-form>
                  </n-tab-pane>
                </n-tabs>
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
import {
  NTabs, NTabPane, NForm, NFormItem, NInput, NButton, NSelect, NDatePicker, NGrid, NGridItem, NIcon
} from 'naive-ui'
import {
  LocationOutline,
  CallOutline,
  MailOutline,
  TimeOutline,
  MapOutline,
  LogoWechat,
  ShareSocial,
  LogoLinkedin
} from '@vicons/ionicons5'

const LogoWeibo = ShareSocial

const message = useMessage()
const activeTab = ref('message')
const submitting = ref(false)
const formRef = ref(null)
const appointmentRef = ref(null)

const formData = reactive({
  name: '',
  phone: '',
  email: '',
  company: '',
  type: null,
  message: ''
})

const appointmentData = reactive({
  name: '',
  phone: '',
  date: null,
  topic: null,
  note: ''
})

const typeOptions = [
  { label: '业务咨询', value: 'consult' },
  { label: '技术支持', value: 'support' },
  { label: '商务合作', value: 'business' },
  { label: '人才招聘', value: 'hr' },
  { label: '其他', value: 'other' }
]

const topicOptions = [
  { label: '业务咨询', value: 'consult' },
  { label: '产品演示', value: 'demo' },
  { label: '方案讨论', value: 'solution' },
  { label: '技术交流', value: 'tech' }
]

const rules = {
  name: { required: true, message: '请输入姓名', trigger: 'blur' },
  phone: { required: true, message: '请输入电话', trigger: 'blur' },
  email: { type: 'email', message: '请输入有效的邮箱地址', trigger: 'blur' },
  type: { required: true, message: '请选择需求类型', trigger: 'change' },
  message: { required: true, message: '请输入留言内容', trigger: 'blur' }
}

const appointmentRules = {
  name: { required: true, message: '请输入姓名', trigger: 'blur' },
  phone: { required: true, message: '请输入电话', trigger: 'blur' },
  date: { required: true, message: '请选择预约时间', trigger: 'change' },
  topic: { required: true, message: '请选择咨询事项', trigger: 'change' }
}

const handleSubmit = () => {
  formRef.value?.validate((errors) => {
    if (!errors) {
      submitting.value = true
      setTimeout(() => {
        message.success('提交成功！我们会尽快联系您。')
        submitting.value = false
        Object.assign(formData, { name: '', phone: '', email: '', company: '', type: null, message: '' })
      }, 1000)
    }
  })
}

const handleAppointment = () => {
  appointmentRef.value?.validate((errors) => {
    if (!errors) {
      submitting.value = true
      setTimeout(() => {
        message.success('预约成功！我们会按时联系您。')
        submitting.value = false
        Object.assign(appointmentData, { name: '', phone: '', date: null, topic: null, note: '' })
      }, 1000)
    }
  })
}
</script>

<style scoped lang="scss">
@use '@/assets/styles/variables' as *;

.contact-info {
  h2 {
    font-size: 32px;
    font-weight: 600;
    margin-bottom: $spacing-md;
  }

  p {
    color: $text-color-secondary;
    margin-bottom: $spacing-xl;
    font-size: 18px;
  }

  .contact-items {
    margin-bottom: $spacing-xl;
  }

  .contact-item {
    display: flex;
    gap: $spacing-lg;
    margin-bottom: $spacing-lg;

    .contact-icon {
      width: 60px;
      height: 60px;
      border-radius: $border-radius-lg;
      background: linear-gradient(135deg, #1890ff 0%, #722ed1 100%);
      display: flex;
      align-items: center;
      justify-content: center;
      flex-shrink: 0;
      color: #fff;
    }

    .contact-content {
      flex: 1;

      span {
        font-weight: 600;
        display: block;
        margin-bottom: $spacing-xs;
        font-size: 16px;
      }

      p {
        color: $text-color-secondary;
        margin: 0;
        font-size: 15px;
      }
    }
  }

  .contact-map {
    height: 300px;
    background: #f5f5f5;
    border-radius: $border-radius-lg;
    margin-bottom: $spacing-xl;
    display: flex;
    align-items: center;
    justify-content: center;

    .map-placeholder {
      text-align: center;
      color: $text-color-light;

      p {
        margin-top: $spacing-sm;
        font-size: 16px;
      }
    }
  }

  .social-links {
    display: flex;
    align-items: center;
    gap: $spacing-md;

    span {
      font-weight: 600;
    }

    .social-btn {
      transition: all $transition-fast;

      &:hover {
        transform: translateY(-2px);
      }
    }
  }
}

.contact-form-wrapper {
  background: #fff;
  padding: $spacing-xl;
  border-radius: $border-radius-lg;
  box-shadow: $shadow-md;
}

@media (max-width: 992px) {
  .contact-form-wrapper {
    padding: $spacing-lg;
  }
}

@media (max-width: 768px) {
  .contact-item {
    flex-direction: column;
    align-items: flex-start;
    gap: $spacing-md !important;
  }
}
</style>

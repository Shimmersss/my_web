<template>
  <div class="customer-service">
    <!-- 悬浮按钮 -->
    <button class="float-btn" type="button" aria-label="邮件联系" @click="showMailForm = true">
      <n-icon size="28" color="#fff">
        <MailOutline />
      </n-icon>
    </button>

    <!-- 邮件表单 -->
    <n-modal v-model:show="showMailForm" preset="card" :style="{ width: 'min(92vw, 520px)' }" title="邮件联系">
      <div class="mail-panel">
        <p class="mail-hint">填写信息后会打开本机邮件应用，收件人已设为：</p>
        <div class="mail-address">
          <n-icon size="20"><MailOutline /></n-icon>
          <span>{{ contactEmail }}</span>
          <n-button text type="primary" size="small" @click="copyEmail">
            复制
          </n-button>
        </div>

        <div class="mail-fields">
          <n-input v-model:value="mailForm.name" aria-label="姓名或称呼" placeholder="姓名 / 称呼（选填）" />
          <n-input v-model:value="mailForm.contact" aria-label="联系方式" placeholder="联系方式（邮箱、QQ、微信等，选填）" />
          <n-input v-model:value="mailForm.subject" aria-label="邮件主题" placeholder="主题，例如：PPT 生成问题" />
          <n-input
            v-model:value="mailForm.message"
            type="textarea"
            aria-label="留言内容"
            placeholder="请写下你想反馈的问题、需求或任务链接"
            :autosize="{ minRows: 5, maxRows: 8 }"
          />
        </div>

        <div class="mail-actions">
          <n-button @click="showMailForm = false">取消</n-button>
          <n-button type="primary" @click="sendMail">
            <template #icon>
              <n-icon><SendOutline /></n-icon>
            </template>
            发送邮件
          </n-button>
        </div>
      </div>
    </n-modal>
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { NModal, NInput, NButton, NIcon, useMessage } from 'naive-ui'
import {
  MailOutline,
  SendOutline
} from '@vicons/ionicons5'

const contactEmail = '1241420431@qq.com'
const showMailForm = ref(false)
const message = useMessage()

const mailForm = reactive({
  name: '',
  contact: '',
  subject: '',
  message: ''
})

const buildMailBody = () => [
  `姓名/称呼：${mailForm.name || '未填写'}`,
  `联系方式：${mailForm.contact || '未填写'}`,
  '',
  '留言内容：',
  mailForm.message,
  '',
  `页面来源：${window.location.href}`
].join('\n')

const sendMail = () => {
  if (!mailForm.subject.trim() || !mailForm.message.trim()) {
    message.warning('请至少填写主题和留言内容')
    return
  }

  const subject = encodeURIComponent(`[Research Desk] ${mailForm.subject.trim()}`)
  const body = encodeURIComponent(buildMailBody())
  window.location.href = `mailto:${contactEmail}?subject=${subject}&body=${body}`
  message.success('已打开邮件应用，请确认发送')
}

const copyEmail = async () => {
  try {
    await navigator.clipboard.writeText(contactEmail)
    message.success('邮箱已复制')
  } catch (error) {
    message.info(contactEmail)
  }
}
</script>

<style scoped lang="scss">
@use '@/assets/styles/variables' as *;

.customer-service {
  .float-btn {
    appearance: none;
    border: 0;
    padding: 0;
    position: fixed;
    bottom: $spacing-xl;
    right: $spacing-xl;
    width: 60px;
    height: 60px;
    border-radius: 50%;
    background: $primary-color;
    display: flex;
    align-items: center;
    justify-content: center;
    cursor: pointer;
    box-shadow: $shadow-lg;
    transition: all $transition-base;
    z-index: 999;

    &:hover {
      transform: scale(1.1);
      box-shadow: 0 8px 40px rgba(24, 144, 255, 0.4);
    }

    &:focus-visible {
      outline: 3px solid rgba(24, 144, 255, 0.35);
      outline-offset: 4px;
    }
  }

  .mail-panel {
    display: grid;
    gap: $spacing-md;

    .mail-hint {
      margin: 0;
      color: $text-color-secondary;
      line-height: $line-height;
    }

    .mail-address {
      display: flex;
      align-items: center;
      gap: $spacing-sm;
      padding: $spacing-sm $spacing-md;
      background: $bg-color-secondary;
      border-radius: $border-radius-base;
      color: $text-color;
      font-weight: 600;

      span {
        flex: 1;
        min-width: 0;
        word-break: break-all;
      }
    }

    .mail-fields {
      display: grid;
      gap: $spacing-sm;
    }

    .mail-actions {
      display: flex;
      justify-content: flex-end;
      gap: $spacing-sm;
    }
  }
}

@media (max-width: 768px) {
  .customer-service {
    .float-btn {
      bottom: $spacing-lg;
      right: $spacing-lg;
      width: 50px;
      height: 50px;
    }
  }
}
</style>

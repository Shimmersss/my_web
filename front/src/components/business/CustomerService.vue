<template>
  <div class="customer-service">
    <!-- 悬浮按钮 -->
    <div class="float-btn" @click="showChat = true">
      <n-icon size="28" color="#fff">
        <ChatbubbleEllipsesOutline />
      </n-icon>
    </div>

    <!-- 对话框 -->
    <n-modal v-model:show="showChat" preset="card" :style="{ width: '400px' }" title="在线客服">
      <div class="chat-content">
        <div class="chat-messages" ref="messagesRef">
          <div v-for="msg in messages" :key="msg.id" :class="['message', msg.type]">
            <div class="message-avatar">
              <n-icon size="24">
                <PersonOutline v-if="msg.type === 'customer'" />
                <ChatbubbleEllipsesOutline v-else />
              </n-icon>
            </div>
            <div class="message-content">{{ msg.content }}</div>
          </div>
        </div>
        <div class="chat-input">
          <n-input
            v-model:value="inputMessage"
            placeholder="请输入您的问题..."
            @keyup.enter="sendMessage"
          >
            <template #suffix>
              <n-button text @click="sendMessage">
                <n-icon><SendOutline /></n-icon>
              </n-button>
            </template>
          </n-input>
        </div>
      </div>
    </n-modal>
  </div>
</template>

<script setup>
import { ref, nextTick } from 'vue'
import { NModal, NInput, NButton, NIcon } from 'naive-ui'
import {
  ChatbubbleEllipsesOutline,
  PersonOutline,
  SendOutline
} from '@vicons/ionicons5'

const showChat = ref(false)
const inputMessage = ref('')
const messagesRef = ref(null)

const messages = ref([
  { id: 1, type: 'service', content: '您好！我是智能客服，有什么可以帮您的？' }
])

const sendMessage = () => {
  if (!inputMessage.value.trim()) return

  messages.value.push({
    id: Date.now(),
    type: 'customer',
    content: inputMessage.value
  })

  const customerMsg = inputMessage.value
  inputMessage.value = ''

  nextTick(() => {
    messagesRef.value?.scrollTo({ top: messagesRef.value.scrollHeight })
  })

  setTimeout(() => {
    let reply = '感谢您的咨询，我们的客服人员会尽快联系您。'
    if (customerMsg.includes('价格')) {
      reply = '我们的产品价格根据具体需求而定，您可以留下联系方式，我们会为您详细报价。'
    } else if (customerMsg.includes('合作')) {
      reply = '非常感谢您的合作意向！请访问我们的招商加盟页面了解更多信息。'
    } else if (customerMsg.includes('产品')) {
      reply = '我们提供多种产品和服务，您可以在业务模块页面查看详细介绍。'
    }

    messages.value.push({
      id: Date.now(),
      type: 'service',
      content: reply
    })

    nextTick(() => {
      messagesRef.value?.scrollTo({ top: messagesRef.value.scrollHeight })
    })
  }, 1000)
}
</script>

<style scoped lang="scss">
@use '@/assets/styles/variables' as *;

.customer-service {
  .float-btn {
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
  }

  .chat-content {
    .chat-messages {
      height: 400px;
      overflow-y: auto;
      padding: $spacing-md;
      background: $bg-color-secondary;
      border-radius: $border-radius-base;
      margin-bottom: $spacing-md;

      .message {
        display: flex;
        gap: $spacing-sm;
        margin-bottom: $spacing-md;

        &.customer {
          flex-direction: row-reverse;

          .message-content {
            background: $primary-color;
            color: #fff;
          }
        }

        &.service {
          .message-content {
            background: #fff;
          }
        }

        .message-avatar {
          flex-shrink: 0;
          width: 32px;
          height: 32px;
          border-radius: 50%;
          background: #e0e0e0;
          display: flex;
          align-items: center;
          justify-content: center;
        }

        .message-content {
          max-width: 70%;
          padding: $spacing-sm $spacing-md;
          border-radius: $border-radius-base;
          line-height: $line-height;
        }
      }
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

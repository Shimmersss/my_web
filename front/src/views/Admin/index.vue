<template>
  <div class="admin-page">
    <div class="admin-shell">
      <div class="admin-header">
        <div>
          <h1>账号后台</h1>
          <p>管理邀请码、用户 credits 和任务扣费价格。</p>
        </div>
        <n-button :loading="loading" @click="loadDashboard">刷新</n-button>
      </div>

      <n-alert v-if="!auth.isRoot" type="warning" title="需要 root 账户登录" />
      <n-alert v-if="errorMsg" type="error" :title="errorMsg" closable @close="errorMsg = ''" />

      <template v-if="auth.isRoot">
        <section class="admin-panel">
          <h2>扣费价格</h2>
          <div class="settings-row">
            <n-input-number v-model:value="settings.translationCreditPerPage" :min="1" aria-label="翻译每页 credits" />
            <span>翻译每页 credits</span>
            <n-input-number v-model:value="settings.pptCreditPerTask" :min="1" aria-label="PPT 每次 credits" />
            <span>PPT 每次 credits</span>
            <n-button type="primary" :loading="savingSettings" @click="saveSettings">保存价格</n-button>
          </div>
        </section>

        <section class="admin-panel">
          <h2>生成邀请码</h2>
          <div class="settings-row">
            <n-input v-model:value="inviteForm.code" placeholder="留空自动生成" />
            <n-input-number v-model:value="inviteForm.credits" :min="0" placeholder="初始 credits" />
            <n-input-number v-model:value="inviteForm.maxUses" :min="1" placeholder="可用次数" />
            <n-button type="primary" :loading="creatingInvite" @click="createInvite">生成</n-button>
          </div>
        </section>

        <section class="admin-panel">
          <h2>用户额度</h2>
          <div class="table-wrap">
            <table>
              <thead>
                <tr><th>ID</th><th>用户名</th><th>角色</th><th>余额</th><th>调整</th></tr>
              </thead>
              <tbody>
                <tr v-for="user in users" :key="user.id">
                  <td>{{ user.id }}</td>
                  <td>{{ user.username }}</td>
                  <td>{{ user.role }}</td>
                  <td>{{ user.credits }}</td>
                  <td>
                    <div class="inline-action">
                      <n-input-number v-model:value="adjustForms[user.id]" size="small" />
                      <n-button size="small" @click="adjustCredits(user.id)">应用</n-button>
                    </div>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </section>

        <section class="admin-grid">
          <div class="admin-panel">
            <h2>邀请码</h2>
            <div class="table-wrap compact">
              <table>
                <thead><tr><th>code</th><th>credits</th><th>使用</th><th>启用</th></tr></thead>
                <tbody>
                  <tr v-for="invite in invites" :key="invite.id">
                    <td>{{ invite.code }}</td>
                    <td>{{ invite.credits }}</td>
                    <td>{{ invite.used_count }}/{{ invite.max_uses }}</td>
                    <td>{{ invite.enabled ? '是' : '否' }}</td>
                  </tr>
                </tbody>
              </table>
            </div>
          </div>

          <div class="admin-panel">
            <h2>最近额度流水</h2>
            <div class="table-wrap compact">
              <table>
                <thead><tr><th>用户</th><th>变动</th><th>类型</th><th>任务</th></tr></thead>
                <tbody>
                  <tr v-for="tx in transactions" :key="tx.id">
                    <td>{{ tx.username }}</td>
                    <td>{{ tx.amount }}</td>
                    <td>{{ tx.kind }}</td>
                    <td>{{ tx.task_id || '-' }}</td>
                  </tr>
                </tbody>
              </table>
            </div>
          </div>
        </section>
      </template>
    </div>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { NAlert, NButton, NInput, NInputNumber, useMessage } from 'naive-ui'
import { adjustUserCredits, createInviteCode, getAdminAccounts, updateQuotaSettings } from '@/api'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const message = useMessage()
const loading = ref(false)
const savingSettings = ref(false)
const creatingInvite = ref(false)
const errorMsg = ref('')
const users = ref([])
const invites = ref([])
const transactions = ref([])
const adjustForms = reactive({})
const settings = reactive({
  translationCreditPerPage: 1,
  pptCreditPerTask: 10
})
const inviteForm = reactive({
  code: '',
  credits: 10,
  maxUses: 1
})

onMounted(async () => {
  await auth.refresh().catch(() => {})
  if (auth.isRoot) loadDashboard()
})

async function loadDashboard() {
  loading.value = true
  errorMsg.value = ''
  try {
    const res = await getAdminAccounts()
    users.value = res.data.users || []
    invites.value = res.data.invites || []
    transactions.value = res.data.transactions || []
    Object.assign(settings, res.data.settings || {})
  } catch (error) {
    errorMsg.value = error.message || '后台数据加载失败'
  } finally {
    loading.value = false
  }
}

async function saveSettings() {
  savingSettings.value = true
  try {
    const res = await updateQuotaSettings(settings)
    Object.assign(settings, res.data || {})
    message.success('价格已保存')
  } catch (error) {
    errorMsg.value = error.message || '保存失败'
  } finally {
    savingSettings.value = false
  }
}

async function createInvite() {
  creatingInvite.value = true
  try {
    const res = await createInviteCode(inviteForm)
    message.success(`邀请码已生成：${res.data.code}`)
    inviteForm.code = ''
    await loadDashboard()
  } catch (error) {
    errorMsg.value = error.message || '邀请码生成失败'
  } finally {
    creatingInvite.value = false
  }
}

async function adjustCredits(userId) {
  const amount = Number(adjustForms[userId] || 0)
  if (!amount) return
  try {
    await adjustUserCredits({ userId, amount, note: 'root 后台调整' })
    adjustForms[userId] = 0
    await loadDashboard()
    await auth.refresh()
  } catch (error) {
    errorMsg.value = error.message || '额度调整失败'
  }
}
</script>

<style scoped lang="scss">
.admin-page {
  min-height: 100vh;
  background: #eee9df;
  padding: 32px 20px 56px;
}

.admin-shell {
  max-width: 1180px;
  margin: 0 auto;
  display: grid;
  gap: 18px;
}

.admin-header,
.admin-panel {
  background: #f8f5ee;
  border: 1px solid #cfc7b7;
  padding: 18px;
}

.admin-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;

  h1 {
    margin: 0 0 6px;
    font-size: 28px;
  }

  p {
    margin: 0;
    color: #6f685f;
  }
}

.admin-panel h2 {
  margin: 0 0 14px;
  font-size: 18px;
}

.settings-row {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.admin-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 18px;
}

.table-wrap {
  overflow: auto;
}

table {
  width: 100%;
  border-collapse: collapse;
  font-size: 14px;
}

th,
td {
  border-bottom: 1px solid #d7cebd;
  padding: 9px;
  text-align: left;
  white-space: nowrap;
}

.inline-action {
  display: flex;
  gap: 8px;
  align-items: center;
  min-width: 180px;
}

.compact {
  max-height: 360px;
}

@media (max-width: 860px) {
  .admin-grid {
    grid-template-columns: 1fr;
  }
}
</style>

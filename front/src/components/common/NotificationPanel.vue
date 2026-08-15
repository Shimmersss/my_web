<template>
  <n-popover v-model:show="open" trigger="click" placement="bottom-end" :show-arrow="false" class="notification-popover" @update:show="handleOpen">
    <template #trigger>
      <n-badge :value="badgeValue" :max="99" :show="auth.unreadNotifications > 0" :offset="[-2, 3]">
        <n-button text circle class="notification-trigger" aria-label="查看站内通知">
          <n-icon size="21"><NotificationsOutline /></n-icon>
        </n-button>
      </n-badge>
    </template>
    <section class="notification-panel" aria-label="站内通知">
      <header><div><strong>站内通知</strong><small v-if="auth.unreadNotifications">{{ auth.unreadNotifications }} 条未读</small></div><n-button v-if="auth.unreadNotifications" text type="primary" :loading="markingAll" @click="markAll">全部已读</n-button></header>
      <n-spin :show="loading">
        <div v-if="items.length" class="notification-list">
          <button v-for="item in items" :key="item.id" type="button" class="notification-item" :class="{ unread: !item.readAt }" @click="openNotification(item)">
            <span class="notification-kind">{{ item.type === 'REPLY' ? '回复' : '点赞' }}</span>
            <span class="notification-copy"><strong>{{ item.actorUsername }}</strong>{{ item.type === 'REPLY' ? ' 回复了你的留言' : ' 点赞了你的留言' }}<small>{{ relativeTime(item.createdAt) }}</small></span>
          </button>
        </div>
        <p v-else class="notification-empty">暂无通知</p>
      </n-spin>
      <n-button v-if="page < totalPages" block text type="primary" :loading="loadingMore" @click="loadMore">加载更多</n-button>
    </section>
  </n-popover>
</template>

<script setup>
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { NBadge, NButton, NIcon, NPopover, NSpin } from 'naive-ui'
import { NotificationsOutline } from '@vicons/ionicons5'
import { getNotifications, markAllNotificationsRead, markNotificationRead } from '@/api'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const router = useRouter()
const open = ref(false)
const loading = ref(false)
const loadingMore = ref(false)
const markingAll = ref(false)
const items = ref([])
const page = ref(1)
const totalPages = ref(1)
const badgeValue = computed(() => auth.unreadNotifications > 99 ? '99+' : auth.unreadNotifications)

async function handleOpen(value) {
  if (!value || !auth.isLoggedIn) return
  await load(1)
  await auth.refreshUnreadNotifications().catch(() => {})
}

async function load(nextPage) {
  if (nextPage === 1) loading.value = true
  else loadingMore.value = true
  try {
    const data = (await getNotifications(nextPage)).data || {}
    items.value = nextPage === 1 ? (data.items || []) : [...items.value, ...(data.items || [])]
    page.value = Number(data.page || nextPage)
    totalPages.value = Number(data.totalPages || 1)
  } finally {
    loading.value = false
    loadingMore.value = false
  }
}

function loadMore() { return load(page.value + 1) }

async function markAll() {
  markingAll.value = true
  try {
    await markAllNotificationsRead()
    items.value = items.value.map(item => ({ ...item, readAt: new Date().toISOString() }))
    await auth.refreshUnreadNotifications()
  } finally { markingAll.value = false }
}

async function openNotification(item) {
  if (!item.readAt) {
    await markNotificationRead(item.id).catch(() => {})
    item.readAt = new Date().toISOString()
    await auth.refreshUnreadNotifications().catch(() => {})
  }
  open.value = false
  await router.push({ path: '/guestbook', query: { entry: item.entryId } })
}

function relativeTime(value) {
  const seconds = Math.max(0, Math.floor((Date.now() - new Date(value).getTime()) / 1000))
  if (seconds < 60) return '刚刚'
  if (seconds < 3600) return `${Math.floor(seconds / 60)} 分钟前`
  if (seconds < 86400) return `${Math.floor(seconds / 3600)} 小时前`
  return `${Math.floor(seconds / 86400)} 天前`
}
</script>

<style scoped lang="scss">
.notification-panel { width:min(380px, calc(100vw - 24px)); max-height:min(560px, calc(100vh - 100px)); display:grid; gap:8px; }
.notification-panel header { display:flex; align-items:center; justify-content:space-between; gap:12px; padding-bottom:8px; border-bottom:1px solid #e4ddd1; }
.notification-panel header div { display:grid; gap:2px; }.notification-panel header strong { font-size:16px; }.notification-panel header small { color:#81786d; font-size:12px; }
.notification-list { max-height:400px; overflow:auto; display:grid; }.notification-item { border:0; border-bottom:1px solid #eee7dc; background:transparent; color:#36332d; text-align:left; display:grid; grid-template-columns:38px 1fr; gap:10px; padding:12px 4px; cursor:pointer; }.notification-item:hover,.notification-item:focus-visible { background:#f7f2e9; outline:none; }.notification-item.unread { background:#fff7ed; }.notification-kind { align-self:start; color:#a13327; font-size:12px; font-weight:700; }.notification-copy { display:grid; gap:2px; font-size:13px; line-height:1.5; }.notification-copy strong { margin-right:3px; }.notification-copy small { color:#8d8376; font-size:11px; }.notification-empty { color:#82796d; text-align:center; padding:26px 0; margin:0; }
</style>

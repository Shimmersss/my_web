import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { claimDailyCheckin, getCurrentUser, getSiteSettings, getUnreadNotificationCount, loginAccount, logoutAccount, registerAccount } from '@/api'

export const useAuthStore = defineStore('auth', () => {
  const user = ref(null)
  const loading = ref(false)
  const visibility = ref({ Publications: 'PUBLIC', Translate: 'USER', Contact: 'USER', ImageGenerate: 'USER', Matchmaking: 'USER', News: 'PUBLIC', Guestbook: 'PUBLIC' })
  const unreadNotifications = ref(0)
  const authPrompt = ref('')
  const pendingPath = ref('')

  const isLoggedIn = computed(() => Boolean(user.value?.id))
  const isRoot = computed(() => Boolean(user.value?.root))
  const credits = computed(() => Number(user.value?.credits || 0))
  const dailyCheckin = computed(() => user.value?.dailyCheckin || null)

  function clearPptTaskSession() {
    try {
      sessionStorage.removeItem('ppt-generation-task-tokens')
      sessionStorage.removeItem('ppt-generation-active-task')
      sessionStorage.removeItem('ppt-generation-task-tokens-v2')
      sessionStorage.removeItem('ppt-generation-active-task-v2')
    } catch {
      // Storage can be disabled in private browsing; auth transitions still proceed.
    }
  }

  function applyUser(data) {
    if (!data || typeof data !== 'object') {
      clearPptTaskSession()
      unreadNotifications.value = 0
      user.value = null
      return
    }
    if (user.value?.id && data.id && user.value.id !== data.id) clearPptTaskSession()
    const csrfToken = data.csrfToken
    if (csrfToken) localStorage.setItem('csrfToken', csrfToken)
    user.value = { ...data }
  }

  async function refresh() {
    loading.value = true
    try {
      const res = await getCurrentUser()
      applyUser(res.data)
      try {
        const site = await getSiteSettings()
        visibility.value = { ...visibility.value, ...(site.data?.visibility || {}) }
      } catch {}
      if (user.value) await refreshUnreadNotifications().catch(() => {})
      return user.value
    } finally {
      loading.value = false
    }
  }

  async function login(username, password) {
    const res = await loginAccount(username, password)
    applyUser(res.data)
    await refreshUnreadNotifications().catch(() => {})
    return user.value
  }

  async function register(username, password, inviteCode) {
    return registerAccount(username, password, inviteCode)
  }

  async function logout() {
    try {
      await logoutAccount()
    } finally {
      localStorage.removeItem('csrfToken')
      clearPptTaskSession()
      user.value = null
      unreadNotifications.value = 0
    }
  }

  function updateCredits(value) {
    if (user.value) user.value = { ...user.value, credits: Number(value || 0) }
  }

  async function checkIn() {
    const res = await claimDailyCheckin()
    if (user.value) {
      const data = res.data || {}
      user.value = { ...user.value, credits: Number(data.balance ?? user.value.credits), dailyCheckin: { ...data } }
    }
    return res.data
  }

  function canView(feature) {
    const level = visibility.value[feature] || 'PUBLIC'
    return level === 'PUBLIC' || (level === 'USER' && isLoggedIn.value) || (level === 'ROOT' && isRoot.value)
  }

  async function refreshUnreadNotifications() {
    if (!user.value) { unreadNotifications.value = 0; return 0 }
    const response = await getUnreadNotificationCount()
    unreadNotifications.value = Number(response.data?.count || 0)
    return unreadNotifications.value
  }

  function requestLogin(path) { pendingPath.value = path || '/'; authPrompt.value = 'login' }
  function requestPermissionDenied() { pendingPath.value = ''; authPrompt.value = 'forbidden' }
  function clearAuthPrompt() { authPrompt.value = '' }
  function consumePendingPath() { const path = pendingPath.value; pendingPath.value = ''; authPrompt.value = ''; return path }

  return { user, loading, isLoggedIn, isRoot, credits, dailyCheckin, visibility, unreadNotifications, authPrompt, canView, requestLogin, requestPermissionDenied, clearAuthPrompt, consumePendingPath, refresh, refreshUnreadNotifications, login, register, logout, updateCredits, checkIn }
})

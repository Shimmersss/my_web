import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { getCurrentUser, loginAccount, logoutAccount, registerAccount } from '@/api'

export const useAuthStore = defineStore('auth', () => {
  const user = ref(null)
  const loading = ref(false)

  const isLoggedIn = computed(() => Boolean(user.value?.id))
  const isRoot = computed(() => Boolean(user.value?.root))
  const credits = computed(() => Number(user.value?.credits || 0))

  function applyUser(data) {
    if (!data || typeof data !== 'object') {
      user.value = null
      return
    }
    const csrfToken = data.csrfToken
    if (csrfToken) localStorage.setItem('csrfToken', csrfToken)
    user.value = { ...data }
  }

  async function refresh() {
    loading.value = true
    try {
      const res = await getCurrentUser()
      applyUser(res.data)
      return user.value
    } finally {
      loading.value = false
    }
  }

  async function login(username, password) {
    const res = await loginAccount(username, password)
    applyUser(res.data)
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
      user.value = null
    }
  }

  function updateCredits(value) {
    if (user.value) user.value = { ...user.value, credits: Number(value || 0) }
  }

  return { user, loading, isLoggedIn, isRoot, credits, refresh, login, register, logout, updateCredits }
})

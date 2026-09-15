<template>
  <header class="app-header">
    <div class="header-content">
      <button class="logo" type="button" aria-label="返回首页" @click="navigateTo('/')">
        <span class="logo-mark" aria-hidden="true"></span>
        <span class="logo-text">闪闪小站</span>
      </button>

      <div class="header-right">
        <nav class="nav-menu" aria-label="主要导航">
          <n-menu
            :value="activeKey"
            mode="horizontal"
            :options="menuOptions"
            @update:value="handleMenuSelect"
          />
        </nav>

        <div class="header-actions">
          <n-button
            text
            @click="toggleTheme"
            class="theme-btn"
            :aria-label="isDark ? '切换到浅色模式' : '切换到深色模式'"
            :aria-pressed="isDark"
          >
            <n-icon size="20">
              <MoonIcon v-if="!isDark" />
              <SunIcon v-else />
            </n-icon>
          </n-button>

          <NotificationPanel v-if="auth.isLoggedIn && !auth.isMatchmakingTrial" class="desktop-auth-action" />

          <n-button v-if="!auth.isLoggedIn" size="small" secondary class="desktop-auth-action" @click="openLogin">
            登录
          </n-button>
          <div v-else class="account-chip desktop-auth-action">
            <button type="button" class="account-button" @click="auth.isRoot ? navigateTo('/admin') : null">
              <span>{{ auth.isMatchmakingTrial ? '婚恋内测' : auth.user?.username }}</span>
              <strong v-if="!auth.isMatchmakingTrial">{{ auth.credits }} credits</strong>
            </button>
            <n-button v-if="!auth.isMatchmakingTrial" size="small" secondary :disabled="!auth.dailyCheckin?.enabled && !auth.dailyCheckin?.claimed" @click="handleDailyCheckin">
              {{ auth.dailyCheckin?.claimed ? '今日牌' : '签到' }}
            </n-button>
            <n-button size="small" text @click="handleLogout">退出</n-button>
          </div>

          <n-button
            text
            circle
            class="mobile-menu-btn"
            aria-label="打开导航菜单"
            @click="mobileMenuOpen = true"
          >
            <n-icon size="24">
              <MenuIcon />
            </n-icon>
          </n-button>
        </div>
      </div>
    </div>

    <n-drawer v-model:show="mobileMenuOpen" placement="right" width="min(84vw, 320px)">
      <n-drawer-content title="导航" closable>
        <n-menu
          :value="activeKey"
          :options="mobileMenuOptions"
          class="mobile-nav-menu"
          @update:value="handleMenuSelect"
        />
        <div class="mobile-account-actions">
          <template v-if="auth.isLoggedIn">
            <NotificationPanel v-if="!auth.isMatchmakingTrial" class="mobile-notification" />
            <button type="button" class="mobile-account" @click="auth.isRoot ? navigateTo('/admin') : null">
              <span>{{ auth.isMatchmakingTrial ? '婚恋内测' : auth.user?.username }}</span>
              <strong v-if="!auth.isMatchmakingTrial">{{ auth.credits }} credits</strong>
            </button>
            <n-button v-if="!auth.isMatchmakingTrial" block secondary :disabled="!auth.dailyCheckin?.enabled && !auth.dailyCheckin?.claimed" @click="handleDailyCheckin">
              {{ auth.dailyCheckin?.claimed ? '查看今日牌' : `每日签到 +${auth.dailyCheckin?.credits || 0}` }}
            </n-button>
            <n-button block secondary @click="handleLogout">退出登录</n-button>
          </template>
          <n-button v-else block type="primary" @click="openLogin">登录 / 注册</n-button>
        </div>
      </n-drawer-content>
    </n-drawer>

    <n-modal v-model:show="authModalOpen" preset="dialog" :title="authMode === 'login' ? '账号登录' : '邀请码注册'">
      <form class="auth-form" autocomplete="on" @submit.prevent="submitAuth">
        <n-input v-model:value="authForm.username" :input-props="usernameInputProps" placeholder="用户名" />
        <n-input v-model:value="authForm.password" type="password" :input-props="passwordInputProps" placeholder="密码" />
        <n-input v-if="authMode === 'register'" v-model:value="authForm.inviteCode" :input-props="inviteInputProps" placeholder="邀请码" />
        <n-alert v-if="authError" type="error" :title="authError" />
        <div class="auth-actions">
          <n-button attr-type="button" text @click="toggleAuthMode">
            {{ authMode === 'login' ? '使用邀请码注册' : '已有账号登录' }}
          </n-button>
          <n-button attr-type="submit" type="primary" :loading="authSubmitting">
            {{ authMode === 'login' ? '登录' : '注册' }}
          </n-button>
        </div>
      </form>
    </n-modal>

    <n-modal v-model:show="dailyTarotOpen" preset="card" class="daily-tarot-modal" title="今日塔罗" :bordered="false" closable>
      <div v-if="dailyTarotCard" class="daily-tarot-content">
        <TarotHoloCard class="daily-tarot-card" :src="`/tarot/${dailyTarotCard.cardId}.webp`" :alt="`${dailyTarotCard.name}牌面`" :title="dailyTarotCard.name" :subtitle="dailyTarotCard.keywordsZh?.join(' · ')" />
        <div><p class="daily-tarot-kicker">DAILY GUIDANCE · {{ dailyTarotCard.date }}</p><h3>{{ dailyTarotCard.name }}</h3><p>{{ dailyTarotCard.dailyGuidanceZh }}</p><small>牌面提供一种观察今天的视角，不是预测或确定性结论。</small></div>
      </div>
    </n-modal>

    <n-modal v-model:show="downloadReminderOpen" preset="dialog" title="下载 Shimmer App">
      <p class="download-reminder-copy">App 内置独立 Gecko 浏览器内核，文件上传、下载和长任务体验更稳定。</p>
      <template #action>
        <n-button @click="downloadReminderOpen = false">稍后</n-button>
        <n-button type="primary" @click="openDownloadPage">查看下载</n-button>
      </template>
    </n-modal>
  </header>
</template>

<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { NAlert, NDrawer, NDrawerContent, NInput, NMenu, NModal, NButton, NIcon, useMessage } from 'naive-ui'
import {
  MenuOutline,
  MoonOutline,
  SunnyOutline
} from '@vicons/ionicons5'
import { useThemeStore } from '@/stores/theme'
import { useAuthStore } from '@/stores/auth'
import NotificationPanel from './NotificationPanel.vue'
import TarotHoloCard from '@/components/matchmaking/TarotHoloCard.vue'
import { isAndroidMobileWeb } from '@/utils/androidBridge'
import { buildDesktopMenuOptions, resolveMenuPath } from '@/utils/navigation'

const router = useRouter()
const route = useRoute()
const { t } = useI18n()
const themeStore = useThemeStore()
const auth = useAuthStore()
const message = useMessage()
const mobileMenuOpen = ref(false)
const authModalOpen = ref(false)
const authMode = ref('login')
const authSubmitting = ref(false)
const authError = ref('')
const downloadReminderOpen = ref(false)
const dailyTarotOpen = ref(false)
const dailyTarotCard = ref(null)
const authForm = reactive({
  username: '',
  password: '',
  inviteCode: ''
})
const usernameInputProps = {
  id: 'shimmer-login-username',
  name: 'username',
  autocomplete: 'username',
  autocapitalize: 'none',
  spellcheck: false,
  enterkeyhint: 'next',
  'aria-label': '用户名'
}
const passwordInputProps = computed(() => ({
  id: 'shimmer-login-password',
  name: 'password',
  autocomplete: authMode.value === 'login' ? 'current-password' : 'new-password',
  enterkeyhint: authMode.value === 'login' ? 'go' : 'next',
  'aria-label': '密码'
}))
const inviteInputProps = {
  id: 'shimmer-register-invite',
  name: 'inviteCode',
  autocomplete: 'off',
  autocapitalize: 'none',
  spellcheck: false,
  enterkeyhint: 'go',
  'aria-label': '邀请码'
}

const isDark = computed(() => themeStore.isDark)
const activeKey = computed(() => route.name === 'MatchmakingReportDetail' ? 'Matchmaking' : route.name)

const MenuIcon = MenuOutline
const MoonIcon = MoonOutline
const SunIcon = SunnyOutline

watch(() => auth.authPrompt, prompt => {
  if (prompt === 'login') {
    openLogin()
  } else if (prompt === 'forbidden') {
    message.error(auth.isMatchmakingTrial ? '内测身份只能使用婚恋报告' : '权限不足：该节目需要 root 权限')
    auth.clearAuthPrompt()
  }
})

function shanghaiDate() {
  return new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit'
  }).format(new Date())
}

function maybeShowDownloadReminder(userId) {
  if (!userId || !isAndroidMobileWeb()) return
  const key = `shimmer-app-download-reminder:${userId}`
  const today = shanghaiDate()
  try {
    if (localStorage.getItem(key) === today) return
    localStorage.setItem(key, today)
  } catch {
    // Private browsing may disable storage; still allow the current reminder.
  }
  downloadReminderOpen.value = true
}

watch(() => auth.user?.id || null, userId => maybeShowDownloadReminder(userId), { immediate: true })

const allMenuOptions = computed(() => [
  {
    label: '文献',
    key: 'Publications'
  },
  {
    label: '翻译',
    key: 'Translate'
  },
  {
    label: 'PPT 生成',
    key: 'Contact'
  },
  {
    label: 'GPT 生图',
    key: 'ImageGenerate'
  },
  {
    label: '婚恋报告',
    key: 'Matchmaking'
  },
  {
    label: 'GitHub 项目',
    key: 'News'
  },
  {
    label: '留言板',
    key: 'Guestbook'
  },
  ...(auth.isLoggedIn && isAndroidMobileWeb() ? [{
    label: '下载 App',
    key: 'DownloadApp'
  }] : []),
  ...(auth.isRoot ? [{
    label: '后台',
    key: 'Admin'
  }] : [])
])

const visibleMenuOptions = computed(() => allMenuOptions.value.filter(item => auth.canView(item.key) || item.key === 'Matchmaking' || item.key === 'Admin'))
const menuOptions = computed(() => buildDesktopMenuOptions(visibleMenuOptions.value))

const mobileMenuOptions = computed(() => visibleMenuOptions.value)

const navigateTo = (path) => {
  mobileMenuOpen.value = false
  router.push(path)
}

const handleMenuSelect = (key) => {
  const path = resolveMenuPath(key)
  if (path) navigateTo(path)
}

const toggleTheme = () => {
  themeStore.toggleTheme()
}

function openDownloadPage() {
  downloadReminderOpen.value = false
  navigateTo('/download-app')
}

function openLogin() {
  mobileMenuOpen.value = false
  authMode.value = 'login'
  authError.value = ''
  authModalOpen.value = true
}

function toggleAuthMode() {
  authMode.value = authMode.value === 'login' ? 'register' : 'login'
  authError.value = ''
}

async function submitAuth() {
  authError.value = ''
  authSubmitting.value = true
  try {
    if (authMode.value === 'login') {
      await auth.login(authForm.username, authForm.password)
      authModalOpen.value = false
      const pendingPath = auth.consumePendingPath()
      message.success('已登录')
      if (pendingPath && pendingPath !== '/') await router.push(pendingPath)
    } else {
      await auth.register(authForm.username, authForm.password, authForm.inviteCode)
      authMode.value = 'login'
      authForm.password = ''
      authForm.inviteCode = ''
      message.success('注册成功，请登录')
    }
  } catch (error) {
    authError.value = error.message || '操作失败'
  } finally {
    authSubmitting.value = false
  }
}

async function handleLogout() {
  const wasTrial = auth.isMatchmakingTrial
  mobileMenuOpen.value = false
  await auth.logout()
  message.success('已退出')
  if (route.name === 'Admin') navigateTo('/')
  else if (wasTrial && route.name === 'MatchmakingReportDetail') navigateTo('/matchmaking-report')
}

async function handleDailyCheckin() {
  try {
    if (auth.dailyCheckin?.claimed && auth.dailyCheckin?.tarotCard) {
      dailyTarotCard.value = auth.dailyCheckin.tarotCard
      dailyTarotOpen.value = true
      mobileMenuOpen.value = false
      return
    }
    const result = await auth.checkIn()
    if (result?.tarotCard) {
      dailyTarotCard.value = result.tarotCard
      dailyTarotOpen.value = true
      mobileMenuOpen.value = false
    }
    if (result?.granted) message.success(`签到成功，获得 ${result.granted} 积分和一张今日牌`)
    else message.info('今天已经签到过了')
  } catch (error) {
    message.error(error.message || '签到失败，请稍后重试')
  }
}
</script>

<style scoped lang="scss">
@use '@/assets/styles/variables' as *;

.app-header {
  position: sticky;
  top: 0;
  z-index: 1000;
  background: var(--desk-bg);
  border-bottom: 1px solid var(--desk-border);
  box-shadow: none;
  transition: background 0.3s ease, box-shadow 0.3s ease;
  overflow: visible;

  &.scrolled {
    background: rgba(255, 255, 255, 0.98);
    box-shadow: $shadow-md;
  }

  .dark & {
    background: var(--n-color);
  }
}

.header-content {
  max-width: $container-max;
  margin: 0 auto;
  padding: 0 $spacing-lg;
  display: flex;
  align-items: center;
  justify-content: flex-start;
  gap: 34px;
  height: 80px;
  width: 100%;
}

.logo {
  appearance: none;
  border: 0;
  background: transparent;
  padding: 0;
  font: inherit;
  display: flex;
  align-items: center;
  gap: 10px;
  cursor: pointer;
  transition: all $transition-fast;
  flex-shrink: 0;

  &:hover {
    transform: scale(1.02);
  }

  &:focus-visible {
    outline: 3px solid rgba(24, 144, 255, 0.35);
    outline-offset: 4px;
  }

  &-text {
    font-family: Georgia, serif;
    font-size: 25px;
    font-weight: 700;
    color: var(--desk-text);

    .dark & {
      color: var(--n-text-color);
    }
  }

  small {
    border-left: 1px solid #b9b1a3;
    padding-left: 10px;
    color: #777168;
    font-size: 11px;
    font-weight: 400;
  }
}

.logo-mark {
  width: 4px;
  height: 27px;
  background: var(--desk-accent);
}

.header-right {
  display: flex;
  align-items: center;
  gap: 18px;
  flex: 1 1 auto;
  justify-content: flex-start;
  min-width: 0;
}

.nav-menu {
  flex: 0 1 auto;
  display: flex;
  justify-content: flex-start;
  min-width: 0;

  :deep(.n-menu) {
    display: flex;
    width: auto;
    justify-content: flex-start;
    gap: 0;

    .n-menu-item {
      font-size: 15px;
      padding: 0 12px;
      font-weight: 500;
      transition: all $transition-fast;
      flex-shrink: 0;
      height: 80px;
      display: flex;
      align-items: center;

      &:hover {
        color: $primary-color;
      }
    }
  }
}

.header-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-left: auto;
  flex-shrink: 0;

  .theme-btn {
    padding: 6px;
  }

}

.account-chip {
  display: flex;
  align-items: center;
  gap: 6px;
  padding-left: 4px;
  min-width: 0;
}

.account-button {
  appearance: none;
  border: 1px solid var(--desk-border);
  background: var(--desk-surface);
  color: #2f2d27;
  display: grid;
  gap: 1px;
  padding: 4px 8px;
  min-width: 92px;
  max-width: 138px;
  text-align: left;
  cursor: pointer;

  span {
    font-size: 12px;
    line-height: 1.2;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  strong {
    font-size: 11px;
    color: var(--desk-accent);
  }
}

.auth-form {
  display: grid;
  gap: 12px;
}

.auth-actions {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  align-items: center;
}

.mobile-menu-btn {
  display: none;
}

.mobile-nav-menu {
  :deep(.n-menu-item-content) {
    min-height: 44px;
    font-size: 16px;
  }
}

.mobile-account-actions {
  display: none;
}

.mobile-notification {
  justify-self: start;
}

@media (max-width: 1200px) {
  .nav-menu {
    :deep(.n-menu) {
      .n-menu-item {
        padding: 0 8px;
        font-size: 14px;
        height: 72px;
      }
    }
  }
}

@media (max-width: 992px) {
  .nav-menu {
    display: none;
  }

  .header-content {
    height: 64px;
    padding: 0 16px;
    justify-content: space-between;
    gap: 16px;
  }

  .logo-text {
    font-size: 22px;
  }

  .mobile-menu-btn {
    display: inline-flex;
    min-width: 36px;
    min-height: 36px;
  }

  .desktop-auth-action {
    display: none;
  }

  .mobile-account-actions {
    display: grid;
    gap: 12px;
    margin-top: 18px;
    padding-top: 18px;
    border-top: 1px solid #e5e1d8;
  }

  .mobile-account {
    width: 100%;
    border: 1px solid #d7cfc0;
    border-radius: 6px;
    background: var(--desk-surface);
    color: #2f2d27;
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
    min-height: 48px;
    padding: 10px 12px;
    cursor: pointer;

    strong { color: var(--desk-accent); font-size: 12px; }
  }
}

@media (max-width: 560px) {
  .logo {
    min-width: 0;
  }

  .logo small {
    display: none;
  }
}

@media (max-width: 420px) {
  .header-content {
    padding: 0 12px;
    gap: 8px;
  }

  .logo {
    gap: 6px;
  }

  .logo-text {
    font-size: 18px;
  }

  .header-actions {
    gap: 4px;
  }

  .theme-btn {
    min-width: 32px;
    min-height: 32px;
  }
}

@media (max-width: 360px) {
  .logo-text {
    font-size: 17px;
  }

  .logo-mark {
    height: 24px;
  }

  .account-button {
    max-width: 92px;
  }
}

@media (min-width: 993px) {
  .app-header {
    background: rgba(248, 245, 238, 0.94);
    backdrop-filter: blur(14px);
  }

  .header-content {
    max-width: 1480px;
    height: 72px;
    padding: 0 28px;
    gap: 30px;
  }

  .logo {
    gap: 9px;
  }

  .logo-text {
    font-size: 24px;
    letter-spacing: -0.04em;
  }

  .logo-mark {
    height: 25px;
    border-radius: 2px;
  }

  .header-right {
    gap: 22px;
  }

  .nav-menu {
    :deep(.n-menu) {
      align-items: center;
      min-height: 72px;

      .n-menu-item {
        height: 72px;
        padding: 0 3px;

        .n-menu-item-content {
          min-height: 40px;
          padding: 0 12px;
          border-radius: 9px;
          transition: color 0.2s ease, background 0.2s ease;
        }

        .n-menu-item-content--selected {
          background: var(--desk-tint);
          color: #9f2a20;
          font-weight: 700;
        }
      }
    }
  }

  .header-actions {
    gap: 9px;
    padding-left: 16px;
    border-left: 1px solid #ddd5c8;
  }

  .theme-btn {
    width: 36px;
    height: 36px;
    border-radius: 9px;

    &:hover {
      background: #f1ebe2;
    }
  }

  .account-chip {
    gap: 7px;
  }

  .account-button {
    min-width: 104px;
    padding: 6px 10px;
    border-color: #d9d0c3;
    border-radius: 8px;
    background: rgba(255, 250, 240, 0.72);
    transition: border-color 0.2s ease, background 0.2s ease;

    &:hover {
      border-color: #c6aa9a;
      background: var(--desk-surface);
    }
  }
}

:global(.daily-tarot-modal) { width: min(760px, calc(100vw - 28px)); }
.daily-tarot-content { display: grid; grid-template-columns: minmax(210px, 300px) 1fr; gap: 28px; align-items: center; }
.daily-tarot-card { aspect-ratio: 2 / 3; }
.daily-tarot-kicker { margin: 0 0 10px; color: #a47a44; font-size: 11px; font-weight: 800; letter-spacing: .13em; }
.daily-tarot-content h3 { margin: 0 0 12px; color: #17213d; font: 500 28px/1.3 Georgia, "Noto Serif SC", serif; }
.daily-tarot-content p:not(.daily-tarot-kicker) { color: #686273; line-height: 1.85; }
.daily-tarot-content small { color: #8b8491; line-height: 1.65; }
@media (max-width: 560px) { .daily-tarot-content { grid-template-columns: 1fr; }.daily-tarot-card { width: min(210px, 66vw); margin: 0 auto; } }
</style>

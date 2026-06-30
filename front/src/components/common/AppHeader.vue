<template>
  <header class="app-header">
    <div class="header-content">
      <button class="logo" type="button" aria-label="返回首页" @click="navigateTo('/')">
        <span class="logo-mark" aria-hidden="true"></span>
        <span class="logo-text">Research Desk</span>
        <small>个人研究工具台</small>
      </button>

      <div class="header-right">
        <nav class="nav-menu" aria-label="主要导航">
          <n-menu
            :value="activeKey"
            mode="horizontal"
            :options="menuOptions"
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

          <n-button v-if="!auth.isLoggedIn" size="small" secondary @click="openLogin">
            登录
          </n-button>
          <div v-else class="account-chip">
            <button type="button" class="account-button" @click="auth.isRoot ? navigateTo('/admin') : null">
              <span>{{ auth.user?.username }}</span>
              <strong>{{ auth.credits }} credits</strong>
            </button>
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
        />
      </n-drawer-content>
    </n-drawer>

    <n-modal v-model:show="authModalOpen" preset="dialog" :title="authMode === 'login' ? '账号登录' : '邀请码注册'">
      <div class="auth-form">
        <n-input v-model:value="authForm.username" placeholder="用户名" />
        <n-input v-model:value="authForm.password" type="password" show-password-on="click" placeholder="密码" @keyup.enter="submitAuth" />
        <n-input v-if="authMode === 'register'" v-model:value="authForm.inviteCode" placeholder="邀请码" @keyup.enter="submitAuth" />
        <n-alert v-if="authError" type="error" :title="authError" />
        <div class="auth-actions">
          <n-button text @click="toggleAuthMode">
            {{ authMode === 'login' ? '使用邀请码注册' : '已有账号登录' }}
          </n-button>
          <n-button type="primary" :loading="authSubmitting" @click="submitAuth">
            {{ authMode === 'login' ? '登录' : '注册' }}
          </n-button>
        </div>
      </div>
    </n-modal>
  </header>
</template>

<script setup>
import { computed, reactive, ref } from 'vue'
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
const authForm = reactive({
  username: '',
  password: '',
  inviteCode: ''
})

const isDark = computed(() => themeStore.isDark)
const activeKey = computed(() => {
  const activeMap = {
    BusinessDetail: 'Business',
    CaseDetail: 'Cases'
  }
  return activeMap[route.name] || route.name
})

const MenuIcon = MenuOutline
const MoonIcon = MoonOutline
const SunIcon = SunnyOutline

const menuOptions = computed(() => [
  {
    label: '文献',
    key: 'Publications',
    onClick: () => navigateTo('/publications')
  },
  {
    label: '翻译',
    key: 'Translate',
    onClick: () => navigateTo('/translate')
  },
  {
    label: 'PPT 生成',
    key: 'Contact',
    onClick: () => navigateTo('/contact')
  },
  {
    label: 'GitHub 项目',
    key: 'News',
    onClick: () => navigateTo('/news')
  },
  ...(auth.isRoot ? [{
    label: '后台',
    key: 'Admin',
    onClick: () => navigateTo('/admin')
  }] : [])
])

const mobileMenuOptions = computed(() => menuOptions.value)

const navigateTo = (path) => {
  mobileMenuOpen.value = false
  router.push(path)
}

const toggleTheme = () => {
  themeStore.toggleTheme()
}

function openLogin() {
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
      message.success('已登录')
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
  await auth.logout()
  message.success('已退出')
  if (route.name === 'Admin') navigateTo('/')
}
</script>

<style scoped lang="scss">
@use '@/assets/styles/variables' as *;

.app-header {
  position: sticky;
  top: 0;
  z-index: 1000;
  background: #f8f5ee;
  border-bottom: 1px solid #cfc7b7;
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
  justify-content: space-between;
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
    color: #25251f;

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
  background: #b83126;
}

.header-right {
  display: flex;
  align-items: center;
  gap: 20px;
  flex: 1;
  justify-content: flex-end;
  min-width: 0;
}

.nav-menu {
  flex: 1;
  display: flex;
  justify-content: flex-end;
  min-width: 0;

  :deep(.n-menu) {
    display: flex;
    width: 100%;
    justify-content: flex-end;
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
}

.account-button {
  appearance: none;
  border: 1px solid #cfc7b7;
  background: #fffaf0;
  color: #2f2d27;
  display: grid;
  gap: 1px;
  padding: 4px 8px;
  min-width: 92px;
  text-align: left;
  cursor: pointer;

  span {
    font-size: 12px;
    line-height: 1.2;
  }

  strong {
    font-size: 11px;
    color: #8f2a22;
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

@media (max-width: 1200px) {
  .nav-menu {
    :deep(.n-menu) {
      .n-menu-item {
        padding: 0 16px;
        font-size: 16px;
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
  }

  .logo-text {
    font-size: 22px;
  }

  .mobile-menu-btn {
    display: inline-flex;
  }
}

@media (max-width: 420px) {
  .header-content {
    padding: 0 12px;
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
}
</style>

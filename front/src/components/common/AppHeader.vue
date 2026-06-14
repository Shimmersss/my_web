<template>
  <header class="app-header">
    <div class="header-content">
      <button class="logo" type="button" aria-label="返回首页" @click="navigateTo('/')">
        <n-icon size="36" :color="isDark ? '#4096ff' : '#1890ff'">
          <LogoIcon />
        </n-icon>
        <span class="logo-text">Research Desk</span>
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

          <n-button
            type="primary"
            @click="navigateTo('/contact')"
            class="contact-btn"
          >
            PPT 生成
          </n-button>

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
        <div class="mobile-drawer-actions">
          <n-button block type="primary" @click="navigateTo('/contact')">PPT 生成</n-button>
        </div>
      </n-drawer-content>
    </n-drawer>
  </header>
</template>

<script setup>
import { computed, ref } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { NDrawer, NDrawerContent, NMenu, NButton, NIcon } from 'naive-ui'
import {
  MenuOutline,
  MoonOutline,
  SunnyOutline
} from '@vicons/ionicons5'
import { useThemeStore } from '@/stores/theme'

const router = useRouter()
const route = useRoute()
const { t } = useI18n()
const themeStore = useThemeStore()
const mobileMenuOpen = ref(false)

const isDark = computed(() => themeStore.isDark)
const activeKey = computed(() => {
  const activeMap = {
    BusinessDetail: 'Business',
    CaseDetail: 'Cases'
  }
  return activeMap[route.name] || route.name
})

const LogoIcon = () => '▣'
const MenuIcon = MenuOutline
const MoonIcon = MoonOutline
const SunIcon = SunnyOutline

const menuOptions = computed(() => [
  {
    label: () => t('common.home'),
    key: 'Home',
    onClick: () => navigateTo('/')
  },
  {
    label: () => t('common.about'),
    key: 'About',
    onClick: () => navigateTo('/about')
  },
  {
    label: () => t('common.business'),
    key: 'Business',
    onClick: () => navigateTo('/business')
  },
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
    label: () => t('common.franchise'),
    key: 'Franchise',
    onClick: () => navigateTo('/franchise')
  },
  {
    label: 'PPT 生成',
    key: 'Contact',
    onClick: () => navigateTo('/contact')
  }
])

const mobileMenuOptions = computed(() => menuOptions.value.filter(option => option.key !== 'Contact'))

const navigateTo = (path) => {
  mobileMenuOpen.value = false
  router.push(path)
}

const toggleTheme = () => {
  themeStore.toggleTheme()
}
</script>

<style scoped lang="scss">
@use '@/assets/styles/variables' as *;

.app-header {
  position: sticky;
  top: 0;
  z-index: 1000;
  background: #fff;
  box-shadow: $shadow-sm;
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
  height: 72px;
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
  gap: $spacing-sm;
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
    font-size: 26px;
    font-weight: 700;
    color: $text-color;

    .dark & {
      color: var(--n-text-color);
    }
  }
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
      font-size: 18px;
      padding: 0 12px;
      font-weight: 500;
      transition: all $transition-fast;
      flex-shrink: 0;
      height: 72px;
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

  .contact-btn {
    padding: 10px 18px;
    font-size: 16px;
    font-weight: 500;
  }
}

.mobile-menu-btn {
  display: none;
}

.mobile-drawer-actions {
  display: grid;
  gap: 10px;
  margin-top: 18px;
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

  .contact-btn {
    display: none;
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

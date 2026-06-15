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
  }
])

const mobileMenuOptions = computed(() => menuOptions.value)

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

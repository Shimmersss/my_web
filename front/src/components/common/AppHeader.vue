<template>
  <div class="app-header">
    <div class="header-content">
      <div class="logo" @click="navigateTo('/')">
        <n-icon size="36" :color="theme === 'light' ? '#1890ff' : '#4096ff'">
          <LogoIcon />
        </n-icon>
        <span class="logo-text">COMPANY</span>
      </div>

      <div class="header-right">
        <div class="nav-menu">
          <n-menu
            v-model:value="activeKey"
            mode="horizontal"
            :options="menuOptions"
          />
        </div>

        <div class="header-actions">
          <n-button
            text
            @click="toggleLanguage"
            class="lang-btn"
          >
            {{ currentLang === 'zh-CN' ? 'EN' : '中文' }}
          </n-button>

          <n-button
            text
            @click="toggleTheme"
            class="theme-btn"
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
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { NMenu, NButton, NIcon } from 'naive-ui'
import {
  MoonOutline,
  SunnyOutline
} from '@vicons/ionicons5'
import { useThemeStore } from '@/stores/theme'

const router = useRouter()
const route = useRoute()
const { locale, t } = useI18n()
const themeStore = useThemeStore()

const isDark = computed(() => themeStore.isDark)
const currentLang = computed(() => locale.value)
const activeKey = computed(() => route.name)

const LogoIcon = () => '🏢'
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
    label: () => t('common.cases'),
    key: 'Cases',
    onClick: () => navigateTo('/cases')
  },
  {
    label: () => t('common.news'),
    key: 'News',
    onClick: () => navigateTo('/news')
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

const navigateTo = (path) => {
  router.push(path)
}

const toggleLanguage = () => {
  locale.value = locale.value === 'zh-CN' ? 'en-US' : 'zh-CN'
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
  display: flex;
  align-items: center;
  gap: $spacing-sm;
  cursor: pointer;
  transition: all $transition-fast;
  flex-shrink: 0;

  &:hover {
    transform: scale(1.02);
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

  .lang-btn {
    font-size: 16px;
    padding: 6px 10px;
    font-weight: 500;
  }

  .theme-btn {
    padding: 6px;
  }

  .contact-btn {
    padding: 10px 18px;
    font-size: 16px;
    font-weight: 500;
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
  }

  .logo-text {
    font-size: 22px;
  }
}
</style>

<template>
  <n-message-provider>
    <n-dialog-provider>
      <n-config-provider :theme="theme" :theme-overrides="themeOverrides" :locale="naiveLocale" :date-locale="dateLocale">
        <a class="skip-link" href="#main-content">跳到主要内容</a>
        <n-layout>
          <n-layout-header bordered>
            <AppHeader />
          </n-layout-header>
          <n-layout-content>
            <main id="main-content" tabindex="-1">
              <router-view />
            </main>
          </n-layout-content>
          <AppFooter />
        </n-layout>
        <ScrollToTop />
        <CustomerService />
      </n-config-provider>
    </n-dialog-provider>
  </n-message-provider>
</template>

<script setup>
import { computed } from 'vue'
import {
  NLayout,
  NLayoutHeader,
  NLayoutContent,
  NConfigProvider,
  NMessageProvider,
  NDialogProvider,
  darkTheme
} from 'naive-ui'
import AppHeader from './components/common/AppHeader.vue'
import AppFooter from './components/common/AppFooter.vue'
import ScrollToTop from './components/common/ScrollToTop.vue'
import CustomerService from './components/business/CustomerService.vue'
import { useThemeStore } from './stores/theme'
import { zhCN, dateZhCN } from 'naive-ui'

const themeStore = useThemeStore()

const theme = computed(() => themeStore.isDark ? darkTheme : null)
const themeOverrides = {
  common: {
    primaryColor: '#b83126',
    primaryColorHover: '#9f2a20',
    primaryColorPressed: '#812219',
    primaryColorSuppl: '#b83126',
    borderRadius: '2px'
  }
}
const naiveLocale = zhCN
const dateLocale = dateZhCN
</script>

<style>
html, body, #app {
  margin: 0;
  padding: 0;
  height: 100%;
  font-family: 'Noto Sans SC', 'PingFang SC', 'Microsoft YaHei', sans-serif;
  background: #eee9df;
}

.skip-link {
  position: fixed;
  top: 8px;
  left: 8px;
  z-index: 3000;
  padding: 10px 14px;
  border-radius: 4px;
  background: #fff;
  color: #1264a3;
  font-weight: 600;
  transform: translateY(-150%);
  transition: transform 0.2s ease;
}

.skip-link:focus {
  transform: translateY(0);
}

#main-content:focus {
  outline: none;
}
</style>

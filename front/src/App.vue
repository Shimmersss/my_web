<template>
  <n-message-provider>
    <n-dialog-provider>
      <n-config-provider :theme="theme" :locale="naiveLocale" :date-locale="dateLocale">
        <n-layout>
          <n-layout-header bordered>
            <AppHeader />
          </n-layout-header>
          <n-layout-content>
            <router-view />
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
import { useI18n } from 'vue-i18n'
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
import { zhCN, dateZhCN, enUS, dateEnUS } from 'naive-ui'

const { locale } = useI18n()
const themeStore = useThemeStore()

const theme = computed(() => themeStore.isDark ? darkTheme : null)
const naiveLocale = computed(() => locale.value === 'zh-CN' ? zhCN : enUS)
const dateLocale = computed(() => locale.value === 'zh-CN' ? dateZhCN : dateEnUS)
</script>

<style>
html, body, #app {
  margin: 0;
  padding: 0;
  height: 100%;
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif;
}
</style>

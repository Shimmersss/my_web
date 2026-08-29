import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import router from './router'
import { useAuthStore } from './stores/auth'
import { createI18n } from 'vue-i18n'
import zhCN from './i18n/zh-CN'
import { isShimmerAndroid } from '@/utils/androidBridge'

import './assets/styles/main.scss'

const i18n = createI18n({
  legacy: false,
  locale: 'zh-CN',
  fallbackLocale: 'zh-CN',
  messages: {
    'zh-CN': zhCN
  }
})

const app = createApp(App)
const pinia = createPinia()

app.use(pinia)
app.use(router)
app.use(i18n)

useAuthStore().refresh().catch(() => {})

app.mount('#app')

if (import.meta.env.PROD && 'serviceWorker' in navigator) {
  window.addEventListener('load', () => {
    if (isShimmerAndroid()) {
      navigator.serviceWorker.getRegistrations()
        .then(registrations => Promise.all(registrations.map(registration => registration.unregister())))
        .catch(error => console.warn('Service Worker cleanup failed:', error))
    } else {
      navigator.serviceWorker.register('/sw.js', { scope: '/' }).catch(error => {
        console.warn('Service Worker registration failed:', error)
      })
    }
  })
}

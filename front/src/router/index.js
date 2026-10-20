import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { isAndroidMobileWeb } from '@/utils/androidBridge'
import { routeAccessDecision } from '@/utils/routeAccess'

const routes = [
  {
    path: '/download-app',
    name: 'DownloadApp',
    component: () => import('@/views/DownloadApp/index.vue'),
    meta: { title: '下载 App', mobileWebOnly: true, requiresLogin: true }
  },
  {
    path: '/',
    name: 'Home',
    component: () => import('@/views/Home/index.vue'),
    meta: { title: '首页' }
  },
  {
    path: '/news',
    name: 'News',
    component: () => import('@/views/News/index.vue'),
    meta: { title: 'GitHub 项目开源', visibility: 'News' }
  },
  {
    path: '/guestbook',
    name: 'Guestbook',
    component: () => import('@/views/Guestbook/index.vue'),
    meta: { title: '留言板', visibility: 'Guestbook' }
  },
  {
    path: '/contact',
    name: 'Contact',
    component: () => import('@/views/PptGenerate/index.vue'),
    meta: { title: 'PPT 生成', visibility: 'Contact' }
  },
  {
    path: '/image-generate',
    name: 'ImageGenerate',
    component: () => import('@/views/ImageGenerate/index.vue'),
    meta: { title: 'GPT 生图', visibility: 'ImageGenerate' }
  },
  {
    path: '/tarot-reading',
    name: 'TarotReading',
    component: () => import('@/views/TarotReading/index.vue'),
    meta: { title: '月下占卜', visibility: 'Matchmaking', trialEntry: true }
  },
  {
    path: '/tarot-reading/report/:reportId',
    name: 'TarotReadingReport',
    component: () => import('@/views/TarotReading/ReportView.vue'),
    meta: { title: '占卜报告', visibility: 'Matchmaking' }
  },
  {
    // Reports saved before 月下占卜 stay readable here until their retention period ends.
    path: '/tarot-reading/legacy/:reportId',
    name: 'TarotReadingLegacyReport',
    component: () => import('@/views/TarotReading/legacy/LegacyReportView.vue'),
    meta: { title: '旧版报告', visibility: 'Matchmaking' }
  },
  { path: '/matchmaking-report', redirect: '/tarot-reading' },
  { path: '/matchmaking-report/:reportId', redirect: to => `/tarot-reading/report/${to.params.reportId}` },
  {
    path: '/moonlit-collection',
    name: 'MoonlitCollection',
    component: () => import('@/views/MoonlitCollection/index.vue'),
    meta: { title: '月下收藏库' }
  },
  {
    path: '/moonlit-draw',
    name: 'MoonlitDraw',
    component: () => import('@/views/MoonlitDraw/index.vue'),
    meta: { title: '月下抽卡' }
  },
  {
    path: '/publications',
    name: 'Publications',
    component: () => import('@/views/Publications/index.vue'),
    meta: { title: '文献库', visibility: 'Publications' }
  },
  {
    path: '/translate',
    name: 'Translate',
    component: () => import('@/views/Translate/index.vue'),
    meta: { title: '论文翻译', visibility: 'Translate' }
  },
  {
    path: '/admin',
    name: 'Admin',
    component: () => import('@/views/Admin/index.vue'),
    meta: { title: '账号后台', visibility: 'Admin' }
  },
  // Retire unused showcase pages without leaving old links on a blank view.
  { path: '/about', redirect: '/' },
  { path: '/business/:pathMatch(.*)*', redirect: '/' },
  { path: '/cases/:pathMatch(.*)*', redirect: '/' },
  { path: '/:pathMatch(.*)*', redirect: '/' }
]

const router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior() {
    return { top: 0 }
  }
})

router.beforeEach(async (to, from, next) => {
  document.title = `${to.meta.title} - 研究工具台`
  if (to.meta.requiresLogin || to.meta.mobileWebOnly) {
    const auth = useAuthStore()
    if (!auth.user) await auth.refresh().catch(() => {})
    if (!auth.isLoggedIn) {
      auth.requestLogin(to.fullPath)
      return next('/')
    }
    if (to.meta.mobileWebOnly && !isAndroidMobileWeb()) return next('/')
  }
  const visibility = to.meta.visibility
  if (visibility) {
    const auth = useAuthStore()
    if (!auth.user) await auth.refresh().catch(() => {})
    const level = auth.visibility[visibility] || 'PUBLIC'
    const decision = routeAccessDecision({
      visibility,
      level,
      trialEntry: Boolean(to.meta.trialEntry),
      isLoggedIn: auth.isLoggedIn,
      isRoot: auth.isRoot,
      isMatchmakingTrial: auth.isMatchmakingTrial
    })
    if (decision === 'login') {
      auth.requestLogin(to.fullPath)
      return next(visibility === 'Matchmaking' ? '/tarot-reading' : '/')
    }
    if (decision === 'forbidden') {
      auth.requestPermissionDenied()
      return next('/')
    }
  }
  next()
})

export default router

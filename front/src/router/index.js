import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const routes = [
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
    path: '/contact',
    name: 'Contact',
    component: () => import('@/views/PptGenerate/index.vue'),
    meta: { title: 'PPT 生成', visibility: 'Contact' }
  },
  {
    path: '/image-generate',
    name: 'ImageGenerate',
    component: () => import('@/views/ImageGenerate/index.vue'),
    meta: { title: 'Codex 生图', visibility: 'ImageGenerate' }
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
  const visibility = to.meta.visibility
  if (visibility && visibility !== 'Admin') {
    const auth = useAuthStore()
    if (!auth.user) await auth.refresh().catch(() => {})
    const level = auth.visibility[visibility] || 'PUBLIC'
    if (level === 'USER' && !auth.isLoggedIn) {
      auth.requestLogin(to.fullPath)
      return next('/')
    }
    if (level === 'ROOT' && !auth.isRoot) {
      auth.requestPermissionDenied()
      return next('/')
    }
  } else if (visibility === 'Admin') {
    const auth = useAuthStore()
    if (!auth.user) await auth.refresh().catch(() => {})
    if (!auth.isRoot) {
      auth.requestPermissionDenied()
      return next('/')
    }
  }
  next()
})

export default router

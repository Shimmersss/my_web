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
    path: '/about',
    name: 'About',
    component: () => import('@/views/About/index.vue'),
    meta: { title: '项目说明' }
  },
  {
    path: '/business',
    name: 'Business',
    component: () => import('@/views/Business/index.vue'),
    meta: { title: '工具模块', visibility: 'Business' }
  },
  {
    path: '/business/:id',
    name: 'BusinessDetail',
    component: () => import('@/views/Business/detail.vue'),
    meta: { title: '工具详情', visibility: 'Business' }
  },
  {
    path: '/cases',
    name: 'Cases',
    component: () => import('@/views/Cases/index.vue'),
    meta: { title: '任务样例', visibility: 'Cases' }
  },
  {
    path: '/cases/:id',
    name: 'CaseDetail',
    component: () => import('@/views/Cases/detail.vue'),
    meta: { title: '样例详情', visibility: 'Cases' }
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
  }
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

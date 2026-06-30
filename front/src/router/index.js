import { createRouter, createWebHistory } from 'vue-router'

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
    meta: { title: '工具模块' }
  },
  {
    path: '/business/:id',
    name: 'BusinessDetail',
    component: () => import('@/views/Business/detail.vue'),
    meta: { title: '工具详情' }
  },
  {
    path: '/cases',
    name: 'Cases',
    component: () => import('@/views/Cases/index.vue'),
    meta: { title: '任务样例' }
  },
  {
    path: '/cases/:id',
    name: 'CaseDetail',
    component: () => import('@/views/Cases/detail.vue'),
    meta: { title: '样例详情' }
  },
  {
    path: '/news',
    name: 'News',
    component: () => import('@/views/News/index.vue'),
    meta: { title: 'GitHub 项目开源' }
  },
  {
    path: '/contact',
    name: 'Contact',
    component: () => import('@/views/PptGenerate/index.vue'),
    meta: { title: 'PPT 生成' }
  },
  {
    path: '/publications',
    name: 'Publications',
    component: () => import('@/views/Publications/index.vue'),
    meta: { title: '文献库' }
  },
  {
    path: '/translate',
    name: 'Translate',
    component: () => import('@/views/Translate/index.vue'),
    meta: { title: '论文翻译' }
  },
  {
    path: '/admin',
    name: 'Admin',
    component: () => import('@/views/Admin/index.vue'),
    meta: { title: '账号后台' }
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior() {
    return { top: 0 }
  }
})

router.beforeEach((to, from, next) => {
  document.title = `${to.meta.title} - 研究工具台`
  next()
})

export default router

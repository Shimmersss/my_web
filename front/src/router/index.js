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
    meta: { title: '关于我们' }
  },
  {
    path: '/business',
    name: 'Business',
    component: () => import('@/views/Business/index.vue'),
    meta: { title: '业务模块' }
  },
  {
    path: '/business/:id',
    name: 'BusinessDetail',
    component: () => import('@/views/Business/detail.vue'),
    meta: { title: '业务详情' }
  },
  {
    path: '/cases',
    name: 'Cases',
    component: () => import('@/views/Cases/index.vue'),
    meta: { title: '案例模块' }
  },
  {
    path: '/cases/:id',
    name: 'CaseDetail',
    component: () => import('@/views/Cases/detail.vue'),
    meta: { title: '案例详情' }
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
    component: () => import('@/views/Contact/index.vue'),
    meta: { title: '联系我们' }
  },
  {
    path: '/franchise',
    name: 'Franchise',
    component: () => import('@/views/Franchise/index.vue'),
    meta: { title: '招商加盟' }
  },
  {
    path: '/publications',
    name: 'Publications',
    component: () => import('@/views/Publications/index.vue'),
    meta: { title: '文献库' }
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
  document.title = `${to.meta.title} - 高端企业官网`
  next()
})

export default router

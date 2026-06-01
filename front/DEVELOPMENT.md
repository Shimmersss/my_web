# 开发指南

## 项目结构

```
高端企业官网模板/
├── public/                  # 静态资源（不会经过 webpack 处理）
├── src/
│   ├── assets/             # 资源文件
│   │   ├── images/         # 图片资源
│   │   └── styles/         # 全局样式
│   ├── components/         # 组件
│   │   ├── common/         # 通用组件
│   │   └── business/       # 业务组件
│   ├── composables/        # 组合式函数
│   ├── views/              # 页面组件
│   ├── router/             # 路由配置
│   ├── stores/             # 状态管理
│   ├── utils/              # 工具函数
│   ├── api/                # API 接口
│   ├── i18n/               # 国际化配置
│   ├── App.vue             # 根组件
│   └── main.js             # 入口文件
├── index.html              # HTML 模板
├── package.json            # 项目配置
├── vite.config.js          # Vite 配置
└── README.md              # 项目说明
```

## 核心技术

### Vue 3

使用 Composition API 和 `<script setup>` 语法：

```vue
<script setup>
import { ref, computed, onMounted } from 'vue'

const count = ref(0)
const double = computed(() => count.value * 2)

onMounted(() => {
  console.log('Component mounted')
})

function increment() {
  count.value++
}
</script>

<template>
  <div>{{ count }} x 2 = {{ double }}</div>
  <button @click="increment">Increment</button>
</template>
```

### Vue Router

路由配置位于 `src/router/index.js`：

```javascript
const routes = [
  {
    path: '/',
    name: 'Home',
    component: () => import('@/views/Home/index.vue'),
    meta: { title: '首页' }
  }
]
```

### Pinia

状态管理使用 Pinia，示例 `src/stores/theme.js`：

```javascript
import { defineStore } from 'pinia'
import { ref } from 'vue'

export const useThemeStore = defineStore('theme', () => {
  const isDark = ref(false)
  const toggleTheme = () => {
    isDark.value = !isDark.value
  }
  return { isDark, toggleTheme }
})
```

### Naive UI

使用 Naive UI 组件：

```vue
<template>
  <n-button type="primary" @click="handleClick">
    点击按钮
  </n-button>
  <n-modal v-model:show="showModal">
    模态框内容
  </n-modal>
</template>

<script setup>
import { ref } from 'vue'

const showModal = ref(false)
const handleClick = () => {
  showModal.value = true
}
</script>
```

### Vue I18n

国际化使用 Vue I18n：

```javascript
import { useI18n } from 'vue-i18n'

const { t, locale } = useI18n()

// 切换语言
const switchLanguage = () => {
  locale.value = locale.value === 'zh-CN' ? 'en-US' : 'zh-CN'
}

// 使用翻译
console.log(t('common.home'))
```

## 组件开发

### 创建新组件

1. 在 `src/components/` 下创建组件文件
2. 使用 `<script setup>` 语法
3. 导出组件供其他页面使用

```vue
<!-- src/components/MyComponent.vue -->
<template>
  <div class="my-component">
    <slot />
  </div>
</template>

<script setup>
// 组件逻辑
</script>

<style scoped lang="scss">
.my-component {
  // 样式
}
</style>
```

### 组件通信

**父传子：**
```vue
<!-- 父组件 -->
<ChildComponent :title="parentTitle" />

<!-- 子组件 -->
<script setup>
const props = defineProps({
  title: {
    type: String,
    required: true
  }
})
</script>
```

**子传父：**
```vue
<!-- 子组件 -->
<script setup>
const emit = defineEmits(['update'])

function handleClick() {
  emit('update', newValue)
}
</script>

<!-- 父组件 -->
<ChildComponent @update="handleUpdate" />
```

## 页面开发

### 创建新页面

1. 在 `src/views/` 下创建页面目录
2. 创建 `index.vue` 作为页面入口
3. 在路由配置中添加路由

```javascript
// src/router/index.js
{
  path: '/new-page',
  name: 'NewPage',
  component: () => import('@/views/NewPage/index.vue'),
  meta: { title: '新页面' }
}
```

### 使用 SEO Composable

```vue
<script setup>
import { useSEO } from '@/composables/useSEO'

useSEO({
  title: '页面标题',
  description: '页面描述',
  keywords: '关键词1,关键词2'
})
</script>
```

### 使用 ScrollToTop Composable

```vue
<script setup>
import { useScrollToTop } from '@/composables/useScroll'

const { scrollToTop } = useScrollToTop()
</script>

<template>
  <button @click="scrollToTop">返回顶部</button>
</template>
```

## 样式开发

### SCSS 变量

使用预定义的变量（`src/assets/styles/variables.scss`）：

```scss
.container {
  padding: $spacing-lg;
  background: $primary-color;
  border-radius: $border-radius-lg;
}
```

### 响应式设计

使用断点混入：

```scss
@import '@/assets/styles/mixins.scss';

.container {
  @include respond-to('md') {
    padding: $spacing-md;
  }
}
```

### 暗黑模式

使用 CSS 变量支持暗黑模式：

```scss
.my-component {
  background: #fff;
  color: #333;

  .dark & {
    background: var(--n-color);
    color: var(--n-text-color);
  }
}
```

## API 开发

### 创建 API 接口

在 `src/api/index.js` 中定义：

```javascript
import { get, post } from '@/utils/request'

export async function getUserList(params) {
  return get('/users', params)
}

export async function createUser(data) {
  return post('/users', data)
}
```

### 在组件中使用

```vue
<script setup>
import { ref, onMounted } from 'vue'
import { getUserList } from '@/api'

const users = ref([])
const loading = ref(false)

onMounted(async () => {
  loading.value = true
  try {
    const res = await getUserList({ page: 1 })
    users.value = res.data
  } finally {
    loading.value = false
  }
})
</script>
```

## 性能优化

### 路由懒加载

```javascript
const routes = [
  {
    path: '/about',
    component: () => import('@/views/About/index.vue')
  }
]
```

### 组件懒加载

```vue
<script setup>
import { defineAsyncComponent } from 'vue'

const HeavyComponent = defineAsyncComponent(() =>
  import('./HeavyComponent.vue')
)
</script>
```

### 图片优化

1. 使用 WebP 格式
2. 使用 CDN 加速
3. 实现图片懒加载

```vue
<img
  src="image.webp"
  loading="lazy"
  alt="描述"
>
```

## 调试技巧

### Vue DevTools

安装 Vue DevTools 浏览器扩展，用于调试 Vue 应用。

### 控制台日志

```javascript
console.log('Debug info', data)
console.warn('Warning message')
console.error('Error message')
```

### Network 面板

在浏览器开发者工具的 Network 面板中查看 API 请求。

## 部署

### 构建生产版本

```bash
npm run build
```

### 环境变量

在 `.env.production` 中配置生产环境变量：

```
VITE_API_BASE_URL=https://api.example.com
```

### 静态服务器部署

使用 `npm run preview` 预览生产构建：

```bash
npm run preview
```

### 服务器部署

将 `dist` 目录部署到服务器，配置 Nginx：

```nginx
server {
  listen 80;
  server_name your-domain.com;
  root /path/to/dist;
  index index.html;

  location / {
    try_files $uri $uri/ /index.html;
  }
}
```

## 最佳实践

1. **代码规范**：遵循 Vue 3 官方风格指南
2. **组件命名**：使用 PascalCase 命名组件
3. **文件命名**：使用 kebab-case 命名文件
4. **注释**：为复杂逻辑添加注释
5. **错误处理**：添加适当的错误处理
6. **类型检查**：考虑使用 TypeScript

## 常见问题

### Q: 如何修改主题颜色？
A: 编辑 `src/assets/styles/variables.scss` 中的颜色变量。

### Q: 如何添加新的路由？
A: 在 `src/router/index.js` 中添加路由配置。

### Q: 如何集成后端 API？
A: 修改 `src/utils/request.js` 中的 BASE_URL，并在 `src/api/` 中定义接口。

### Q: 如何优化首屏加载？
A: 使用路由懒加载、代码分割、资源压缩等技术。

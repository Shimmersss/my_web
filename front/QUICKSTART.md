# 快速启动指南

## 安装依赖

在项目根目录下执行：

```bash
npm install
```

## 启动开发服务器

```bash
npm run dev
```

启动成功后，浏览器会自动打开 http://localhost:3000

## 功能列表

### 已实现的核心功能

✅ **首页**
- 品牌Banner（带动画效果）
- 核心业务板块展示
- 企业优势介绍
- 客户案例轮播
- 新闻动态展示
- 联系方式（含在线表单）

✅ **关于我们**
- 企业简介
- 发展历程（时间轴）
- 组织架构
- 核心团队
- 荣誉资质
- 企业文化

✅ **业务模块**
- 业务分类导航
- 业务列表展示
- 业务详情页
- 解决方案展示
- 服务流程图

✅ **案例模块**
- 案例列表（支持按行业/类型筛选）
- 案例详情页（背景/方案/成果）
- 案例下载功能
- 分页功能

✅ **新闻动态**
- 新闻列表（支持搜索）
- 新闻详情页
- 相关新闻推荐
- 社交分享功能

✅ **联系我们**
- 在线留言表单
- 预约咨询功能
- 联系信息展示
- 地图位置展示
- 社交媒体链接

✅ **招商加盟**
- 加盟优势展示
- 加盟流程图
- 加盟条件说明
- 在线申请表单
- 成功案例展示

✅ **基础功能**
- 多语言切换（中/英）
- 暗黑模式
- 响应式设计
- SEO优化
- 客服弹窗

## 技术特性

### Vue 3 特性
- Composition API
- <script setup> 语法
- 响应式系统
- 生命周期钩子

### Vite 特性
- 快速冷启动
- 即时热更新
- 优化的生产构建
- 开发工具集成

### Naive UI 特性
- 丰富的组件库
- 主题定制
- 暗黑模式支持
- 国际化支持

## 样式定制

### 修改主题颜色

编辑 `src/assets/styles/variables.scss` 文件：

```scss
$primary-color: #1890ff;  // 主色调
$success-color: #52c41a;  // 成功色
$warning-color: #faad14;  // 警告色
$error-color: #ff4d4f;    // 错误色
```

### 修改断点

```scss
$breakpoints: (
  'xs': 576px,
  'sm': 768px,
  'md': 992px,
  'lg': 1200px,
  'xl': 1600px
);
```

## 数据对接

### 替换静态数据

当前项目使用静态数据，对接后端API时：

1. 在 `src/api/` 目录下创建 API 文件
2. 在页面组件中替换静态数据为 API 调用
3. 添加加载状态和错误处理

示例：

```javascript
// 替换前
const services = ref([
  { id: 1, title: '云计算服务', ... }
])

// 替换后
import { getServices } from '@/api/business'
const services = ref([])
const loading = ref(false)

onMounted(async () => {
  loading.value = true
  try {
    services.value = await getServices()
  } finally {
    loading.value = false
  }
})
```

## 部署

### 构建生产版本

```bash
npm run build
```

构建完成后，`dist` 目录包含所有生产文件。

### 部署到服务器

将 `dist` 目录上传到你的服务器，配置 Nginx 或其他 Web 服务器。

### Nginx 配置示例

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

## 浏览器兼容性

- Chrome 90+
- Firefox 88+
- Safari 14+
- Edge 90+

## 常见问题

### Q: 如何修改网站标题？
A: 编辑 `index.html` 中的 `<title>` 标签，或在路由配置中设置 `meta.title`

### Q: 如何添加新页面？
A:
1. 在 `src/views/` 下创建新页面组件
2. 在 `src/router/index.js` 中添加路由配置
3. 在导航菜单中添加入口

### Q: 如何修改语言翻译？
A: 编辑 `src/i18n/zh-CN.js` 和 `src/i18n/en-US.js` 文件

### Q: 如何添加新的图标？
A: 从 `@vicons/ionicons5` 导入图标，或使用其他图标库

## 技术支持

如有问题，请联系：support@company.com

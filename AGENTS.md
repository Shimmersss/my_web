# AGENTS.md

> 给后续接手的 AI / 协作者看的项目说明书。先读这个再动代码。

## 这是什么

个人主页 + Zotero 文献库展示。
- 前端：Vue 3 + Vite + Naive UI
- 后端：Spring Boot 3 + Java 17（包名 `com.web.backen`，目录拼写就是 `backen`，别改）
- 数据源：Zotero Web API v3（用户的私有库）

## 一句话流程

浏览器 → Vite 代理（`/api/*` → 后端 8080）→ Spring Boot → 内存缓存 → 返回。
缓存由后端启动时预热 + 每 5 分钟后台刷新维护，**用户请求不直连 Zotero**。

## 启停

```bash
./project.sh start         # 后端 + 前端
./project.sh restart backend
./project.sh stop
./project.sh status
./project.sh logs backend  # tail -f 后端日志
```

PID/日志在 `.run/`（已 gitignore）。

## 凭证

`.env.local`（已 gitignore）：

```
ZOTERO_API_KEY=...
ZOTERO_USER_ID=...
```

`project.sh` 启动时自动 source。`.env.local.example` 是模板，可提交。

## 目录

```
backen/                                      Spring Boot 后端
  src/main/java/com/web/backen/
    BackenApplication.java                   入口（@EnableScheduling 别删，缓存定时刷新靠它）
    config/ZoteroConfig.java                 @ConfigurationProperties 读 zotero.* 环境变量
    zotero/
      ZoteroService.java                     调 Zotero API 的薄封装
      ZoteroCache.java                       内存缓存 + 启动预热 + 5min 静默刷新
      ZoteroController.java                  REST 接口，命中缓存
front/                                       Vue 3 前端
  src/views/Publications/index.vue           文献库主页面（核心，所有 Zotero 展示逻辑都在这）
  src/api/index.js                           getZoteroItems / getZoteroCollections
  src/utils/request.js                       axios 实例
  vite.config.js                             /api 代理到 :8080
project.sh                                   一键启停
```

## 后端接口

| 路径 | 说明 | 性能 |
|---|---|---|
| `GET /api/zotero/items` | 主条目列表（母条目 + 孤立附件，挂上 attachments 子项）| ~2 ms（缓存）|
| `GET /api/zotero/items?refresh=true` | 触发后台刷新，不阻塞当前请求 | ~2 ms |
| `GET /api/zotero/collections` | 文献分组 | ~2 ms（缓存）|
| `GET /api/zotero/file/{key}` | 附件代理。自动跟 S3 302、自动解 ZIP（snapshot 类型）、按文件名推断真实 content-type | 取决于上游 |
| `GET /api/zotero/items/{key}/export?format=bibtex\|ris\|bibliography&style=apa` | 引用导出 | 走上游，~1-3s |
| `GET /api/zotero/items/raw` | 原始 Zotero JSON（调试用，不走缓存）| 慢 |

`/items` 返回结构：

```json
{
  "code": 200,
  "data": [{
    "key": "ATE47N35",
    "itemType": "journalArticle",
    "title": "...",
    "creators": [...],
    "date": "2023",
    "publicationTitle": "...",
    "DOI": "10.xxx/xxx",
    "url": "...",
    "abstractNote": "...",
    "tags": [{"tag": "..."}],
    "collections": ["HJW949Y2"],
    "attachments": [{"key": "YDY2Y293", "filename": "...", "contentType": "application/pdf", "isPdf": true}]
  }],
  "updatedAt": 1717228443229,
  "warmedUp": true
}
```

## 主条目的两种来源

`/items` 返回里的"主条目"包含两类，前端统一渲染：

1. **正常文献**（`itemType=journalArticle / book / ...`）：母条目，子附件（PDF 等）通过 `parentItem` 反向挂载到它的 `attachments` 数组
2. **孤立附件**（`itemType=attachment` 且 `parentItem=null`）：用户直接拖进 collection 的 md / 单文件 pdf 等。这类条目的 `attachments` 里只有一个元素——它自己（让前端"查看附件"按钮逻辑零改动复用）

## 附件代理的特殊处理

`GET /api/zotero/file/{key}` 不是单纯透传：

- **跟 302**：Zotero 用 S3 存大文件，会 302 跳转。Spring `RestClient` 默认不跟，所以这里改用 JDK `HttpClient.followRedirects(NORMAL)`
- **解 ZIP**：Zotero 把 markdown / 网页快照 / 部分单文件附件**用 ZIP 打包存**。上游 content-type 可能写 `text/plain` 但字节是 ZIP（前 4 字节 `PK\003\004`）。后端检测到 ZIP 头会解出第一个非目录条目
- **推断 content-type**：解压后按文件名后缀返回正确类型（.md → `text/markdown`，.html → `text/html` 等）。如果上游不是 ZIP 就保留上游 content-type

## 前端 markdown 渲染

孤立 md 附件：前端检测 `filename` 以 `.md` 结尾时，不挂 iframe，而是：
1. `fetch('/api/zotero/file/{key}')` 拿文本
2. `marked.parse()` 渲染成 HTML（开 `breaks: true, gfm: true`）
3. `DOMPurify.sanitize()` 防 XSS（**不能省**，上游内容不可信）
4. `v-html` 注入到 `.md-render` 容器，样式见组件内 `<style>` 块

依赖：`marked` + `dompurify`（已加到 package.json）

**为什么有这玩意**：Zotero 直连 API 慢得离谱（16-30s 是常态），不能让用户每次都等。

**怎么工作**：
- `ZoteroCache` 是 `@Component` 单例
- `@PostConstruct` → 后台线程 `warmAsync()` 拉数据 → 写进 `AtomicReference`（原子切换，并发读零锁）
- `@Scheduled(fixedDelay = 5min)` 定时刷新
- 刷新失败保留旧数据（不会清空），日志打 ERROR
- 用户请求都走 `cache.getItems()`，O(1) 内存读

**新鲜度代价**：Zotero 上改了文献，最坏 5 分钟后才反映。可接受。

**调缓存周期**：改 `ZoteroCache.java` 里的 `@Scheduled(fixedDelay = ...)`。

## 前端要点

- `Publications/index.vue` 一个文件 600+ 行，承载所有逻辑（侧栏、过滤、卡片、PDF 展开、导出）。不要拆成多文件除非真的复用
- 一次性拿全部 47 条 items，**前端做过滤分组**（不重复请求接口）
- 渲染懒加载：默认 20 条，`IntersectionObserver` 滚动到底加载下一批
- PDF 懒加载：每张卡片 "查看 PDF" 按钮点击才挂 iframe（`/api/zotero/file/{key}`），再点收起卸载 iframe
- 导出三种：BibTeX / RIS 直接下载 .bib/.ris 文件；APA 复制到剪贴板（HTML 转纯文本）
- 启动时 `warmedUp=false` 会自动 2s 后重试 load（保险机制，应对极端首次冷启动）

## Git 分支

- `main` → 当前生产版（= zotero-b-custom）
- `zotero-b-custom` → 自建卡片方案（main 同步指向）
- `zotero-a-official` → 官方 `zotero-publications` 嵌入方案（备选保留）

提交风格：`feat: ...` / `feat(B): ...` / `perf: ...`，中文描述。

## 改东西时注意

- **包名拼写 `backen` 不是 `backend`**：建项目时手滑了，现在牵动 Maven、IDE、所有 import，**别改**
- **改了 ZoteroCache 字段处理逻辑**，记得也改前端模板对应字段（前后端字段是手工对齐的，没用 OpenAPI/codegen）
- **加新接口走缓存**：在 `ZoteroCache` 里加 ref 和 refresh 逻辑，不要在 Controller 里直接调 Service（会失去缓存价值）
- **重启后端**：用 `./project.sh restart backend` 而不是 `mvn spring-boot:run`，前者管 PID 和日志
- **前端改完不用重启**：Vite HMR 自动热更
- **写文件别用 cat/echo 重定向**，用专门的写工具（保护 UTF-8 中文）

## 已知小坑

- Zotero 偶尔抽风返回 503，缓存层吞掉错误并保留上一次数据，日志会 ERROR
- 火狐 PDF iframe 内嵌可能有 CSP 问题（Chrome OK，没测过 Safari）
- `useMessage` 依赖 `<n-message-provider>` 包裹，已在 `App.vue` 加了，新页面要用就别拆掉
- **Zotero 服务端把部分附件用 ZIP 打包**（md / 网页快照等），但上游 content-type 撒谎写 `text/plain`。后端 `fetchItemFile` 已处理；如果以后看到附件代理乱码，先看上游字节是不是 `PK\003\004`
- npm 在某些环境下 `~/.npm/_cacache` 会有权限问题，绕过：`npm install --cache /tmp/npm-cache <pkg>`

## 添加新功能的套路

想加个什么 Zotero 相关功能（比如收藏、笔记、注解）：
1. 看 Zotero API 文档对应字段
2. `ZoteroCache.simplify()` 里加字段提取
3. `ZoteroController` 看是否要新接口
4. 前端 `Publications/index.vue` 模板加渲染
5. 重启后端 → 浏览器刷新

完。

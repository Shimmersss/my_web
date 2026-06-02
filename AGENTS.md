# AGENTS.md

> 给后续接手的 AI / 协作者看的项目说明书。先读这个再动代码。

## 这是什么

个人主页 + Zotero 文献库展示 + GitHub 开源项目展示 + PDF 论文翻译。
- 前端：Vue 3 + Vite + Naive UI
- 后端：Spring Boot 3 + Java 17（包名 `com.web.backen`，目录拼写就是 `backen`，别改）
- 数据源：Zotero Web API v3（用户的私有库）+ GitHub API / raw README + LLM 翻译 API

## 一句话流程

浏览器 → Vite 代理（`/api/*` → 后端 8080）→ Spring Boot → 内存缓存 → 返回。
缓存由后端启动时预热 + 每 5 分钟后台刷新维护，**用户请求不直连 Zotero**。

GitHub 开源项目也一样：浏览器只请求 `/api/github-projects*`，由后端 `GithubProjectService` 去请求 GitHub API / raw README。**前端不要直接 fetch `api.github.com` 或 `raw.githubusercontent.com`**。

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
ADMIN_KEY=...

# LLM 翻译 API（Anthropic Messages API 格式）
LLM_API_URL=https://token-plan-cn.xiaomimimo.com/anthropic
LLM_API_KEY=...
LLM_MODEL=mimo-v2.5-pro
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
    github/
      GithubProjectStore.java                `.run/github-projects.json` 本地展示配置读写
      GithubProjectService.java              后端请求 GitHub API / raw README 并补全字段
      GithubProjectController.java           REST 接口，前端只调用这里
    config/LlmConfig.java                    @ConfigurationProperties 读 llm.* 环境变量（翻译 API）
    translate/
      PdfParseService.java                   PDFBox 提取 PDF 文本+坐标，按段落拆分，支持页面范围过滤
      PdfRenderService.java                  翻译 PDF 渲染：原页面→图片背景→白色遮罩→中文译文
      LlmService.java                        调 Anthropic Messages API 翻译，含重试
      TranslationSession.java                单次翻译任务的内存状态（含页面范围）
      TranslationService.java                编排器：预览→页面范围→解析→翻译→SSE 推送
      TranslateController.java               REST 接口，上传/开始/流式/状态/下载
front/                                       Vue 3 前端
  src/views/Publications/index.vue           文献库主页面（核心，所有 Zotero 展示逻辑都在这）
  src/views/News/index.vue                   GitHub 开源项目页 + 管理后台 + README 弹窗
  src/views/Translate/index.vue              PDF 论文翻译页（上传/配置/进度/结果四态）
  src/api/index.js                           getZoteroItems / getZoteroCollections
  src/utils/request.js                       fetch 请求封装
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
| `GET /api/github-projects` | GitHub 开源项目列表。后端读取本地配置并补 stars/forks/language/topics/default_branch 等 | 取决于上游 |
| `GET /api/github-projects/{owner}/{repo}/readme` | README.md 代理。后端尝试 default_branch/main/master + README 文件名变体 | 取决于上游 |
| `POST /api/github-projects/login` | 管理员密钥登录 | 校验 `ADMIN_KEY` |
| `PUT /api/github-projects` | 保存展示配置，需 `X-Admin-Key` | 写入 `.run/github-projects.json` |
| `POST /api/translate/upload` | 上传 PDF，返回 taskId + totalPages（不立即翻译）| PDFBox 解析 |
| `POST /api/translate/start/{taskId}?startPage=1&endPage=10` | 指定页面范围，提取段落并准备翻译 | PDFBox 按页提取 |
| `GET /api/translate/stream/{taskId}` | SSE 流式推送翻译进度（progress/done/error 事件）| 逐段翻译 |
| `GET /api/translate/status/{taskId}` | 查询任务状态（断线重连用）| 内存读 |
| `GET /api/translate/download/{taskId}?mode=bilingual\|translated` | 下载翻译结果 .txt | 内存拼接 |
| `GET /api/translate/download-pdf/{taskId}` | 下载翻译 PDF（译文覆盖原位置，保留图片公式）| PDF 渲染 |

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

## PDF 论文翻译功能

**四步流程**：
1. 上传 PDF → 返回 taskId + totalPages
2. 选择页面范围（默认全部）→ 点击"开始翻译"
3. SSE 实时推送翻译进度 → 前端实时渲染
4. 下载 TXT / 翻译 PDF

**页面范围选择**：用户可指定翻译第几页到第几页。上传后进入配置态，选择起止页码后点击"开始翻译"。

**翻译 PDF 渲染**（`PdfRenderService`）：
- 原 PDF 页面渲染为 200 DPI 高清图片作为背景（保留图片、公式、表格）
- 白色矩形遮罩覆盖原文区域
- 中文译文绘制在同一位置，自动换行
- 使用 `/Library/Fonts/Arial Unicode.ttf`（macOS 系统字体）
- 输出 PDF 保留原版页面尺寸

**LLM 配置**：`.env.local` 中设置 `LLM_API_URL`、`LLM_API_KEY`、`LLM_MODEL`。当前使用 Anthropic Messages API（`mimo-v2.5-pro` 模型）。

**前端四态**：上传态（拖拽/选择）→ 配置态（页面范围选择）→ 翻译中态（进度条 + 实时预览）→ 结果态（双语对照 / 纯中文切换，复制/下载 TXT/下载翻译 PDF）。

**SSE 事件**：`progress`（每段完成）、`done`（全部完成）、`error`（失败）。前端通过 `EventSource` 监听。

**内存存储**：翻译结果存在 `ConcurrentHashMap`，不持久化到 H2。重启后端丢失。

**关键依赖**：`org.apache.pdfbox:pdfbox:3.0.3`（PDF 解析 + 渲染）+ `RestClient`（LLM 调用）。

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
- `News/index.vue` 现在是 GitHub 开源项目页，不是新闻页。它只调用本地 `/api/github-projects`，README 也走 `/api/github-projects/{owner}/{repo}/readme`。
- GitHub 项目后台入口在开源项目页右上角“后台管理”，用 `ADMIN_KEY` 登录。可以添加项目、填写 GitHub 地址、分类、说明、首页展示、上移/下移排序、删除。
- GitHub 项目展示顺序按 `.run/github-projects.json` 保存顺序来，不要在前端按 stars 自动重排。首页取前 3 个 `featured=true` 的项目。
- README 渲染仍然在前端用 `marked` + `DOMPurify`，但 README 文本必须由后端代理返回，不能让浏览器直连 GitHub。

## Git 分支

- `main` → 当前生产版（= zotero-b-custom）
- `zotero-b-custom` → 自建卡片方案（main 同步指向）
- `zotero-a-official` → 官方 `zotero-publications` 嵌入方案（备选保留）

提交风格：`feat: ...` / `feat(B): ...` / `perf: ...`，中文描述。

## 改东西时注意

- **包名拼写 `backen` 不是 `backend`**：建项目时手滑了，现在牵动 Maven、IDE、所有 import，**别改**
- **改了 ZoteroCache 字段处理逻辑**，记得也改前端模板对应字段（前后端字段是手工对齐的，没用 OpenAPI/codegen）
- **加新接口走缓存**：在 `ZoteroCache` 里加 ref 和 refresh 逻辑，不要在 Controller 里直接调 Service（会失去缓存价值）
- **GitHub / Zotero 这类外部数据源，前端一律不直连**：前端只打 `/api/*`，外部 API、raw 文件、重定向、限流降级都放后端处理。
- **GitHub 项目字段来源**：展示配置在 `.run/github-projects.json`，实时字段由 `GithubProjectService` 补全；保存列表时只保存 repo/highlight/category/featured，不保存 stars 等上游数据。
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

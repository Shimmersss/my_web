# Worklog

按时间倒序，最新在最上。每条对应一个或几个 git commit。

格式：日期 · 一句话总结 · 改了什么 · 为什么 · 踩了什么坑。

---

## 2026-06-01

### feat: 孤立附件 + markdown 渲染（commit `3a5f281`）

**问题**：用户在 Zotero 里直接拖进 collection 一个 `README.md`，前端不显示。

**两层根因**：
1. **过滤逻辑误伤**：之前假设所有 `attachment` 都是母条目的子项（论文+PDF 模式），处理流程会过滤掉 attachment 类型。但用户直接拖进 collection 的 md / 单文件 pdf，是 `parentItem=null` 的孤立附件，两边都漏。同时还有 2 条单文件 PDF（RDKit.pdf、1-s2.0-S1385894725123899-main.pdf）也被误吞。
2. **Zotero ZIP 打包**：md / 网页快照这类附件，Zotero **服务端会用 ZIP 打包存**（contentType 写的是 `text/plain` 但实际字节是 ZIP，PK 头）。直接代理给浏览器是乱码。

**改动**：
- 后端 `ZoteroCache.processItems`：保留 `parentItem=null` 的 attachment 作为独立条目
- 后端 `ZoteroCache.simplify`：孤立附件把自己挂进自己的 `attachments` 数组（前端"查看 PDF"按钮零改动复用）
- 后端 `ZoteroService.fetchItemFile`：检测 ZIP 头（`PK\003\004`）自动解第一个非目录条目，按文件名后缀推断 content-type（md → text/markdown，html → text/html，pdf → pdf 等）
- 前端 `Publications/index.vue`：
  - 按钮文案随类型变："查看 PDF" / "查看 Markdown" / "查看文件"
  - 装 `marked` + `dompurify`，md 类型 fetch 文本 → marked 渲染 → DOMPurify sanitize → v-html
  - 加 `.md-render` 样式（标题、代码块、表格、引用、链接）
  - `typeMap` 加 `attachment: '附件'` 映射
- 总条目数 47 → 50

**踩坑**：
- ZIP 头检测必须看前 4 字节 `0x50 0x4B 0x03 0x04`，光看 contentType 会被骗（Zotero 那边写的不是 application/zip）
- npm 缓存权限报错 `EACCES: ~/.npm/_cacache/...`，用 `npm install --cache /tmp/npm-cache` 绕过
- DOMPurify 不能省，用户 md 内容如果含 `<script>` 直接 v-html 是 XSS

---

### perf: 缓存层 + 启动预热（commit `b726af8`）

**问题**：直连 Zotero API 16-30 秒，前端每次刷新都白屏十几秒，体验崩。

**测了一下，瓶颈不在我们后端**：
| 链路 | 耗时 |
|---|---|
| 直连 Zotero `/items?limit=200` | 16-30 s |
| 我们后端 `/api/zotero/items`（之前）| 1.3-13 s |
| 后端→前端（同机器）| 2 ms |

慢的是上游，不是我们的处理。Zotero 不支持 streaming，再怎么"流式"也得先等它吐完 JSON。

**方案**：内存缓存 + 启动异步预热 + 每 5min 后台静默刷新。
- 新增 `ZoteroCache.java`（@Component），`AtomicReference<List<Map>>` 存预处理后的数据
- `@PostConstruct` 启动一个 `zotero-cache-warm` 线程拉数据，不阻塞 Spring 启动
- `@Scheduled(fixedDelay = 5min)` 后台刷新
- `BackenApplication` 加 `@EnableScheduling`
- Controller 的 `/items` 和 `/collections` 直接读 cache.getItems() / getCollections()
- 多了 `?refresh=true` 触发后台拉新，当前请求仍秒返
- 响应里加了 `warmedUp` 字段，前端冷启动期间会自动 2s 后重试

**结果**：
- 启动后 3.4s 缓存就绪（用户感知不到，因为前端会重试）
- 之后所有请求 ~2ms（提速 600-15000 倍）
- 5min 后台刷新，用户永远命中热缓存

**为什么不用 Redis**：47 条数据几十 KB，内存里 List 就够。引入 Redis 是过度工程。

**没做的**：服务端 ETag。当前缓存已经够快了，加 ETag 收益微乎其微，先不做。

---

### feat: 分页懒加载 + PDF 按需打开 + 引用导出（commit `77e18dc`）

**问题**：B 分支首版一次性渲染 75 条 + 每张卡片若干字段，DOM 重；如果未来文献多了会更慢。同时用户希望 PDF 不要全量预加载，点了再看。

**改动**：
- 后端 `/items` 重做：返回母条目（过滤掉 `itemType=attachment|note`），把 PDF 等附件按 `parentItem` 反向挂到母条目的 `attachments` 数组。47 条母条目里 16 条带 PDF
- 后端新增：
  - `/api/zotero/file/{key}` PDF 流代理。Zotero 返回 302 重定向到 S3，**Spring RestClient 默认不跟随重定向**，改用 JDK `HttpClient.followRedirects(NORMAL)` 才拿到 PDF 字节
  - `/api/zotero/items/{key}/export?format=bibtex|ris|bibliography&style=apa` 引用导出
    - bibtex/ris：透传 `?format=bibtex|ris`
    - bibliography：要用 `?include=bib&style=apa`，从返回 JSON 取 `.bib` 字段（一开始用 `?format=bibliography` 报 400）
- 前端 `Publications/index.vue`：
  - 渲染分页：`pageCount * 20`，`IntersectionObserver` 滚动到底自动 +1
  - 切 collection / 搜索时重置 pageCount=1
  - 卡片右下两个按钮：「查看 PDF」（有附件才显示）、「导出」下拉
  - PDF iframe 懒加载，再点收起卸载（省内存）
  - APA 导出走剪贴板（HTML 转纯文本）

**踩坑**：
- Zotero PDF 走 S3 重定向，必须自跟随
- `?format=bibliography` 是无效参数，要用 `include=bib`
- naive-ui 的 `useMessage` 必须在 `<n-message-provider>` 内调用，App.vue 已经包了

---

### feat(B): 自建 collection 树 + 卡片列表（commit `b1686dd`、`656cbd9`）

**问题**：和 A 分支并行做对比方案。A 用官方 `zotero-publications` 组件，但样式跟 Naive UI 风格不统一，且组件内部 `groupByCollections` 实际上 throws "not implemented"（README 是空头支票）。

**改动**：
- 后端 `simplify()` 加 `collections` 字段（item 所属的 collection key 数组）
- 前端完全自建：左侧 collection 树（支持嵌套、过滤、计数），右侧卡片列表
- collection 树用扁平化 + depth 字段做缩进（不是真的递归组件，简单粗暴够用）
- 卡片底部显示该 item 所属 collection 名字

**为什么选这条路**：风格可控、能展示 collection 标签、之后好加功能（PDF 内嵌、导出、搜索都比改官方组件容易）。

---

### feat(A): 嵌入官方 zotero-publications + 自建 collection 侧栏（commit `c938948`）

**改动**：把 `zotero-publications` npm 包嵌进来，左侧用我们自己的 collection 列表过滤。

**结论**：保留作为备选分支（`zotero-a-official`），但官方组件样式和我们站点不搭，且导出/详情外的功能扩展性差。

---

### init: Vue3 前端 + Spring Boot 后端 + Zotero 接入（commit `bff6041`）

**做了**：
- 创建 `backen/`（Spring Boot 3 + Java 17）+ `front/`（Vue 3 + Vite + Naive UI）
- `ZoteroConfig` + `ZoteroService` + `ZoteroController` 三件套，调通 Zotero API
- `project.sh` 一键启停（PID 文件、日志、健康检查、`.env.local` 加载）
- 前端 `Publications` 页面初版，路由接入

**踩坑**：
- 包名拼写 `backen`（不是 `backend`），建项目时手滑了，已经牵动太多文件，决定**不改**
- Naive UI 自动导入插件 `unplugin-vue-components` 配好后 NXxx 组件可以直接用，不用手动 import

---

## 状态快照（2026-06-01 18:00）

- `main` ↔ `zotero-b-custom` 同步在 `b726af8`
- `zotero-a-official` 备选，停在 `c938948`
- 后端：47 条母条目（16 条带 PDF）+ 4 个 collection，缓存命中 ~2ms
- 前端：分页 20 条 / 批，PDF 内嵌 iframe，三种导出
- 接口在 `http://localhost:8080/api/zotero/*`，前端在 `http://localhost:3000`

下一步可能要做（未排期）：
- [ ] 文献按年份/期刊聚合视图
- [ ] 全文搜索（前端 fuse.js？）
- [ ] 标签云
- [ ] 把首页其他模块的 mock 数据接真实后端
- [ ] 上线部署（Caddy 反代 / Docker compose）
